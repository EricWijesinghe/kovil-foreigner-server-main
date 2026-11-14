package com.kovil.domain.verification.controller;

import com.kovil.common.config.SecurityConfig;
import com.kovil.common.exception.ErrorCodeResolver;
import com.kovil.common.exception.ErrorMessageResolver;
import com.kovil.common.handler.GlobalExceptionHandler;
import com.kovil.common.security.filter.JwtAuthenticationFilter;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.verification.dto.response.AdminVerificationResponse;
import com.kovil.domain.verification.service.VerificationReviewService;
import com.kovil.infra.jwt.BlacklistedTokenStore;
import com.kovil.infra.jwt.JwtProperties;
import com.kovil.infra.jwt.JwtTokenProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentMatchers;
import org.mockito.BDDMockito;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 이 슬라이스는 @EnableMethodSecurity를 스스로 켜지 않는다 — 애플리케이션 클래스가 켠 것에
// 의존해야 운영에서 꺼진 경우를 여기서 잡는다.
@WebMvcTest(controllers = AdminVerificationController.class)
@Import({AdminVerificationController.class, SecurityConfig.class, GlobalExceptionHandler.class,
        ErrorCodeResolver.class, ErrorMessageResolver.class, JwtAuthenticationFilter.class,
        AdminVerificationControllerTest.TestBeans.class})
class AdminVerificationControllerTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final Instant NOW = Instant.parse("2026-08-11T00:00:00Z");
    private static final String SECRET;

    static {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 0x44);
        SECRET = Base64.getEncoder().encodeToString(key);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    BlacklistedTokenStore blacklistedTokenStore;

    /*
     * 토큰 취소 판정은 이 테스트의 관심사가 아니다 — 기본을 "취소되지 않음"으로 선언한다.
     * check() 는 enum 을 돌려주므로 스텁이 없으면 null 이고, 필터는 NONE 이 아니면 거절한다
     * (안전한 실패 방향). 선언하지 않으면 이 클래스의 모든 테스트가 401 로 무너진다.
     */
    @BeforeEach
    void allowTokensByDefault() {
        BDDMockito.given(blacklistedTokenStore.check(
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any()))
                .willReturn(BlacklistedTokenStore.Rejection.NONE);
    }

    @MockitoBean
    VerificationReviewService verificationReviewService;

    @TestConfiguration
    static class TestBeans {

        @Bean
        @Primary
        Clock clock() {
            return Clock.fixed(NOW, ZONE);
        }

        @Bean
        JwtProperties jwtProperties() {
            return new JwtProperties(SECRET, Duration.ofMinutes(30), "kovil");
        }

        @Bean
        JwtTokenProvider jwtTokenProvider(JwtProperties properties, Clock clock) {
            return new JwtTokenProvider(properties, clock);
        }
    }

    private String bearer(UserRole role) {
        return "Bearer " + jwtTokenProvider.issueAccessToken("uuid-" + role.name().toLowerCase(), role);
    }

    private static AdminVerificationResponse response() {
        return new AdminVerificationResponse("v-uuid", "APPROVED", "u-uuid", "NGUYEN VAN A",
                LocalDate.of(1998, 3, 15), "vn", "STUDENT", List.of("PASSPORT"), List.of(),
                null, LocalDate.of(2027, 8, 11), LocalDateTime.now(Clock.fixed(NOW, ZONE)), null,
                null, 0, null, null);
    }

    // 심사 권한이 새면 신원 확인 자체가 무의미해진다 — 이 서비스의 제1 불변식이 걸린 지점이다.
    @Test
    @DisplayName("USER 토큰으로는 대기열을 볼 수 없다")
    void user_cannot_read_queue() throws Exception {
        mockMvc.perform(get("/api/v1/admin/verifications").header("Authorization", bearer(UserRole.USER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN_ACCESS"));

        then(verificationReviewService).should(never()).findByStatus(any(), any(), any());
    }

    @Test
    @DisplayName("파트너 토큰으로도 승인할 수 없다 — 심사는 ADMIN 전용이다")
    void partner_cannot_approve() throws Exception {
        mockMvc.perform(post("/api/v1/admin/verifications/v-uuid/approve")
                        .header("Authorization", bearer(UserRole.PARTNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());

        then(verificationReviewService).should(never()).approve(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("토큰 없이는 401이다")
    void no_token_is_401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/verifications"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED_ACCESS"));
    }

    @Test
    @DisplayName("ADMIN 토큰은 만료일 없이도 승인할 수 있다 — 무기한 승인")
    void admin_can_approve_without_expiry() throws Exception {
        given(verificationReviewService.approve(anyString(), anyString(), any()))
                .willReturn(response());

        mockMvc.perform(post("/api/v1/admin/verifications/v-uuid/approve")
                        .header("Authorization", bearer(UserRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
    }

    // 사유 없는 반려는 유저가 무엇을 고쳐야 할지 알 수 없게 만든다.
    @Test
    @DisplayName("반려 사유가 비면 400 REJECT_REASON_REQUIRED이고 서비스는 호출되지 않는다")
    void reject_requires_reason() throws Exception {
        mockMvc.perform(post("/api/v1/admin/verifications/v-uuid/reject")
                        .header("Authorization", bearer(UserRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors[0].errorCode").value("REJECT_REASON_REQUIRED"));

        then(verificationReviewService).should(never()).reject(anyString(), anyString(), anyString());
    }
}
