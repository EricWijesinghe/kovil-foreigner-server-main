package com.kovil.domain.file.service;

import com.kovil.common.security.AuthenticatedUser;
import com.kovil.domain.file.entity.StoredFile;
import com.kovil.domain.file.enums.FilePurpose;
import com.kovil.domain.file.repository.StoredFileRepository;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.exception.UserErrorCode;
import com.kovil.domain.user.exception.UserException;
import com.kovil.domain.user.service.UserQueryService;
import com.kovil.infra.storage.StorageClient;
import com.kovil.infra.storage.StorageProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

// ★ 정지된 직원의 살아 있는 액세스 토큰이 민감 문서 통로로 남지 않아야 한다.
// 이 경로만 getActiveUser 정지 게이트를 우회하던 구멍을 막았다 — 판정도 토큰 롤이 아니라 DB다.
@ExtendWith(MockitoExtension.class)
class FileQueryServiceTest {

    @Mock StorageClient storageClient;
    @Mock StoredFileRepository storedFileRepository;
    @Mock FileAccessPolicy fileAccessPolicy;
    @Mock UserQueryService userQueryService;

    private FileQueryService service() {
        return new FileQueryService(storageClient,
                new StorageProperties("fake", 300L, 300L),
                storedFileRepository, fileAccessPolicy, userQueryService);
    }

    private static StoredFile passport() {
        return StoredFile.builder()
                .storageKey("k").contentType("image/png").byteSize(1L)
                .purpose(FilePurpose.VERIFICATION_DOC).uploaderId(7L).build();
    }

    private static AuthenticatedUser principal() {
        return new AuthenticatedUser("u-1", UserRole.ADMIN, "jti", Instant.now().plusSeconds(600));
    }

    @Test
    @DisplayName("정지된 계정은 토큰이 살아 있어도 민감 파일 URL을 받지 못한다")
    void suspended_user_is_gated() {
        given(storedFileRepository.findByUuidAndDeletedAtIsNull("f-1"))
                .willReturn(Optional.of(passport()));
        given(userQueryService.getActiveUser("u-1"))
                .willThrow(new UserException(UserErrorCode.USER_SUSPENDED));

        assertThatThrownBy(() -> service().issueReadUrl("f-1", principal()))
                .isInstanceOf(UserException.class);

        then(storageClient).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("판정은 DB에서 읽은 유저로 한다 — 토큰 클레임이 아니다")
    void policy_receives_db_user() {
        StoredFile file = passport();
        User dbUser = User.builder().uuid("u-1").role(UserRole.USER).build();
        given(storedFileRepository.findByUuidAndDeletedAtIsNull("f-1"))
                .willReturn(Optional.of(file));
        given(userQueryService.getActiveUser("u-1")).willReturn(dbUser);
        given(fileAccessPolicy.canRead(file, dbUser)).willReturn(true);
        given(storageClient.presignGet("k", Duration.ofSeconds(300))).willReturn("url");

        service().issueReadUrl("f-1", principal());

        then(fileAccessPolicy).should().canRead(file, dbUser);
    }
}
