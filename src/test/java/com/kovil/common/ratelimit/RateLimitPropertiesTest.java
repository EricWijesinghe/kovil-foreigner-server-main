package com.kovil.common.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimitPropertiesTest {

    @Test
    @DisplayName("enabled인데 enum 정책 하나라도 yaml에 없으면 부팅 시점에 실패한다")
    void missingPolicy_failsAtConstruction() {
        Map<RateLimitPolicy, RateLimitProperties.Rule> incomplete = RateLimitTestFixtures.fullPolicies();
        incomplete.remove(RateLimitPolicy.SMS_GLOBAL);

        assertThatThrownBy(() -> new RateLimitProperties(true, null, incomplete))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SMS_GLOBAL");
    }

    @Test
    @DisplayName("limit이 0 이하이거나 window가 0/음수면 부팅 시점에 실패한다")
    void invalidRule_failsAtConstruction() {
        Map<RateLimitPolicy, RateLimitProperties.Rule> zeroLimit = RateLimitTestFixtures.fullPolicies();
        zeroLimit.put(RateLimitPolicy.SIGNUP, new RateLimitProperties.Rule(0, Duration.ofMinutes(1)));
        assertThatThrownBy(() -> new RateLimitProperties(true, null, zeroLimit))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SIGNUP");

        Map<RateLimitPolicy, RateLimitProperties.Rule> zeroWindow = RateLimitTestFixtures.fullPolicies();
        zeroWindow.put(RateLimitPolicy.REFRESH, new RateLimitProperties.Rule(5, Duration.ZERO));
        assertThatThrownBy(() -> new RateLimitProperties(true, null, zeroWindow))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REFRESH");
    }

    @Test
    @DisplayName("disabled면 정책 맵이 비어 있어도 생성된다(테스트 프로파일 off 경로)")
    void disabled_skipsValidation() {
        assertThatCode(() -> new RateLimitProperties(false, null, null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("정상 구성이면 ruleOf가 정책별 숫자를 돌려준다")
    void ruleOf_returnsConfiguredRule() {
        RateLimitProperties properties = RateLimitTestFixtures.enabledProperties();

        assertThat(properties.ruleOf(RateLimitPolicy.SIGNUP).limit()).isEqualTo(10);
        assertThat(properties.ruleOf(RateLimitPolicy.SIGNUP).window()).isEqualTo(Duration.ofMinutes(1));
    }
}
