package com.kovil.domain.file.service;

import com.kovil.domain.file.entity.StoredFile;
import com.kovil.domain.file.enums.FilePurpose;
import com.kovil.domain.file.enums.FileStatus;
import com.kovil.domain.file.repository.StoredFileRepository;
import com.kovil.infra.storage.FakeStorageClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * ★ 목이 아니라 **실제 트랜잭션·실제 프록시·실제 파생 쿼리**로 생명주기를 한 바퀴 돌린다.
 *
 * 단위 테스트가 절대 볼 수 없는 것들이 여기서만 드러난다:
 *   ① `move` 의 REQUIRES_NEW 가 진짜로 걸리나 — 같은 빈 안에서 부르면 프록시를 안 타고
 *      조용히 바깥 트랜잭션에 합류한다. 스케줄러가 다른 빈이라는 사실이 여기서 증명된다.
 *   ② `findTop200ByStatusAndIsMovedFalseOrderByIdAsc` 가 실제로 파생되고, `IsMovedFalse` 가
 *      Boolean 래퍼 컬럼을 제대로 거르나.
 *   ③ 더티체킹으로 바뀐 storage_key·is_moved 가 **DB 까지 내려가나**. 엔티티만 보면 통과하고
 *      DB 는 옛 값인 경우가 있다(ReassignFlushTest 가 잡았던 updatable=false 계열).
 *
 * ⚠ 이 클래스에 @Transactional 을 붙이면 안 된다. REQUIRES_NEW 가 바깥 트랜잭션을 **중단**하고
 *   새 트랜잭션에서 findById 를 하므로, 아직 커밋 안 된 행을 **못 본다** — 전부 조용히 no-op 이 되고
 *   테스트는 초록으로 거짓말한다.
 */
@SpringBootTest
class FileLifecycleIntegrationTest {

    @Autowired StoredFileRepository storedFileRepository;
    @Autowired FileDeletionService fileDeletionService;
    @Autowired FileCleanupScheduler scheduler;
    @Autowired FileCleanupProcessor processor;
    @Autowired FakeStorageClient storageClient;

