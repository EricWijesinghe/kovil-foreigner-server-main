package com.kovil.domain.file.service;

import com.kovil.domain.file.entity.StoredFile;
import com.kovil.domain.file.enums.FilePurpose;
import com.kovil.domain.file.enums.FileStatus;
import com.kovil.domain.file.repository.StoredFileRepository;
import com.kovil.infra.storage.StorageClient;
import com.kovil.infra.storage.exception.StorageException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

// 지워진 파일을 **격리 접두사로 옮긴다.** 실제 파기는 그 접두사에 걸린 R2 수명 규칙이 한다.
//
// 왜 옮기기만 하고 지우지 않는가: 되돌릴 시간을 벌기 위해서다. 잘못 지운 것을 알아채는 데는
// 보통 며칠이 걸리는데, 즉시 파기하면 그 사이에 손쓸 방법이 없다. 대신 **대기실을 둘로 나눈다** —
// 법이 보존을 요구하는 것과 아닌 것은 머물 기간이 완전히 달라서, 한 방에 두면 짧은 규칙이
// 긴 의무를 지운다(FilePurpose.Retention).
//
// ★ 스토리지 이동이 먼저, DB 마킹이 나중이다. 반대로 하면 마킹 직후 죽었을 때 행은 "옮겼다"고
//   말하는데 오브젝트는 옛 자리에 남아 **영원히 아무도 안 치우는 고아**가 된다. 이 순서면
//   최악이 "옮겼는데 마킹을 못 함"이고, 그건 다음 회차가 복구한다.
//
// ★★ 복구 판정은 **DB 가 아니라 스토리지에 물어야 한다.** markMoved 가 키와 플래그를 한 UPDATE 로
//    쓰기 때문에, 커밋이 실패하면 **둘 다** 롤백된다 — DB 만 보면 "아직 안 옮긴 행"과 구별이 안 된다.
//    그래서 이동을 다시 시도하고, 원본이 없어서 실패하면 목적지를 확인해 마킹만 하고 끝낸다.
//    같은 판단을 FileService.confirm 의 probeFinal 이 이미 하고 있다(크래시 창 복구, AUDIT_2026_08_26).
//    이게 없으면 그 행은 **매일 밤 같은 실패를 영원히 반복하고** 회차 슬롯을 점유한다
//    (적대적 리뷰 2026-09-23 — 5개 축이 독립적으로 같은 결함을 지목했다).
@Slf4j
@Component
@RequiredArgsConstructor
public class FileCleanupProcessor {

    private final StoredFileRepository storedFileRepository;
    private final StorageClient storageClient;

    /** 이번 회차가 집을 것 — 지워졌지만 아직 못 옮긴 것. */
    @Transactional(readOnly = true)
    public List<StoredFile> findMovable() {
        return storedFileRepository.findTop200ByStatusAndIsMovedFalseOrderByIdAsc(FileStatus.DELETED);
    }

    /**
     * 한 건을 옮긴다. <b>건당 독립 트랜잭션</b>이라 한 파일의 실패가 회차 전체를 되돌리지 않는다.
     *
     * <p>★ 이미 옮긴 행을 다시 집었으면 아무것도 하지 않는다 — 같은 회차를 두 번 돌리거나
     * 인스턴스 둘이 같은 행을 집어도 오브젝트를 두 번 옮기지 않는다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void move(Long fileId) {
        StoredFile file = storedFileRepository.findById(fileId).orElse(null);
        // ★ 이 isMoved 검사는 **빠른 탈출일 뿐 안전장치가 아니다.** markMoved 가 항상 격리 키를
        //   넣으므로 isMoved=true 이면 키는 반드시 격리 접두사 아래이고, 아래 키 비교가 같은 것을
        //   막는다(변이 테스트로 확인 — 이 줄을 지워도 행동이 같다). 진짜 방어선은 키 비교다.
        if (file == null || file.isMoved()) {
            return;
        }

        String sourceKey = file.getStorageKey();
        String movedKey = quarantineKey(file.getPurpose(), sourceKey);
        if (sourceKey.equals(movedKey)) {
            // ★ 자기 자신으로 옮기는 것을 막는다. moveObject 는 copy 후 **원본을 지우므로**,
            //   출발지와 목적지가 같으면 복사한 것을 그대로 지워 **파일이 사라진다.**
            file.markMoved(sourceKey);
            return;
        }

        try {
            storageClient.moveObject(sourceKey, movedKey);
        } catch (StorageException e) {
            // 원본이 없다. 이전 회차가 옮기고 **마킹 전에 죽은** 경우이므로 목적지에 물어본다 —
            // 거기 있으면 남은 일은 마킹뿐이다. 없으면 진짜 실패라 그대로 올린다.
            if (storageClient.headObject(movedKey).isEmpty()) {
                throw e;
            }
            log.warn("[file] 이동은 끝났는데 마킹을 못 한 행을 복구한다 file={} key={}", fileId, movedKey);
        }
        file.markMoved(movedKey);
    }

    /**
     * 격리 키 — `{대기실 접두사}{원래 키}`.
     *
     * <p>원래 키를 통째로 뒤에 붙이는 이유: 용도·파일 uuid가 그대로 보존되어 <b>어디서 온
     * 무엇인지 키만 보고 알 수 있다</b>. 복구할 때 접두사만 떼면 제자리로 돌아간다.
     */
    String quarantineKey(FilePurpose purpose, String storageKey) {
        String prefix = purpose.getRetention().getPathPrefix();
        return storageKey.startsWith(prefix) ? storageKey : prefix + storageKey;
    }
}
