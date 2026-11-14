package com.kovil.domain.auth.controller;

import com.kovil.common.ratelimit.RateLimitPolicy;
import com.kovil.common.ratelimit.RateLimited;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OAuth 엔드포인트의 레이트리밋 정책 결합을 고정한다.
 *
 * ★ 교환(2단계)을 SIGNUP 으로 되돌리면 가입 1건이 IP 카운터를 2회(교환+link) 소비해,
 *   같은 NAT(쉐어하우스 Wi-Fi — 이 제품이 파는 주거 형태다) 뒤 3번째 가입의 link 가 429 로
 *   막히고 기가입자의 재로그인도 같은 카운터에 잡힌다(a04ea6e 가 고친 결함). 되돌려도 전
 *   스위트가 그린이었다(적대적 리뷰 2026-09-02) — 이 테스트가 그 그물이다.
 */
class OAuthRateLimitBindingTest {

    private static RateLimitPolicy[] policyOf(String methodName) {
        return policyOf(OAuthController.class, methodName);
    }

    private static RateLimitPolicy[] policyOf(Class<?> controller, String methodName) {
        Method method = Arrays.stream(controller.getDeclaredMethods())
                .filter(m -> m.getName().equals(methodName))
                .findFirst().orElseThrow();
        RateLimited annotation = method.getAnnotation(RateLimited.class);
        assertThat(annotation).as("%s 에 @RateLimited 가 없다", methodName).isNotNull();
        return annotation.value();
    }

    @Test
    @DisplayName("code 교환은 OAUTH_LOGIN 축이다 — SIGNUP 을 공유 소비하면 안 된다")
    void exchange_uses_oauth_login_policy() {
        assertThat(policyOf("login")).containsExactly(RateLimitPolicy.OAUTH_LOGIN);
    }

    // Apple 은 SIGNUP 을 쓰지 않는다. 가입 확정은 다른 수단과 같은 link 한 번이다.
    @Test
    @DisplayName("Apple nonce 발급 · 네이티브 로그인은 OAUTH_LOGIN 축이다")
    void apple_native_uses_oauth_login_policy() {
        assertThat(policyOf(AppleNativeAuthController.class, "nonce")).containsExactly(RateLimitPolicy.OAUTH_LOGIN);
        assertThat(policyOf(AppleNativeAuthController.class, "login")).containsExactly(RateLimitPolicy.OAUTH_LOGIN);
    }

    @Test
    @DisplayName("연결(실제 계정 생성)은 SIGNUP 축 그대로다")
    void link_stays_on_signup_policy() {
        assertThat(policyOf("link")).containsExactly(RateLimitPolicy.SIGNUP);
    }
}
