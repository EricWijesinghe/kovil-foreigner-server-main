package com.kovil.domain.auth.service;

import com.kovil.domain.auth.exception.AuthErrorCode;
import com.kovil.domain.auth.exception.OAuthException;
import com.kovil.domain.user.service.UserService;
import com.kovil.infra.oauth.apple.AppleAccountEvent;
import com.kovil.infra.oauth.apple.AppleIdTokenVerifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

// 공개 주소로 오는 알림이라 서명을 통과한 것만 세션을 끊는다. 이메일 알림은 기록만 한다.
@ExtendWith(MockitoExtension.class)
class AppleAccountEventServiceTest {

    @Mock UserService userService;
    @Mock AppleIdTokenVerifier verifier;

    private AppleAccountEventService service() {
        return new AppleAccountEventService(userService, verifier);
    }

    @Test
    @DisplayName("consent-revoked 는 그 sub 의 Apple 세션을 끊는다")
    void consent_revoked_ends_session() {
        given(verifier.verifyEvent("jws")).willReturn(new AppleAccountEvent("consent-revoked", "sub-1"));

        service().handle("jws");

        then(userService).should().endAppleSession("sub-1");
    }

    @Test
    @DisplayName("account-delete 도 세션을 끊는다")
    void account_delete_ends_session() {
        given(verifier.verifyEvent("jws")).willReturn(new AppleAccountEvent("account-delete", "sub-1"));

        service().handle("jws");

        then(userService).should().endAppleSession("sub-1");
    }

    @Test
    @DisplayName("email-disabled · email-enabled 는 아무것도 바꾸지 않는다")
    void email_events_are_only_logged() {
        given(verifier.verifyEvent("a")).willReturn(new AppleAccountEvent("email-disabled", "sub-1"));
        given(verifier.verifyEvent("b")).willReturn(new AppleAccountEvent("email-enabled", "sub-1"));

        service().handle("a");
        service().handle("b");

        then(userService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("서명 검증이 실패하면 세션을 건드리지 않고 그 오류가 그대로 나간다")
    void forged_payload_touches_nothing() {
        given(verifier.verifyEvent("forged")).willThrow(new OAuthException(AuthErrorCode.OAUTH_TOKEN_INVALID));

        assertThatThrownBy(() -> service().handle("forged")).isInstanceOf(OAuthException.class);
        then(userService).should(never()).endAppleSession(anyString());
    }
}
