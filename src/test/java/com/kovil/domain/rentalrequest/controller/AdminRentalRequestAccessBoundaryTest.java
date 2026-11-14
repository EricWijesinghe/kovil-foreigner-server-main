package com.kovil.domain.rentalrequest.controller;

import com.kovil.common.config.SecurityConfig;
import com.kovil.common.dto.response.PageResponse;
import com.kovil.common.exception.ErrorCodeResolver;
import com.kovil.common.exception.ErrorMessageResolver;
import com.kovil.common.handler.GlobalExceptionHandler;
import com.kovil.common.security.filter.JwtAuthenticationFilter;
import com.kovil.domain.partner.enums.PartnerDivisionType;
import com.kovil.domain.rentalrequest.enums.RentalRequestStatus;
import com.kovil.domain.rentalrequest.service.AdminRentalRequestService;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.data.domain.Page;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * ★★ 임대 요청의 두 문은 서로 다른 권한이 지킨다 — 둘 다 컨트롤러의 @PreAuthorize **한 줄**이다.
 *    · 관리자 열람 /admin/rental-requests 는 RENTAL_REQUEST_VIEW(관리자) — 공인중개사가 들어오면 전국 요청의 본문과
 *      신청자 이름을 잡지 않고 본다(요약만 보게 한 결정이 뚫린다).
 *    · 파트너 처리 /partner/rental-requests 는 RENTAL_REQUEST_HANDLE(승인된 공인중개사 부문) — 관리자가 들어오면
 *      요청을 맡는 사업자가 아닌데 잡고 유학생 휴대폰을 본다.
 *    지우거나 서로 바꿔도 컴파일되고 서비스 테스트는 전부 통과한다. 진짜 보안 필터 · 메서드 보안을 태워 교차로 박는다.
 * ★ 관리자 열람에는 쓰기 경로가 없다 — 쓰기 메서드는 매핑이 없어 404 이고 서비스에 닿지 않는다.
 */
@WebMvcTest(controllers = {AdminRentalRequestController.class, PartnerRentalRequestController.class})
@Import({AdminRentalRequestController.class, PartnerRentalRequestController.class, SecurityConfig.class,
        GlobalExceptionHandler.class, ErrorCodeResolver.class, ErrorMessageResolver.class,
        JwtAuthenticationFilter.class, AdminRentalRequestAccessBoundaryTest.TestBeans.class})
class AdminRentalRequestAccessBoundaryTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final Instant NOW = Instant.parse("2026-10-07T00:00:00Z");
    private static final String SECRET;

    static {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 0x68);
        SECRET = Base64.getEncoder().encodeToString(key);
    }

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwtTokenProvider;

    @MockitoBean BlacklistedTokenStore blacklistedTokenStore;
    @MockitoBean AdminRentalRequestService adminService;
    @MockitoBean PartnerRentalRequestService partnerService;

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
        return "Bearer " + jwtTokenProvider.issueAccessToken("uuid-partner", UserRole.PARTNER, divisions);
    }

    private String brokerBearer() {
        return partnerBearer(Set.of(PartnerDivisionType.REAL_ESTATE_BROKER));
    }

    private static List<RequestBuilder> adminPaths(String token) {
        return List.of(
                get("/api/v1/admin/rental-requests").header("Authorization", token),
                get("/api/v1/admin/rental-requests/r-1").header("Authorization", token));
    }

    private static List<RequestBuilder> partnerPaths(String token) {
        return List.of(
                get("/api/v1/partner/rental-requests").header("Authorization", token),
                get("/api/v1/partner/rental-requests/mine").header("Authorization", token),
                get("/api/v1/partner/rental-requests/r-1").header("Authorization", token),
                post("/api/v1/partner/rental-requests/r-1/claim").header("Authorization", token),
                post("/api/v1/partner/rental-requests/r-1/release").header("Authorization", token),
                post("/api/v1/partner/rental-requests/r-1/close").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"outcome\":\"FOUND\"}"));
    }

    @Test
    @DisplayName("토큰 없이는 관리자 열람 두 경로 모두 401 이다")
    void login_required() throws Exception {
        for (RequestBuilder request : List.of(
                get("/api/v1/admin/rental-requests"), get("/api/v1/admin/rental-requests/r-1"))) {
            mockMvc.perform(request).andExpect(status().isUnauthorized());
        }
        then(adminService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("유학생 · 승인된 공인중개사 · 부문 없는 파트너는 관리자 열람이 403 이고 서비스를 부르지 않는다")
    void admin_view_requires_view_permission() throws Exception {
        for (String token : List.of(bearer(UserRole.USER), brokerBearer(), partnerBearer(Set.of()))) {
            for (RequestBuilder request : adminPaths(token)) {
                mockMvc.perform(request)
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.errorCode").value("FORBIDDEN_ACCESS"));
            }
        }
        then(adminService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("관리자 토큰은 파트너 처리 여섯 경로 모두 403 이다 — 열람 권한이 처리 권한이 되지 않는다")
    void admin_cannot_handle() throws Exception {
        for (RequestBuilder request : partnerPaths(bearer(UserRole.ADMIN))) {
            mockMvc.perform(request)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.errorCode").value("FORBIDDEN_ACCESS"));
        }
        then(partnerService).shouldHaveNoInteractions();
    }

    // 위 403 들이 "아무도 못 들어가는 문"이라서 나온 것이 아님을 함께 고정한다 — 각 문은 제 주인에게는 열린다.
    @Test
    @DisplayName("관리자 토큰은 관리자 열람을, 승인된 공인중개사 토큰은 파트너 처리를 통과한다 — 필터는 그대로 서비스에 간다")
    void each_door_opens_for_its_owner() throws Exception {
        given(adminService.search(any(), any(), any(), any()))
                .willReturn(PageResponse.from(Page.empty(PageRequest.of(0, 20))));

        mockMvc.perform(get("/api/v1/admin/rental-requests").header("Authorization", bearer(UserRole.ADMIN))
                        .param("status", "IN_PROGRESS").param("officeUuid", "o-1").param("query", "RQ-26")
                        .param("page", "2").param("size", "30"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/rental-requests/r-1").header("Authorization", bearer(UserRole.ADMIN)))
                .andExpect(status().isOk());
        then(adminService).should().search(RentalRequestStatus.IN_PROGRESS, "o-1", "RQ-26", PageRequest.of(2, 30));
        then(adminService).should().get("r-1");

        mockMvc.perform(get("/api/v1/partner/rental-requests").header("Authorization", brokerBearer()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("관리자 열람에는 쓰기 경로가 없다 — 관리자 토큰으로 써도 404 이고 서비스에 닿지 않는다")
    void admin_view_has_no_write_routes() throws Exception {
        String admin = bearer(UserRole.ADMIN);
        for (RequestBuilder request : List.of(
                post("/api/v1/admin/rental-requests").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"),
                put("/api/v1/admin/rental-requests/r-1").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"),
                patch("/api/v1/admin/rental-requests/r-1").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"),
                delete("/api/v1/admin/rental-requests/r-1").header("Authorization", admin),
                post("/api/v1/admin/rental-requests/r-1/claim").header("Authorization", admin),
                post("/api/v1/admin/rental-requests/r-1/close").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"outcome\":\"FOUND\"}"))) {
            mockMvc.perform(request)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
        }
        then(adminService).shouldHaveNoInteractions();
        then(partnerService).shouldHaveNoInteractions();
    }
}
