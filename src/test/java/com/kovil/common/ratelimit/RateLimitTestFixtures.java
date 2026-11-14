package com.kovil.common.ratelimit;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

// enabled 검증(전 정책 필수)을 통과하는 완전한 정책 맵을 만든다.
final class RateLimitTestFixtures {

    private RateLimitTestFixtures() {
    }

    static RateLimitProperties enabledProperties() {
        return new RateLimitProperties(true, null, fullPolicies());
    }

    static Map<RateLimitPolicy, RateLimitProperties.Rule> fullPolicies() {
        Map<RateLimitPolicy, RateLimitProperties.Rule> policies = new EnumMap<>(RateLimitPolicy.class);
        for (RateLimitPolicy policy : RateLimitPolicy.values()) {
            policies.put(policy, new RateLimitProperties.Rule(10, Duration.ofMinutes(1)));
        }
        return policies;
    }
}
