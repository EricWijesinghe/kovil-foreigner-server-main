package com.kovil.common.config;

import io.swagger.v3.oas.annotations.media.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// open_api.json은 FE 계약의 정본이다. 어노테이션만 달아 두고
// 실제로 생성되는지 확인하지 않으면, 깨진 채로 FE가 착수하게 된다.
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiDocumentTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    @DisplayName("문서가 생성되고 주요 엔드포인트가 전부 실려 있다")
    void document_is_generated_with_all_endpoints() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("KOVIL API"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andReturn();

        String json = result.getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(json).contains(
                "/api/v1/languages", "/api/v1/countries", "/api/v1/regions",
                "/api/v1/auth/phone/send", "/api/v1/auth/phone/verify",
                "/api/v1/auth/signup", "/api/v1/auth/login", "/api/v1/auth/token/refresh",
                "/api/v1/auth/logout", "/api/v1/auth/oauth/{provider}",
                "/api/v1/me", "/api/v1/me/consents", "/api/v1/me/devices",
                "/api/v1/verifications", "/api/v1/verifications/me",
                "/api/v1/admin/verifications",
                "/api/v1/admin/verifications/{verificationUuid}/approve",
                "/api/v1/admin/verifications/{verificationUuid}/reject",
                "/api/v1/rental-requests", "/api/v1/rental-requests/options",
                "/api/v1/rental-requests/{requestUuid}",
                "/api/v1/rental-requests/{requestUuid}/cancel",
                "/api/v1/me/notifications", "/api/v1/me/notifications/unread-count",
                "/api/v1/me/notifications/settings",
                "/api/v1/me/notifications/{notificationUuid}/read",
                "/api/v1/admin/notifications/failed",
                "/api/v1/admin/notifications/{outboxUuid}/retry",
                "/api/v1/community/categories",
                "/api/v1/community/posts",
                "/api/v1/community/posts/{postUuid}",
                "/api/v1/community/posts/{postUuid}/comments",
                "/api/v1/community/comments/{commentUuid}",
                "/api/v1/me/community/posts",
                "/api/v1/community/posts/{postUuid}/like",
                "/api/v1/community/comments/{commentUuid}/like",
                "/api/v1/community/posts/{postUuid}/report",
                "/api/v1/community/comments/{commentUuid}/report",
                "/api/v1/community/posts/{postUuid}/block-author",
                "/api/v1/community/comments/{commentUuid}/block-author",
                "/api/v1/admin/community/reports/{reportUuid}/resolve",
                "/api/v1/admin/community/reports/{reportUuid}/dismiss",
                "/api/v1/admin/community/posts/{postUuid}/blind",
                "/api/v1/admin/community/posts/{postUuid}/unblind",
                "/api/v1/admin/community/comments/{commentUuid}/blind",
                "/api/v1/admin/community/comments/{commentUuid}/unblind",
                "/api/v1/admin/community/reports",
                "/api/v1/admin/users",
                "/api/v1/admin/users/{userUuid}",
                "/api/v1/admin/users/{userUuid}/suspend",
                "/api/v1/admin/users/{userUuid}/unsuspend",
                "/api/v1/admin/master/countries",
                "/api/v1/admin/master/regions",
                "/api/v1/admin/master/resident-statuses",
                "/api/v1/admin/master/document-types",
                "/api/v1/admin/master/requirement-matrix",
                "/api/v1/admin/master/request-options",
                "/api/v1/admin/master/community-categories",

                // BE_REQUEST_08_20_02 — 마스터 정렬 UX 개편(이동 조작)의 재정렬 표면
                "/api/v1/admin/master/countries/order",
                "/api/v1/admin/master/regions/order",
                "/api/v1/admin/master/resident-statuses/order",
                "/api/v1/admin/master/document-types/order",
                "/api/v1/admin/master/request-options/order",
                "/api/v1/admin/master/community-categories/order",

                // 관리자 웹(디자인 스테이지 BE_REQUEST_ADMIN)이 추가로 쓰는 표면
                "/api/v1/admin/verifications/{verificationUuid}/revoke",
                "/api/v1/admin/users/{userUuid}/verifications",
                "/api/v1/admin/users/{userUuid}/phone-reveal",
                "/api/v1/admin/community/posts/{postUuid}",
                "/api/v1/admin/community/comments/{commentUuid}",
                "/api/v1/admin/community/posts/{postUuid}/sibling-posts",

                // BE_REQUEST_08_16_03 — 신고 대상 묶음 대기열 · 신고 단건(딥링크 축)
                "/api/v1/admin/community/reports/targets",
                "/api/v1/admin/community/reports/{reportUuid}",

                "/api/v1/auth/email/find",
                "/api/v1/admin/users/{userUuid}/role",

                // 파트너 가입 · 부문 심사 (PO 결정 2026-10-06)
                "/api/v1/partner/signup",
                "/api/v1/partner/divisions/REAL_ESTATE_BROKER",
                "/api/v1/partner/divisions/REAL_ESTATE_BROKER/office",
                "/api/v1/partner/me",
                "/api/v1/admin/partner-divisions",
                "/api/v1/admin/partner-divisions/{divisionUuid}",
                "/api/v1/admin/partner-divisions/{divisionUuid}/approve",
                "/api/v1/admin/partner-divisions/{divisionUuid}/reject",
                "/api/v1/admin/partner-divisions/{divisionUuid}/suspend",
                "/api/v1/admin/partner-divisions/{divisionUuid}/reinstate",

                // 승인된 사무소의 정보 변경 신청 심사 (PO 결정 2026-10-07)
                "/api/v1/admin/office-change-requests",
                "/api/v1/admin/office-change-requests/{requestUuid}",
                "/api/v1/admin/office-change-requests/{requestUuid}/approve",
                "/api/v1/admin/office-change-requests/{requestUuid}/reject",

                // 임대 요청을 파트너스(공인중개사 사무소)가 맡는다 — 대기 목록 · 맡은 요청 · 상세 · 잡기 · 놓기 · 종료 (PO 결정 2026-10-07)
                "/api/v1/partner/rental-requests",
                "/api/v1/partner/rental-requests/mine",
                "/api/v1/partner/rental-requests/{requestUuid}",
                "/api/v1/partner/rental-requests/{requestUuid}/claim",
                "/api/v1/partner/rental-requests/{requestUuid}/release",
                "/api/v1/partner/rental-requests/{requestUuid}/close",

                // 관리자는 임대 요청을 맡지 않고 본다 — 읽기 전용 목록 · 상세 (PO 결정 2026-10-06)
                "/api/v1/admin/rental-requests",
                "/api/v1/admin/rental-requests/{requestUuid}",
                "AdminRentalRequestResponse");

        // 상세 응답에 전화번호가 실리면 마스킹이 UI 장식이 되고 열람 기록이 무의미해진다.
        assertThat(json).contains("phone-reveal");

        // 관리자 임대 요청 열람에도 전화 칸이 없다. 스키마 이름은 단순 클래스 이름이라 중첩 레코드의 이름이 겹치면
        // 파트너 응답의 Applicant(휴대폰) · 유학생 응답의 Office(대표자 휴대폰)와 한 스키마로 합쳐져 문서가 전화 칸을 약속한다.
        JsonNode schemas = JsonMapper.builder().build().readTree(json).get("components").get("schemas");
        JsonNode adminRequest = schemas.get("AdminRentalRequestResponse").get("properties");
        assertThat(adminRequest.get("applicant").get("$ref").asText()).endsWith("/AdminRentalRequestApplicant");
        assertThat(adminRequest.get("office").get("$ref").asText()).endsWith("/AdminRentalRequestOffice");
        assertThat(schemas.get("AdminRentalRequestApplicant").get("properties").has("phone")).isFalse();
        assertThat(schemas.get("AdminRentalRequestOffice").get("properties").has("ceoPhone")).isFalse();

        // 잡기 이력(누가 잡고 놓았나)은 관리자 상세에만 — 파트너 · 유학생 응답은 다른 사무소의 흔적을 싣지 않는다.
        assertThat(adminRequest.get("claimHistory").get("items").get("$ref").asText())
                .endsWith("/AdminRentalRequestClaimEvent");
        for (String other : List.of("PartnerRentalRequestResponse", "PartnerRentalRequestSummaryResponse",
                "RentalRequestResponse")) {
            assertThat(schemas.get(other).get("properties").has("claimHistory")).as(other).isFalse();
        }

        // 주소 검색 프록시 (PO 지시 2026-08-20) — 매물 주소 입력용으로 파트너 경로로 옮겼다.
        assertThat(json).contains("/api/v1/partner/address/search");

        // 내부 담당자 · 직원 가입 신청 · 관리자 사무소 수기 관리는 통째로 사라졌다 — 표면이 하나라도 남아 있으면
        // FE 가 없는 기능을 부른다. 파트너는 가입 → 부문 심사로만 생긴다.
        assertThat(json).doesNotContain(
                "/api/v1/auth/roles", "/api/v1/me/staff-application", "/api/v1/admin/staff-applications",
                "/api/v1/manager/users", "/api/v1/manager/address", "/api/v1/admin/brokerage-offices",
                "PERM_STAFF_APPROVE", "PERM_STAFF_LOOKUP");

        // 담당자의 임대 요청 처리(대기열 · 시작 · 이관 · 메모 · 종료)와 이관 드롭다운도 사라졌다 — 요청은 파트너스(사무소)가 맡는다.
        assertThat(json).doesNotContain(
                "/api/v1/manager/rental-requests", "/api/v1/manager/staff",
                "PERM_RENTAL_REQUEST_MANAGE", "PERM_ASSIGNMENT_OVERRIDE",
                "RentalRequestAssignRequest", "RentalRequestMemoRequest", "ManagerRentalRequestResponse");

        // 계약 도메인(계약 · 계약서 · 입주 확인서 · 해지 · 수납 계좌의 은행)은 통째로 사라졌다 —
        // 표면이 하나라도 남아 있으면 FE 가 없는 기능을 부른다.
        assertThat(json).doesNotContain(
                "/api/v1/contracts", "/api/v1/manager/contracts", "/api/v1/admin/contracts",
                "/api/v1/manager/terminations", "/api/v1/manager/inspections",
                "/api/v1/banks", "/api/v1/admin/master/banks",
                "ContractRef");

        // 관리자 테스트 푸시 (PO 지시 2026-08-21)
        assertThat(json).contains("/api/v1/admin/notifications/push-test");

        // 지도 레이어(비로그인 공개)
        assertThat(json).contains("/api/v1/universities", "/api/v1/transit/subway-stations", "/api/v1/search/places");

        // 테스트 전용 컨트롤러는 @SpringBootTest의 컴포넌트 스캔에 잡히면 그대로 FE 계약이 된다.
        // 실제로 /api/v1/test/admin이 문서에 실려 나간 적이 있다 — @TestComponent로 막았고 여기서 고정한다.
        assertThat(json).doesNotContain("/test/");

        // 설계 의도가 실제로 문서에 실렸는지 — 어노테이션이 붙어 있기만 한 게 아니라.
        assertThat(json).contains("PATCH다").contains("멱등");
        // 소셜 연결은 같은 번호 계정을 흡수하지 않고 거절한다 — 옛 흡수 문구가 남으면 앱이 옛 동작을 기대한다.
        assertThat(json).contains("그 번호의 계정이 이미 있으면 붙이지 않고")
                .doesNotContain("계정 통합 지점").doesNotContain("통합 경로로 성공")
                .doesNotContain("그 계정에 이 소셜 수단을 붙인다").doesNotContain("새 계정을 만들지 않고");

        // 다음 세션·FE가 바로 집어갈 수 있게 파일로도 떨군다.
        Path out = Path.of("build", "open_api.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, json, StandardCharsets.UTF_8);
    }

    /*
     * ★★ springdoc 은 중첩 record 의 **홑이름**으로 스키마를 만든다. 이름이 같으면 문서에서 하나로 합쳐져
     *    생성된 타입이 없는 칸을 믿는다 — 공개 상세의 사무소(4칸)가 파트너용 8칸(대표번호 · 등록증)으로 덮였다.
     *    응답 모양은 그대로라 런타임 테스트로는 영원히 초록이다. 문서에서만 보인다.
     */
    @Test
    @DisplayName("공개 상세의 사무소와 파트너의 사무소는 다른 스키마다 — 공개 쪽에 대표번호 · 사업자번호 · 등록증이 없다")
    void office_views_are_separate_schemas() throws Exception {
        JsonNode schemas = schemas();

        assertThat(propertyNames(schemas.get("ListingOfficeView")))
                .containsExactlyInAnyOrder("officeUuid", "name", "ceoName", "regNo", "address");
        assertThat(propertyNames(schemas.get("PartnerOfficeView")))
                .contains("officeUuid", "tel", "bizNo", "licenseFileUuid");

        assertThat(refOf(schemas, "ListingDetailResponse", "office")).isEqualTo("ListingOfficeView");
        assertThat(refOf(schemas, "PartnerMeResponse", "brokerageOffice")).isEqualTo("PartnerOfficeView");
        assertThat(refOf(schemas, "AdminPartnerDivisionDetailResponse", "office")).isEqualTo("PartnerOfficeView");
        assertThat(refOf(schemas, "AdminOfficeChangeRequestDetailResponse", "current")).isEqualTo("PartnerOfficeView");
        assertThat(schemas.has("OfficeView")).isFalse();
    }

    /*
     * ★ 요청과 응답의 관리비가 한 스키마로 합쳐지면 응답이 요청의 필수 · 길이 제약을 물려받는다.
     *   이름표가 엉뚱한 record 에 붙어도 컴파일은 된다 — 사진 목록이 관리비 비목 이름으로 나가는 식이다. 참조로 고정한다.
     */
    @Test
    @DisplayName("중개사 매물의 관리비는 요청 · 응답 스키마가 따로이고, 사진 목록은 관리비 이름을 빌려 쓰지 않는다")
    void broker_maintenance_input_and_view_are_separate() throws Exception {
        JsonNode schemas = schemas();

        assertThat(refOf(schemas, "BrokerListingSaveRequest", "maintenance")).isEqualTo("BrokerListingMaintenanceInput");
        assertThat(itemRefOf(schemas, "BrokerListingMaintenanceInput", "items")).isEqualTo("BrokerListingMaintenanceItemInput");
        assertThat(refOf(schemas, "BrokerListingResponse", "maintenance")).isEqualTo("BrokerListingMaintenanceView");
        assertThat(itemRefOf(schemas, "BrokerListingMaintenanceView", "items")).isEqualTo("BrokerListingMaintenanceItemView");
        assertThat(itemRefOf(schemas, "BrokerListingResponse", "images")).isEqualTo("ImagePart");

        // 길이 제약은 요청에만 있다 — 응답 비목이 요청 제약을 물려받지 않았는지.
        assertThat(schemas.get("BrokerListingMaintenanceItemInput").get("properties").get("etcDesc").has("maxLength")).isTrue();
        assertThat(schemas.get("BrokerListingMaintenanceItemView").get("properties").get("etcDesc").has("maxLength")).isFalse();
        assertThat(schemas.has("MaintenancePart")).isFalse();
        assertThat(schemas.has("ItemPart")).isFalse();
    }

    /*
     * ★ 다음 충돌을 문서 생성 단계에서 잡는다. 도메인 · 공통 DTO 의 스키마 이름(@Schema(name) 이 있으면 그것,
     *   없으면 홑이름 — springdoc 규칙)이 둘 이상의 타입에서 같고 그 이름이 문서에 실려 있으면 실패한다.
     *   문서에 안 나가는 외부 연동 DTO(infra) 의 Address · Content 같은 홑이름 중복은 보지 않는다.
     */
    @Test
    @DisplayName("문서에 실리는 DTO 끼리 스키마 이름이 겹치지 않는다")
    void published_dto_schema_names_are_unique() throws Exception {
        JsonNode schemas = schemas();
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        CachingMetadataReaderFactory readers = new CachingMetadataReaderFactory(resolver);

        Map<String, List<String>> byName = new TreeMap<>();
        for (String pattern : List.of("classpath*:com/kovil/domain/**/dto/**/*.class",
                "classpath*:com/kovil/common/**/dto/**/*.class")) {
            for (Resource resource : resolver.getResources(pattern)) {
                Class<?> type = Class.forName(readers.getMetadataReader(resource).getClassMetadata().getClassName());
                if (type.isEnum() || type.isInterface() || type.isAnonymousClass() || type.isSynthetic()) {
                    continue;
                }
                Schema schema = type.getAnnotation(Schema.class);
                String name = schema != null && !schema.name().isBlank() ? schema.name() : type.getSimpleName();
                byName.computeIfAbsent(name, k -> new ArrayList<>()).add(type.getName());
            }
        }

        Map<String, List<String>> collisions = new TreeMap<>();
        byName.forEach((name, types) -> {
            if (types.size() > 1 && schemas.has(name)) {
                collisions.put(name, types);
            }
        });
        assertThat(collisions)
                .as("같은 스키마 이름이 여러 타입에 있다 — 중첩 record 에 @Schema(name = \"도메인접두이름\") 을 붙여라")
                .isEmpty();
    }

    // 학생 요청 상세의 "맡았던 사무소가 놓았어요" 한 줄(BE-15) — 시각만 있고 사무소 · 원인은 없다.
    @Test
    @DisplayName("학생 요청 응답에 대기 복귀 시각 칸이 있다")
    void rental_request_has_last_released_at() throws Exception {
        assertThat(propertyNames(schemas().get("RentalRequestResponse"))).contains("lastReleasedAt");
    }

    // 파트너가 학생에게 가는 자기 번호를 확인하는 칸(BE-17).
    @Test
    @DisplayName("파트너 내 정보에 본인 휴대폰 칸이 있다")
    void partner_me_has_phone() throws Exception {
        assertThat(propertyNames(schemas().get("PartnerMeResponse"))).contains("phone");
    }

    /*
     * ★ 중첩 record Line 은 홑이름이 너무 흔하다 — 이름표가 빠지면 문서에 'Line' 으로 나가 FE 생성 타입명이 바뀌고,
     *   다른 도메인에 Line 이 생기는 순간 한 스키마로 합쳐진다. 참조로 고정한다.
     */
    @Test
    @DisplayName("지하철역 노선은 SubwayStationLine 스키마다 — 홑이름 Line 으로 나가지 않는다")
    void subway_station_line_schema_is_prefixed() throws Exception {
        JsonNode schemas = schemas();
        assertThat(itemRefOf(schemas, "SubwayStationResponse", "lines")).isEqualTo("SubwayStationLine");
        assertThat(propertyNames(schemas.get("SubwayStationLine"))).containsExactlyInAnyOrder("code", "name", "color");
        assertThat(schemas.has("Line")).isFalse();
    }

    // ★ 홑이름 Item 은 이미 요청 선택지 항목(RequestOptionGroupResponse.Item)이 쓴다 — 이름표가 빠지면 그 스키마와 합쳐진다.
    @Test
    @DisplayName("장소 검색 항목은 PlaceSearchItem 스키마다 — 요청 선택지의 Item 과 합쳐지지 않는다")
    void place_search_item_schema_is_prefixed() throws Exception {
        JsonNode schemas = schemas();
        assertThat(itemRefOf(schemas, "PlaceSearchResponse", "items")).isEqualTo("PlaceSearchItem");
        assertThat(propertyNames(schemas.get("PlaceSearchItem")))
                .containsExactlyInAnyOrder("type", "code", "name", "subtitle", "lat", "lng", "regionCode");
        assertThat(propertyNames(schemas.get("Item"))).doesNotContain("subtitle", "regionCode");

        // 검색어는 query — 서버의 다른 검색(주소 · 관리자 회원)과 같은 이름. 한 글자 q 로 되돌리지 않는다.
        String json = mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode parameters = JsonMapper.builder().build().readTree(json)
                .get("paths").get("/api/v1/search/places").get("get").get("parameters");
        assertThat(parameters.valueStream().map(p -> p.get("name").asString()).toList())
                .containsExactlyInAnyOrder("query", "limit");
    }

    // "이 사무소의 다른 방" — 사무소 필터는 목록에만 있다. 지도에 열면 핀만 걸리고 개수 · 묶음은 안 걸린다.
    @Test
    @DisplayName("목록은 officeUuid 를 받고 지도는 받지 않는다")
    void office_filter_is_list_only() throws Exception {
        String json = mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode paths = JsonMapper.builder().build().readTree(json).get("paths");
        assertThat(parameterNames(paths, "/api/v1/listings")).contains("officeUuid", "regionCode", "polygon");
        assertThat(parameterNames(paths, "/api/v1/listings/map")).doesNotContain("officeUuid");
        assertThat(parameterNames(paths, "/api/v1/listings/market-prices")).doesNotContain("officeUuid");
    }

    private List<String> parameterNames(JsonNode paths, String path) {
        return paths.get(path).get("get").get("parameters").valueStream().map(p -> p.get("name").asString()).toList();
    }

    private JsonNode schemas() throws Exception {
        String json = mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JsonMapper.builder().build().readTree(json).get("components").get("schemas");
    }

    private List<String> propertyNames(JsonNode schema) {
        assertThat(schema).as("스키마가 문서에 없다").isNotNull();
        return schema.get("properties").properties().stream().map(Map.Entry::getKey).toList();
    }

    // OpenAPI 3.1 — 설명이 붙은 참조도 형제 칸으로 $ref 를 그대로 둔다.
    private String refOf(JsonNode schemas, String schema, String property) {
        return nameOf(schemas.get(schema).get("properties").get(property).get("$ref"));
    }

    private String itemRefOf(JsonNode schemas, String schema, String property) {
        return nameOf(schemas.get(schema).get("properties").get(property).get("items").get("$ref"));
    }

    private String nameOf(JsonNode ref) {
        assertThat(ref).as("$ref 가 없다").isNotNull();
        return ref.asString().substring("#/components/schemas/".length());
    }

    @Test
    @DisplayName("Swagger UI가 열린다")
    void swagger_ui_is_served() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }
}
