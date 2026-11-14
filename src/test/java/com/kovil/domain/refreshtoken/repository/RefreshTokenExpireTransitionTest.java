package com.kovil.domain.refreshtoken.repository;

import com.kovil.common.config.JpaAuditingConfig;
import com.kovil.domain.refreshtoken.entity.RefreshToken;
import com.kovil.domain.refreshtoken.enums.RefreshTokenStatus;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EXPIRED 마킹은 ACTIVE→EXPIRED 전이만 허용한다.
 *
 * ★ 읽고-쓰기(findById → entity.expire)로 되돌리면 안 된다 — 그 읽기와 커밋 사이 밀리초 창에서
 *   revokeAllFor(재설정·탈퇴·정지)가 같은 행을 REVOKED 로 커밋하면, 무조건 대입이 그걸 EXPIRED 로
 *   덮어써 재사용 탐지(도난 대응) 신호가 지워진다(적대적 리뷰 2026-09-02).
 *   조건부 벌크 UPDATE 는 어떤 인터리빙에서도 REVOKED 를 건드리지 않는다 — 여기서 실검증한다.
 */
@DataJpaTest
@Import(JpaAuditingConfig.class)
class RefreshTokenExpireTransitionTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 9, 2, 12, 0);

    @Autowired RefreshTokenRepository repository;
    @Autowired UserRepository userRepository;
    @Autowired EntityManager entityManager;

    private RefreshToken saved(RefreshTokenStatus status) {
        User user = userRepository.save(User.builder()
                .uuid("u-" + status).nickname("N" + status).nicknameNormalized("n" + status)
                .isNicknameCustom(false).preferredLanguage("ko")
                .status(UserStatus.ACTIVE).role(UserRole.USER).build());
        return repository.saveAndFlush(RefreshToken.builder()
                .user(user).tokenHash("hash-" + status).status(status)
                .issuedAt(AT.minusDays(31)).expiresAt(AT.minusDays(1)).build());
    }

    @Test
    @DisplayName("ACTIVE 토큰은 EXPIRED 로 전이된다")
    void active_token_expires() {
        RefreshToken token = saved(RefreshTokenStatus.ACTIVE);

        int updated = repository.expireIfActive(token.getId(), AT);
        entityManager.clear();

        assertThat(updated).isEqualTo(1);
        assertThat(repository.findById(token.getId()).orElseThrow().getStatus())
                .isEqualTo(RefreshTokenStatus.EXPIRED);
    }

    @ParameterizedTest(name = "{0} 는 그대로")
    @EnumSource(value = RefreshTokenStatus.class, names = {"REVOKED", "ROTATED"})
    @DisplayName("REVOKED · ROTATED 토큰은 덮어쓰지 않는다 — ROTATED 는 재사용 탐지 신호다")
    void ended_token_is_never_overwritten(RefreshTokenStatus status) {
        RefreshToken token = saved(status);

        int updated = repository.expireIfActive(token.getId(), AT);
        entityManager.clear();

        assertThat(updated).isZero();
        assertThat(repository.findById(token.getId()).orElseThrow().getStatus()).isEqualTo(status);
    }
}
