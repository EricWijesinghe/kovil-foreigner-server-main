package com.kovil.domain.user.enums;

import com.kovil.domain.user.exception.InvalidUserRoleException;
import com.kovil.domain.user.exception.UserErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserRoleTest {

    @ParameterizedTest
    @ValueSource(strings = {"PARTNER", "partner", "Partner"})
    @DisplayName("대소문자와 무관하게 변환된다")
    void from_is_case_insensitive(String value) {
        assertThat(UserRole.from(value)).isEqualTo(UserRole.PARTNER);
    }

    // 역할은 셋뿐이다. 사라진 역할 이름이 어디선가 들어오면(옛 토큰 · 옛 화면) 조용히 다른 역할이 되지 않는다.
    @ParameterizedTest
    @ValueSource(strings = {"MANAGER", "BROKER"})
    @DisplayName("사라진 역할(MANAGER · BROKER)은 INVALID_USER_ROLE 로 거절한다")
    void retired_roles_are_rejected(String value) {
        assertThatThrownBy(() -> UserRole.from(value))
                .isInstanceOf(InvalidUserRoleException.class)
                .extracting("errorCode")
                .isEqualTo(UserErrorCode.INVALID_USER_ROLE);
    }

    @Test
    @DisplayName("역할은 USER · PARTNER · ADMIN 셋이다")
    void exactly_three_roles() {
        assertThat(UserRole.values()).containsExactlyInAnyOrder(UserRole.USER, UserRole.PARTNER, UserRole.ADMIN);
    }

    @Test
    @DisplayName("null은 USER_ROLE_NULL로 구분해서 거절한다")
    void null_is_rejected_with_its_own_code() {
        assertThatThrownBy(() -> UserRole.from(null))
                .isInstanceOf(InvalidUserRoleException.class)
                .extracting("errorCode")
                .isEqualTo(UserErrorCode.USER_ROLE_NULL);
    }

    @Test
    @DisplayName("모르는 값은 INVALID_USER_ROLE로 거절한다")
    void unknown_value_is_rejected() {
        assertThatThrownBy(() -> UserRole.from("SUPERADMIN"))
                .isInstanceOf(InvalidUserRoleException.class)
                .extracting("errorCode")
                .isEqualTo(UserErrorCode.INVALID_USER_ROLE);
    }

    @Test
    @DisplayName("빈 문자열도 INVALID_USER_ROLE로 거절한다")
    void blank_is_rejected() {
        assertThatThrownBy(() -> UserRole.from(""))
                .isInstanceOf(InvalidUserRoleException.class)
                .extracting("errorCode")
                .isEqualTo(UserErrorCode.INVALID_USER_ROLE);
    }
}
