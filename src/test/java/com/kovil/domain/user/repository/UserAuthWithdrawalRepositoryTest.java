package com.kovil.domain.user.repository;

import com.kovil.common.config.JpaAuditingConfig;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.entity.UserAuth;
import com.kovil.domain.user.enums.AuthType;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 탈퇴 → 재가입에서 로그인 수단이 어떻게 쌓이는지를 진짜 DB 로 고정한다.
 *
 * 정책(PO 확정 2026-09-01): 탈퇴는 soft delete — 행도 식별자도 남긴다.
 * 재가입은 옛 행을 **건드리지 않고 새 행을 넣는다.** 그래서 같은 신원의 행이 여러 개 쌓이고,
 * 그중 **살아 있는 것은 언제나 최대 하나**다.
 *
 * ★ 정직 보고 — "살아 있는 행끼리만 유니크"는 V19 의 생성 컬럼(active_identifier)이 강제하는데,
 *   테스트는 H2 + ddl-auto 라 생성 컬럼이 없다. **여기서 고정되는 것은 조회 규약까지**이고,
 *   유니크 자체는 운영 스키마에서만 성립한다. 배포 후 실측이 필요하다.
 */
@DataJpaTest
@Import(JpaAuditingConfig.class)
class UserAuthWithdrawalRepositoryTest {

    private static final String GOOGLE_SUB = "google-sub-fixed-1";
    private static final LocalDateTime WITHDRAWN_AT = LocalDateTime.of(2026, 9, 1, 12, 0);

    @Autowired
    UserRepository userRepository;

    @Autowired
    UserAuthRepository userAuthRepository;

    private User user(String uuid, String phoneHash) {
        return userRepository.save(User.builder()
                .uuid(uuid)
                .name("Tester")
                .phoneHash(phoneHash)
                .nickname(uuid)
                .nicknameNormalized(uuid)
                .isNicknameCustom(false)
                .preferredLanguage("ko")
                .status(UserStatus.ACTIVE)
                .role(UserRole.USER)
                .build());
    }

    private UserAuth link(User user, AuthType type, String identifier) {
        return userAuthRepository.save(UserAuth.builder()
                .user(user)
                .authType(type)
                .identifier(identifier)
                .build());
    }

    // 식별자를 남기는 것이 핵심이다 — 옛 계정이 무엇으로 가입했는지가 여기 말고는 남지 않는다.
    @Test
    @DisplayName("탈퇴는 해제만 한다 — 행도 식별자도 남는다")
    void unlink_keeps_the_row_and_identifier() {
        User owner = user("u-1", "HASH-1");
        UserAuth auth = link(owner, AuthType.GOOGLE, GOOGLE_SUB);

        auth.unlink(WITHDRAWN_AT);
        userAuthRepository.saveAndFlush(auth);

        assertThat(userAuthRepository.findAll()).singleElement().satisfies(saved -> {
            assertThat(saved.getIdentifier()).isEqualTo(GOOGLE_SUB);
            assertThat(saved.getDeletedAt()).isEqualTo(WITHDRAWN_AT);
        });
    }

    /*
     * ★ 재가입의 정본. 옛 행을 되살리지 않는다 — 되살리면 옛 계정의 가입 이력이 사라지고,
     *   동시 요청 둘이 같은 행을 UPDATE 해 한쪽 계정이 조용히 로그인 수단 0개가 된다.
     *   새 행을 넣으면 옛 행이 그대로 남고, 경합은 유니크 위반으로 드러난다.
     */
    @Test
    @DisplayName("재가입은 새 행을 넣고 옛 행은 옛 계정에 그대로 둔다")
    void resignup_inserts_a_new_row_and_keeps_the_old_one() {
        User first = user("u-2", "HASH-2");
        UserAuth old = link(first, AuthType.GOOGLE, GOOGLE_SUB);
        old.unlink(WITHDRAWN_AT);
        userAuthRepository.saveAndFlush(old);

        User rejoined = user("u-3", "HASH-3");
        link(rejoined, AuthType.GOOGLE, GOOGLE_SUB);
        userAuthRepository.flush();

        // 같은 신원의 행이 둘 — 하나는 옛 계정의 이력, 하나는 새 계정의 수단.
        assertThat(userAuthRepository.findAll()).hasSize(2);
        assertThat(userAuthRepository.findAllByUserId(first.getId()))
                .singleElement()
                .satisfies(auth -> assertThat(auth.getDeletedAt()).isEqualTo(WITHDRAWN_AT));
    }

    // 로그인은 언제나 살아 있는 행 하나만 집는다 — 해제된 옛 행이 몇 개 쌓여도 흔들리지 않는다.
    @Test
    @DisplayName("로그인 조회는 살아 있는 행만 집는다")
    void login_lookup_sees_only_the_live_row() {
        User first = user("u-4", "HASH-4");
        UserAuth old = link(first, AuthType.GOOGLE, GOOGLE_SUB);
        old.unlink(WITHDRAWN_AT);
        userAuthRepository.saveAndFlush(old);

        User rejoined = user("u-5", "HASH-5");
        link(rejoined, AuthType.GOOGLE, GOOGLE_SUB);
        userAuthRepository.flush();

        assertThat(userAuthRepository.findByAuthTypeAndIdentifierAndDeletedAtIsNull(
                AuthType.GOOGLE, GOOGLE_SUB))
                .get()
                .extracting(auth -> auth.getUser().getUuid())
                .isEqualTo("u-5");
    }

    // 탈퇴 때 해제 대상을 모으는 축 — 해제된 행도 포함해야 두 번째 탈퇴에서 빠지지 않는다.
    @Test
    @DisplayName("findAllByUserId 는 해제된 행도 포함한다")
    void find_all_includes_released_rows() {
        User owner = user("u-6", "HASH-6");
        UserAuth released = link(owner, AuthType.FACEBOOK, "fb-sub-1");
        released.unlink(WITHDRAWN_AT);
        userAuthRepository.saveAndFlush(released);
        link(owner, AuthType.GOOGLE, GOOGLE_SUB);

        assertThat(userAuthRepository.findAllByUserId(owner.getId())).hasSize(2);
    }
}
