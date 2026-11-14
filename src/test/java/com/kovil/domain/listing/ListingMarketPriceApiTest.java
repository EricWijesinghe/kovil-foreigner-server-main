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
import com.kovil.domain.region.entity.Region;
import com.kovil.domain.region.repository.RegionRepository;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.hamcrest.Matchers.closeTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * 시세를 **진짜 보안 필터 · 진짜 라우팅 · 진짜 DB 집계** 로 태운다.
 * 픽스처: 서대문구 = 원룸 월세 공개 5건(1건은 예약 중) — 지도 영역 안 2건 · 밖 3건,
 *         그 밖에 거래 완료 1 · 전세 1 · 투룸 1 · 오피스텔 원룸 1 이 섞여 있다(조건별로 빠지거나 들어와야 한다).
 *         마포구 = 원룸 월세 4건 — 영역 안 3건(표본 부족).
 * ★ 숫자는 그 구 **전체**여야 한다 — 영역을 옮겨도 서대문구 평균(500만 / 50만)은 그대로, 영역 안 개수만 바뀐다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ListingMarketPriceApiTest {

    private static final String PATH = "/api/v1/listings/market-prices";

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired BrokerageOfficeRepository officeRepository;
    @Autowired BrokerProfileRepository profileRepository;
    @Autowired PartnerDivisionRepository divisionRepository;
    @Autowired ListingRepository listingRepository;
    @Autowired RegionRepository regionRepository;

    @BeforeEach
    void setUp() {
        Long office = officeOf("mp-office", "99999-2026-00071");
        Long seodaemun = regionRepository.saveAndFlush(Region.builder()
                .code("11410").nameKo("서대문구").nameEn("Seodaemun-gu").build()).getId();
        Long mapo = regionRepository.saveAndFlush(Region.builder()
                .code("11440").nameKo("마포구").nameEn("Mapo-gu").build()).getId();

        // 서대문구 — 영역 안 2 · 밖 3, 보증금 300~700만(평균 500만) · 월세 40~60만(평균 50만)
        save("mp-s1", office, seodaemun, ListingStatus.ACTIVE, SellingType.MONTHLY_RENT, RoomType.ONE_ROOM, 3_000_000, 400_000, "37.5550000", "126.9350000");
        save("mp-s2", office, seodaemun, ListingStatus.ACTIVE, SellingType.MONTHLY_RENT, RoomType.ONE_ROOM, 4_000_000, 450_000, "37.5560000", "126.9360000");
        save("mp-s3", office, seodaemun, ListingStatus.ACTIVE, SellingType.MONTHLY_RENT, RoomType.ONE_ROOM, 5_000_000, 500_000, "37.5650000", "126.9450000");
        save("mp-s4", office, seodaemun, ListingStatus.ACTIVE, SellingType.MONTHLY_RENT, RoomType.ONE_ROOM, 6_000_000, 550_000, "37.5660000", "126.9460000");
        save("mp-s5", office, seodaemun, ListingStatus.RESERVED, SellingType.MONTHLY_RENT, RoomType.ONE_ROOM, 7_000_000, 600_000, "37.5670000", "126.9470000");
        // 빠져야 하는 것 — 거래 완료(공개 아님) · 전세(거래 종류가 다름) · 투룸(방 종류 조건이 있을 때)
        save("mp-done", office, seodaemun, ListingStatus.COMPLETED, SellingType.MONTHLY_RENT, RoomType.ONE_ROOM, 99_000_000, 9_900_000, "37.5550000", "126.9350000");
        save("mp-lease", office, seodaemun, ListingStatus.ACTIVE, SellingType.LEASE, RoomType.ONE_ROOM, 150_000_000, 0, "37.5550000", "126.9350000");
        save("mp-two", office, seodaemun, ListingStatus.ACTIVE, SellingType.MONTHLY_RENT, RoomType.TWO_ROOM, 11_000_000, 800_000, "37.5550000", "126.9350000");
        // 카테고리가 다름 — 시세가 category 를 조건에 넘기지 않으면 원룸 표본 · 방 종류 없는 표본에 섞여 숫자가 바뀐다.
        save("mp-officetel", office, seodaemun, ListingCategory.OFFICETEL, ListingStatus.ACTIVE, SellingType.MONTHLY_RENT, RoomType.ONE_ROOM, 99_000_000, 9_900_000, "37.5550000", "126.9350000");
        // 마포구 — 원룸 월세 4건(영역 안 3) → 표본 부족
        save("mp-m1", office, mapo, ListingStatus.ACTIVE, SellingType.MONTHLY_RENT, RoomType.ONE_ROOM, 5_000_000, 500_000, "37.5520000", "126.9100000");
        save("mp-m2", office, mapo, ListingStatus.ACTIVE, SellingType.MONTHLY_RENT, RoomType.ONE_ROOM, 5_000_000, 500_000, "37.5530000", "126.9110000");
        save("mp-m3", office, mapo, ListingStatus.ACTIVE, SellingType.MONTHLY_RENT, RoomType.ONE_ROOM, 5_000_000, 500_000, "37.5540000", "126.9120000");
        save("mp-m4", office, mapo, ListingStatus.ACTIVE, SellingType.MONTHLY_RENT, RoomType.ONE_ROOM, 5_000_000, 500_000, "37.5700000", "126.9100000");
    }

    private MockHttpServletRequestBuilder prices(String neLat, String sellingType) {
        MockHttpServletRequestBuilder request = get(PATH)
                .param("swLat", "37.5500000").param("swLng", "126.9000000")
                .param("neLat", neLat).param("neLng", "126.9400000")
                .param("category", "ONE_TWO_ROOM")
                .header("Accept-Language", "ko");
        return sellingType == null ? request : request.param("sellingType", sellingType);
    }

    @Nested
    @DisplayName("집계")
    class Aggregation {

        @Test
        @DisplayName("비로그인으로 열리고(상세 경로로 새지 않는다), 영역 안 개수 순 · 숫자는 구 전체 · 표본 부족은 null")
        void anonymous_whole_region_numbers() throws Exception {
            mockMvc.perform(prices("37.5600000", "MONTHLY_RENT").param("roomType", "ONE_ROOM"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.minSampleSize").value(5))
                    .andExpect(jsonPath("$.data.regions.length()").value(2))
                    // 마포 — 영역 안 3 · 전체 4 → 평균 없음
                    .andExpect(jsonPath("$.data.regions[0].regionCode").value("11440"))
                    .andExpect(jsonPath("$.data.regions[0].regionName").value("마포구"))
                    .andExpect(jsonPath("$.data.regions[0].inViewCount").value(3))
                    .andExpect(jsonPath("$.data.regions[0].sampleSize").value(4))
                    .andExpect(jsonPath("$.data.regions[0].avgDeposit").isEmpty())
                    .andExpect(jsonPath("$.data.regions[0].avgMonthlyRent").isEmpty())
                    // 서대문 — 영역 안 2 · 전체 5(예약 중 포함, 거래 완료 · 전세 · 투룸 제외)
                    .andExpect(jsonPath("$.data.regions[1].regionCode").value("11410"))
                    .andExpect(jsonPath("$.data.regions[1].inViewCount").value(2))
                    .andExpect(jsonPath("$.data.regions[1].sampleSize").value(5))
                    .andExpect(jsonPath("$.data.regions[1].avgDeposit").value(5_000_000))
                    .andExpect(jsonPath("$.data.regions[1].avgMonthlyRent").value(500_000))
                    // 말풍선 자리 = 그 구 전체 5건의 흐린 좌표 평균(영역 안 2건 평균이 아니다)
                    .andExpect(jsonPath("$.data.regions[1].lat").value(closeTo(37.5618, 1e-9)))
                    .andExpect(jsonPath("$.data.regions[1].lng").value(closeTo(126.9418, 1e-9)));
        }

        @Test
        @DisplayName("지도를 조금 옮겨도 서대문구 시세는 그대로 — 영역 안 개수만 바뀐다")
        void moving_the_view_keeps_the_price() throws Exception {
            mockMvc.perform(prices("37.5555000", "MONTHLY_RENT").param("roomType", "ONE_ROOM"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.regions[1].regionCode").value("11410"))
                    .andExpect(jsonPath("$.data.regions[1].inViewCount").value(1))
                    .andExpect(jsonPath("$.data.regions[1].sampleSize").value(5))
                    .andExpect(jsonPath("$.data.regions[1].avgDeposit").value(5_000_000))
                    .andExpect(jsonPath("$.data.regions[1].avgMonthlyRent").value(500_000));
        }

        @Test
        @DisplayName("방 종류를 비우면 그 카테고리 전체 — 투룸이 표본에 들어온다(영역 안 개수가 같으면 지역 id 순)")
        void without_room_type_includes_other_rooms() throws Exception {
            mockMvc.perform(prices("37.5600000", "MONTHLY_RENT"))
                    .andExpect(status().isOk())
                    // 서대문 영역 안 3(원룸 2 + 투룸 1) = 마포 3 → 먼저 만든 서대문(작은 id)이 앞
                    .andExpect(jsonPath("$.data.regions[0].regionCode").value("11410"))
                    .andExpect(jsonPath("$.data.regions[0].inViewCount").value(3))
                    .andExpect(jsonPath("$.data.regions[0].sampleSize").value(6))
                    .andExpect(jsonPath("$.data.regions[0].avgDeposit").value(6_000_000))
                    .andExpect(jsonPath("$.data.regions[1].regionCode").value("11440"));
        }

        @Test
        @DisplayName("전세는 전세 매물만 · 표본 1건이라 평균이 없고 월세 평균은 늘 없다")
        void lease_only_counts_lease() throws Exception {
            mockMvc.perform(prices("37.5600000", "LEASE"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.regions.length()").value(1))
                    .andExpect(jsonPath("$.data.regions[0].regionCode").value("11410"))
                    .andExpect(jsonPath("$.data.regions[0].sampleSize").value(1))
                    .andExpect(jsonPath("$.data.regions[0].avgDeposit").isEmpty())
                    .andExpect(jsonPath("$.data.regions[0].avgMonthlyRent").isEmpty());
        }

        @Test
        @DisplayName("영역 안에 조건 맞는 매물이 없으면 빈 목록")
        void empty_view() throws Exception {
            mockMvc.perform(get(PATH)
                            .param("swLat", "35.0").param("swLng", "129.0").param("neLat", "35.1").param("neLng", "129.1")
                            .param("sellingType", "MONTHLY_RENT"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.regions.length()").value(0));
        }
    }

    @Nested
    @DisplayName("요청 검증")
    class Validation {

        @Test
        @DisplayName("거래 종류가 없으면 400 INVALID_REQUEST — 월세와 전세를 섞은 평균은 뜻이 없다")
        void selling_type_required() throws Exception {
            mockMvc.perform(prices("37.5600000", null))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
        }

        @Test
        @DisplayName("지도 영역 네 값 중 하나라도 없으면 400")
        void bbox_required() throws Exception {
            mockMvc.perform(get(PATH).param("swLat", "37.55").param("swLng", "126.90").param("neLat", "37.56")
                            .param("sellingType", "MONTHLY_RENT"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
        }
    }

    /*
     * ★ 숫자 칸 지수 표기 — `1e-999999999` 는 범위 검사를 통과하고 DB 바인딩에서 10억 자 문자열을 만든다.
     *   목록 · 지도 · 시세 세 공개 경로가 같은 검증(ListingSearchCondition)을 지나는지 경로마다 박는다.
     */
    @Nested
    @DisplayName("숫자 칸 검증(지수 표기 · 범위 · 자릿수)")
    class NumberGuard {

        @ParameterizedTest(name = "{0} {1}={2} → 400")
        @CsvSource({
                "/api/v1/listings, neLat, 1e-999999999",
                "/api/v1/listings, swLng, 1E+999999999",
                "/api/v1/listings, areaMin, 1e-999999999",
                "/api/v1/listings, neLat, 91",
                "/api/v1/listings, neLat, 37.5600000000000001",
                "/api/v1/listings/map, neLat, 1e-999999999",
                "/api/v1/listings/map, swLng, 181",
                "/api/v1/listings/market-prices, neLat, 1e-999999999",
                "/api/v1/listings/market-prices, neLng, -181"
        })
        void rejects_absurd_numbers(String path, String field, String value) throws Exception {
            // ★ 같은 이름을 두 번 붙이면 스프링이 첫 값만 써서 검사가 통과해 버린다 — 값을 바꿔 끼운다.
            Map<String, String> params = new LinkedHashMap<>();
            params.put("swLat", "37.55");
            params.put("swLng", "126.90");
            params.put("neLat", "37.56");
            params.put("neLng", "126.94");
            params.put("sellingType", "MONTHLY_RENT");
            params.put(field, value);
            MockHttpServletRequestBuilder request = get(path);
            params.forEach(request::param);

            mockMvc.perform(request)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
        }

        @Test
        @DisplayName("소수 15자리 · 경계값(90 · 180)은 통과한다")
        void accepts_fifteen_decimals_and_limits() throws Exception {
            mockMvc.perform(get("/api/v1/listings")
                            .param("swLat", "-90").param("swLng", "-180")
                            .param("neLat", "37.560000000000001").param("neLng", "180"))
                    .andExpect(status().isOk());
        }
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

    private void save(String uuid, Long officeId, Long regionId, ListingStatus status, SellingType sellingType,
                      RoomType roomType, long deposit, long monthlyRent, String lat, String lng) {
        save(uuid, officeId, regionId, ListingCategory.ONE_TWO_ROOM, status, sellingType, roomType,
                deposit, monthlyRent, lat, lng);
    }

    private void save(String uuid, Long officeId, Long regionId, ListingCategory category, ListingStatus status,
                      SellingType sellingType, RoomType roomType, long deposit, long monthlyRent, String lat, String lng) {
        listingRepository.saveAndFlush(Listing.builder()
                // listing_no 는 VARCHAR(20) 이라 uuid 를 그대로 붙이면 넘친다.
                .uuid(uuid).listingNo("LS-261012-" + Integer.toHexString(uuid.hashCode()).toUpperCase())
                .officeId(officeId).createdByUserId(10L).regionId(regionId)
                .category(category).roomType(roomType)
                .title("테스트").sellingType(sellingType)
                .deposit(deposit).monthlyRent(monthlyRent)
                .exclusiveArea(new BigDecimal("26.44")).floorType(FloorType.GROUND)
                .residentRegistration(AvailabilityAnswer.ASK).depositInsurance(AvailabilityAnswer.ASK)
                .realLat(new BigDecimal(lat)).realLng(new BigDecimal(lng))
                .displayLat(new BigDecimal(lat)).displayLng(new BigDecimal(lng))
                .addressJibun("서울특별시 서대문구 창천동")
                .postedAt(LocalDateTime.of(2026, 10, 1, 0, 0))
                .status(status).build());
    }
}
