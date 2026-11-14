package com.kovil.common.ratelimit;

import com.kovil.common.security.AuthenticatedUser;
import com.kovil.domain.user.enums.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class RateLimitInterceptorTest {

    private static final String CLIENT_IP = "203.0.113.7";
    private static final String USER_UUID = "user-uuid-77";

    @Mock
    private RateLimitStore store;

    private RateLimitInterceptor interceptor;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    // 인터셉터가 읽는 것은 HandlerMethod의 메서드 어노테이션이다 — 실제 컨트롤러 모양의 더미로 재현한다.
    @SuppressWarnings("unused")
    static class DummyController {

        @RateLimited({RateLimitPolicy.SIGNUP, RateLimitPolicy.SMS_GLOBAL})
        public ResponseEntity<Void> annotated() {
            return null;
        }

        @RateLimited(RateLimitPolicy.PHONE_VERIFICATION_SEND_IP)
        public ResponseEntity<Void> ipKeyed() {
            return null;
        }

        @RateLimited(RateLimitPolicy.PHONE_VERIFICATION_SEND)   // MANUAL — 붙어도 무시가 계약
        public ResponseEntity<Void> manualAnnotated() {
            return null;
        }

        @RateLimited({RateLimitPolicy.ADDRESS_SEARCH, RateLimitPolicy.ADDRESS_SEARCH_GUEST})
        public ResponseEntity<Void> guestCapped() {
            return null;
        }

        @RateLimited({RateLimitPolicy.ADDRESS_SEARCH, RateLimitPolicy.ADDRESS_SEARCH_DAILY,
                RateLimitPolicy.ADDRESS_SEARCH_GUEST})
        public ResponseEntity<Void> addressSearchChain() {
            return null;
        }

        public ResponseEntity<Void> plain() {
            return null;
        }
    }

    @BeforeEach
    void setUp() {
        interceptor = new RateLimitInterceptor(
                store, new ClientIpResolver(new RateLimitProperties(false, null, null)), RateLimitTestFixtures.enabledProperties());
        request = new MockHttpServletRequest("POST", "/api/v1/any");
        request.setRemoteAddr(CLIENT_IP);
        response = new MockHttpServletResponse();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private HandlerMethod handler(String methodName) throws Exception {
        Method method = DummyController.class.getMethod(methodName);
        return new HandlerMethod(new DummyController(), method);
    }

    private void authenticate() {
        AuthenticatedUser principal =
                new AuthenticatedUser(USER_UUID, UserRole.USER, "jti", Instant.now().plusSeconds(600));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @Test
    @DisplayName("어노테이션이 없어도 전역 backstop은 항상 판정한다(미인증이면 IP 키)")
    void plainMethod_backstopOnly() throws Exception {
        given(store.tryConsume(any(), anyString())).willReturn(RateLimitStore.RateLimitResult.allow());

        assertThat(interceptor.preHandle(request, response, handler("plain"))).isTrue();

        then(store).should().tryConsume(RateLimitPolicy.DEFAULT_BACKSTOP, CLIENT_IP);
        then(store).should(never()).tryConsume(eq(RateLimitPolicy.SIGNUP), anyString());
    }

    @Test
    @DisplayName("@RateLimited 정책을 backstop에 더해 판정한다 — IP 정책은 IP 키, GLOBAL은 고정 키")
    void annotatedMethod_appliesDeclaredPolicies() throws Exception {
        given(store.tryConsume(any(), anyString())).willReturn(RateLimitStore.RateLimitResult.allow());

        interceptor.preHandle(request, response, handler("annotated"));

        then(store).should().tryConsume(RateLimitPolicy.DEFAULT_BACKSTOP, CLIENT_IP);
        then(store).should().tryConsume(RateLimitPolicy.SIGNUP, CLIENT_IP);
        then(store).should().tryConsume(RateLimitPolicy.SMS_GLOBAL, "global");
    }

    @Test
    @DisplayName("USER_OR_IP 키는 인증 사용자면 uuid, 미인증이면 IP로 판정한다")
    void userOrIp_keysByPrincipalThenIp() throws Exception {
        given(store.tryConsume(any(), anyString())).willReturn(RateLimitStore.RateLimitResult.allow());

        authenticate();
        interceptor.preHandle(request, response, handler("plain"));
        then(store).should().tryConsume(RateLimitPolicy.DEFAULT_BACKSTOP, USER_UUID);

        SecurityContextHolder.clearContext();
        interceptor.preHandle(request, response, handler("plain"));
        then(store).should().tryConsume(RateLimitPolicy.DEFAULT_BACKSTOP, CLIENT_IP);
    }

    @Test
    @DisplayName("IP 키 정책은 인증 여부와 무관하게 IP로 판정한다")
    void ipPolicy_alwaysKeysByIp() throws Exception {
        given(store.tryConsume(any(), anyString())).willReturn(RateLimitStore.RateLimitResult.allow());
        authenticate();

        interceptor.preHandle(request, response, handler("ipKeyed"));

        then(store).should().tryConsume(RateLimitPolicy.PHONE_VERIFICATION_SEND_IP, CLIENT_IP);
    }

    // 비로그인 공개 경로(주소 검색)의 전체 상한 — 로그인한 중개사까지 세면 비로그인 폭주가 매물 등록의 검색을 막는다.
    @Test
    @DisplayName("GUEST_GLOBAL 은 비로그인 호출만 한 키로 세고, 로그인한 호출은 세지 않는다")
    void guestGlobal_countsOnlyAnonymousCallers() throws Exception {
        given(store.tryConsume(any(), anyString())).willReturn(RateLimitStore.RateLimitResult.allow());

        interceptor.preHandle(request, response, handler("guestCapped"));
        then(store).should().tryConsume(RateLimitPolicy.ADDRESS_SEARCH, CLIENT_IP);
        then(store).should().tryConsume(RateLimitPolicy.ADDRESS_SEARCH_GUEST, "guest");

        authenticate();
        interceptor.preHandle(request, response, handler("guestCapped"));
        then(store).should().tryConsume(RateLimitPolicy.ADDRESS_SEARCH, USER_UUID);
        then(store).should(times(1)).tryConsume(eq(RateLimitPolicy.ADDRESS_SEARCH_GUEST), anyString());
    }

    @Test
    @DisplayName("비로그인 전체 상한을 넘으면 거절한다")
    void guestGlobal_deniesWhenExhausted() throws Exception {
        given(store.tryConsume(any(), anyString())).willReturn(RateLimitStore.RateLimitResult.allow());
        given(store.tryConsume(RateLimitPolicy.ADDRESS_SEARCH_GUEST, "guest"))
                .willReturn(RateLimitStore.RateLimitResult.deny(600));

        assertThatThrownBy(() -> interceptor.preHandle(request, response, handler("guestCapped")))
                .isInstanceOf(RateLimitException.class)
                .extracting("retryAfterSeconds").isEqualTo(600L);
    }

    // 앞에서 막히면 뒤 카운터를 쓰지 않는다 — 한 IP 의 폭주가 비로그인 전체 상한을 먹지 않게 하는 장치다.
    @Test
    @DisplayName("주소 검색 — 연속 한도에서 막히면 하루 · 비로그인 전체 카운터를 쓰지 않는다")
    void addressSearch_burstDenied_skipsDailyAndGuest() throws Exception {
        given(store.tryConsume(any(), anyString())).willReturn(RateLimitStore.RateLimitResult.allow());
        given(store.tryConsume(RateLimitPolicy.ADDRESS_SEARCH, CLIENT_IP))
                .willReturn(RateLimitStore.RateLimitResult.deny(30));

        assertThatThrownBy(() -> interceptor.preHandle(request, response, handler("addressSearchChain")))
                .isInstanceOf(RateLimitException.class);

        then(store).should(never()).tryConsume(eq(RateLimitPolicy.ADDRESS_SEARCH_DAILY), anyString());
        then(store).should(never()).tryConsume(eq(RateLimitPolicy.ADDRESS_SEARCH_GUEST), anyString());
    }

    @Test
    @DisplayName("주소 검색 — 하루 한도에서 막히면 비로그인 전체 카운터를 쓰지 않는다")
    void addressSearch_dailyDenied_skipsGuest() throws Exception {
        given(store.tryConsume(any(), anyString())).willReturn(RateLimitStore.RateLimitResult.allow());
        given(store.tryConsume(RateLimitPolicy.ADDRESS_SEARCH_DAILY, CLIENT_IP))
                .willReturn(RateLimitStore.RateLimitResult.deny(3600));

        assertThatThrownBy(() -> interceptor.preHandle(request, response, handler("addressSearchChain")))
                .isInstanceOf(RateLimitException.class);

        then(store).should().tryConsume(RateLimitPolicy.ADDRESS_SEARCH, CLIENT_IP);
        then(store).should(never()).tryConsume(eq(RateLimitPolicy.ADDRESS_SEARCH_GUEST), anyString());
    }

    @Test
    @DisplayName("한도 초과면 RateLimitException에 잔여 초를 실어 던진다")
    void denied_throwsWithRetryAfter() throws Exception {
        given(store.tryConsume(RateLimitPolicy.DEFAULT_BACKSTOP, CLIENT_IP))
                .willReturn(RateLimitStore.RateLimitResult.deny(42));

        assertThatThrownBy(() -> interceptor.preHandle(request, response, handler("plain")))
                .isInstanceOf(RateLimitException.class)
                .extracting("retryAfterSeconds").isEqualTo(42L);
    }

    @Test
    @DisplayName("enabled=false면 아무 판정도 하지 않는다(비상 스위치)")
    void disabled_skipsEverything() throws Exception {
        RateLimitInterceptor off = new RateLimitInterceptor(
                store, new ClientIpResolver(new RateLimitProperties(false, null, null)), new RateLimitProperties(false, null, null));

        assertThat(off.preHandle(request, response, handler("annotated"))).isTrue();
        then(store).shouldHaveNoInteractions();
    }

    // MANUAL은 키가 phone_hash처럼 요청 본문에서만 나온다 — 인터셉터가 알 수 없다.
    @Test
    @DisplayName("MANUAL 정책은 어노테이션에 붙어 있어도 인터셉터가 무시한다 — 서비스 레벨 전용")
    void manualPolicy_ignoredByInterceptor() throws Exception {
        given(store.tryConsume(any(), anyString())).willReturn(RateLimitStore.RateLimitResult.allow());

        assertThat(interceptor.preHandle(request, response, handler("manualAnnotated"))).isTrue();

        then(store).should().tryConsume(RateLimitPolicy.DEFAULT_BACKSTOP, CLIENT_IP);
        then(store).should(never()).tryConsume(eq(RateLimitPolicy.PHONE_VERIFICATION_SEND), anyString());
    }

    @Test
    @DisplayName("backstop이 먼저 거절되면 어노테이션 정책의 카운터는 소비되지 않는다(판정 순서 계약)")
    void backstopDenied_annotatedPoliciesNotConsumed() throws Exception {
        given(store.tryConsume(RateLimitPolicy.DEFAULT_BACKSTOP, CLIENT_IP))
                .willReturn(RateLimitStore.RateLimitResult.deny(30));

        assertThatThrownBy(() -> interceptor.preHandle(request, response, handler("annotated")))
                .isInstanceOf(RateLimitException.class);

        then(store).should(never()).tryConsume(eq(RateLimitPolicy.SIGNUP), anyString());
        then(store).should(never()).tryConsume(eq(RateLimitPolicy.SMS_GLOBAL), anyString());
    }

    @Test
    @DisplayName("OPTIONS 프리플라이트와 비-HandlerMethod 핸들러는 세지 않는다")
    void optionsAndNonHandlerMethod_skipped() throws Exception {
        MockHttpServletRequest preflight = new MockHttpServletRequest("OPTIONS", "/api/v1/any");
        preflight.setRemoteAddr(CLIENT_IP);

        assertThat(interceptor.preHandle(preflight, response, handler("plain"))).isTrue();
        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();

        then(store).shouldHaveNoInteractions();
    }
}
