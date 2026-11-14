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
import org.assertj.core.api.Assertions;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * 목록 반경 필터를 **진짜 쿼리**로 태운다(H2). 실제 MySQL 은 ListingRadiusMySqlTest 가 같은 판정을 본다.
 * ★ 원과 원을 감싼 사각형은 다르다 — 모서리(북동 400m · 400m = 566m)는 사각형 안 · 원 밖이라 빠져야 한다.
 * ★ 판정은 흐린 좌표다 — 실좌표가 원 안이어도 흐린 좌표가 밖이면 빠지고, 그 반대면 들어온다.
 * 좌표는 위도 37.56 기준 1m = 위도 1/111,195 도 · 경도 1/88,136 도로 깔았다. 원 경계와 20m 이상 떨어뜨려 근사 오차와 무관하다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ListingRadiusApiTest {

    private static final String PATH = "/api/v1/listings";
    private static final BigDecimal LAT = new BigDecimal("37.5600000");
    private static final BigDecimal LNG = new BigDecimal("126.9400000");
    private static final double LAT_PER_M = 1 / 111_195.08;
    private static final double LNG_PER_M = 1 / (111_195.08 * Math.cos(Math.toRadians(37.56)));

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired BrokerageOfficeRepository officeRepository;
    @Autowired BrokerProfileRepository profileRepository;
    @Autowired PartnerDivisionRepository divisionRepository;
    @Autowired ListingRepository listingRepository;

    private Long office;

    @BeforeEach
    void setUp() {
        office = officeOf("rad-office", "99999-2026-00121");
        at("rad-n300", 300, 0, ListingStatus.ACTIVE, SellingType.MONTHLY_RENT);
        at("rad-n480", 480, 0, ListingStatus.ACTIVE, SellingType.MONTHLY_RENT);
        at("rad-e450", 0, 450, ListingStatus.RESERVED, SellingType.MONTHLY_RENT);
        at("rad-sw300", -212, -212, ListingStatus.ACTIVE, SellingType.LEASE);
        at("rad-e550", 0, 550, ListingStatus.ACTIVE, SellingType.MONTHLY_RENT);
        at("rad-corner", 400, 400, ListingStatus.ACTIVE, SellingType.MONTHLY_RENT);
        at("rad-done", 100, 0, ListingStatus.COMPLETED, SellingType.MONTHLY_RENT);
        at("rad-e950", 0, 950, ListingStatus.ACTIVE, SellingType.MONTHLY_RENT);
    }

    private MockHttpServletRequestBuilder near(int radius) {
        return get(PATH).param("lat", LAT.toPlainString()).param("lng", LNG.toPlainString())
                .param("radius", String.valueOf(radius)).param("size", "50");
    }

    @Test
    @DisplayName("500m — 원 안 공개 · 예약 중만, 원 밖 · 모서리 · 거래 완료는 빠진다")
    void within_500() throws Exception {
        mockMvc.perform(near(500))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(4))
                .andExpect(jsonPath("$.data.content[*].listingUuid")
                        .value(containsInAnyOrder("rad-n300", "rad-n480", "rad-e450", "rad-sw300")));
    }

    @Test
    @DisplayName("1km — 대학교 반경은 950m 동쪽까지 들어온다")
    void within_1000() throws Exception {
        mockMvc.perform(near(1_000))
                .andExpect(jsonPath("$.data.totalElements").value(7))
                .andExpect(jsonPath("$.data.content[*].listingUuid").value(containsInAnyOrder(
                        "rad-n300", "rad-n480", "rad-e450", "rad-sw300", "rad-e550", "rad-corner", "rad-e950")));
    }

    @Test
    @DisplayName("최신순 등 다른 정렬에도 반경이 걸린다")
    void other_sorts_apply_radius() throws Exception {
        mockMvc.perform(near(500).param("sort", "LATEST"))
                .andExpect(jsonPath("$.data.totalElements").value(4));
        mockMvc.perform(near(500).param("sort", "PRICE_ASC"))
                .andExpect(jsonPath("$.data.totalElements").value(4));
    }

    /*
     * ★ 첫 장 결과가 size 보다 적으면 Spring Data 가 개수 쿼리를 건너뛰고 결과 수를 그대로 개수로 쓴다 — size=1 로 개수 쿼리를 태운다.
     *   추천순 · 최신순은 개수 쿼리를 따로 가진다. near() 는 size=50 을 이미 붙여, 다시 주면 첫 값만 쓰인다 — 요청을 새로 만든다.
     */
    @Test
    @DisplayName("개수는 원 안의 수다 — 한 장에 1건씩 불러도 totalElements 4 · totalPages 4(추천순 · 최신순)")
    void total_counts_only_inside_the_circle() throws Exception {
        for (String sort : new String[]{"RECOMMENDED", "LATEST"}) {
            mockMvc.perform(get(PATH).param("lat", LAT.toPlainString()).param("lng", LNG.toPlainString())
                            .param("radius", "500").param("size", "1").param("sort", sort))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content", hasSize(1)))
                    .andExpect(jsonPath("$.data.totalElements").value(4))
                    .andExpect(jsonPath("$.data.totalPages").value(4));
        }
    }

    /*
     * ★ 반경 추천순은 일반 목록과 따로 ORDER BY 를 한 벌 더 가진다 — 전입신고 가능 먼저 → 최초 게시 최신 → id 내림.
     *   rad-tie-ask 는 rad-new-ask 와 게시 시각이 같고 나중에 저장해 id 가 크다 — id 내림이 빠지면 둘의 순서가 흔들린다.
     */
    @Test
    @DisplayName("반경 추천순은 전입신고 가능 먼저 · 최신 · 나중 등록 순, 최신순은 게시 시각 · 나중 등록 순")
    void radius_keeps_the_order() throws Exception {
        listingRepository.deleteAll(listingRepository.findAll());
        ranked("rad-old-possible", 100, LocalDateTime.of(2026, 10, 1, 0, 0), AvailabilityAnswer.POSSIBLE);
        ranked("rad-new-ask", 200, LocalDateTime.of(2026, 10, 5, 0, 0), AvailabilityAnswer.ASK);
        ranked("rad-tie-ask", 300, LocalDateTime.of(2026, 10, 5, 0, 0), AvailabilityAnswer.ASK);

        mockMvc.perform(near(500))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[*].listingUuid")
                        .value(contains("rad-old-possible", "rad-tie-ask", "rad-new-ask")));
        mockMvc.perform(near(500).param("sort", "LATEST"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[*].listingUuid")
                        .value(contains("rad-tie-ask", "rad-new-ask", "rad-old-possible")));
    }

    @Test
    @DisplayName("다른 필터와 AND — 월세만 고르면 원 안 전세가 빠진다")
    void and_with_other_filters() throws Exception {
        mockMvc.perform(near(500).param("sellingTypes", "MONTHLY_RENT"))
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.content[*].listingUuid")
                        .value(containsInAnyOrder("rad-n300", "rad-n480", "rad-e450")));
    }

    @Test
    @DisplayName("지도 영역과 함께 주면 둘 다 만족 — 북쪽 반만 그린 영역이면 북쪽 매물만, 안 겹치면 0건")
    void and_with_bounding_box() throws Exception {
        mockMvc.perform(near(500)
                        .param("swLat", LAT.toPlainString()).param("swLng", "126.90")
                        .param("neLat", "37.60").param("neLng", "126.98"))
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.content[*].listingUuid")
                        .value(containsInAnyOrder("rad-n300", "rad-n480", "rad-e450")));

        mockMvc.perform(near(500)
                        .param("swLat", "37.70").param("swLng", "127.10")
                        .param("neLat", "37.80").param("neLng", "127.20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    @DisplayName("판정은 흐린 좌표 — 실좌표가 원 안이어도 흐린 좌표가 밖이면 빠지고, 반대면 들어온다")
    void judged_on_display_coordinates() throws Exception {
        listingRepository.deleteAll(listingRepository.findAll());
        save("rad-real-in", office, ListingStatus.ACTIVE, SellingType.MONTHLY_RENT,
                LAT, LNG, offsetLat(0), offsetLng(700));
        save("rad-real-out", office, ListingStatus.ACTIVE, SellingType.MONTHLY_RENT,
                offsetLat(5_000), LNG, LAT, offsetLng(100));

        mockMvc.perform(near(500))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].listingUuid").value("rad-real-out"));
    }

    // ★ 반지름² 은 100m 에서 0.0000008088 남짓이다 — 소수 7자리로 잘리면 실제 반지름이 99.45m 가 되어 99.5m 매물이 빠진다.
    @Test
    @DisplayName("100m 원에서 99.5m 는 들어오고 100.5m 는 빠진다(반지름²이 반올림으로 잘리지 않는다)")
    void small_circle_keeps_precision() throws Exception {
        listingRepository.deleteAll(listingRepository.findAll());
        BigDecimal in = LAT.add(BigDecimal.valueOf(99.5 * LAT_PER_M)).setScale(7, RoundingMode.HALF_UP);
        BigDecimal out = LAT.add(BigDecimal.valueOf(100.5 * LAT_PER_M)).setScale(7, RoundingMode.HALF_UP);
        save("rad-99", office, ListingStatus.ACTIVE, SellingType.MONTHLY_RENT, in, LNG, in, LNG);
        save("rad-101", office, ListingStatus.ACTIVE, SellingType.MONTHLY_RENT, out, LNG, out, LNG);

        mockMvc.perform(near(100))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].listingUuid").value("rad-99"));
    }

    @Test
    @DisplayName("셋 중 일부만 · 범위 밖은 400 LISTING_RADIUS_INVALID, 정수가 아닌 반경은 400 INVALID_REQUEST")
    void invalid_radius() throws Exception {
        for (MockHttpServletRequestBuilder req : new MockHttpServletRequestBuilder[]{
                get(PATH).param("lat", "37.56"),
                get(PATH).param("lat", "37.56").param("lng", "126.94"),
                get(PATH).param("radius", "500"),
                near(99), near(3_001),
                get(PATH).param("lat", "1e-999999999").param("lng", "126.94").param("radius", "500")}) {
            mockMvc.perform(req)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("LISTING_RADIUS_INVALID"));
        }
        mockMvc.perform(get(PATH).param("lat", "37.56").param("lng", "126.94").param("radius", "500.5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("반경 없이 부르면 지금처럼 전체 — 공개 매물 7건")
    void no_radius_is_unchanged() throws Exception {
        mockMvc.perform(get(PATH).param("size", "50"))
                .andExpect(jsonPath("$.data.totalElements").value(7));
    }

    // 지도 API 는 반경을 받지 않는다(마커는 그대로) — 붙여 보내도 무시되고 같은 수가 나온다.
    @Test
    @DisplayName("지도는 반경을 받지 않는다")
    void map_ignores_radius() throws Exception {
        String[] box = {"37.50", "126.88", "37.62", "127.00"};
        String plain = mockMvc.perform(get(PATH + "/map").param("swLat", box[0]).param("swLng", box[1])
                        .param("neLat", box[2]).param("neLng", box[3]))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String withRadius = mockMvc.perform(get(PATH + "/map").param("swLat", box[0]).param("swLng", box[1])
                        .param("neLat", box[2]).param("neLng", box[3])
                        .param("lat", LAT.toPlainString()).param("lng", LNG.toPlainString()).param("radius", "300"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        Assertions.assertThat(withRadius).isEqualTo(plain);
    }

    private void at(String uuid, int northMeters, int eastMeters, ListingStatus status, SellingType type) {
        BigDecimal lat = offsetLat(northMeters);
        BigDecimal lng = offsetLng(eastMeters);
        save(uuid, office, status, type, lat, lng, lat, lng);
    }

    private void ranked(String uuid, int northMeters, LocalDateTime postedAt, AvailabilityAnswer residentRegistration) {
        BigDecimal lat = offsetLat(northMeters);
        save(uuid, office, ListingStatus.ACTIVE, SellingType.MONTHLY_RENT, lat, LNG, lat, LNG,
                postedAt, residentRegistration);
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

    private void save(String uuid, Long officeId, ListingStatus status, SellingType type,
                      BigDecimal realLat, BigDecimal realLng, BigDecimal displayLat, BigDecimal displayLng) {
        save(uuid, officeId, status, type, realLat, realLng, displayLat, displayLng,
                LocalDateTime.of(2026, 10, 1, 0, 0), AvailabilityAnswer.ASK);
    }

    private void save(String uuid, Long officeId, ListingStatus status, SellingType type,
                      BigDecimal realLat, BigDecimal realLng, BigDecimal displayLat, BigDecimal displayLng,
                      LocalDateTime postedAt, AvailabilityAnswer residentRegistration) {
        listingRepository.saveAndFlush(Listing.builder()
                .uuid(uuid).listingNo("LS-261013-" + Integer.toHexString(uuid.hashCode()).toUpperCase())
                .officeId(officeId).createdByUserId(10L)
                .category(ListingCategory.ONE_TWO_ROOM).roomType(RoomType.ONE_ROOM)
                .title("테스트").sellingType(type)
                .deposit(3_000_000L).monthlyRent(type == SellingType.LEASE ? 0L : 300_000L)
                .exclusiveArea(new BigDecimal("26.44")).floorType(FloorType.GROUND)
                .residentRegistration(residentRegistration).depositInsurance(AvailabilityAnswer.ASK)
                .realLat(realLat).realLng(realLng).displayLat(displayLat).displayLng(displayLng)
                .addressJibun("서울특별시 서대문구 창천동")
                .postedAt(postedAt)
                .status(status).build());
    }
}
