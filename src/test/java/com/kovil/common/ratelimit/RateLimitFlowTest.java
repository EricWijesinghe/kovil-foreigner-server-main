package com.kovil.common.ratelimit;

import com.kovil.common.config.RateLimitConfig;
import com.kovil.common.dto.response.ApiResponse;
import com.kovil.common.exception.ErrorCodeResolver;
import com.kovil.common.exception.ErrorMessageResolver;
import com.kovil.common.handler.GlobalExceptionHandler;
import com.kovil.common.security.filter.JwtAuthenticationFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 인터셉터는 preHandle에서, 즉 컨트롤러 호출 전에 던진다. 그 예외가 @RestControllerAdvice까지
// 도달하지 못하면 429가 아니라 서블릿 기본 500 페이지가 나간다 — 그걸 여기서 고정한다.
@WebMvcTest(
        controllers = RateLimitFlowTest.TestController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE, classes = JwtAuthenticationFilter.class)
)
@AutoConfigureMockMvc(addFilters = false)
@Import({RateLimitFlowTest.TestController.class, RateLimitConfig.class, ClientIpResolver.class,
        GlobalExceptionHandler.class, ErrorCodeResolver.class, ErrorMessageResolver.class})
@EnableConfigurationProperties(RateLimitProperties.class)
@TestPropertySource(properties = {
        "spring.messages.basename=messages/errors",
        "spring.messages.encoding=UTF-8",
        "spring.messages.fallback-to-system-locale=false",
        "ratelimit.enabled=true",
        "ratelimit.policies.phone-verification-send.limit=5",
        "ratelimit.policies.phone-verification-send.window=1h",
        "ratelimit.policies.phone-verification-send-ip.limit=20",
        "ratelimit.policies.phone-verification-send-ip.window=1h",
        "ratelimit.policies.phone-verification-verify.limit=5",
        "ratelimit.policies.phone-verification-verify.window=10m",
        "ratelimit.policies.sms-global.limit=500",
        "ratelimit.policies.sms-global.window=1d",
        "ratelimit.policies.signup.limit=5",
        "ratelimit.policies.signup.window=1h",
        "ratelimit.policies.refresh.limit=60",
        "ratelimit.policies.refresh.window=10m",
        "ratelimit.policies.signin-fail-id.limit=5",
        "ratelimit.policies.signin-fail-id.window=5m",
        "ratelimit.policies.signin-fail-ip.limit=30",
        "ratelimit.policies.signin-fail-ip.window=10m",
        "ratelimit.policies.default-backstop.limit=120",
        "ratelimit.policies.default-backstop.window=1m"
})
class RateLimitFlowTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    RateLimitStore store;

    @RestController
    static class TestController {

        @GetMapping("/api/v1/test/limited")
        ApiResponse<String> limited() {
            return ApiResponse.success("ok");
        }
    }

    @Test
    @DisplayName("한도 내면 컨트롤러가 정상 실행된다")
    void within_limit_passes() throws Exception {
        given(store.tryConsume(any(), anyString())).willReturn(RateLimitStore.RateLimitResult.allow());

        mockMvc.perform(get("/api/v1/test/limited"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("ok"));
    }

    @Test
    @DisplayName("한도 초과면 429 + RATE_LIMITED 봉투 + Retry-After 헤더로 나간다")
    void exceeded_returns429Envelope() throws Exception {
        given(store.tryConsume(any(), anyString())).willReturn(RateLimitStore.RateLimitResult.deny(42));

        mockMvc.perform(get("/api/v1/test/limited").header("Accept-Language", "ko"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "42"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.message").value("요청이 너무 많아요. 잠시 후 다시 시도해 주세요."));
    }

    @Test
    @DisplayName("차단 문구도 사용자 언어로 나간다")
    void message_is_localized() throws Exception {
        given(store.tryConsume(any(), anyString())).willReturn(RateLimitStore.RateLimitResult.deny(10));

        mockMvc.perform(get("/api/v1/test/limited").header("Accept-Language", "en"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("Too many requests. Please try again shortly."));
    }
}
