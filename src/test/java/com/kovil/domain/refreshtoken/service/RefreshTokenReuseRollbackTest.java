package com.kovil.domain.refreshtoken.service;

import com.kovil.domain.auth.service.AuthService;
import com.kovil.domain.refreshtoken.dto.result.RefreshTokenIssueResult;
import com.kovil.domain.refreshtoken.entity.RefreshToken;
import com.kovil.domain.refreshtoken.enums.RefreshTokenStatus;
import com.kovil.domain.refreshtoken.exception.RefreshTokenException;
import com.kovil.domain.refreshtoken.repository.RefreshTokenRepository;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// ★ 실측에서 잡은 결함의 회귀 테스트.
//
// 재사용 탐지는 세션을 전량 폐기한 뒤 예외를 던진다. 그런데 기본 규칙이
// "RuntimeException이면 롤백"이라, **폐기가 통째로 되돌려지고 경고 로그만 남았다.**
// 단위 테스트는 엔티티 메서드 호출만 확인해서 이 구간을 볼 수 없다 — 트랜잭션이 없기 때문이다.
// 그래서 실제 컨텍스트에서 커밋 결과를 본다.
@SpringBootTest
class RefreshTokenReuseRollbackTest {

    @Autowired
    RefreshTokenService refreshTokenService;

    // ★ 실제 경로로 부른다. rotate()를 직접 부르면 바깥 트랜잭션이 없어 결함이 숨는다.
    @Autowired
    AuthService authService;

    @Autowired
    RefreshTokenRepository refreshTokenRepository;

    @Autowired
    UserRepository userRepository;

    private User newUser() {
        return userRepository.save(User.builder()
                .uuid(UUID.randomUUID().toString())
                .name("reuse-test")
                .phoneHash(UUID.randomUUID().toString())
                .status(UserStatus.ACTIVE)
                .role(UserRole.USER)
                .build());
    }

    // 회전으로 받은 자식이 한 번 더 회전된 뒤(자식이 쓰임)라 유예 창 안이어도 탈취로 본다 — 응답 유실로 설명되지 않는다.
    @Test
    @DisplayName("회전에 쓴 토큰을 재사용하면 그 계정의 살아 있는 세션이 실제로 DB에서 전부 끊긴다")
    void reuse_revokes_every_session_and_survives_the_rollback() {
        User user = newUser();
        RefreshTokenIssueResult first = refreshTokenService.issue(user);
        RefreshTokenIssueResult other = refreshTokenService.issue(user);

        // 정상 회전 두 번 — first 의 자식이 다시 회전돼 rotated 가 살아난다.
        RefreshTokenIssueResult rotated = refreshTokenService.rotate(
                refreshTokenService.rotate(first.rawToken()).rawToken());

        assertThat(activeCount(user)).isEqualTo(2);

        // 도난 시나리오: 이미 회전된 토큰이 다시 온다.
        // AuthService.refresh가 바깥 트랜잭션이라 여기를 통과해야 진짜 검증이다.
        assertThatThrownBy(() -> authService.refresh(first.rawToken()))
                .isInstanceOf(RefreshTokenException.class);

        // ★ 여기가 핵심이다. 롤백되면 이 값이 2로 남는다.
        assertThat(activeCount(user))
                .as("재사용 탐지 후 살아 있는 세션이 남아 있으면 폐기가 롤백된 것이다")
                .isZero();

        // 다른 기기 세션도, 방금 회전으로 받은 토큰도 더는 쓸 수 없어야 한다.
        assertThatThrownBy(() -> authService.refresh(rotated.rawToken()))
                .isInstanceOf(RefreshTokenException.class);
        assertThatThrownBy(() -> authService.refresh(other.rawToken()))
                .isInstanceOf(RefreshTokenException.class);
    }

    /*
     * 응답만 잃은 기기 — 회전은 커밋됐는데 새 쌍이 기기에 안 닿아 옛 토큰을 다시 보낸다.
     * 유예 창 안이고 자식이 안 쓰였으니 그 자식만 끊긴다: 이 기기만 다시 로그인하고 다른 기기는 산다.
     * ★ 자식 폐기가 바깥 예외에 롤백되지 않는지도 여기서 본다(별도 트랜잭션).
     */
    @Test
    @DisplayName("회전 직후 옛 토큰이 다시 오면 그 자식만 끊기고 다른 기기는 산다")
    void reuse_right_after_rotation_revokes_only_that_child() {
        User user = newUser();
        RefreshTokenIssueResult lost = refreshTokenService.issue(user);
        RefreshTokenIssueResult other = refreshTokenService.issue(user);
        RefreshTokenIssueResult unseen = refreshTokenService.rotate(lost.rawToken());

        assertThatThrownBy(() -> authService.refresh(lost.rawToken()))
                .isInstanceOf(RefreshTokenException.class);

        assertThat(activeCount(user)).as("다른 기기 하나만 남는다").isEqualTo(1);

        // 그 401 도 사라져 같은 옛 토큰을 창 안에 또 보낸다 — 여전히 401 만이고 다른 기기는 산다.
        assertThatThrownBy(() -> authService.refresh(lost.rawToken()))
                .isInstanceOf(RefreshTokenException.class);
        assertThat(activeCount(user)).as("세 번째 전송도 다른 기기를 끊지 않는다").isEqualTo(1);

        assertThatThrownBy(() -> authService.refresh(unseen.rawToken()))
                .isInstanceOf(RefreshTokenException.class);
        assertThat(authService.refresh(other.rawToken())).isNotNull();
    }

    // 로그아웃으로 끊긴 기기가 나중에 리프레시해도 탈취가 아니다 — 그 토큰만 401, 다른 기기는 그대로 산다.
    @Test
    @DisplayName("로그아웃한 토큰이 다시 와도 다른 세션은 끊기지 않는다")
    void logged_out_token_does_not_cascade() {
        User user = newUser();
        RefreshTokenIssueResult loggedOut = refreshTokenService.issue(user);
        RefreshTokenIssueResult other = refreshTokenService.issue(user);
        refreshTokenService.revokeIfOwnedBy(loggedOut.rawToken(), user.getId());

        assertThatThrownBy(() -> authService.refresh(loggedOut.rawToken()))
                .isInstanceOf(RefreshTokenException.class);

        assertThat(activeCount(user)).isEqualTo(1);
        assertThat(authService.refresh(other.rawToken())).isNotNull();
    }

    private long activeCount(User user) {
        List<RefreshToken> tokens = refreshTokenRepository.findAll();
        return tokens.stream()
                .filter(token -> token.getUser().getId().equals(user.getId()))
                .filter(token -> token.getStatus() == RefreshTokenStatus.ACTIVE)
                .count();
    }
}
