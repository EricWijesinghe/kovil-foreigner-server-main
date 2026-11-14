package com.kovil.domain.auth.service;

import com.kovil.common.security.AuthenticatedUser;
import com.kovil.domain.auth.service.support.TokenIssuer;
import com.kovil.domain.refreshtoken.service.RefreshTokenService;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.repository.UserRepository;
import com.kovil.infra.jwt.BlacklistedTokenStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.ArgumentMatchers;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

/*
 * ★ 로그아웃의 **쓰기 쪽**은 어떤 테스트도 실행하지 않고 있었다 — add() 위임을 통째로 지워도
 *   전 저장소가 초록이었다(적대적 리뷰 2026-09-17 3라운드). 읽는 쪽(필터)만 고정하면
 *   "블랙리스트에 담기지 않는 로그아웃"이 조용히 통과한다: 리프레시만 끊기고 액세스 토큰은
 *   남은 수명(최대 30분)만큼 그대로 통한다.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock TokenIssuer tokenIssuer;
    @Mock UserRepository userRepository;
    @Mock RefreshTokenService refreshTokenService;
    @Mock BlacklistedTokenStore blacklistedTokenStore;

    @InjectMocks AuthService service;

    private AuthenticatedUser principal(Instant expiresAt) {
        return new AuthenticatedUser("user-uuid", UserRole.USER, "jti-1", expiresAt);
    }

    @Test
    @DisplayName("로그아웃은 리프레시를 끊고 액세스 토큰도 블랙리스트에 담는다")
    void logout_revokes_refresh_and_blacklists_access_token() {
        User user = User.builder().uuid("user-uuid").role(UserRole.USER).build();
        given(userRepository.findByUuidAndDeletedAtIsNull("user-uuid")).willReturn(Optional.of(user));

        service.logout(principal(Instant.now().plusSeconds(600)), "raw-refresh");

        then(refreshTokenService).should().revokeIfOwnedBy(eqRaw(), any());
        then(blacklistedTokenStore).should().add(ArgumentMatchers.eq("jti-1"), any(Duration.class));
    }

    // 이미 만료된 토큰에 음수 TTL 을 넘기면 Redis 가 거절한다 — 남은 수명은 0 으로 바닥을 친다.
    @Test
    @DisplayName("만료된 액세스 토큰의 남은 수명은 음수가 아니라 0이다")
    void remaining_never_goes_negative() {
        assertThat(principal(Instant.now().minusSeconds(60)).remaining()).isEqualTo(Duration.ZERO);
    }

    private static String eqRaw() {
        return ArgumentMatchers.eq("raw-refresh");
    }
}
