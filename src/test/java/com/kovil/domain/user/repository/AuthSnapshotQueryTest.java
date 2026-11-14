package com.kovil.domain.user.repository;

import com.kovil.common.config.JpaAuditingConfig;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * ★ 토큰에 실릴 역할이 여기서 나온다. 발급 경로의 서비스 테스트는 이 메서드를 목으로 대체하므로
 *   **쿼리 자체는 한 줄도 실행되지 않는다** — 별칭이 어긋나거나 컬럼명이 틀리면 모든 로그인이
 *   런타임에 깨지는데 전 테스트가 초록이다(관리자 수 쿼리와 같은 구멍, 적대적 리뷰 2026-09-17).
 *
 * ★ 탈퇴자도 반드시 조회돼야 한다. 탈퇴 처리가 이 조회로 취소선을 긋기 때문이다 —
 *   deleted_at 을 걸러 버리면 탈퇴한 사람의 액세스 토큰이 수명만큼 살아남는다.
 */
@DataJpaTest
@Import(JpaAuditingConfig.class)
class AuthSnapshotQueryTest {

    @Autowired UserRepository userRepository;
    @Autowired EntityManager entityManager;

    private User persist(String uuid, UserRole role, UserStatus status, LocalDateTime deletedAt) {
        User user = User.builder()
                .uuid(uuid).name("n")
                .nickname(uuid).nicknameNormalized(uuid).isNicknameCustom(false)
                .preferredLanguage("ko").status(status).role(role)
                .build();
        if (deletedAt != null) {
            user.withdraw(deletedAt);
        }
        return userRepository.saveAndFlush(user);
    }

    @Test
    @DisplayName("uuid·역할·상태를 그대로 읽는다")
    void reads_uuid_role_and_status() {
        User saved = persist("snap-1", UserRole.PARTNER, UserStatus.ACTIVE, null);
        entityManager.clear();

        UserRepository.AuthSnapshot snapshot =
                userRepository.findAuthSnapshotForUpdate(saved.getId()).orElseThrow();

        assertThat(snapshot.getUuid()).isEqualTo("snap-1");
        // 문자열로 돌아온다 — UserRole.from 이 그대로 받을 수 있어야 한다.
        assertThat(UserRole.from(snapshot.getRole())).isEqualTo(UserRole.PARTNER);
        assertThat(UserStatus.from(snapshot.getStatus())).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    @DisplayName("탈퇴자도 조회된다 — 그래야 탈퇴가 액세스 토큰까지 끊는다")
    void withdrawn_user_is_still_found() {
        User saved = persist("snap-2", UserRole.USER, UserStatus.WITHDRAWN, LocalDateTime.now());
        entityManager.clear();

        Optional<UserRepository.AuthSnapshot> snapshot =
                userRepository.findAuthSnapshotForUpdate(saved.getId());

        assertThat(snapshot).isPresent();
        assertThat(UserStatus.from(snapshot.orElseThrow().getStatus())).isEqualTo(UserStatus.WITHDRAWN);
    }

    @Test
    @DisplayName("없는 사용자는 빈 값이다")
    void missing_user_is_empty() {
        assertThat(userRepository.findAuthSnapshotForUpdate(999_999L)).isEmpty();
    }
}
