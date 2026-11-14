package com.kovil.domain.file.service;

import com.kovil.domain.file.dto.request.ConfirmUploadRequest;
import com.kovil.domain.file.dto.request.PresignUploadRequest;
import com.kovil.domain.file.dto.response.FileResponse;
import com.kovil.domain.file.entity.StoredFile;
import com.kovil.domain.file.enums.FilePurpose;
import com.kovil.domain.file.exception.FileException;
import com.kovil.domain.file.repository.StoredFileRepository;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.user.service.UserQueryService;
import com.kovil.infra.storage.FakeStorageClient;
import com.kovil.infra.storage.StorageProperties;
import com.kovil.infra.storage.StoredObjectMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.ArgumentMatchers;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;

@ExtendWith(MockitoExtension.class)
class FileServiceTest {

    private static final String UUID = "user-uuid";
    // ★ 실제 발급되는 형식 그대로 쓴다. 가짜 형식("temp/abc.png")으로 두면 테스트가
    //   통과하면서 실제 데이터 형상을 위장한다.
    private static final String FILE_ID = "11111111-2222-3333-4444-555555555555";
    private static final String COMMUNITY_KEY = "temp/COMMUNITY_IMAGE/" + UUID + "/" + FILE_ID + ".png";
    private static final String DOC_KEY = "temp/VERIFICATION_DOC/" + UUID + "/" + FILE_ID + ".png";
    private static final String PROFILE_KEY = "temp/PROFILE_IMAGE/" + UUID + "/" + FILE_ID + ".png";

    @Mock StoredFileRepository storedFileRepository;
    @Mock UserQueryService userQueryService;

    private FakeStorageClient storageClient;
    private FileService service;

    @BeforeEach
    void setUp() {
        storageClient = spy(new FakeStorageClient());
        service = new FileService(storageClient, new StorageProperties("fake", 600, 300),
                storedFileRepository, userQueryService);
    }

