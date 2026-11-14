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
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 탈퇴가 되살아나는 사고를 막는 잠금.
 *
 * ★ 무엇이 문제였나 — JPA 는 @DynamicUpdate 가 없으면 UPDATE 에 **전 컬럼**을 싣는다.
 *   관리자가 회원 행을 읽은 뒤 커밋하기 전에 그 회원이 탈퇴하면, 관리자 커밋이 자기가 읽었던
 *   낡은 스냅샷으로 전부 덮어써 deleted_at 이 풀리고 **탈퇴가 NULL 로 지운 이름·전화·이메일이
 *   되살아난다.** 상태 한 칸이 어긋나는 문제가 아니라 지운 개인정보가 부활하는 사고다.
 *
 * @Version 이 있으면 늦은 커밋이 예외로 드러나고 GlobalExceptionHandler 가 409 로 번역한다.
 * 리포지토리 테스트로 두는 이유: 서비스 테스트는 리포지토리를 목킹하므로 이 충돌을 못 만든다.
 */
@DataJpaTest
@Import(JpaAuditingConfig.class)
class UserOptimisticLockTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 9, 2, 10, 0);

    @Autowired UserRepository userRepository;
    @Autowired EntityManager entityManager;

    private User saved() {
        User user = userRepository.saveAndFlush(User.builder()
                .uuid("u-1").name("김이름").email("a@x.com")
                .phone("enc").phoneHash("HASH-1")
                .nickname("닉").nicknameNormalized("닉").isNicknameCustom(false)
                .preferredLanguage("ko").status(UserStatus.ACTIVE).role(UserRole.USER)
                .build());
        entityManager.flush();
        entityManager.clear();
        return user;
    }

    @Test
    @DisplayName("탈퇴와 겹친 관리자 갱신은 충돌로 드러난다 — 조용히 덮어쓰지 않는다")
    void concurrent_admin_update_conflicts_with_withdrawal() {
        Long id = saved().getId();

        // T2(관리자)가 읽은 시점의 스냅샷. 아직 커밋하지 않았다.
        User adminView = userRepository.findById(id).orElseThrow();
        entityManager.detach(adminView);

        // T1(사용자)이 먼저 탈퇴를 커밋한다.
        User self = userRepository.findById(id).orElseThrow();
        self.withdraw(AT);
        userRepository.saveAndFlush(self);
        entityManager.flush();
        entityManager.clear();

        // T2 가 뒤늦게 커밋한다 — 낡은 version 이라 통과하면 안 된다.
        adminView.suspend("도배", AT);
        assertThatThrownBy(() -> {
            userRepository.saveAndFlush(adminView);
            entityManager.flush();
        }).isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    // 충돌이 났을 때 실제로 지켜져야 하는 것 — 탈퇴 상태와 지워진 개인정보.
    @Test
    @DisplayName("충돌 뒤에도 탈퇴가 유지되고 지워진 개인정보가 돌아오지 않는다")
    void withdrawal_survives_the_conflict() {
        Long id = saved().getId();

        User adminView = userRepository.findById(id).orElseThrow();
        entityManager.detach(adminView);

        User self = userRepository.findById(id).orElseThrow();
        self.withdraw(AT);
        userRepository.saveAndFlush(self);
        entityManager.flush();
        entityManager.clear();

        adminView.suspend("도배", AT);
        try {
            userRepository.saveAndFlush(adminView);
            entityManager.flush();
        } catch (ObjectOptimisticLockingFailureException expected) {
            // 충돌은 위 테스트가 고정한다 — 여기서는 그 뒤 DB 상태만 본다.
        }
        entityManager.clear();

        User row = userRepository.findById(id).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(UserStatus.WITHDRAWN);
        assertThat(row.getDeletedAt()).isEqualTo(AT);
        assertThat(row.getName()).isNull();
        assertThat(row.getPhoneHash()).isNull();
        assertThat(row.getEmail()).isNull();
    }

    /*
     * ★ @Version 이 낳은 부작용의 회귀 방지. 엔티티를 반환하는 잠금 조회(JPQL + PESSIMISTIC_WRITE)는
     *   영속성 컨텍스트의 낡은 version 과 FOR UPDATE 가 읽은 최신 version 을 대조해서, 조회와 잠금
     *   사이에 무관한 users 갱신이 끼기만 해도 StaleObjectStateException 으로 죽는다 —
     *   임대요청 제출이 자기와 무관한 PATCH /me 에 409 를 맞았다(적대적 리뷰 2026-09-02).
     *   스칼라 네이티브 조회로 바꿔 그 대조 자체를 없앴다. 엔티티 잠금 조회로 되돌리면 이 테스트가 죽는다.
     */
    @Test
    @DisplayName("제출 직렬화 잠금은 영속성 컨텍스트의 낡은 version 과 충돌하지 않는다")
    void submission_lock_ignores_stale_version_in_persistence_context() {
        Long id = saved().getId();

        // 임대요청 흐름 재현: 유저가 영속성 컨텍스트에 이미 올라와 있어도
        userRepository.findById(id).orElseThrow();

        // 그 사이 무관한 갱신(다른 기기의 PATCH /me 등)이 version 을 올린다.
        entityManager.createNativeQuery("UPDATE users SET version = version + 1 WHERE id = ?1")
                .setParameter(1, id).executeUpdate();

        // 잠금은 걸리되 version 대조는 없어야 한다 — 엔티티 잠금 조회였다면 여기서 Stale 로 죽는다.
        assertThat(userRepository.findIdByUuidForUpdate("u-1")).contains(id);
    }

    // 탈퇴자는 잠그지 않는다 — 잠글 행이 없으면 호출부(getActiveUser)가 올바른 코드로 거절한다.
    @Test
    @DisplayName("탈퇴한 유저의 잠금 조회는 빈 Optional 이다")
    void withdrawn_user_is_not_lockable() {
        Long id = saved().getId();
        User self = userRepository.findById(id).orElseThrow();
        self.withdraw(AT);
        userRepository.saveAndFlush(self);

        assertThat(userRepository.findIdByUuidForUpdate("u-1")).isEmpty();
    }
}
