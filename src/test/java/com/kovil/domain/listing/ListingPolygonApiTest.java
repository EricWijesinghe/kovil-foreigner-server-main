package com.kovil.domain.listing;

import com.kovil.domain.listing.entity.BrokerProfile;
import com.kovil.domain.listing.entity.BrokerageOffice;
import com.kovil.domain.listing.entity.Listing;
import com.kovil.domain.listing.enums.*;
import com.kovil.domain.listing.repository.BrokerProfileRepository;
import com.kovil.domain.listing.repository.BrokerageOfficeRepository;
import com.kovil.domain.listing.repository.ListingRepository;
import com.kovil.domain.partner.entity.PartnerDivision;
import com.kovil.domain.partner.enums.PartnerDivisionStatus;
import com.kovil.domain.partner.enums.PartnerDivisionType;
import com.kovil.domain.partner.repository.PartnerDivisionRepository;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import com.jayway.jsonpath.JsonPath;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * 목록 그린 구역(그리기)을 **진짜 쿼리**로 태운다(H2).
 * ★ 구역과 구역을 감싼 사각형은 다르다 — U 자 구역의 홈(pg-notch)은 사각형 안 · 구역 밖이라 빠져야 한다.
 *   DB 선필터만 하고 판정을 빼먹으면 여기서 걸린다.
 * ★ 판정 뒤에 페이지를 자른다 — 먼저 자르면 페이지마다 개수가 줄고 totalElements 가 틀린다.
 * ★ 북쪽 변 위의 매물(pg-edge)은 안이다. 꼭짓점과 같은 위도로 깔아 정확히 변 위에 놓았다.
 * 좌표는 위도 37.56 기준 미터로 깔았다(ListingRadiusApiTest 와 같은 환산).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ListingPolygonApiTest {

    private static final String PATH = "/api/v1/listings";
    private static final BigDecimal LAT = new BigDecimal("37.5600000");
    private static final BigDecimal LNG = new BigDecimal("126.9400000");
    private static final double LAT_PER_M = 1 / 111_195.08;
    private static final double LNG_PER_M = 1 / (111_195.08 * Math.cos(Math.toRadians(37.56)));

    // 한 변 800m 정사각형(중심 ± 400m).
    private static final int[][] SQUARE = {{-400, -400}, {-400, 400}, {400, 400}, {400, -400}};
    // ± 300m 사각형에서 북쪽 가운데(동서 ± 100m, 남북 0 ~ 300m)를 파낸 U 자.
    private static final int[][] U_SHAPE = {
            {-300, -300}, {-300, 300}, {300, 300}, {300, 100}, {0, 100}, {0, -100}, {300, -100}, {300, -300}};

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired BrokerageOfficeRepository officeRepository;
    @Autowired BrokerProfileRepository profileRepository;
    @Autowired PartnerDivisionRepository divisionRepository;
    @Autowired ListingRepository listingRepository;

    private Long office;

    @BeforeEach
    void setUp() {
        office = officeOf("pg-office", "99999-2026-00131");
        at("pg-center", -100, 0, ListingStatus.ACTIVE, SellingType.MONTHLY_RENT, 5, AvailabilityAnswer.ASK);
        at("pg-ne", 200, 200, ListingStatus.RESERVED, SellingType.MONTHLY_RENT, 4, AvailabilityAnswer.ASK);
        at("pg-sw", -200, -200, ListingStatus.ACTIVE, SellingType.LEASE, 3, AvailabilityAnswer.ASK);
        at("pg-notch", 200, 0, ListingStatus.ACTIVE, SellingType.MONTHLY_RENT, 2, AvailabilityAnswer.ASK);
        at("pg-edge", 400, 0, ListingStatus.ACTIVE, SellingType.MONTHLY_RENT, 1, AvailabilityAnswer.POSSIBLE);
        at("pg-far", 600, 0, ListingStatus.ACTIVE, SellingType.MONTHLY_RENT, 6, AvailabilityAnswer.ASK);
        at("pg-done", -100, 100, ListingStatus.COMPLETED, SellingType.MONTHLY_RENT, 7, AvailabilityAnswer.ASK);
    }

    private MockHttpServletRequestBuilder drawn(int[][] meters) {
        return get(PATH).param("polygon", polygonOf(meters)).param("size", "50");
    }

    @Test
    @DisplayName("정사각형 — 안의 공개 · 예약 중 · 변 위 매물이 오고, 밖 · 거래 완료는 빠진다")
    void square() throws Exception {
        mockMvc.perform(drawn(SQUARE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(5))
                .andExpect(jsonPath("$.data.content[*].listingUuid").value(containsInAnyOrder(
                        "pg-center", "pg-ne", "pg-sw", "pg-notch", "pg-edge")));
    }

    @Test
    @DisplayName("처음 점을 끝에 다시 써도 같은 구역이다")
    void closed_ring_is_same_area() throws Exception {
        String ring = polygonOf(SQUARE) + "," + pointOf(SQUARE[0]);
        mockMvc.perform(get(PATH).param("polygon", ring).param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(5));
    }

    @Test
    @DisplayName("U 자 — 외접 사각형 안이어도 파낸 홈의 매물은 빠진다")
    void concave_excludes_notch() throws Exception {
        mockMvc.perform(drawn(U_SHAPE))
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.content[*].listingUuid")
                        .value(containsInAnyOrder("pg-center", "pg-ne", "pg-sw")));
    }

    @Test
    @DisplayName("페이지를 넘겨도 totalElements 는 구역 안 개수 그대로, 페이지를 이으면 빠짐 · 겹침이 없다")
    void exact_total_across_pages() throws Exception {
        List<String> seen = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            String body = mockMvc.perform(get(PATH).param("polygon", polygonOf(SQUARE))
                            .param("sort", "LATEST").param("page", String.valueOf(page)).param("size", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(5))
                    .andExpect(jsonPath("$.data.totalPages").value(3))
                    .andExpect(jsonPath("$.data.currentPage").value(page))
                    .andExpect(jsonPath("$.data.last").value(page == 2))
                    .andReturn().getResponse().getContentAsString();
            seen.addAll(JsonPath.read(body, "$.data.content[*].listingUuid"));
        }
        assertThat(seen).containsExactly("pg-center", "pg-ne", "pg-sw", "pg-notch", "pg-edge");

        mockMvc.perform(get(PATH).param("polygon", polygonOf(SQUARE)).param("page", "9").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(5))
                .andExpect(jsonPath("$.data.content").isEmpty());
    }

    @Test
    @DisplayName("정렬은 그대로 — 최신순은 게시 시각, 추천순은 전입신고 가능 먼저")
    void sort_is_preserved() throws Exception {
        mockMvc.perform(drawn(SQUARE).param("sort", "LATEST"))
                .andExpect(jsonPath("$.data.content[*].listingUuid")
                        .value(contains("pg-center", "pg-ne", "pg-sw", "pg-notch", "pg-edge")));
        mockMvc.perform(drawn(SQUARE))
                .andExpect(jsonPath("$.data.content[*].listingUuid")
                        .value(contains("pg-edge", "pg-center", "pg-ne", "pg-sw", "pg-notch")));
        // 보증금이 모두 같다 — 월세 0 인 전세(pg-sw)가 먼저, 나머지는 id 내림차순(나중 매물 먼저).
        mockMvc.perform(drawn(SQUARE).param("sort", "PRICE_ASC"))
                .andExpect(jsonPath("$.data.totalElements").value(5))
                .andExpect(jsonPath("$.data.content[*].listingUuid")
                        .value(contains("pg-sw", "pg-edge", "pg-notch", "pg-ne", "pg-center")));
    }

    @Test
    @DisplayName("추천순 · 같은 게시 시각이면 나중 매물이 먼저 — 목록 추천순과 같다")
    void recommended_ties_break_by_id_desc() throws Exception {
        listingRepository.deleteAll(listingRepository.findAll());
        at("pg-tie-old", 0, 0, ListingStatus.ACTIVE, SellingType.MONTHLY_RENT, 5, AvailabilityAnswer.ASK);
        at("pg-tie-new", 100, 0, ListingStatus.ACTIVE, SellingType.MONTHLY_RENT, 5, AvailabilityAnswer.ASK);

        mockMvc.perform(drawn(SQUARE))
                .andExpect(jsonPath("$.data.content[*].listingUuid").value(contains("pg-tie-new", "pg-tie-old")));
    }

    @Test
    @DisplayName("다른 필터와 AND — 월세만 고르면 구역 안 전세가 빠진다")
    void and_with_other_filters() throws Exception {
        mockMvc.perform(drawn(SQUARE).param("sellingTypes", "MONTHLY_RENT"))
                .andExpect(jsonPath("$.data.totalElements").value(4))
                .andExpect(jsonPath("$.data.content[*].listingUuid")
                        .value(containsInAnyOrder("pg-center", "pg-ne", "pg-notch", "pg-edge")));
    }

    @Test
    @DisplayName("지도 영역과 함께 주면 둘 다 만족 — 북쪽 반이면 북쪽 매물만, 안 겹치면 0건")
    void and_with_bounding_box() throws Exception {
        mockMvc.perform(drawn(SQUARE)
                        .param("swLat", LAT.toPlainString()).param("swLng", "126.90")
                        .param("neLat", "37.60").param("neLng", "126.98"))
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.content[*].listingUuid")
                        .value(containsInAnyOrder("pg-ne", "pg-notch", "pg-edge")));

        mockMvc.perform(drawn(SQUARE)
                        .param("swLat", "37.70").param("swLng", "127.10")
                        .param("neLat", "37.80").param("neLng", "127.20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    @DisplayName("판정은 흐린 좌표 — 실좌표가 안이어도 흐린 좌표가 밖이면 빠지고, 반대면 들어온다")
    void judged_on_display_coordinates() throws Exception {
        listingRepository.deleteAll(listingRepository.findAll());
        save("pg-real-in", ListingStatus.ACTIVE, SellingType.MONTHLY_RENT, 1, AvailabilityAnswer.ASK,
                LAT, LNG, offsetLat(0), offsetLng(700));
        save("pg-real-out", ListingStatus.ACTIVE, SellingType.MONTHLY_RENT, 1, AvailabilityAnswer.ASK,
                offsetLat(5_000), LNG, LAT, offsetLng(100));

        mockMvc.perform(drawn(SQUARE))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].listingUuid").value("pg-real-out"));
    }

    // 후보 쿼리는 정렬에 따라 둘로 갈린다 — 위도 · 경도 칸을 따로, 두 쿼리 모두에서 본다.
    @Test
    @DisplayName("판정은 흐린 좌표 — 정렬마다, 위도 · 경도 축마다 실좌표가 밖이어도 흐린 좌표가 안이면 들어온다")
    void judged_on_display_coordinates_every_sort_each_axis() throws Exception {
        listingRepository.deleteAll(listingRepository.findAll());
        save("pg-real-lat-out", ListingStatus.ACTIVE, SellingType.MONTHLY_RENT, 1, AvailabilityAnswer.ASK,
                offsetLat(5_000), LNG, offsetLat(100), LNG);
        save("pg-real-lng-out", ListingStatus.ACTIVE, SellingType.MONTHLY_RENT, 2, AvailabilityAnswer.ASK,
                LAT, offsetLng(5_000), LAT, offsetLng(100));

        for (String sort : new String[]{"RECOMMENDED", "LATEST", "PRICE_ASC"}) {
            mockMvc.perform(drawn(SQUARE).param("sort", sort))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(2))
                    .andExpect(jsonPath("$.data.content[*].listingUuid")
                            .value(containsInAnyOrder("pg-real-lat-out", "pg-real-lng-out")));
        }
    }

    @Test
    @DisplayName("빈 값 · 형식 · 개수 · 지수 표기 · 선 겹침은 400 LISTING_POLYGON_INVALID")
    void invalid_polygon() throws Exception {
        String bowTie = pointOf(new int[]{-400, -400}) + "," + pointOf(new int[]{400, 400}) + ","
                + pointOf(new int[]{-400, 400}) + "," + pointOf(new int[]{400, -400});
        for (String raw : new String[]{
                "", " ",
                "37.56:126.94,37.57:126.95",
                "37.56,126.94,37.57,126.95,37.58,126.93",
                "abc:126.94,37.57:126.95,37.58:126.93",
                "1e-999999999:126.94,37.57:126.95,37.58:126.93",
                bowTie}) {
            mockMvc.perform(get(PATH).param("polygon", raw))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("LISTING_POLYGON_INVALID"));
        }
    }

    @Test
    @DisplayName("반경과 함께 주면 400 LISTING_RADIUS_INVALID")
    void polygon_with_radius_is_rejected() throws Exception {
        mockMvc.perform(drawn(SQUARE).param("lat", LAT.toPlainString()).param("lng", LNG.toPlainString())
                        .param("radius", "500"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("LISTING_RADIUS_INVALID"));
    }

    @Test
    @DisplayName("구역 없이 부르면 지금처럼 전체 — 공개 매물 6건")
    void no_polygon_is_unchanged() throws Exception {
        mockMvc.perform(get(PATH).param("size", "50"))
                .andExpect(jsonPath("$.data.totalElements").value(6));
    }

    // 지도 API 는 그린 구역을 받지 않는다 — 붙여 보내도 무시되고 같은 응답이 나온다.
    @Test
    @DisplayName("지도는 그린 구역을 받지 않는다")
    void map_ignores_polygon() throws Exception {
        String[] box = {"37.50", "126.88", "37.62", "127.00"};
        String plain = mockMvc.perform(get(PATH + "/map").param("swLat", box[0]).param("swLng", box[1])
                        .param("neLat", box[2]).param("neLng", box[3]))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String withPolygon = mockMvc.perform(get(PATH + "/map").param("swLat", box[0]).param("swLng", box[1])
                        .param("neLat", box[2]).param("neLng", box[3]).param("polygon", polygonOf(U_SHAPE)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(withPolygon).isEqualTo(plain);
    }

    private static String polygonOf(int[][] meters) {
        return Arrays.stream(meters).map(ListingPolygonApiTest::pointOf).collect(Collectors.joining(","));
    }

    private static String pointOf(int[] northEast) {
        return offsetLat(northEast[0]).toPlainString() + ":" + offsetLng(northEast[1]).toPlainString();
    }

    private void at(String uuid, int northMeters, int eastMeters, ListingStatus status, SellingType type,
                    int postedDay, AvailabilityAnswer residentRegistration) {
        BigDecimal lat = offsetLat(northMeters);
        BigDecimal lng = offsetLng(eastMeters);
        save(uuid, status, type, postedDay, residentRegistration, lat, lng, lat, lng);
    }

    private static BigDecimal offsetLat(int meters) {
        return LAT.add(BigDecimal.valueOf(meters * LAT_PER_M)).setScale(7, RoundingMode.HALF_UP);
    }

    private static BigDecimal offsetLng(int meters) {
        return LNG.add(BigDecimal.valueOf(meters * LNG_PER_M)).setScale(7, RoundingMode.HALF_UP);
    }

    private Long officeOf(String uuid, String regNo) {
        Long representativeId = userRepository.save(User.builder()
                .uuid("u-" + uuid).name("홍길동")
                .nickname("u-" + uuid).nicknameNormalized("u-" + uuid).isNicknameCustom(false)
                .preferredLanguage("ko").status(UserStatus.ACTIVE).role(UserRole.PARTNER)
                .build()).getId();
        Long officeId = officeRepository.save(BrokerageOffice.builder().uuid(uuid).regNo(regNo)
                .name("테스트공인중개사사무소").ceoName("홍길동")
                .address("서울특별시 서대문구").tel("02-000-0000").build()).getId();
        profileRepository.save(BrokerProfile.builder().userId(representativeId).officeId(officeId).build());
        divisionRepository.save(PartnerDivision.builder().uuid("pd-" + representativeId).userId(representativeId)
                .division(PartnerDivisionType.REAL_ESTATE_BROKER).status(PartnerDivisionStatus.APPROVED)
                .submittedAt(LocalDateTime.of(2026, 10, 1, 0, 0)).build());
        return officeId;
    }

    private void save(String uuid, ListingStatus status, SellingType type, int postedDay,
                      AvailabilityAnswer residentRegistration,
                      BigDecimal realLat, BigDecimal realLng, BigDecimal displayLat, BigDecimal displayLng) {
        listingRepository.saveAndFlush(Listing.builder()
                .uuid(uuid).listingNo("LS-261013-" + Integer.toHexString(uuid.hashCode()).toUpperCase())
                .officeId(office).createdByUserId(10L)
                .category(ListingCategory.ONE_TWO_ROOM).roomType(RoomType.ONE_ROOM)
                .title("테스트").sellingType(type)
                .deposit(3_000_000L).monthlyRent(type == SellingType.LEASE ? 0L : 300_000L)
                .exclusiveArea(new BigDecimal("26.44")).floorType(FloorType.GROUND)
                .residentRegistration(residentRegistration).depositInsurance(AvailabilityAnswer.ASK)
                .realLat(realLat).realLng(realLng).displayLat(displayLat).displayLng(displayLng)
                .addressJibun("서울특별시 서대문구 창천동")
                .postedAt(LocalDateTime.of(2026, 10, postedDay, 0, 0))
                .status(status).build());
    }
}
