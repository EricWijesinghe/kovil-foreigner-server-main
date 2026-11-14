package com.kovil.domain.rentalrequest.controller;

import com.kovil.common.config.SecurityConfig;
import com.kovil.common.exception.ErrorCodeResolver;
import com.kovil.common.exception.ErrorMessageResolver;
import com.kovil.common.handler.GlobalExceptionHandler;
import com.kovil.common.security.filter.JwtAuthenticationFilter;
import com.kovil.domain.rentalrequest.dto.response.RequestOptionGroupResponse;
import com.kovil.domain.rentalrequest.exception.RentalRequestException;
import com.kovil.domain.rentalrequest.service.RentalRequestService;
import com.kovil.domain.rentalrequest.service.RequestOptionQueryService;
import com.kovil.domain.user.enums.UserRole;
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
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import static com.kovil.domain.rentalrequest.exception.RentalRequestErrorCode.VERIFICATION_REQUIRED;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = RentalRequestController.class)
@Import({RentalRequestController.class, SecurityConfig.class, GlobalExceptionHandler.class,
        ErrorCodeResolver.class, ErrorMessageResolver.class, JwtAuthenticationFilter.class,
        RentalRequestControllerTest.TestBeans.class})
class RentalRequestControllerTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final Instant NOW = Instant.parse("2026-08-11T00:00:00Z");
    private static final String SECRET;

    static {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 0x55);
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
    RentalRequestService rentalRequestService;

    @MockitoBean
    RequestOptionQueryService requestOptionQueryService;

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

    private String bearer() {
        return "Bearer " + jwtTokenProvider.issueAccessToken("uuid-user", UserRole.USER);
    }

    private static final String BODY = """
            {"regionCodes":["11215"],"optionCodes":["ONE_ROOM"],
             "moveInDate":"2026-10-01","moveInFlexible":true}
            """;

    // ★ FR-301 — FE는 이 코드 하나를 보고 인증 화면으로 보낸다. 상태와 코드가 둘 다 계약이다.
    @Test
    @DisplayName("미인증이면 403 VERIFICATION_REQUIRED가 코드와 함께 나간다")
    void unverified_is_403_with_code() throws Exception {
        willThrow(new RentalRequestException(VERIFICATION_REQUIRED))
                .given(rentalRequestService).create(anyString(), any());

        mockMvc.perform(post("/api/v1/rental-requests")
                        .header("Authorization", bearer())
                        .header("Accept-Language", "ko")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("VERIFICATION_REQUIRED"))
                .andExpect(jsonPath("$.message").value("임대 요청 전에 외국인 인증이 필요해요."));
    }

    @Test
    @DisplayName("토큰 없이는 401이다 — 요청 도메인 전체가 로그인 뒤에 있다")
    void no_token_is_401() throws Exception {
        mockMvc.perform(get("/api/v1/rental-requests"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED_ACCESS"));
    }

    // 무엇을 물어보는지 미리 보여줘야 인증할 마음이 생긴다 — 다만 지금은 로그인 뒤에 있다.
    @Test
    @DisplayName("옵션 트리는 로그인한 유저면 인증 없이도 볼 수 있다")
    void options_need_no_verification() throws Exception {
        given(requestOptionQueryService.findActiveTree()).willReturn(List.of(
                new RequestOptionGroupResponse("ROOM_TYPE", "방 구조", "SINGLE", "home",
                        null, true, null, null, false, null, null, null, null, false,
                        List.of(new RequestOptionGroupResponse.Item(
                                "ONE_ROOM", "원룸", "square", "NONE", null, false)))));

        mockMvc.perform(get("/api/v1/rental-requests/options").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].selectionType").value("SINGLE"))
                .andExpect(jsonPath("$.data[0].items[0].code").value("ONE_ROOM"));
    }

    // V14 (PO 번복 2026-08-26) — 고정 축(지역·입주일)이 질문지로 이관됐다. 빈 본문도
    // 컨트롤러를 통과하고, 필수 여부는 질문지의 required 가 서비스 계층에서 강제한다.
    @Test
    @DisplayName("지역·입주일 없이도 접수된다 — 필수는 질문지의 required 가 강제한다(V14)")
    void fixed_axes_are_optional_since_v14() throws Exception {
        mockMvc.perform(post("/api/v1/rental-requests")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"regionCodes\":[]}"))
                .andExpect(status().isOk());

        then(rentalRequestService).should().create(anyString(), any());
    }

    // 사유는 선택이다 — 본문 없이 호출해도 취소는 되어야 한다.
    @Test
    @DisplayName("취소는 본문 없이도 호출된다")
    void cancel_without_body() throws Exception {
        mockMvc.perform(post("/api/v1/rental-requests/r-uuid/cancel")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk());

        then(rentalRequestService).should().cancel(anyString(), anyString(), any());
    }
}
