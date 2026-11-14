package com.kovil.domain.refreshtoken.service;

import com.kovil.domain.refreshtoken.entity.RefreshToken;
import com.kovil.domain.refreshtoken.enums.RefreshTokenStatus;
import com.kovil.domain.refreshtoken.repository.RefreshTokenRepository;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.user.repository.UserRepository;
import com.kovil.infra.jwt.BlacklistedTokenStore;
import com.kovil.infra.jwt.JwtProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.ArgumentMatchers;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

/*
 * ★ 이 클래스에는 테스트가 하나도 없었다(적대적 리뷰 2026-09-17 3라운드).
 *
 * 도난 대응은 리프레시 재사용을 탐지했을 때 부르는 경로다. 액세스 토큰 취소선을 빠뜨리면
 * **피해자만 쫓겨나고 탈취자는 남는다** — 피해자는 리프레시가 끊겨 로그아웃되지만, 탈취자가
 * 이미 받아 둔 액세스 토큰은 수명(기본 30분)만큼 그대로 통한다. 가장 아플 때 반만 끊는 셈이다.
 */
@ExtendWith(MockitoExtension.class)
class RefreshTokenRevokerTest {

    private static final Instant NOW = Instant.parse("2026-09-17T10:00:00Z");
    private static final Duration ACCESS_TTL = Duration.ofMinutes(30);

    @Mock RefreshTokenRepository refreshTokenRepository;
    @Mock UserRepository userRepository;
    @Mock BlacklistedTokenStore blacklistedTokenStore;

    private RefreshTokenRevoker revoker;

    @BeforeEach
    void setUp() {
        revoker = new RefreshTokenRevoker(
                refreshTokenRepository, userRepository, blacklistedTokenStore,
                new JwtProperties("x".repeat(64), ACCESS_TTL, "kovil"),
                Clock.fixed(NOW, ZoneId.of("Asia/Seoul")));
    }

    private void givenSnapshot() {
        given(userRepository.findAuthSnapshotForUpdate(7L)).willReturn(Optional.of(
                new UserRepository.AuthSnapshot() {
                    @Override public String getUuid() { return "user-uuid"; }
                    @Override public String getRole() { return UserRole.USER.name(); }
                    @Override public String getStatus() { return UserStatus.ACTIVE.name(); }
                }));
    }

    @Test
    @DisplayName("도난 대응은 리프레시와 액세스 토큰을 함께 끊는다")
    void theft_response_cuts_both_axes() {
        User user = User.builder().uuid("user-uuid").role(UserRole.USER).build();
        RefreshToken active = RefreshToken.builder()
                .user(user).tokenHash("h").status(RefreshTokenStatus.ACTIVE)
                .issuedAt(LocalDateTime.now(Clock.fixed(NOW, ZoneId.of("Asia/Seoul"))))
                .expiresAt(LocalDateTime.now(Clock.fixed(NOW, ZoneId.of("Asia/Seoul"))).plusDays(10))
                .build();
        given(refreshTokenRepository.findByUserIdAndStatusForUpdate(7L, RefreshTokenStatus.ACTIVE))
                .willReturn(List.of(active));
        givenSnapshot();

        revoker.revokeAllFor(7L);

        assertThat(active.getStatus()).isEqualTo(RefreshTokenStatus.REVOKED);
        then(blacklistedTokenStore).should().revokeAllBefore("user-uuid", NOW, ACCESS_TTL);
    }

    // 사라진 사용자에도 조용히 넘어가야 한다 — 여기서 터지면 도난 대응 자체가 실패한다.
    @Test
    @DisplayName("사용자를 못 찾으면 취소선은 긋지 않고 조용히 끝난다")
    void missing_user_is_silent() {
        given(refreshTokenRepository.findByUserIdAndStatusForUpdate(7L, RefreshTokenStatus.ACTIVE))
                .willReturn(List.of());
        given(userRepository.findAuthSnapshotForUpdate(7L)).willReturn(Optional.empty());

        revoker.revokeAllFor(7L);

        then(blacklistedTokenStore).should(never()).revokeAllBefore(
                ArgumentMatchers.anyString(),
                ArgumentMatchers.any(),
                ArgumentMatchers.any());
    }

    private RefreshToken child(RefreshTokenStatus status) {
        LocalDateTime now = LocalDateTime.now(Clock.fixed(NOW, ZoneId.of("Asia/Seoul")));
        return RefreshToken.builder().user(User.builder().uuid("user-uuid").role(UserRole.USER).build())
                .tokenHash("child").status(status).issuedAt(now).expiresAt(now.plusDays(10)).build();
    }

    // 유예 창 안의 재사용 — 자식이 안 쓰였으면 그 자식만 끊는다. 액세스 토큰 취소선은 사용자 단위라 긋지 않는다(다른 기기가 끊긴다).
    @Test
    @DisplayName("안 쓰인 자식은 끊고 true — 액세스 토큰 취소선은 긋지 않는다")
    void unused_child_is_revoked_alone() {
        RefreshToken child = child(RefreshTokenStatus.ACTIVE);
        given(refreshTokenRepository.findChildrenForUpdate(1L)).willReturn(List.of(child));

        assertThat(revoker.revokeUnusedChildOf(1L)).isTrue();

        assertThat(child.getStatus()).isEqualTo(RefreshTokenStatus.REVOKED);
        then(blacklistedTokenStore).shouldHaveNoInteractions();
    }

    // 응답이 연달아 두 번 사라진 기기의 세 번째 전송 — 앞선 유예 처리로 자식은 이미 끊겼지만 쓰인 적은 없다.
    @Test
    @DisplayName("이미 끊긴(쓰이지 않은) 자식은 true — 그대로 두고 전부 끊기로 넘기지 않는다")
    void already_revoked_child_is_not_a_use() {
        RefreshToken revoked = child(RefreshTokenStatus.REVOKED);
        given(refreshTokenRepository.findChildrenForUpdate(1L)).willReturn(List.of(revoked));

        assertThat(revoker.revokeUnusedChildOf(1L)).isTrue();
        assertThat(revoked.getStatus()).isEqualTo(RefreshTokenStatus.REVOKED);
    }

    @Test
    @DisplayName("자식이 이미 회전됐거나 없으면 false — 아무것도 바꾸지 않는다")
    void used_or_missing_child_is_false() {
        RefreshToken used = child(RefreshTokenStatus.ROTATED);
        given(refreshTokenRepository.findChildrenForUpdate(1L)).willReturn(List.of(used));
        given(refreshTokenRepository.findChildrenForUpdate(2L)).willReturn(List.of());

        assertThat(revoker.revokeUnusedChildOf(1L)).isFalse();
        assertThat(revoker.revokeUnusedChildOf(2L)).isFalse();
        assertThat(used.getStatus()).isEqualTo(RefreshTokenStatus.ROTATED);
    }
}
