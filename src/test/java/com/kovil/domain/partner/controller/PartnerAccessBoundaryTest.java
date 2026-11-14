package com.kovil.domain.partner.controller;

import com.kovil.common.config.SecurityConfig;
import com.kovil.common.exception.ErrorCodeResolver;
import com.kovil.common.exception.ErrorMessageResolver;
import com.kovil.common.handler.GlobalExceptionHandler;
import com.kovil.common.security.filter.JwtAuthenticationFilter;
import com.kovil.domain.address.controller.PartnerAddressController;
import com.kovil.domain.partner.enums.OfficeChangeRequestStatus;
import com.kovil.domain.partner.enums.PartnerDivisionType;
import com.kovil.domain.partner.service.AdminOfficeChangeRequestService;
import com.kovil.domain.partner.service.AdminPartnerDivisionService;
import com.kovil.domain.partner.service.PartnerService;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.infra.geocoding.GeocodedPage;
import com.kovil.infra.geocoding.GeocodingClient;
import com.kovil.infra.jwt.BlacklistedTokenStore;
import com.kovil.infra.jwt.JwtProperties;
import com.kovil.infra.jwt.JwtTokenProvider;
import com.kovil.domain.address.service.AddressSearchService;
import com.kovil.domain.address.service.AddressCoordinateService;
import com.kovil.infra.addresssearch.GeocodingAddressSearchClient;
import com.kovil.infra.geocoding.GeocodedAddress;
import com.kovil.domain.region.service.AddressRegionResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.mockito.ArgumentMatchers;
import org.mockito.BDDMockito;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * ★★ 파트너 표면의 인가 경계 — 셋 다 **문자열 하나**가 지킨다:
 *    · 파트너 가입은 비로그인에게 연다 — SecurityConfig.PUBLIC_POST_PATHS 의 한 줄.
 *    · 주소 검색은 비로그인에게도 연다(가입 중 소재지 검색) — SecurityConfig.PUBLIC_GET_PATHS 의 한 줄. 막는 것은 요청 제한.
 *    · 부문 심사 · 사무소 변경 심사는 PARTNER_APPROVE 만 — 컨트롤러의 @PreAuthorize.
 *    지우거나 바꿔도 컴파일되고 서비스 테스트는 전부 통과한다. 그래서 진짜 보안 필터 · 메서드 보안을 태워 박는다.
 *
 * ★ 400 · 200 은 **보안을 지나 컨트롤러까지 갔다**는 뜻이고, 401 · 403 은 문 앞에서 막힌 것이다.
 *   메서드 보안은 본문 검증 **뒤에** 판정되므로, 403 을 기대하는 요청은 본문이 유효해야 한다(아니면 400 이 먼저 난다).
 */
@WebMvcTest(controllers = {PartnerController.class, AdminPartnerDivisionController.class,
        AdminOfficeChangeRequestController.class, PartnerAddressController.class})
@Import({PartnerController.class, AdminPartnerDivisionController.class, AdminOfficeChangeRequestController.class,
        PartnerAddressController.class, AddressSearchService.class, GeocodingAddressSearchClient.class,
        AddressCoordinateService.class,
        SecurityConfig.class, GlobalExceptionHandler.class, ErrorCodeResolver.class, ErrorMessageResolver.class,
        JwtAuthenticationFilter.class, PartnerAccessBoundaryTest.TestBeans.class})
class PartnerAccessBoundaryTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final Instant NOW = Instant.parse("2026-10-06T00:00:00Z");
    private static final String SECRET;

    static {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 0x66);
        SECRET = Base64.getEncoder().encodeToString(key);
    }

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwtTokenProvider;

    @MockitoBean BlacklistedTokenStore blacklistedTokenStore;
    @MockitoBean PartnerService partnerService;
    @MockitoBean AdminPartnerDivisionService adminPartnerDivisionService;
    @MockitoBean AdminOfficeChangeRequestService adminOfficeChangeRequestService;
    @MockitoBean GeocodingClient geocodingClient;
    @MockitoBean AddressRegionResolver addressRegionResolver;

    /*
     * 토큰 취소 판정은 이 테스트의 관심사가 아니다 — 기본을 "취소되지 않음"으로 선언한다.
     * check() 는 enum 을 돌려주므로 스텁이 없으면 null 이고, 필터는 NONE 이 아니면 거절한다
     * (안전한 실패 방향). 선언하지 않으면 이 클래스의 모든 테스트가 401 로 무너진다.
     */
    @BeforeEach
    void allowTokensByDefault() {
        BDDMockito.given(blacklistedTokenStore.check(any(), any(), any()))
                .willReturn(BlacklistedTokenStore.Rejection.NONE);
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

    @Nested
    @DisplayName("파트너 가입은 공개, 나머지 파트너 경로는 로그인")
    class SignUpIsPublic {

        // 가입하려는 사람은 아직 토큰이 없다 — 여기가 401 이면 파트너스 앱의 첫 화면이 막힌다.
        @Test
        @DisplayName("토큰 없이 가입을 부르면 401 이 아니라 본문 검증(400)까지 간다")
        void signup_reaches_controller_without_token() throws Exception {
            mockMvc.perform(post("/api/v1/partner/signup")
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
        }

        // 가입 한 줄만 열었다 — 패턴을 넓혀 /partner/** 를 열면 제출 · 변경 · 내 상태가 함께 열린다.
        @Test
        @DisplayName("제출 · 대표번호 변경 · 내 상태는 토큰 없이 401 이다")
        void other_partner_paths_require_login() throws Exception {
            for (RequestBuilder request : List.of(
                    put("/api/v1/partner/divisions/REAL_ESTATE_BROKER")
                            .contentType(MediaType.APPLICATION_JSON).content("{}"),
                    patch("/api/v1/partner/divisions/REAL_ESTATE_BROKER/office")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"tel\":\"02-000-0000\"}"),
                    get("/api/v1/partner/me"))) {
                mockMvc.perform(request)
                        .andExpect(status().isUnauthorized())
                        .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED_ACCESS"));
            }
            then(partnerService).shouldHaveNoInteractions();
        }
    }

    /*
     * ★ 가입 중(로그인 전)인 공인중개사가 소재지를 검색해야 해서 주소 검색은 공개다(PO 확정 2026-10-23).
     *   공개 목록에서 빠지면 가입 화면의 검색이 401 로 막혀 직접 입력으로만 남는다. 호출당 과금은 요청 제한이 막는다.
     */
    @Nested
    @DisplayName("주소 검색은 비로그인 공개")
    class AddressSearchIsPublic {

        private MockHttpServletRequestBuilder search() {
            return get("/api/v1/partner/address/search").param("query", "관악로");
        }

        @Test
        @DisplayName("토큰 없이도 · 유학생 · 심사 중 파트너 · 승인된 중개사 모두 통과한다")
        void anyone_passes() throws Exception {
            BDDMockito.given(geocodingClient.search("관악로", 1, 10))
                    .willReturn(new GeocodedPage(0, 1, List.of()));

            mockMvc.perform(search()).andExpect(status().isOk());
            for (String token : List.of(bearer(UserRole.USER), partnerBearer(Set.of()), brokerBearer())) {
                mockMvc.perform(search().header("Authorization", token)).andExpect(status().isOk());
            }
            then(geocodingClient).should(times(4)).search("관악로", 1, 10);
        }
    }

    /*
     * ★ 고른 주소의 좌표는 호출마다 네이버 과금이고 매물 주소에만 쓴다 — 매물 관리 권한(PERM_LISTING_MANAGE)만.
     *   검색처럼 공개로 열리면 비로그인이 좌표 변환을 무료 지오코더처럼 쓴다.
     */
    @Nested
    @DisplayName("고른 주소의 좌표는 매물 관리 권한만")
    class CoordinateNeedsListingManage {

        private MockHttpServletRequestBuilder coordinate() {
            return get("/api/v1/partner/address/coordinate").param("roadAddress", "경기도 성남시 분당구 불정로 6");
        }

        @Test
        @DisplayName("토큰 없이는 401")
        void no_token_is_401() throws Exception {
            mockMvc.perform(coordinate())
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED_ACCESS"));
            then(geocodingClient).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("유학생 · 심사 중 파트너는 403 — 네이버를 부르지 않는다")
        void without_listing_manage_is_403() throws Exception {
            for (String token : List.of(bearer(UserRole.USER), partnerBearer(Set.of()))) {
                mockMvc.perform(coordinate().header("Authorization", token)).andExpect(status().isForbidden());
            }
            then(geocodingClient).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("승인된 중개사는 좌표를 받는다")
        void broker_gets_coordinate() throws Exception {
            BDDMockito.given(geocodingClient.search("경기도 성남시 분당구 불정로 6", 1, 1))
                    .willReturn(new GeocodedPage(1, 1, List.of(new GeocodedAddress("경기도 성남시 분당구 불정로 6",
                            "경기도 성남시 분당구 정자동 178-1", null, null, "경기도", "성남시 분당구",
                            "127.1054328", "37.3595963", null, "정자동"))));

            mockMvc.perform(coordinate().header("Authorization", brokerBearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.longitude").value("127.1054328"))
                    .andExpect(jsonPath("$.data.latitude").value("37.3595963"));
        }
    }

    /*
     * ★ 부문 심사는 PARTNER_APPROVE 만 — 승인된 공인중개사(외부 사업자)가 자기 · 남의 심사에 닿으면 심사가 없는 것과 같다.
     *   여섯 경로 전부를 본다: 메서드마다 가드를 따로 좁히는 날이 오면 하나만 새도 같은 일이 난다.
     */
    @Nested
    @DisplayName("부문 심사는 PARTNER_APPROVE 만")
    class ReviewNeedsPartnerApprove {

        private List<RequestBuilder> reviewRequests(String token) {
            return List.of(
                    get("/api/v1/admin/partner-divisions").header("Authorization", token),
                    get("/api/v1/admin/partner-divisions/pd-1").header("Authorization", token),
                    post("/api/v1/admin/partner-divisions/pd-1/approve").header("Authorization", token)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"version\":3}"),
                    post("/api/v1/admin/partner-divisions/pd-1/reject").header("Authorization", token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"등록증이 흐립니다\",\"version\":3}"),
                    post("/api/v1/admin/partner-divisions/pd-1/suspend").header("Authorization", token)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"허위 매물\"}"),
                    post("/api/v1/admin/partner-divisions/pd-1/reinstate").header("Authorization", token));
        }

        @Test
        @DisplayName("유학생 · 승인된 공인중개사 토큰은 여섯 경로 모두 403 이고 서비스를 부르지 않는다")
        void without_partner_approve_is_forbidden() throws Exception {
            for (String token : List.of(bearer(UserRole.USER), brokerBearer())) {
                for (RequestBuilder request : reviewRequests(token)) {
                    mockMvc.perform(request)
                            .andExpect(status().isForbidden())
                            .andExpect(jsonPath("$.errorCode").value("FORBIDDEN_ACCESS"));
                }
            }
            then(adminPartnerDivisionService).shouldHaveNoInteractions();
        }

        // 위 403 이 "아무도 못 들어가는 문"이라서 나온 것이 아님을 함께 고정한다(오타 난 권한은 누구도 통과하지 못한다).
        @Test
        @DisplayName("관리자 토큰은 여섯 경로 모두 통과한다")
        void admin_passes() throws Exception {
            for (RequestBuilder request : reviewRequests(bearer(UserRole.ADMIN))) {
                mockMvc.perform(request).andExpect(status().isOk());
            }
            then(adminPartnerDivisionService).should().approve("uuid-admin", "pd-1", 3L);
        }
    }

    /*
     * ★ 사무소 변경 심사도 PARTNER_APPROVE 만 — 승인된 공인중개사가 자기 변경 신청을 스스로 승인하면 등록증 심사가 없는 것과
     *   같다(번호까지 옮길 수 있다). 네 경로 전부를 본다.
     */
    @Nested
    @DisplayName("사무소 변경 심사는 PARTNER_APPROVE 만")
    class OfficeChangeReviewNeedsPartnerApprove {

        private List<RequestBuilder> reviewRequests(String token) {
            return List.of(
                    get("/api/v1/admin/office-change-requests").header("Authorization", token),
                    get("/api/v1/admin/office-change-requests/cr-1").header("Authorization", token),
                    post("/api/v1/admin/office-change-requests/cr-1/approve").header("Authorization", token)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"version\":3}"),
                    post("/api/v1/admin/office-change-requests/cr-1/reject").header("Authorization", token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"상호가 다릅니다\",\"version\":3}"));
        }

        @Test
        @DisplayName("유학생 · 승인된 공인중개사 · 심사 중 파트너 토큰은 네 경로 모두 403 이고 서비스를 부르지 않는다")
        void without_partner_approve_is_forbidden() throws Exception {
            for (String token : List.of(bearer(UserRole.USER), brokerBearer(), partnerBearer(Set.of()))) {
                for (RequestBuilder request : reviewRequests(token)) {
                    mockMvc.perform(request)
                            .andExpect(status().isForbidden())
                            .andExpect(jsonPath("$.errorCode").value("FORBIDDEN_ACCESS"));
                }
            }
            then(adminOfficeChangeRequestService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("관리자 토큰은 네 경로 모두 통과하고, 상태를 비우면 대기(PENDING)를 본다")
        void admin_passes() throws Exception {
            for (RequestBuilder request : reviewRequests(bearer(UserRole.ADMIN))) {
                mockMvc.perform(request).andExpect(status().isOk());
            }
            then(adminOfficeChangeRequestService).should().findQueue(ArgumentMatchers.eq(
                    OfficeChangeRequestStatus.PENDING), any());
            then(adminOfficeChangeRequestService).should().approve("uuid-admin", "cr-1", 3L);
            then(adminOfficeChangeRequestService).should().reject("uuid-admin", "cr-1", "상호가 다릅니다", 3L);
        }

        @Test
        @DisplayName("토큰 없이는 401 이다")
        void requires_login() throws Exception {
            mockMvc.perform(get("/api/v1/admin/office-change-requests"))
                    .andExpect(status().isUnauthorized());
            then(adminOfficeChangeRequestService).shouldHaveNoInteractions();
        }
    }
}
