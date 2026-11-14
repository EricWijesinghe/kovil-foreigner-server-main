package com.kovil.domain.auth.service.support;

import com.kovil.domain.auth.dto.TokenPair;
import com.kovil.domain.refreshtoken.dto.result.RefreshTokenIssueResult;
import com.kovil.domain.refreshtoken.service.RefreshTokenService;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.infra.jwt.JwtProperties;
import com.kovil.infra.jwt.JwtTokenProvider;
import com.kovil.domain.partner.enums.PartnerDivisionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/*
 * ★ 이 클래스는 호출부마다 목으로 대체되어 **한 줄도 실행되지 않고 있었다**
 *   (적대적 리뷰 2026-09-17 3라운드). 그 상태에서는 아래가 조용히 되돌아간다:
 *
 *   액세스 토큰의 역할을 user 엔티티에서 읽으면, 로그인 트랜잭션의 스냅샷이 비밀번호 검증
 *   (수백 ms) 중에 커밋된 강등을 보지 못해 **강등된 사람이 옛 권한 토큰을 받아 간다.**
 *   발급 결과(refresh)는 users 행 잠금 읽기에서 나온 값이라 그 경합을 통과한 뒤의 진실이다.
 */
@ExtendWith(MockitoExtension.class)
class TokenIssuerTest {

    private static final Instant NOW = Instant.parse("2026-09-17T10:00:00Z");

    @Mock RefreshTokenService refreshTokenService;

    private TokenIssuer issuer;
    private JwtTokenProvider provider;

    @BeforeEach
    void setUp() {
        JwtProperties properties =
                new JwtProperties("x".repeat(64), Duration.ofMinutes(30), "kovil");
        provider = new JwtTokenProvider(properties, Clock.fixed(NOW, ZoneId.of("Asia/Seoul")));
        issuer = new TokenIssuer(provider, refreshTokenService, properties);
    }

    @Test
    @DisplayName("액세스 토큰의 역할은 엔티티가 아니라 발급 결과에서 온다")
    void access_token_role_comes_from_issue_result() {
        User staleAdmin = User.builder().uuid("stale-uuid").role(UserRole.ADMIN).build();
        given(refreshTokenService.issue(staleAdmin)).willReturn(
                new RefreshTokenIssueResult("raw", 1L, "fresh-uuid", UserRole.USER));

        TokenPair pair = issuer.issue(staleAdmin);

        assertThat(provider.parse(pair.accessToken()).role()).isEqualTo(UserRole.USER);
        assertThat(provider.parse(pair.accessToken()).userUuid()).isEqualTo("fresh-uuid");
    }

    @Test
    @DisplayName("회전도 같은 출처를 쓴다")
    void reissue_uses_the_same_source() {
        given(refreshTokenService.rotate("old")).willReturn(
                new RefreshTokenIssueResult("raw", 1L, "u", UserRole.ADMIN));

        TokenPair pair = issuer.reissue("old");

        assertThat(provider.parse(pair.accessToken()).role()).isEqualTo(UserRole.ADMIN);
    }

    /*
     * ★ 승인된 부문도 발급 결과(users 잠금 뒤의 잠금 읽기)에서 온다. 토큰의 divisions 클레임과
     *   로그인 응답이 쓰는 TokenPair 의 부문이 같은 값이어야 한다 — 어긋나면 화면은 열렸다고
     *   그리는데 API 는 403 을 낸다.
     */
    @Test
    @DisplayName("파트너의 승인 부문은 토큰 클레임과 TokenPair 에 같은 값으로 실린다")
    void partner_divisions_flow_from_issue_result() {
        User partner = User.builder().uuid("p-uuid").role(UserRole.PARTNER).build();
        given(refreshTokenService.issue(partner)).willReturn(new RefreshTokenIssueResult(
                "raw", 1L, "p-uuid", UserRole.PARTNER,
                Set.of(PartnerDivisionType.REAL_ESTATE_BROKER)));

        TokenPair pair = issuer.issue(partner);

        assertThat(provider.parse(pair.accessToken()).approvedDivisions())
                .containsExactly(PartnerDivisionType.REAL_ESTATE_BROKER);
        assertThat(pair.approvedDivisions())
                .containsExactly(PartnerDivisionType.REAL_ESTATE_BROKER);
    }
}