    private static <T> T withId(T entity, Long id) {
        try {
            var field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return entity;
    }

    private void givenUser() {
        given(userQueryService.getActiveUser(UUID)).willReturn(withId(
                User.builder().uuid(UUID).status(UserStatus.ACTIVE).role(UserRole.USER).build(), 7L));
    }

    private void givenSaveEchoes() {
        given(storedFileRepository.save(any(StoredFile.class)))
                .willAnswer(inv -> withId(inv.getArgument(0), 1L));
    }

    private StoredFile captureSaved() {
        ArgumentCaptor<StoredFile> captor = ArgumentCaptor.forClass(StoredFile.class);
        then(storedFileRepository).should().save(captor.capture());
        return captor.getValue();
    }

    // presign에서 행을 만들면 "올리다 만" 행이 계속 쌓인다.
    @Test
    @DisplayName("발급 단계는 DB에 아무것도 남기지 않는다")
    void presign_writes_nothing_to_db() {
        givenUser();
        var response = service.presign(UUID,
                new PresignUploadRequest("VERIFICATION_DOC", "image/png", "passport.png"));

        assertThat(response.key()).startsWith("temp/").endsWith(".png");
        assertThat(response.uploadUrl()).isNotBlank();
        then(storedFileRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("용도가 받지 않는 형식이면 발급 자체를 거절한다")
    void presign_rejects_unsupported_type() {
        assertThatThrownBy(() -> service.presign(UUID,
                new PresignUploadRequest("PROFILE_IMAGE", "application/pdf", null)))
                .isInstanceOf(FileException.class)
                .extracting(e -> ((FileException) e).getErrorCode().getCode())
                .isEqualTo("UNSUPPORTED_CONTENT_TYPE");
    }

    // 계약 도메인과 함께 은퇴한 용도 — 상수는 남아 있지만(이미 저장된 행) 새 파일은 받지 않는다.
    @Test
    @DisplayName("은퇴한 용도는 발급하지 않는다 — 없는 용도와 같은 답이다")
    void presign_rejects_retired_purposes() {
        for (String purpose : new String[]{"CONTRACT_DOC", "MOVE_IN_PHOTO", "BANK_ICON"}) {
            assertThatThrownBy(() -> service.presign(UUID,
                    new PresignUploadRequest(purpose, "image/png", null)))
                    .as(purpose)
                    .isInstanceOf(FileException.class)
                    .extracting(e -> ((FileException) e).getErrorCode().getCode())
                    .isEqualTo("INVALID_FILE_PURPOSE");
        }
        then(storageClient).should(never()).presignPut(any(), any(), any());
    }

    // 은퇴 전에 받아 둔 presign 키로 올린 파일이 확정되면 소유자 없는 행이 새로 생긴다.
    @Test
    @DisplayName("은퇴한 용도는 확정하지 않는다 — 은퇴 전에 받은 키여도 행을 만들지 않는다")
    void confirm_rejects_retired_purposes() {
        String contractKey = "temp/CONTRACT_DOC/" + UUID + "/" + FILE_ID + ".pdf";

        assertThatThrownBy(() -> service.confirm(UUID,
                new ConfirmUploadRequest(contractKey, "CONTRACT_DOC", null)))
                .isInstanceOf(FileException.class)
                .extracting(e -> ((FileException) e).getErrorCode().getCode())
                .isEqualTo("INVALID_FILE_PURPOSE");

        then(storageClient).should(never()).moveObject(any(), any());
        then(storedFileRepository).shouldHaveNoInteractions();
    }

    // ★ INV-08 — 여권 사진 URL 하나가 유출되면 회복이 안 된다.
    @Test
    @DisplayName("민감 파일은 공개 URL을 만들지 않는다")
    void sensitive_file_has_no_public_url() {
        givenUser();
        givenSaveEchoes();

        FileResponse response = service.confirm(UUID,
                new ConfirmUploadRequest(DOC_KEY, "VERIFICATION_DOC", "passport.png"));

        assertThat(captureSaved().getFileUrl()).isNull();
        assertThat(response.sensitive()).isTrue();
        assertThat(response.fileUrl()).isNull();
    }

    @Test
    @DisplayName("비민감 파일은 공개 URL을 갖는다")
    void public_file_has_url() {
        givenUser();
        givenSaveEchoes();

        FileResponse response = service.confirm(UUID,
                new ConfirmUploadRequest(COMMUNITY_KEY, "COMMUNITY_IMAGE", null));

        assertThat(response.sensitive()).isFalse();
        assertThat(response.fileUrl()).isNotBlank();
    }

    // 클라이언트가 신고한 값을 믿으면 크기·형식 제한이 무의미해진다.
    @Test
    @DisplayName("크기·형식은 스토리지 실측값으로 판정한다")
    void confirm_uses_measured_metadata() {
        givenUser();
        given(storageClient.headObject(COMMUNITY_KEY))
                .willReturn(Optional.of(new StoredObjectMetadata("image/png", 999L)));
        givenSaveEchoes();

        service.confirm(UUID, new ConfirmUploadRequest(COMMUNITY_KEY, "COMMUNITY_IMAGE", null));

        StoredFile saved = captureSaved();
        assertThat(saved.getByteSize()).isEqualTo(999L);
        assertThat(saved.getContentType()).isEqualTo("image/png");
    }

    @Test
    @DisplayName("실측 용량이 상한을 넘으면 확정하지 않는다")
    void confirm_rejects_oversized() {
        givenUser();
        given(storageClient.headObject(PROFILE_KEY))
                .willReturn(Optional.of(new StoredObjectMetadata("image/png", 10L * 1024 * 1024)));

        assertThatThrownBy(() -> service.confirm(UUID,
                new ConfirmUploadRequest(PROFILE_KEY, "PROFILE_IMAGE", null)))
                .isInstanceOf(FileException.class)
                .extracting(e -> ((FileException) e).getErrorCode().getCode())
                .isEqualTo("FILE_TOO_LARGE");

        then(storedFileRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("실측 형식이 용도와 다르면 확정하지 않는다 — 신고값으로 우회할 수 없다")
    void confirm_rejects_type_mismatch() {
        givenUser();
        given(storageClient.headObject(COMMUNITY_KEY))
                .willReturn(Optional.of(new StoredObjectMetadata("application/pdf", 100L)));

        assertThatThrownBy(() -> service.confirm(UUID,
                new ConfirmUploadRequest(COMMUNITY_KEY, "COMMUNITY_IMAGE", null)))
                .isInstanceOf(FileException.class)
                .extracting(e -> ((FileException) e).getErrorCode().getCode())
                .isEqualTo("UNSUPPORTED_CONTENT_TYPE");
    }

    @Test
    @DisplayName("charset이 붙은 content-type도 정규화해서 받는다")
    void normalizes_content_type_with_charset() {
        givenUser();
        given(storageClient.headObject(COMMUNITY_KEY))
                .willReturn(Optional.of(new StoredObjectMetadata("image/png; charset=binary", 100L)));
        givenSaveEchoes();

        service.confirm(UUID, new ConfirmUploadRequest(COMMUNITY_KEY, "COMMUNITY_IMAGE", null));

        assertThat(captureSaved().getContentType()).isEqualTo("image/png");
    }

    @Test
    @DisplayName("올리지 않은 키는 404로 거절한다 — 복구 조회(final 키)까지 비어 있을 때만")
    void confirm_rejects_missing_object() {
        givenUser();
        // temp도 없고, 크래시 복구 후보(final 키)도 없다 — 그때가 진짜 "업로드 없음"이다.
        given(storageClient.headObject(ArgumentMatchers.anyString()))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirm(UUID,
                new ConfirmUploadRequest(COMMUNITY_KEY, "COMMUNITY_IMAGE", null)))
                .isInstanceOf(FileException.class)
                .extracting(e -> ((FileException) e).getErrorCode().getCode())
                .isEqualTo("UPLOADED_FILE_NOT_FOUND");
    }

    // ★ AUDIT_2026_08_26 — move 후 행 저장 전에 죽은 confirm의 재시도가 업로드를 되찾는다.
    //   무작위 finalKey 시절에는 이 창이 "업로드 영구 유실 + 재시도 404"였다.
    @Test
    @DisplayName("move까지 끝나고 죽은 재시도는 final 키에서 복구한다 — 다시 옮기지 않고 행만 만든다")
    void confirm_recovers_from_crash_window() {
        givenUser();
        givenSaveEchoes();
        // temp는 이미 이동됐고, 실제 오브젝트는 png 후보 키에만 있다(현실에는 후보 중 하나만 존재).
        given(storageClient.headObject(ArgumentMatchers.anyString()))
                .willReturn(Optional.empty());
        given(storageClient.headObject("verification-docs/" + FILE_ID + ".png"))
                .willReturn(Optional.of(new StoredObjectMetadata("image/png", 1024L)));

        var response = service.confirm(UUID, new ConfirmUploadRequest(DOC_KEY, "VERIFICATION_DOC", null));

        assertThat(response).isNotNull();
        assertThat(captureSaved().getStorageKey()).isEqualTo("verification-docs/" + FILE_ID + ".png");
        then(storageClient).should(never()).moveObject(any(), any());
    }

    // temp/ 밖의 키나 경로 탈출을 허용하면 남의 오브젝트를 확정 경로로 옮길 수 있다.
    @Test
    @DisplayName("temp/ 밖의 키와 경로 탈출은 거절한다")
    void confirm_rejects_foreign_key() {
        for (String key : new String[]{"verification-docs/other.png", "temp/../secret.png", "abc.png"}) {
            assertThatThrownBy(() -> service.confirm(UUID,
                    new ConfirmUploadRequest(key, "COMMUNITY_IMAGE", null)))
                    .as("key=%s", key)
                    .isInstanceOf(FileException.class)
                    .extracting(e -> ((FileException) e).getErrorCode().getCode())
                    .isEqualTo("INVALID_TEMP_KEY");
        }
        then(storedFileRepository).shouldHaveNoInteractions();
    }

    // 행동 변경(AUDIT): 연/월/무작위 → **파일 uuid 결정 키**. 무작위 키는 "move 후 저장 전
    // 죽음"을 영구 유실로 만들었다 — 재시도가 오브젝트를 못 찾는다. 결정성이 폴더 미관을 이긴다.
    @Test
    @DisplayName("확정 키는 파일 uuid로 결정적이다 — 재시도가 언제나 같은 키를 다시 찾을 수 있다")
    void final_key_is_deterministic() {
        givenUser();
        givenSaveEchoes();

        service.confirm(UUID, new ConfirmUploadRequest(DOC_KEY, "VERIFICATION_DOC", null));

        assertThat(captureSaved().getStorageKey())
                .isEqualTo("verification-docs/" + FILE_ID + ".png");
    }

    // ★★ INV-08의 급소. presign은 DB에 아무것도 남기지 않으므로, 키에 박힌 용도가
    //     "이 오브젝트가 무슨 용도로 발급됐는지"의 유일한 증거다. 이 검사가 없으면
    //     여권 스캔을 COMMUNITY_IMAGE로 확정해 **공개 URL을 만들 수 있다**.
    @Test
    @DisplayName("키에 박힌 용도와 신고한 용도가 다르면 확정하지 않는다 — 민감 파일 세탁 차단")
    void confirm_rejects_purpose_forgery() {
        givenUser();

        assertThatThrownBy(() -> service.confirm(UUID,
                new ConfirmUploadRequest(DOC_KEY, "COMMUNITY_IMAGE", null)))
                .isInstanceOf(FileException.class)
                .extracting(e -> ((FileException) e).getErrorCode().getCode())
                .isEqualTo("INVALID_TEMP_KEY");

        then(storedFileRepository).should(never()).save(any());
    }

    // 키 문자열만 알면 남이 올린 오브젝트를 자기 이름으로 확정할 수 있으면 안 된다.
    @Test
    @DisplayName("남의 키는 확정하지 못한다 — 업로더가 다르면 거절")
    void confirm_rejects_other_users_key() {
        givenUser();

        assertThatThrownBy(() -> service.confirm(UUID,
                new ConfirmUploadRequest("temp/COMMUNITY_IMAGE/someone-else/" + FILE_ID + ".png",
                        "COMMUNITY_IMAGE", null)))
                .isInstanceOf(FileException.class)
                .extracting(e -> ((FileException) e).getErrorCode().getCode())
                .isEqualTo("INVALID_TEMP_KEY");
    }

    @Test
    @DisplayName("형식이 어긋난 키는 거절한다 — 옛 형식(temp/xxx.png)도 포함")
    void confirm_rejects_malformed_key() {
        givenUser();

        assertThatThrownBy(() -> service.confirm(UUID,
                new ConfirmUploadRequest("temp/abc.png", "COMMUNITY_IMAGE", null)))
                .isInstanceOf(FileException.class)
                .extracting(e -> ((FileException) e).getErrorCode().getCode())
                .isEqualTo("INVALID_TEMP_KEY");
    }

    // ★ 확정은 copy+delete라 재시도하면 temp 오브젝트가 없다. 여기서 404를 내면
    //   응답만 유실된 경우 원장에 행은 있는데 클라이언트는 fileUuid를 영영 못 받는다
    //   — 아무 도메인에도 안 붙은 고아가 남고, 정리 배치도 없다.
    @Test
    @DisplayName("같은 키로 다시 확정하면 그때 만든 것을 그대로 돌려준다 — 새 행을 만들지 않는다")
    void confirm_is_idempotent() {
        givenUser();
        StoredFile existing = withId(StoredFile.builder()
                .uuid(FILE_ID)
                .storageKey("community-images/2026/08/" + FILE_ID + ".png")
                .contentType("image/png")
                .byteSize(100L)
                .purpose(FilePurpose.COMMUNITY_IMAGE)
                .uploaderId(7L)
                .fileUrl("https://cdn.example.com/x.png")
                .build(), 1L);
        given(storedFileRepository.findByUuidAndDeletedAtIsNull(FILE_ID))
                .willReturn(Optional.of(existing));

        FileResponse response = service.confirm(UUID,
                new ConfirmUploadRequest(COMMUNITY_KEY, "COMMUNITY_IMAGE", null));

        assertThat(response.fileUuid()).isEqualTo(FILE_ID);
        then(storedFileRepository).should(never()).save(any());
        then(storageClient).should(never()).moveObject(any(), any());
    }

    // 원장 식별자가 tempKey의 uuid와 같아야 위 재시도가 같은 행을 찾는다.
    @Test
    @DisplayName("원장 uuid는 키에 박힌 파일 uuid를 그대로 쓴다")
    void stored_uuid_comes_from_temp_key() {
        givenUser();
        givenSaveEchoes();

        service.confirm(UUID, new ConfirmUploadRequest(COMMUNITY_KEY, "COMMUNITY_IMAGE", null));

        assertThat(captureSaved().getUuid()).isEqualTo(FILE_ID);
    }

    @Test
    @DisplayName("발급 키에 용도와 업로더가 박힌다")
    void presign_key_carries_purpose_and_uploader() {
        givenUser();

        var response = service.presign(UUID,
                new PresignUploadRequest("COMMUNITY_IMAGE", "image/png", null));

        assertThat(response.key()).startsWith("temp/COMMUNITY_IMAGE/" + UUID + "/").endsWith(".png");
    }

    @Test
    @DisplayName("확정하면 temp에서 확정 경로로 실제로 옮긴다")
    void confirm_moves_object() {
        givenUser();
        givenSaveEchoes();

        service.confirm(UUID, new ConfirmUploadRequest(COMMUNITY_KEY, "COMMUNITY_IMAGE", null));

        assertThat(storageClient.movedObjects()).containsKey(COMMUNITY_KEY);
    }
}
