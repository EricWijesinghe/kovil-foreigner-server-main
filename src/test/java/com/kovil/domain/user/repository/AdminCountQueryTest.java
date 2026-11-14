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

import static org.assertj.core.api.Assertions.assertThat;

/*
 * ★ "관리자가 몇 명 남았나"의 판정. 강등·탈퇴·정지가 모두 이 값으로 막힌다.
 *
 * 서비스 테스트는 이 메서드를 목으로 대체하므로 **쿼리 자체는 한 줄도 실행되지 않는다** —
 * 술어가 틀려도 아무도 모른다. 실제로 그랬다: status 를 보지 않아 정지된 관리자를 세었고,
 * 정지 → 탈퇴 두 수로 관리자가 0명이 될 수 있었다(적대적 리뷰 2026-09-17 2라운드).
 * 그래서 이 검사는 진짜 DB 를 쓴다.
 */
@DataJpaTest
@Import(JpaAuditingConfig.class)
class AdminCountQueryTest {

    @Autowired UserRepository userRepository;
    @Autowired EntityManager entityManager;

    private void admin(String uuid, UserStatus status, LocalDateTime deletedAt) {
        User user = User.builder()
                .uuid(uuid).name("n")
                .nickname(uuid).nicknameNormalized(uuid).isNicknameCustom(false)
                .preferredLanguage("ko").status(status).role(UserRole.ADMIN)
                .build();
        if (deletedAt != null) {
            user.withdraw(deletedAt);
            // withdraw 는 role 을 건드리지 않는다 — 탈퇴한 관리자도 role 은 ADMIN 으로 남는다.
        }
        userRepository.saveAndFlush(user);
    }

    @Test
    @DisplayName("쓸 수 있는 관리자만 센다 — 정지·탈퇴는 빠진다")
    void counts_only_usable_admins() {
        admin("live-1", UserStatus.ACTIVE, null);
        admin("suspended", UserStatus.SUSPENDED, null);
        admin("withdrawn", UserStatus.ACTIVE, LocalDateTime.of(2026, 9, 1, 0, 0));
        entityManager.flush();
        entityManager.clear();

        assertThat(userRepository.findIdsByRoleForUpdate(UserRole.ADMIN)).hasSize(1);
    }

    // 역할이 다르면 세지 않는다 — 파트너를 관리자로 세면 마지막 관리자 가드가 헐거워진다.
    @Test
    @DisplayName("다른 역할은 세지 않는다")
    void other_roles_are_not_counted() {
        admin("live-1", UserStatus.ACTIVE, null);
        userRepository.saveAndFlush(User.builder()
                .uuid("partner").name("n")
                .nickname("partner").nicknameNormalized("partner").isNicknameCustom(false)
                .preferredLanguage("ko").status(UserStatus.ACTIVE).role(UserRole.PARTNER)
                .build());
        entityManager.flush();
        entityManager.clear();

        assertThat(userRepository.findIdsByRoleForUpdate(UserRole.ADMIN)).hasSize(1);
    }
}
