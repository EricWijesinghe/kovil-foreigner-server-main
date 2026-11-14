package com.kovil.domain.user.repository;

import com.kovil.common.config.JpaAuditingConfig;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * 매물 등록이 INSERT 전에 잡는 작성자 행 공유 잠금. 서비스 테스트는 이 쿼리를 목으로 대체하므로 쿼리 자체는 여기서만 실행된다.
 *
 * ★ 잠금 모드가 공유(PESSIMISTIC_READ → MySQL FOR SHARE)인 것을 박는다. 배타로 바뀌면 지역 닫힘(지역 행을 쥔 채
 *   그 중개사에게 알림 INSERT — FK 검사가 users S)과 매물 등록이 교착한다(MySQL 9.5 재현). H2 는 공유 잠금 문법이 없어
 *   FOR UPDATE 로 실행되므로, 모드는 실행 결과가 아니라 선언으로 확인한다.
 * ★ 키가 id(기본 키)인 것도 박는다. uuid 로 공유 잠금을 걸면 MySQL 은 커버링 인덱스(uq_users_uuid)만 잠그고 FK 검사가 잡는
 *   PK 행은 잠그지 않아 교착이 그대로 난다(MySQL 9.5 실측).
 */
@DataJpaTest
@Import(JpaAuditingConfig.class)
class UserSharedLockQueryTest {

    @Autowired UserRepository userRepository;
    @Autowired EntityManager entityManager;

    private User persist(String uuid) {
        return userRepository.saveAndFlush(User.builder()
                .uuid(uuid).name("n")
                .nickname(uuid).nicknameNormalized(uuid).isNicknameCustom(false)
                .preferredLanguage("ko").status(UserStatus.ACTIVE).role(UserRole.PARTNER)
                .build());
    }

    @Test
    @DisplayName("그 사람의 id 를 돌려준다")
    void returns_the_id() {
        User saved = persist("lock-share-1");
        entityManager.clear();

        assertThat(userRepository.lockSharedById(saved.getId())).contains(saved.getId());
    }

    @Test
    @DisplayName("없는 id 는 빈 값이다")
    void unknown_id_is_empty() {
        assertThat(userRepository.lockSharedById(999_999L)).isEmpty();
    }

    @Test
    @DisplayName("영속성 컨텍스트의 User 와 버전을 대조하지 않는다 — 무관한 users 갱신이 끼어도 터지지 않는다")
    void does_not_compare_the_managed_entity_version() {
        User saved = persist("lock-share-2");
        entityManager.createNativeQuery("UPDATE users SET version = version + 1 WHERE id = :id")
                .setParameter("id", saved.getId())
                .executeUpdate();

        assertThat(userRepository.lockSharedById(saved.getId())).contains(saved.getId());
    }

    @Test
    @DisplayName("공유 잠금으로 선언돼 있다(PESSIMISTIC_READ) — 배타로 바꾸면 지역 닫힘과 교착한다")
    void is_declared_as_a_shared_lock() throws NoSuchMethodException {
        Lock lock = UserRepository.class.getMethod("lockSharedById", Long.class).getAnnotation(Lock.class);

        assertThat(lock).isNotNull();
        assertThat(lock.value()).isEqualTo(LockModeType.PESSIMISTIC_READ);
    }

    @Test
    @DisplayName("기본 키로 찾는다 — uuid 로 공유 잠금을 걸면 MySQL 이 PK 행을 잠그지 않는다")
    void locks_by_primary_key() throws NoSuchMethodException {
        Query query = UserRepository.class.getMethod("lockSharedById", Long.class).getAnnotation(Query.class);

        assertThat(query.value()).isEqualTo("SELECT u.id FROM User u WHERE u.id = :id");
    }
}
