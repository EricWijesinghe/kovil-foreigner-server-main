package com.kovil.domain.file.service;

import com.kovil.domain.file.entity.StoredFile;
import com.kovil.domain.file.repository.StoredFileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

// 도메인이 "이 파일은 이제 필요 없다"고 말하는 **유일한 통로**다.
//
// 왜 한 곳으로 모으는가: 도메인마다 따로 지우면 어떤 곳은 원장만 고치고 어떤 곳은 스토리지만
// 건드리는 식으로 갈라진다. 여기서는 **원장에 표시만** 하고 실제 이동·파기는 정리 배치와
// R2 수명 규칙이 맡는다 — 도메인 트랜잭션이 스토리지 왕복을 기다리지 않아도 된다.
//
// ★ 스토리지를 여기서 건드리지 않는 것이 핵심이다. 글 삭제 트랜잭션 안에서 오브젝트를 지우면,
//   그 트랜잭션이 롤백돼도 파일은 이미 사라진다 — 글은 살아 있는데 사진만 없는 상태가 된다.
@Slf4j
@Service
@RequiredArgsConstructor
public class FileDeletionService {

    private final StoredFileRepository storedFileRepository;
    private final Clock clock;

    /** 파일 uuid 로 버린다. 없는 uuid·이미 버린 것은 조용히 넘어간다(재시도 안전). */
    @Transactional
    public int discardByUuids(Collection<String> fileUuids) {
        if (fileUuids == null || fileUuids.isEmpty()) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        List<StoredFile> targets = fileUuids.stream()
                .distinct()
                .map(storedFileRepository::findByUuidAndDeletedAtIsNull)
                .flatMap(Optional::stream)
                .toList();

        return discard(targets, now);
    }

    /**
     * 공개 URL 로 버린다 — 비민감 파일을 참조하는 도메인이 쓰는 축
     * (`community_post_images.image_url`).
     *
     * <p>★ 민감 파일은 `file_url` 이 NULL 이라 이 경로로는 절대 지워지지 않는다(INV-08).
     * 그쪽은 uuid 로만 버린다.
     */
    @Transactional
    public int discardByUrls(Collection<String> fileUrls) {
        if (fileUrls == null || fileUrls.isEmpty()) {
            return 0;
        }
        List<String> distinct = fileUrls.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return 0;
        }
        return discard(storedFileRepository.findByFileUrlInAndDeletedAtIsNull(distinct),
                LocalDateTime.now(clock));
    }

    /**
     * 공개 URL + <b>업로더</b>로 버린다 — <b>사용자가 보낸 URL</b>을 근거로 버릴 때는 반드시 이쪽이다.
     *
     * <p>★ {@link #discardByUrls} 는 URL 만 보고 매칭한다. 사용자가 고른 URL 에 그걸 쓰면
     * 남의 파일을 지울 수 있다 — 커뮤니티 이미지는 공개 URL 이라 남의 글에서 주소를 베껴
     * 자기 글에 붙인 뒤 그 글을 지우면 원래 주인의 사진이 폐기 표시된다(적대적 리뷰 2026-09-23).
     *
     * <p>URL 의 출처가 사용자가 아니라 <b>우리 원장</b>인 경우(예: {@code users.profile_image_url})는
     * 그쪽을 써도 된다 — 사용자가 값을 고를 수 없기 때문이다.
     */
    @Transactional
    public int discardByUrlsOwnedBy(Collection<String> fileUrls, Long uploaderId) {
        if (fileUrls == null || fileUrls.isEmpty() || uploaderId == null) {
            return 0;
        }
        List<String> distinct = fileUrls.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return 0;
        }
        return discard(
                storedFileRepository.findByFileUrlInAndUploaderIdAndDeletedAtIsNull(distinct, uploaderId),
                LocalDateTime.now(clock));
    }

    /**
     * 파일 id 로 버린다 — 도메인이 FK 로 파일을 들고 있는 축
     * (`foreigner_verification_documents.file_id`).
     */
    @Transactional
    public int discardByIds(Collection<Long> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return 0;
        }
        List<StoredFile> targets = storedFileRepository.findAllById(fileIds).stream()
                .filter(file -> file.getDeletedAt() == null)
                .toList();
        return discard(targets, LocalDateTime.now(clock));
    }

    private int discard(List<StoredFile> targets, LocalDateTime now) {
        targets.forEach(file -> file.markDeleted(now));
        if (!targets.isEmpty()) {
            log.info("[file] 폐기 표시 {}건", targets.size());
        }
        return targets.size();
    }
}
