package com.kovil.common.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "ratelimit")
public record RateLimitProperties(
        boolean enabled,
        /**
         * 이 대역에서 온 요청만 X-Forwarded-For를 믿는다.
         *
         * 비워 두면 프록시 헤더를 전혀 신뢰하지 않는다(getRemoteAddr만 쓴다) — 헤더는 누구나
         * 위조할 수 있으므로 기본값은 "안 믿는다"여야 한다. 우리 배포는 Cloudflare → nginx →
         * Next 프록시 → 이 서버라, 서버가 보는 원격 주소는 언제나 루프백이거나 도커 사설망이다.
         */
        List<String> trustedProxies,
        Map<RateLimitPolicy, Rule> policies
) {

    public record Rule(int limit, Duration window) {}

    // enum과 yaml의 1:1을 부팅 시점에 강제한다. 정책을 추가하고 설정을 빠뜨리면
    // 그 엔드포인트만 무제한으로 열린 채 정상 기동하는데, 그건 아무도 눈치채지 못한다.
    public RateLimitProperties {
        trustedProxies = trustedProxies == null ? List.of() : List.copyOf(trustedProxies);
        policies = policies == null ? Map.of() : Map.copyOf(policies);
        if (enabled) {
            for (RateLimitPolicy policy : RateLimitPolicy.values()) {
                Rule rule = policies.get(policy);
                if (rule == null) {
                    throw new IllegalStateException(
                            "ratelimit.policies에 " + policy + " 설정이 없습니다 — 정책 enum과 yaml은 1:1이어야 합니다");
                }
                if (rule.limit() <= 0 || rule.window() == null
                        || rule.window().isZero() || rule.window().isNegative()) {
                    throw new IllegalStateException(
                            "ratelimit.policies." + policy + " 값이 무효합니다 (limit>0, window>0 필요): " + rule);
                }
            }
        }
    }

    public Rule ruleOf(RateLimitPolicy policy) {
        return policies.get(policy);
    }
}
