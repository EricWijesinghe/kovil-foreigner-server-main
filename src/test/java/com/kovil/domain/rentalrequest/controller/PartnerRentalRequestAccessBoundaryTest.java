package com.kovil.domain.rentalrequest.controller;

import com.kovil.common.config.SecurityConfig;
import com.kovil.common.exception.ErrorCodeResolver;
import com.kovil.common.exception.ErrorMessageResolver;
import com.kovil.common.handler.GlobalExceptionHandler;
import com.kovil.common.security.filter.JwtAuthenticationFilter;
import com.kovil.domain.partner.enums.PartnerDivisionType;
import com.kovil.domain.rentalrequest.dto.response.PartnerRentalRequestResponse;
import com.kovil.domain.rentalrequest.dto.response.PartnerRentalRequestSummaryResponse;
import com.kovil.domain.rentalrequest.dto.response.RentalRequestResponse;
import com.kovil.domain.rentalrequest.enums.RentalRequestOutcome;
import com.kovil.domain.rentalrequest.service.PartnerRentalRequestService;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.infra.jwt.BlacklistedTokenStore;
import com.kovil.infra.jwt.JwtProperties;
import com.kovil.infra.jwt.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.web.servlet.RequestBuilder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * ★★ 파트너 임대 요청 표면의 인가 경계 — 컨트롤러의 @PreAuthorize **한 줄**(RENTAL_REQUEST_HANDLE)이 지킨다.
 *    지우거나 다른 권한으로 바꿔도 컴파일되고 서비스 테스트는 전부 통과한다. 그런데 이 경로는 유학생 요청의 요약과
 *    (맡은 사무소에는) 휴대폰을 내준다 — 진짜 보안 필터 · 메서드 보안을 태워 박는다.
 *    관리자도 갖지 않는다(부문이 여는 권한이다 — 관리자는 읽기 전용 열람이 따로 있다).
 * ★ 실제 메시지 변환기로 직렬화된 응답에서, 요약에는 연락처 칸이 없고 전체에는 있는지도 함께 본다.
 */
@WebMvcTest(controllers = PartnerRentalRequestController.class)
@Import({PartnerRentalRequestController.class, SecurityConfig.class, GlobalExceptionHandler.class,
        ErrorCodeResolver.class, ErrorMessageResolver.class, JwtAuthenticationFilter.class,
        PartnerRentalRequestAccessBoundaryTest.TestBeans.class})
class PartnerRentalRequestAccessBoundaryTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final Instant NOW = Instant.parse("2026-10-07T00:00:00Z");
    private static final String SECRET;
    private static final String BROKER = "uuid-partner";

    static {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 0x67);
        SECRET = Base64.getEncoder().encodeToString(key);
    }

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwtTokenProvider;

    @MockitoBean BlacklistedTokenStore blacklistedTokenStore;
    @MockitoBean PartnerRentalRequestService service;

    // 토큰 취소 판정은 이 테스트의 관심사가 아니다 — 기본을 "취소되지 않음"으로 둔다(PartnerAccessBoundaryTest 와 같다).
    @BeforeEach
    void allowTokensByDefault() {
        given(blacklistedTokenStore.check(any(), any(), any())).willReturn(BlacklistedTokenStore.Rejection.NONE);
    }

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

    private String partnerBearer(Set<PartnerDivisionType> divisions) {
        return "Bearer " + jwtTokenProvider.issueAccessToken(BROKER, UserRole.PARTNER, divisions);
    }

    private String brokerBearer() {
        return partnerBearer(Set.of(PartnerDivisionType.REAL_ESTATE_BROKER));
    }

    private List<RequestBuilder> everyPath(String token) {
        return List.of(
                get("/api/v1/partner/rental-requests").header("Authorization", token),
                get("/api/v1/partner/rental-requests/mine").header("Authorization", token),
                get("/api/v1/partner/rental-requests/r-1").header("Authorization", token),
                post("/api/v1/partner/rental-requests/r-1/claim").header("Authorization", token),
                post("/api/v1/partner/rental-requests/r-1/release").header("Authorization", token),
                post("/api/v1/partner/rental-requests/r-1/close").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"outcome\":\"FOUND\"}"));
    }

    private static PartnerRentalRequestSummaryResponse summary() {
        return new PartnerRentalRequestSummaryResponse("SUMMARY", "r-1", "RQ-261007-AAAAAA", "SUBMITTED",
                List.of(new RentalRequestResponse.Region("11215", "광진구")), List.of(),
                LocalDate.of(2026, 11, 1), true, (short) 12, null, 20_000_000L, null, 900_000L, (byte) 1,
                "건대입구역 도보 10분", "vi", true, LocalDateTime.of(2026, 10, 6, 9, 0));
    }

    private static PartnerRentalRequestResponse full() {
        return new PartnerRentalRequestResponse("FULL", "r-1", "RQ-261007-AAAAAA", "IN_PROGRESS",
                List.of(), List.of(), LocalDate.of(2026, 11, 1), true, (short) 12, null, 20_000_000L, null, 900_000L,
                (byte) 1, "건대입구역 도보 10분", "vi", true, LocalDateTime.of(2026, 10, 6, 9, 0),
                LocalDateTime.of(2026, 10, 7, 9, 0), null, null, "조용한 방이면 좋겠습니다",
                new PartnerRentalRequestResponse.Applicant("홍길동", "01000000000"));
    }

    @Test
    @DisplayName("토큰 없이는 여섯 경로 모두 401 이다")
    void login_required() throws Exception {
        for (RequestBuilder request : List.of(
                get("/api/v1/partner/rental-requests"),
                post("/api/v1/partner/rental-requests/r-1/claim"))) {
            mockMvc.perform(request).andExpect(status().isUnauthorized());
        }
        then(service).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("유학생 · 심사 중(부문 없는) 파트너 · 관리자 토큰은 여섯 경로 모두 403 이고 서비스를 부르지 않는다")
    void without_handle_is_forbidden() throws Exception {
        for (String token : List.of(bearer(UserRole.USER), partnerBearer(Set.of()), bearer(UserRole.ADMIN))) {
            for (RequestBuilder request : everyPath(token)) {
                mockMvc.perform(request)
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.errorCode").value("FORBIDDEN_ACCESS"));
            }
        }
        then(service).shouldHaveNoInteractions();
    }

    // 위 403 이 "아무도 못 들어가는 문"이라서 나온 것이 아님을 함께 고정한다.
    @Test
    @DisplayName("승인된 공인중개사 부문의 토큰은 여섯 경로 모두 통과한다")
    void approved_broker_passes() throws Exception {
        for (RequestBuilder request : everyPath(brokerBearer())) {
            mockMvc.perform(request).andExpect(status().isOk());
        }
        then(service).should().claim(BROKER, "r-1");
        then(service).should().release(BROKER, "r-1");
        then(service).should().close(BROKER, "r-1", RentalRequestOutcome.FOUND);
    }

    @Test
    @DisplayName("종료에 결과가 없으면 400 — 필드 코드는 RENTAL_REQUEST_OUTCOME_REQUIRED, 서비스를 부르지 않는다")
    void close_requires_outcome() throws Exception {
        mockMvc.perform(post("/api/v1/partner/rental-requests/r-1/close").header("Authorization", brokerBearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.validationErrors[0].errorCode").value("RENTAL_REQUEST_OUTCOME_REQUIRED"));
        then(service).shouldHaveNoInteractions();
    }

    // 실제 메시지 변환기로 나간 응답에서 본다 — 상세는 한 경로가 두 모양을 내므로 칸 이름으로 갈린다.
    @Test
    @DisplayName("대기 건 상세(요약)에는 신청자 · 자유 요청사항 칸이 없고, 내 사무소 건(전체)에는 있다")
    void detail_shapes_on_the_wire() throws Exception {
        given(service.get(BROKER, "r-1")).willReturn(summary());
        mockMvc.perform(get("/api/v1/partner/rental-requests/r-1").header("Authorization", brokerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.view").value("SUMMARY"))
                .andExpect(jsonPath("$.data.verified").value(true))
                .andExpect(jsonPath("$.data.preferredLanguage").value("vi"))
                .andExpect(jsonPath("$.data.applicant").doesNotExist())
                .andExpect(jsonPath("$.data.requestNote").doesNotExist())
                .andExpect(jsonPath("$.data.claimedAt").doesNotExist());

        given(service.get(BROKER, "r-2")).willReturn(full());
        mockMvc.perform(get("/api/v1/partner/rental-requests/r-2").header("Authorization", brokerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.view").value("FULL"))
                .andExpect(jsonPath("$.data.applicant.name").value("홍길동"))
                .andExpect(jsonPath("$.data.applicant.phone").value("01000000000"))
                .andExpect(jsonPath("$.data.requestNote").value("조용한 방이면 좋겠습니다"));
    }
}