    private final List<Long> created = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        storedFileRepository.deleteAllById(created);
        created.clear();
    }

    private StoredFile persist(FilePurpose purpose, String key, String publicUrl) {
        StoredFile saved = storedFileRepository.save(StoredFile.builder()
                .uuid(UUID.randomUUID().toString())
                .storageKey(key)
                .contentType("image/jpeg")
                .byteSize(1024L)
                .purpose(purpose)
                .fileUrl(publicUrl)
                .build());
        created.add(saved.getId());
        return saved;
    }

    private StoredFile reload(Long id) {
        return storedFileRepository.findById(id).orElseThrow();
    }

    /*
     * 한 바퀴: 업로드된 상태 → 도메인이 버림 → 배치가 옮김.
     * 용도마다 행선지가 갈리는 것을 **DB 에서 읽어** 확인한다.
     */
    @Test
    @DisplayName("버린 파일이 용도별 대기실로 실제로 옮겨지고 DB 에 남는다")
    void discarded_files_are_moved_to_their_quarantine() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        StoredFile passport = persist(FilePurpose.VERIFICATION_DOC, "verification-docs/" + tag + ".jpg", null);
        StoredFile contract = persist(FilePurpose.CONTRACT_DOC, "contract-docs/" + tag + ".pdf", null);
        StoredFile photo = persist(FilePurpose.COMMUNITY_IMAGE, "community-images/" + tag + ".jpg",
                "https://files.example/community-images/" + tag + ".jpg");

        fileDeletionService.discardByIds(List.of(passport.getId(), contract.getId(), photo.getId()));

        scheduler.run();

        assertThat(reload(passport.getId()).getStorageKey())
                .isEqualTo("retained/3y/verification-docs/" + tag + ".jpg");
        assertThat(reload(contract.getId()).getStorageKey())
                .isEqualTo("retained/5y/contract-docs/" + tag + ".pdf");
        assertThat(reload(photo.getId()).getStorageKey())
                .isEqualTo("deleted/community-images/" + tag + ".jpg");

        assertThat(reload(passport.getId()).isMoved()).isTrue();
        assertThat(reload(passport.getId()).getStatus()).isEqualTo(FileStatus.DELETED);

        // 스토리지도 실제로 움직였다 — DB 만 고치고 오브젝트를 두면 파일은 본 디렉터리에 남는다.
        assertThat(storageClient.movedObjects())
                .containsEntry("verification-docs/" + tag + ".jpg",
                        "retained/3y/verification-docs/" + tag + ".jpg");
    }

    /*
     * ★ 배치는 매일 돈다. 두 번째 회차가 이미 옮긴 행을 다시 집어 moveObject 를 또 부르면
     *   원본이 없어 실패하고 그 행은 영원히 재시도에 걸린다.
     */
    @Test
    @DisplayName("두 번 돌려도 한 번 옮긴 것과 결과가 같다")
    void second_run_changes_nothing() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        StoredFile file = persist(FilePurpose.PROFILE_IMAGE, "profile-images/" + tag + ".png",
                "https://files.example/profile-images/" + tag + ".png");
        fileDeletionService.discardByIds(List.of(file.getId()));

        scheduler.run();
        String afterFirst = reload(file.getId()).getStorageKey();
        scheduler.run();

        assertThat(reload(file.getId()).getStorageKey()).isEqualTo(afterFirst);
        assertThat(afterFirst).isEqualTo("deleted/profile-images/" + tag + ".png");
        // 두 번째 회차가 집었다면 키가 deleted/deleted/... 로 겹쳤을 것이다.
        assertThat(afterFirst).doesNotContain("deleted/deleted/");
    }

    /*
     * ★ 이미 옮긴 행은 **후보 목록에서 빠져야** 한다. 걸러지지 않으면 회차마다 그 행을 다시 집어
     *   빠른 탈출만 하고 끝난다 — 회차 정원(200건)을 옛 행이 채워 **새로 버린 파일이 영원히
     *   차례를 못 받는다.** 배치는 매일 성공한 것처럼 보이는데 대기실은 안 비워진다.
     *
     *   move() 가 멱등이라는 것만으로는 이게 안 잡힌다. 걸러내기는 **쿼리**의 일이다.
     */
    @Test
    @DisplayName("이미 옮긴 행은 후보에서 빠진다 — 옛 행이 회차 정원을 먹으면 새 파일이 굶는다")
    void already_moved_rows_leave_the_candidate_list() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        StoredFile old = persist(FilePurpose.PROFILE_IMAGE, "profile-images/" + tag + "-old.png", null);
        fileDeletionService.discardByIds(List.of(old.getId()));
        scheduler.run();
        assertThat(reload(old.getId()).isMoved()).isTrue();

        StoredFile fresh = persist(FilePurpose.PROFILE_IMAGE, "profile-images/" + tag + "-new.png", null);
        fileDeletionService.discardByIds(List.of(fresh.getId()));

        // 배치가 실제로 쓰는 이음매로 확인한다 — 리포지토리 메서드 이름이 아니라 **행동**을 고정한다.
        assertThat(processor.findMovable())
                .extracting(StoredFile::getId)
                .doesNotContain(old.getId())
                .contains(fresh.getId());
    }

    // 살아 있는 파일을 배치가 집으면 쓰는 중인 사진이 사라진다.
    @Test
    @DisplayName("버리지 않은 파일은 배치가 건드리지 않는다")
    void active_files_are_untouched() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        StoredFile alive = persist(FilePurpose.PROFILE_IMAGE, "profile-images/" + tag + ".png",
                "https://files.example/profile-images/" + tag + ".png");

        scheduler.run();

        assertThat(reload(alive.getId()).getStorageKey()).isEqualTo("profile-images/" + tag + ".png");
        assertThat(reload(alive.getId()).isMoved()).isFalse();
        assertThat(reload(alive.getId()).getStatus()).isEqualTo(FileStatus.ACTIVE);
    }

    /*
     * ★ 민감 파일은 `file_url` 이 NULL 이다(INV-08). URL 축으로 지우려 하면 **조용히 0건**이 되고
     *   여권 사진이 남는다. 이 경로를 쓰는 호출부가 생기면 여기서 먼저 말을 건다.
     */
    @Test
    @DisplayName("민감 파일은 URL 축으로는 지워지지 않는다 — uuid·id 축으로만 버릴 수 있다")
    void sensitive_files_cannot_be_discarded_by_url() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        StoredFile passport = persist(FilePurpose.VERIFICATION_DOC, "verification-docs/" + tag + ".jpg", null);

        int byUrl = fileDeletionService.discardByUrls(
                List.of("https://files.example/verification-docs/" + tag + ".jpg"));

        assertThat(byUrl).isZero();
        assertThat(reload(passport.getId()).getStatus()).isEqualTo(FileStatus.ACTIVE);

        assertThat(fileDeletionService.discardByIds(List.of(passport.getId()))).isEqualTo(1);
        assertThat(reload(passport.getId()).getStatus()).isEqualTo(FileStatus.DELETED);
    }

    // 같은 파일을 두 번 버려도 두 번 세지 않는다 — 호출부가 재시도해도 안전해야 한다.
    @Test
    @DisplayName("이미 버린 파일을 다시 버려도 0건이다")
    void discarding_twice_is_idempotent() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        StoredFile file = persist(FilePurpose.COMMUNITY_IMAGE, "community-images/" + tag + ".jpg",
                "https://files.example/community-images/" + tag + ".jpg");

        assertThat(fileDeletionService.discardByIds(List.of(file.getId()))).isEqualTo(1);
        assertThat(fileDeletionService.discardByIds(List.of(file.getId()))).isZero();
    }
}
