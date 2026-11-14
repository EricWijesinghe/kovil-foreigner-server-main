package com.kovil.domain.file.service;

import com.kovil.domain.file.entity.StoredFile;
import com.kovil.domain.file.enums.FilePurpose;
import com.kovil.domain.file.repository.StoredFileRepository;
import com.kovil.infra.storage.StorageClient;
import com.kovil.infra.storage.StoredObjectMetadata;
import com.kovil.infra.storage.exception.StorageErrorCode;
import com.kovil.infra.storage.exception.StorageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

/*
 * ★ 이 배치가 없던 동안 지워진 파일은 **본 디렉터리에 영원히 남았다** — markDeleted 는
 *   호출부가 0곳이었고 정리 배치도 없었다. 여권 사진이 탈퇴 후에도 그대로 있었다는 뜻이다.
 *
 * 여기서 고정하는 것은 둘이다:
 *   ① 행선지가 용도에 따라 갈린다 — 법정 보존 대상이 짧은 대기실로 가면 의무를 어긴다
 *   ② 몇 번을 돌려도 결과가 같다 — 배치가 중간에 죽는 것은 정상이고, 그때 복구된다
 */
@ExtendWith(MockitoExtension.class)
class FileCleanupProcessorTest {

    @Mock StoredFileRepository storedFileRepository;
    @Mock StorageClient storageClient;

    @InjectMocks FileCleanupProcessor processor;

    private StoredFile deletedFile(Long id, FilePurpose purpose, String key) {
        StoredFile file = StoredFile.builder()
                .uuid("f-" + id).storageKey(key).contentType("image/jpeg")
                .byteSize(100L).purpose(purpose).build();
        setId(file, id);
        file.markDeleted(LocalDateTime.now());
        return file;
    }

