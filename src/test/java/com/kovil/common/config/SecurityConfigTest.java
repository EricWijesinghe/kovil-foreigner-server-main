package com.kovil.common.config;

import com.kovil.common.dto.response.ApiResponse;
import com.kovil.common.exception.ErrorCodeResolver;
import com.kovil.common.exception.ErrorMessageResolver;
import com.kovil.common.handler.GlobalExceptionHandler;
import com.kovil.common.security.AuthenticatedUser;
import com.kovil.common.security.annotation.CurrentUser;
import com.kovil.common.security.filter.JwtAuthenticationFilter;
import com.kovil.domain.country.controller.CountryController;
import com.kovil.domain.country.service.CountryQueryService;
import com.kovil.domain.language.controller.LanguageController;
import com.kovil.domain.language.service.LanguageQueryService;
import com.kovil.domain.region.controller.RegionController;
import com.kovil.domain.region.service.RegionQueryService;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.infra.jwt.JwtProperties;
import com.kovil.infra.jwt.JwtTokenProvider;
import com.kovil.domain.partner.enums.PartnerDivisionType;
import com.kovil.infra.jwt.BlacklistedTokenStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.BDDMockito.given;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.assertj.core.api.Assertions;
import org.hamcrest.Matchers;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.BDDMockito;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Arrays;
import java.util.Date;
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {
        SecurityConfigTest.TestController.class,
        LanguageController.class, CountryController.class,
        RegionController.class
})
@Import({
        SecurityConfigTest.TestController.class,
        SecurityConfig.class,
        GlobalExceptionHandler.class,
        ErrorCodeResolver.class, ErrorMessageResolver.class,
        JwtAuthenticationFilter.class,
        SecurityConfigTest.TestBeans.class
})
class SecurityConfigTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final Instant NOW = Instant.parse("2026-08-11T00:00:00Z");
    private static final String SECRET;

    static {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 0x33);
        SECRET = Base64.getEncoder().encodeToString(key);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JwtTokenProvider jwtTokenProvider;

    // 보안 경계만 검증한다 — 목록 내용은 각 도메인 테스트가 본다.
    // 이 슬라이스가 검증하는 것은 경로별 접근 경계다. 블랙리스트 판정 자체는 별도 테스트가 본다.
    @MockitoBean
    BlacklistedTokenStore blacklistedTokenStore;

    /*
     * ★ 기본은 "통과"로 둔다.
     *
     * check() 는 enum 을 돌려주므로 스텁하지 않은 목은 null 을 준다. 필터는 NONE 이 아니면
     * 거절하므로(안전한 실패 방향) 스텁이 없으면 **모든 경계 테스트가 401 로 무너진다** —
     * 실제로 그렇게 깨졌다. 프로덕션에서는 null 이 나올 수 없다.
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
    LanguageQueryService languageQueryService;

    @MockitoBean
    CountryQueryService countryQueryService;

    @MockitoBean
    RegionQueryService regionQueryService;

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

    @TestComponent
    @RestController
    static class TestController {

        @GetMapping("/actuator/health")
        ApiResponse<String> health() {
            return ApiResponse.success("UP");
        }

        @GetMapping("/api/v1/test/me")
        ApiResponse<String> me(@CurrentUser AuthenticatedUser user) {
            return ApiResponse.success(user.userUuid() + ":" + user.role());
        }

        @GetMapping("/api/v1/test/user")
        @PreAuthorize("hasRole('USER')")
        ApiResponse<String> userRole() {
            return ApiResponse.success("user area");
        }

        // 공인중개사 부문이 여는 능력 — 매물 관리 화면과 같은 가드다.
        @GetMapping("/api/v1/test/listing-manage")
        @PreAuthorize("hasAuthority('PERM_LISTING_MANAGE')")
        ApiResponse<String> listingManage() {
            return ApiResponse.success("listing area");
        }

        @GetMapping("/api/v1/test/admin")
        @PreAuthorize("hasRole('ADMIN')")
        ApiResponse<String> adminOnly() {
            return ApiResponse.success("admin area");
        }

        // Apple 네이티브 로그인 두 경로와 같은 자리. 경계만 보므로 실제 컨트롤러는 띄우지 않는다.
        @GetMapping("/api/v1/auth/oauth/apple/nonce")
        ApiResponse<String> appleNonce() {
            return ApiResponse.success("nonce");
        }

        @PostMapping("/api/v1/auth/oauth/apple/native")
        ApiResponse<String> appleNative() {
            return ApiResponse.success("native");
        }

        // Apple 이 직접 POST 하는 두 경로와 같은 자리.
        @PostMapping("/oauth/apple/callback")
        ApiResponse<String> appleCallback() {
            return ApiResponse.success("callback");
        }

        @PostMapping("/oauth/apple/notifications")
        ApiResponse<String> appleNotifications() {
            return ApiResponse.success("notifications");
        }

        @PostMapping("/oauth/google/callback")
        ApiResponse<String> googleCallbackPost() {
            return ApiResponse.success("google");
        }
    }

    private String bearer(UserRole role) {
        return "Bearer " + jwtTokenProvider.issueAccessToken("uuid-" + role.name().toLowerCase(), role);
    }

    private String partnerBearer(Set<PartnerDivisionType> divisions) {
        return "Bearer " + jwtTokenProvider.issueAccessToken("uuid-partner", UserRole.PARTNER, divisions);
    }

    @Nested
    @DisplayName("기본 차단")
    class DenyByDefault {

        @Test
        @DisplayName("토큰 없이 보호된 경로에 접근하면 401 UNAUTHORIZED_ACCESS 봉투가 나온다")
        void no_token_is_401() throws Exception {
            mockMvc.perform(get("/api/v1/test/me").header("Accept-Language", "ko"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED_ACCESS"))
                    .andExpect(jsonPath("$.message").value("로그인이 필요해요."));
        }

        @Test
        @DisplayName("깨진 토큰은 인증되지 않은 것으로 취급되어 401이 된다")
        void broken_token_is_401() throws Exception {
            mockMvc.perform(get("/api/v1/test/me").header("Authorization", "Bearer not-a-jwt"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED_ACCESS"));
        }

        // ★ 실측에서 잡은 결함: 필터가 BlacklistedTokenStore를 주입만 하고 쓰지 않아
        // 로그아웃한 액세스 토큰이 TTL(30분)만큼 그대로 통했다.
        @Test
        @DisplayName("로그아웃(블랙리스트)된 토큰은 만료 전이라도 401이다")
        void blacklisted_token_is_401() throws Exception {
            given(blacklistedTokenStore.check(
                    ArgumentMatchers.anyString(),
                    ArgumentMatchers.anyString(),
                    ArgumentMatchers.any()))
                    .willReturn(BlacklistedTokenStore
                            .Rejection.BLACKLISTED_TOKEN);

            mockMvc.perform(get("/api/v1/test/me").header("Authorization", bearer(UserRole.USER)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED_ACCESS"));
        }

        /*
         * ★ 역할이 바뀐 사람의 살아 있는 액세스 토큰. 리프레시만 끊으면 토큰 수명(기본 30분)
         *   동안 옛 role 로 통한다 — 강등된 관리자가 공범 계정을 ADMIN 으로 올릴 수 있다
         *   (적대적 리뷰 2026-09-17).
         */
        @Test
        @DisplayName("역할이 바뀐 사람의 옛 토큰은 만료 전이라도 401이다")
        void revoked_for_user_is_401() throws Exception {
            given(blacklistedTokenStore.check(
                    ArgumentMatchers.anyString(),
                    ArgumentMatchers.anyString(),
                    ArgumentMatchers.any()))
                    .willReturn(BlacklistedTokenStore
                            .Rejection.REVOKED_USER);

            mockMvc.perform(get("/api/v1/test/me").header("Authorization", bearer(UserRole.ADMIN)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED_ACCESS"));
        }

        /*
         * ★ 필터가 취소선에 넘기는 iat 가 **토큰의 iat 여야 한다.** any() 로만 받으면 필터가
         *   null 이나 Instant.now() 를 넘겨도 테스트가 초록이다 — 전자는 유저 축을 통째로
         *   무력화하고 후자는 항상 "지금 발급"이라 취소선을 영원히 빠져나간다
         *   (적대적 리뷰 2026-09-17 3라운드).
         */
        @Test
        @DisplayName("필터는 토큰의 발급 시각을 그대로 넘긴다")
        void filter_passes_token_issued_at() throws Exception {
            String token = bearer(UserRole.USER);
            Instant issuedAt = jwtTokenProvider.parse(token.substring("Bearer ".length())).issuedAt();

            mockMvc.perform(get("/api/v1/test/me").header("Authorization", token))
                    .andExpect(status().isOk());

            ArgumentCaptor<Instant> captor =
                    ArgumentCaptor.forClass(Instant.class);
            BDDMockito.then(blacklistedTokenStore).should().check(
                    ArgumentMatchers.anyString(),
                    ArgumentMatchers.anyString(),
                    captor.capture());
            Assertions.assertThat(captor.getValue()).isEqualTo(issuedAt);
        }

        /*
         * ★ 인증된 **모든** 요청이 지나는 자리다 — 왕복 하나가 곧 전 API 지연이다.
         *   두 축을 따로 물으면 요청마다 Redis 를 두 번 간다(PO 지시 2026-09-17: 합쳐라).
         */
        @Test
        @DisplayName("토큰 검사는 Redis 를 한 번만 간다")
        void token_check_is_one_round_trip() throws Exception {
            given(blacklistedTokenStore.check(
                    ArgumentMatchers.anyString(),
                    ArgumentMatchers.anyString(),
                    ArgumentMatchers.any()))
                    .willReturn(BlacklistedTokenStore.Rejection.NONE);

            mockMvc.perform(get("/api/v1/test/me").header("Authorization", bearer(UserRole.USER)))
                    .andExpect(status().isOk());

            BDDMockito.then(blacklistedTokenStore).should(Mockito.times(1))
                    .check(ArgumentMatchers.anyString(),
                            ArgumentMatchers.anyString(),
                            ArgumentMatchers.any());
            BDDMockito.then(blacklistedTokenStore).shouldHaveNoMoreInteractions();
        }

        @Test
        @DisplayName("공개 경로는 토큰 없이도 통과한다")
        void public_path_allowed() throws Exception {
            mockMvc.perform(get("/actuator/health"))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    // ★ 아래는 **메커니즘** 검증이지 이 서비스의 정책이 아니다. 실제 엔드포인트는 전부
    //   hasAuthority('PERM_…') 로 묻는다(AuthorizationPolicyTest 가 그것을 고정한다).
    //   여기 쓰이는 hasRole 컨트롤러는 이 테스트 전용이다 — 프로덕션에는 없다.
    @DisplayName("역할 계층 ADMIN > USER · PARTNER > USER (메커니즘 — 정책 아님)")
    class RoleHierarchy {

        @Test
        @DisplayName("USER 토큰은 인증만 필요한 경로를 통과하고 principal이 주입된다")
        void user_passes_authenticated_path() throws Exception {
            mockMvc.perform(get("/api/v1/test/me").header("Authorization", bearer(UserRole.USER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").value("uuid-user:USER"));
        }

        @Test
        @DisplayName("USER 토큰은 ADMIN 경로에서 403이다")
        void user_denied_on_admin_path() throws Exception {
            mockMvc.perform(get("/api/v1/test/admin").header("Authorization", bearer(UserRole.USER)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.errorCode").value("FORBIDDEN_ACCESS"));
        }

        @Test
        @DisplayName("PARTNER 토큰은 ADMIN 경로에서 403이다")
        void partner_denied_on_admin_path() throws Exception {
            mockMvc.perform(get("/api/v1/test/admin").header("Authorization", partnerBearer(Set.of(
                            PartnerDivisionType.REAL_ESTATE_BROKER))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("ADMIN · PARTNER 토큰은 USER 경로를 통과한다")
        void admin_and_partner_pass_user_path() throws Exception {
            mockMvc.perform(get("/api/v1/test/user").header("Authorization", bearer(UserRole.ADMIN)))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/v1/test/user").header("Authorization", partnerBearer(Set.of())))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("ADMIN 토큰은 ADMIN 경로를 통과한다")
        void admin_passes_admin_path() throws Exception {
            mockMvc.perform(get("/api/v1/test/admin").header("Authorization", bearer(UserRole.ADMIN)))
                    .andExpect(status().isOk());
        }
    }

    /*
     * ★ 파트너의 능력 = 토큰에 실린 **승인된 부문**의 권한 합집합. 요청마다 DB 를 묻지 않는다.
     *   부문 상태가 바뀌면 심사가 세션을 끊으므로 토큰이 오래 어긋나지 않는다.
     */
    @Nested
    @DisplayName("파트너 권한은 승인된 부문이 연다")
    class PartnerDivisionAuthority {

        @Test
        @DisplayName("공인중개사 부문이 승인된 파트너 토큰은 매물 관리 가드를 통과한다")
        void approved_broker_division_opens_listing_manage() throws Exception {
            mockMvc.perform(get("/api/v1/test/listing-manage").header("Authorization", partnerBearer(Set.of(
                            PartnerDivisionType.REAL_ESTATE_BROKER))))
                    .andExpect(status().isOk());
        }

        // 심사 중 · 반려 · 정지 — 승인된 부문이 없으면 계정 자체에는 권한이 하나도 없다.
        @Test
        @DisplayName("승인된 부문이 없는 파트너 토큰은 매물 관리 가드에서 403이다")
        void partner_without_division_is_forbidden() throws Exception {
            mockMvc.perform(get("/api/v1/test/listing-manage").header("Authorization", partnerBearer(Set.of())))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.errorCode").value("FORBIDDEN_ACCESS"));
        }

        @Test
        @DisplayName("유학생 토큰은 매물 관리 가드에서 403이다")
        void user_is_forbidden() throws Exception {
            mockMvc.perform(get("/api/v1/test/listing-manage").header("Authorization", bearer(UserRole.USER)))
                    .andExpect(status().isForbidden());
        }

        /*
         * ★ 내부 담당자(MANAGER)와 옛 중개사(BROKER) 역할이 사라지기 전에 발급된 토큰. 서명이 맞아도
         *   500 이 아니라 401 이어야 앱이 리프레시로 새 역할의 토큰을 받는다.
         */
        @Test
        @DisplayName("사라진 역할(MANAGER · BROKER)의 토큰은 401이다")
        void retired_role_token_is_401() throws Exception {
            for (String role : new String[]{"MANAGER", "BROKER"}) {
                String token = Jwts.builder()
                        .id("jti-" + role).issuer("kovil").subject("uuid-old")
                        .claim("role", role)
                        .issuedAt(Date.from(NOW))
                        .expiration(Date.from(NOW.plus(Duration.ofMinutes(30))))
                        .signWith(Keys.hmacShaKeyFor(Base64.getDecoder().decode(SECRET)))
                        .compact();

                mockMvc.perform(get("/api/v1/test/me").header("Authorization", "Bearer " + token))
                        .andExpect(status().isUnauthorized())
                        .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED_ACCESS"));
            }
        }
    }


    // 언어·국적·지역은 로그인 화면 이전에 필요하다. 여기가 막히면 앱 첫 진입이 막힌다.
    @Nested
    @DisplayName("마스터 데이터 공개 경계")
    class MasterDataAccess {

        @Test
        @DisplayName("언어·국가·지역은 토큰 없이 조회된다")
        void master_data_is_public() throws Exception {
            mockMvc.perform(get("/api/v1/languages")).andExpect(status().isOk());
            mockMvc.perform(get("/api/v1/countries")).andExpect(status().isOk());
            mockMvc.perform(get("/api/v1/regions")).andExpect(status().isOk());
        }

        // GET만 열었다. 마스터 데이터 변경은 관리자 API가 따로 가져간다(FR-906).
        @Test
        @DisplayName("공개는 GET에만 적용된다 — 같은 경로의 POST는 막힌다")
        void only_get_is_public() throws Exception {
            mockMvc.perform(MockMvcRequestBuilders
                            .post("/api/v1/languages"))
                    .andExpect(status().isUnauthorized());
        }
    }

    // 로그인 전에 부르는 경로라 토큰 없이 열려야 한다. 다만 정확한 경로 · 메서드만 — 한 칸 패턴(*)은 두 칸 경로를 못 열고,
    // 넓은 패턴(/**)으로 열면 이 아래에 생기는 무엇이든 비로그인으로 열린다.
    @Nested
    @DisplayName("Apple 네이티브 로그인 공개 경계")
    class AppleNativeAccess {

        @Test
        @DisplayName("GET nonce · POST native 는 토큰 없이 통과한다")
        void native_paths_are_public() throws Exception {
            mockMvc.perform(get("/api/v1/auth/oauth/apple/nonce")).andExpect(status().isOk());
            mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/oauth/apple/native"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("메서드가 다르거나 옆 경로면 막힌다")
        void only_exact_paths_and_methods_are_public() throws Exception {
            mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/oauth/apple/nonce"))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(get("/api/v1/auth/oauth/apple/native"))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/oauth/apple/other"))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/oauth/apple/native/extra"))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(get("/api/v1/auth/oauth/apple/nonce/extra"))
                    .andExpect(status().isUnauthorized());
        }
    }

    // Apple 은 우리 토큰 없이 POST 한다. 막히면 웹 Apple 로그인을 마친 사람이 401 에 갇히고 알림은 영영 실패한다.
    @Nested
    @DisplayName("Apple 이 부르는 공개 경계")
    class AppleServerToServer {

        @Test
        @DisplayName("POST /oauth/apple/callback · /oauth/apple/notifications 는 토큰 없이 통과한다")
        void apple_posts_are_public() throws Exception {
            mockMvc.perform(MockMvcRequestBuilders.post("/oauth/apple/callback")).andExpect(status().isOk());
            mockMvc.perform(MockMvcRequestBuilders.post("/oauth/apple/notifications")).andExpect(status().isOk());
        }

        @Test
        @DisplayName("다른 provider 의 POST 콜백 · 옆 경로는 막힌다")
        void only_apple_posts_are_public() throws Exception {
            mockMvc.perform(MockMvcRequestBuilders.post("/oauth/google/callback"))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(MockMvcRequestBuilders.post("/oauth/apple/notifications/extra"))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(get("/oauth/apple/notifications"))
                    .andExpect(status().isUnauthorized());
        }
    }

    // 브라우저 프런트(Expo 웹 8081 · Next 관리자 웹 3000)가 이 API를 부를 수 있는지를 고정한다.
    // 네이티브 앱은 Origin을 보내지 않아 이 경계와 무관하다.
    @Nested
    @DisplayName("CORS 경계")
    class Cors {

        @Test
        @DisplayName("허용 출처의 프리플라이트는 토큰 없이도 통과한다 — Expo 웹 기본 포트")
        void preflight_allowed_from_expo_web() throws Exception {
            mockMvc.perform(options("/api/v1/test/me")
                            .header("Origin", "http://localhost:8081")
                            .header("Access-Control-Request-Method", "GET")
                            .header("Access-Control-Request-Headers", "authorization,accept-language"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:8081"));
        }

        @Test
        @DisplayName("Next 기본 포트도 통과한다")
        void preflight_allowed_from_next_web() throws Exception {
            mockMvc.perform(options("/api/v1/test/me")
                            .header("Origin", "http://localhost:3000")
                            .header("Access-Control-Request-Method", "POST"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"));
        }

        @Test
        @DisplayName("목록에 없는 출처는 프리플라이트에서 막힌다")
        void preflight_rejected_from_foreign_origin() throws Exception {
            mockMvc.perform(options("/api/v1/test/me")
                            .header("Origin", "https://evil.example.com")
                            .header("Access-Control-Request-Method", "GET"))
                    .andExpect(status().isForbidden());
        }

        // 프리플라이트만 통과시키고 실제 요청에 헤더가 없으면 브라우저가 응답을 버린다.
        @Test
        @DisplayName("실제 요청 응답에도 허용 헤더가 붙는다")
        void actual_request_carries_allow_origin() throws Exception {
            mockMvc.perform(get("/api/v1/test/me")
                            .header("Origin", "http://localhost:8081")
                            .header("Authorization", bearer(UserRole.USER)))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:8081"));
        }

        // 인증 방식이 Bearer 헤더뿐이라 쿠키를 허용할 이유가 없다. 켜는 순간 출처 와일드카드와 충돌한다.
        @Test
        @DisplayName("자격 증명(쿠키)은 허용하지 않는다")
        void credentials_not_allowed() throws Exception {
            mockMvc.perform(options("/api/v1/test/me")
                            .header("Origin", "http://localhost:8081")
                            .header("Access-Control-Request-Method", "GET"))
                    .andExpect(status().isOk())
                    .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
        }

        // 웹 프런트는 429 의 Retry-After 로 "N분 뒤"를 그린다 — 노출하지 않으면 브라우저가 스크립트에 숨긴다.
        @Test
        @DisplayName("교차 출처 응답은 Retry-After 를 스크립트에 노출한다")
        void exposes_retry_after() throws Exception {
            mockMvc.perform(get("/api/v1/languages").header("Origin", "http://localhost:8081"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Expose-Headers",
                            Matchers.containsString("Retry-After")));
        }

        // 브라우저가 부르는 것은 API뿐이다 — actuator·swagger까지 열어 두지 않는다.
        @Test
        @DisplayName("/api 밖 경로에는 CORS 헤더가 붙지 않는다")
        void non_api_path_has_no_cors_header() throws Exception {
            mockMvc.perform(get("/actuator/health").header("Origin", "http://localhost:8081"))
                    .andExpect(status().isOk())
                    .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        }
    }
}
