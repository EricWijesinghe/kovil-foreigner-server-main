package com.kovil.domain.user.repository;

import com.kovil.common.config.JpaAuditingConfig;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

// searchForAdmin 의 phone_hash 축을 진짜 DB 에 고정한다 — 서비스 테스트는 리포지토리를
// 목킹하므로 JPQL 의 파라미터 결합(널 처리·축 간섭)을 잡지 못한다.
@DataJpaTest
@Import(JpaAuditingConfig.class)
class AdminUserSearchRepositoryTest {

    @Autowired
    UserRepository userRepository;

    private User user(String uuid, String name, String email, String phoneHash, UserRole role) {
        return userRepository.save(User.builder()
                .uuid(uuid)
                .name(name)
                .email(email)
                .phoneHash(phoneHash)
                .nickname(uuid)
                .nicknameNormalized(uuid)
                .isNicknameCustom(false)
                .preferredLanguage("ko")
                .status(UserStatus.ACTIVE)
                .role(role)
                .build());
    }

    private List<String> search(UserStatus status, UserRole role, String query, String phoneHash) {
        return userRepository.searchForAdmin(status, role, query, phoneHash, PageRequest.of(0, 20))
                .getContent().stream().map(User::getUuid).toList();
    }

    @Test
    @DisplayName("phone_hash 완전일치 — 텍스트 축과 간섭하지 않는다")
    void phone_hash_exact_match() {
        user("u-a", "Andy", "a@x.com", "HASH-A", UserRole.USER);
        user("u-b", "Andy Twin", "b@x.com", "HASH-B", UserRole.USER);

        assertThat(search(null, null, null, "HASH-A")).containsExactly("u-a");
        // 이름이 같아도 해시 축에서는 해시만 본다 — LIKE 가 끼어들면 둘 다 나온다.
        assertThat(search(null, null, null, "HASH-Z")).isEmpty();
    }

    @Test
    @DisplayName("phoneHash 가 null 이면 기존 텍스트 축이 그대로 동작한다 — 회귀 방지")
    void text_axis_unchanged_when_hash_null() {
        user("u-a", "Andy", "a@x.com", "HASH-A", UserRole.USER);
        user("u-c", "Cindy", "c@x.com", null, UserRole.USER);

        assertThat(search(null, null, "Andy", null)).containsExactly("u-a");
        assertThat(search(null, null, null, null)).containsExactlyInAnyOrder("u-a", "u-c");
    }

    @Test
    @DisplayName("해시 축에도 status·role 필터가 함께 걸린다")
    void filters_combine_with_hash_axis() {
        User suspended = user("u-s", "Sam", null, "HASH-S", UserRole.USER);
        suspended.suspend("정지 사유", LocalDateTime.of(2026, 8, 31, 10, 0));
        userRepository.save(suspended);

        assertThat(search(UserStatus.ACTIVE, null, null, "HASH-S")).isEmpty();
        assertThat(search(UserStatus.SUSPENDED, null, null, "HASH-S")).containsExactly("u-s");
    }

    /*
     * ★ 탈퇴는 status 와 deleted_at 을 **함께** 찍는다. 목록이 deleted_at IS NULL 을 하드 필터하면
     *   관리자 화면의 "탈퇴" 드롭다운이 영원히 빈 페이지를 준다 — 필터가 있는데 아무것도 못 찾는
     *   화면은 고장난 화면이다(적대적 리뷰 2026-09-02).
     */
    @Test
    @DisplayName("기본 목록은 탈퇴자를 감추고, status=WITHDRAWN 을 고르면 보여준다")
    void withdrawn_is_hidden_by_default_but_findable_by_filter() {
        user("u-live", "Live", "live@x.com", "HASH-L", UserRole.USER);
        User gone = user("u-gone", "Gone", "gone@x.com", "HASH-G", UserRole.USER);
        gone.withdraw(LocalDateTime.of(2026, 9, 2, 10, 0));
        userRepository.saveAndFlush(gone);

        assertThat(search(null, null, null, null)).containsExactly("u-live");
        assertThat(search(UserStatus.WITHDRAWN, null, null, null)).containsExactly("u-gone");
        // 다른 상태를 고르면 탈퇴자가 섞여 들어오면 안 된다.
        assertThat(search(UserStatus.ACTIVE, null, null, null)).containsExactly("u-live");
    }
}