    private static void setId(StoredFile file, Long id) {
        try {
            var field = StoredFile.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(file, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /*
     * 법정 보존 의무가 있는 것과 없는 것은 **머무는 기간이 다르다**. 한 대기실에 넣으면
     * 짧은 수명 규칙이 5년 의무를 지운다 — 그래서 접두사부터 갈라 둔다.
     */
    @Test
    @DisplayName("법정 보존 대상은 deleted/ 가 아니라 retained/ 로 간다")
    void legal_files_go_to_retained() {
        assertThat(processor.quarantineKey(FilePurpose.CONTRACT_DOC, "contract-docs/a.pdf"))
                .isEqualTo("retained/5y/contract-docs/a.pdf");
        assertThat(processor.quarantineKey(FilePurpose.MOVE_IN_PHOTO, "move-in-photos/b.jpg"))
                .isEqualTo("retained/3y/move-in-photos/b.jpg");
    }

    /*
     * ★ 신분 증빙도 3년 격리다(PO 확정 2026-09-23) — 임대 중 사고가 나면 "이 사람이
     *   누구였는지"를 증명할 유일한 근거다. 계약 종료 직후 탈퇴해도 퇴거 정산 분쟁은
     *   그 뒤에 터진다.
     */
    @Test
    @DisplayName("신분 증빙은 분쟁 기간만큼 격리된다")
    void verification_docs_are_retained_for_dispute_window() {
        assertThat(processor.quarantineKey(FilePurpose.VERIFICATION_DOC, "verification-docs/c.jpg"))
                .isEqualTo("retained/3y/verification-docs/c.jpg");
    }

    // 보존 의무가 없는 것만 짧은 대기실로 간다.
    @Test
    @DisplayName("보존 의무가 없는 것은 deleted/ 로 간다")
    void ordinary_files_go_to_deleted() {
        assertThat(processor.quarantineKey(FilePurpose.COMMUNITY_IMAGE, "community-images/d.jpg"))
                .isEqualTo("deleted/community-images/d.jpg");
        assertThat(processor.quarantineKey(FilePurpose.PROFILE_IMAGE, "profile-images/e.png"))
                .isEqualTo("deleted/profile-images/e.png");
    }

    // 원래 키를 통째로 뒤에 붙인다 — 접두사만 떼면 제자리로 돌아가고, 키만 보고 출처를 안다.
    @Test
    @DisplayName("격리 키는 원래 경로를 그대로 품는다")
    void quarantine_key_keeps_origin() {
        String moved = processor.quarantineKey(FilePurpose.PROFILE_IMAGE, "profile-images/uuid.png");
        assertThat(moved).endsWith("profile-images/uuid.png");
        assertThat(moved.replace("deleted/", "")).isEqualTo("profile-images/uuid.png");
    }

    @Test
    @DisplayName("옮기면 스토리지가 움직이고 키와 이동 표시가 함께 바뀐다")
    void move_updates_storage_and_row() {
        StoredFile file = deletedFile(1L, FilePurpose.VERIFICATION_DOC, "verification-docs/x.jpg");
        given(storedFileRepository.findById(1L)).willReturn(Optional.of(file));

        processor.move(1L);

        then(storageClient).should().moveObject(
                "verification-docs/x.jpg", "retained/3y/verification-docs/x.jpg");
        assertThat(file.getStorageKey()).isEqualTo("retained/3y/verification-docs/x.jpg");
        assertThat(file.isMoved()).isTrue();
    }

    /*
     * ★ 배치는 중간에 죽는 것이 정상이다. 같은 행을 다시 집었을 때 오브젝트를 두 번 옮기면
     *   두 번째 moveObject 는 원본이 없어 실패하고, 그 행은 영원히 재시도에 걸린다.
     */
    @Test
    @DisplayName("이미 옮긴 행은 다시 집어도 스토리지를 건드리지 않는다")
    void already_moved_is_noop() {
        StoredFile file = deletedFile(2L, FilePurpose.VERIFICATION_DOC, "verification-docs/y.jpg");
        file.markMoved("retained/3y/verification-docs/y.jpg");
        given(storedFileRepository.findById(2L)).willReturn(Optional.of(file));

        processor.move(2L);

        then(storageClient).should(never()).moveObject(anyString(), anyString());
    }

    /*
     * ★ **진짜 크래시 창이다.** 스토리지 이동은 끝났는데 커밋 전에 죽었다.
     *
     *   이때 DB 는 아무것도 모른다 — markMoved 가 키와 플래그를 한 UPDATE 로 쓰므로 롤백되면
     *   **둘 다** 원래대로다. 즉 "아직 안 옮긴 행"과 생김새가 똑같다. 그래서 DB 를 보고
     *   판정할 수 없고, 다시 옮겨 보고 **실패하면 목적지에 물어야** 한다.
     *
     *   이게 없으면 그 행은 매일 밤 NoSuchKey 로 실패하며 회차 슬롯을 영원히 점유한다.
     */
    @Test
    @DisplayName("이동 후 마킹 전에 죽은 행은 목적지를 확인해 마킹만 하고 끝낸다")
    void crashed_after_move_is_recovered_from_storage_truth() {
        StoredFile file = deletedFile(3L, FilePurpose.CONTRACT_DOC, "contract-docs/z.pdf");
        given(storedFileRepository.findById(3L)).willReturn(Optional.of(file));
        willThrow(new StorageException(StorageErrorCode.STORAGE_UNAVAILABLE))
                .given(storageClient).moveObject("contract-docs/z.pdf", "retained/5y/contract-docs/z.pdf");
        given(storageClient.headObject("retained/5y/contract-docs/z.pdf"))
                .willReturn(Optional.of(new StoredObjectMetadata("application/pdf", 10L)));

        processor.move(3L);

        assertThat(file.isMoved()).isTrue();
        assertThat(file.getStorageKey()).isEqualTo("retained/5y/contract-docs/z.pdf");
    }

    /*
     * 목적지에도 없으면 그건 복구가 아니라 **진짜 실패**다. 여기서 삼키면 옮기지도 않은 행이
     * "옮겼다"고 표시되어 정리 대상에서 영구히 빠진다 — 파일이 본 디렉터리에 그대로 남는다.
     */
    @Test
    @DisplayName("목적지에도 없으면 실패를 삼키지 않는다")
    void real_failure_is_not_swallowed() {
        StoredFile file = deletedFile(4L, FilePurpose.CONTRACT_DOC, "contract-docs/w.pdf");
        given(storedFileRepository.findById(4L)).willReturn(Optional.of(file));
        willThrow(new StorageException(StorageErrorCode.STORAGE_UNAVAILABLE))
                .given(storageClient).moveObject(anyString(), anyString());
        given(storageClient.headObject(anyString())).willReturn(Optional.empty());

        assertThatThrownBy(() -> processor.move(4L)).isInstanceOf(StorageException.class);

        assertThat(file.isMoved()).isFalse();
        assertThat(file.getStorageKey()).isEqualTo("contract-docs/w.pdf");
    }

    /*
     * ★ 출발지와 목적지가 같으면 **절대 옮기면 안 된다.** moveObject 는 copy 후 원본을 지우므로
     *   자기 자신으로 옮기면 복사한 것을 그대로 지워 파일이 사라진다.
     */
    @Test
    @DisplayName("이미 격리 접두사 아래인 행은 스토리지를 건드리지 않는다 — 자기 자신으로 옮기면 사라진다")
    void self_move_is_never_attempted() {
        StoredFile file = deletedFile(3L, FilePurpose.CONTRACT_DOC, "retained/5y/contract-docs/z.pdf");
        given(storedFileRepository.findById(3L)).willReturn(Optional.of(file));

        processor.move(3L);

        then(storageClient).should(never()).moveObject(anyString(), anyString());
        assertThat(file.isMoved()).isTrue();
        assertThat(file.getStorageKey()).isEqualTo("retained/5y/contract-docs/z.pdf");
    }

    @Test
    @DisplayName("사라진 행은 조용히 넘어간다")
    void missing_row_is_silent() {
        given(storedFileRepository.findById(9L)).willReturn(Optional.empty());

        processor.move(9L);

        then(storageClient).should(never()).moveObject(anyString(), anyString());
    }

    /*
     * ★ 용도를 새로 추가하면서 retention 을 판단하지 않고 복붙하면, 그 파일이 어느 대기실로
     *   갈지가 조용히 정해진다. 여기서 전수로 못을 박아 둔다 — 새 용도가 들어오면 이 테스트가
     *   먼저 말을 건다.
     */
    @Test
    @DisplayName("모든 용도가 행선지를 가진다")
    void every_purpose_has_a_destination() {
        Arrays.stream(FilePurpose.values()).forEach(purpose -> {
            assertThat(purpose.getRetention())
                    .describedAs("%s 에 retention 이 없다", purpose)
                    .isNotNull();
            assertThat(purpose.getRetention().getPathPrefix()).endsWith("/");
        });
    }
}
