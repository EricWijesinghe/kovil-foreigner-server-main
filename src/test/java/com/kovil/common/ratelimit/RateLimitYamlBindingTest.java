package com.kovil.common.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

// 테스트 프로파일은 리미터를 끄고 돌기 때문에 application.yaml의 정책 목록이 한 번도 검증되지 않는다.
// 여기서만 켜서 "실제 배포 설정이 enum과 1:1인가"를 고정한다 — 오타나 정책 누락이 dev 부팅이 아니라
// 여기서 잡히게.
@SpringBootTest(classes = RateLimitYamlBindingTest.Config.class)
@TestPropertySource(properties = "ratelimit.enabled=true")
class RateLimitYamlBindingTest {

    @Configuration
    @EnableConfigurationProperties(RateLimitProperties.class)
    static class Config {
    }

    @Autowired
    RateLimitProperties properties;

    @Test
    @DisplayName("application.yaml이 모든 정책에 유효한 한도를 준다 — 하나라도 빠지면 컨텍스트가 뜨지 않는다")
    void every_policy_is_configured() {
        for (RateLimitPolicy policy : RateLimitPolicy.values()) {
            RateLimitProperties.Rule rule = properties.ruleOf(policy);

            assertThat(rule).as("%s 설정 누락", policy).isNotNull();
            assertThat(rule.limit()).as("%s limit", policy).isPositive();
            assertThat(rule.window()).as("%s window", policy).isNotNull();
            assertThat(rule.window().isZero() || rule.window().isNegative())
                    .as("%s window", policy).isFalse();
        }
    }

    // SMS는 발송당 과금이라 한도가 느슨하면 그대로 비용이 된다.
    @Test
    @DisplayName("SMS 축 한도가 비용을 감당할 수 있는 범위에 있다")
    void sms_limits_are_conservative() {
        assertThat(properties.ruleOf(RateLimitPolicy.PHONE_VERIFICATION_SEND).limit())
                .isLessThanOrEqualTo(10);
        assertThat(properties.ruleOf(RateLimitPolicy.SMS_GLOBAL).limit())
                .isLessThanOrEqualTo(2000);
    }
}
