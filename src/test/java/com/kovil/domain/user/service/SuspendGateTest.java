package com.kovil.domain.user.service;

import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.user.exception.UserException;
import com.kovil.domain.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

// 정지의 관문은 getActiveUser 한 곳이다 — 여기가 막히면 살아 있는 토큰도 전부 막힌다.
@ExtendWith(MockitoExtension.class)
class SuspendGateTest {

    @Mock UserRepository userRepository;
    @InjectMocks UserQueryService userQueryService;

    @Test
    @DisplayName("정지된 유저는 살아 있는 토큰으로도 어떤 API도 지나지 못한다")
    void suspended_user_is_blocked_at_the_gate() {
        User suspended = User.builder().uuid("u").name("n").preferredLanguage("en")
                .status(UserStatus.SUSPENDED).role(UserRole.USER).build();
        given(userRepository.findByUuidAndDeletedAtIsNull("u")).willReturn(Optional.of(suspended));

        assertThatThrownBy(() -> userQueryService.getActiveUser("u"))
                .isInstanceOfSatisfying(UserException.class, e ->
                        assertThat(e.getErrorCode().getCode()).isEqualTo("USER_SUSPENDED"));
    }
}
