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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * 가격 · 면적 · 주차 · 단기 필터를 컨트롤러부터 **진짜 쿼리**까지 태운다(H2).
 * ★ 컨트롤러 → 조건 → 저장소 호출은 같은 형(Long 넷 · BigDecimal 둘 · boolean 둘)을 자리 순서로 넘긴다 — 자리를 바꿔도 컴파일되고,
 *   목으로 저장소를 받는 서비스 테스트는 그 자리를 보지 못한다. 필터마다 정답 집합을 다르게 깔아 어느 둘이 자리를 바꿔도 집합이 달라진다.
 * ★ 목록 쿼리는 넷이다(추천순 · 최신순 × 반경 없음 · 있음) — 호출이 따로라 자리도 따로 틀릴 수 있어 네 경로를 다 돈다.
 *   f-far 는 원(500m) 밖이라 반경이 있으면 빠진다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ListingFilterApiTest {

    private static final String PATH = "/api/v1/listings";
    private static final BigDecimal LAT = new BigDecimal("37.5600000");
    private static final BigDecimal LNG = new BigDecimal("126.9400000");
    private static final double LAT_PER_M = 1 / 111_195.08;
    private static final String[] SORTS = {"RECOMMENDED", "LATEST"};

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired BrokerageOfficeRepository officeRepository;
    @Autowired BrokerProfileRepository profileRepository;
    @Autowired PartnerDivisionRepository divisionRepository;
    @Autowired ListingRepository listingRepository;

    private Long office;

    @BeforeEach
    void setUp() {
        office = officeOf("flt-office", "99999-2026-00161");
        //          uuid     북(m)   보증금        월세       면적     주차   단기
        save("f-a",       0,   1_000_000L, 700_000L, "18.00", false, false);
        save("f-b",     100,   5_000_000L, 300_000L, "45.00", true,  false);
        save("f-c",     200,  20_000_000L, 900_000L, "33.00", false, true);
        save("f-d",     300, 100_000_000L, 100_000L, "60.00", true,  true);
        save("f-far", 2_000,   5_000_000L, 300_000L, "45.00", true,  true);
    }

    private static MockHttpServletRequestBuilder list(boolean nearby, String sort) {
        MockHttpServletRequestBuilder request = get(PATH).param("sort", sort);
        return nearby
                ? request.param("lat", LAT.toPlainString()).param("lng", LNG.toPlainString()).param("radius", "500")
                : request;
    }

    @Test
    @DisplayName("필터가 없으면 다섯 건 · 반경 500m 면 원 밖 f-far 를 뺀 넷(추천순 · 최신순)")
    void without_filters() throws Exception {
        for (String sort : SORTS) {
            mockMvc.perform(list(false, sort))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content[*].listingUuid")
                            .value(containsInAnyOrder("f-a", "f-b", "f-c", "f-d", "f-far")));
            mockMvc.perform(list(true, sort))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content[*].listingUuid")
                            .value(containsInAnyOrder("f-a", "f-b", "f-c", "f-d")));
        }
    }

    /*
     * 자리가 바뀌면 이렇게 갈린다(원 안 기준): depositMin 5,000,000 → {b,c,d}, depositMax 로 가면 {a,b}, rentMin 으로 가면 {},
     * rentMax 로 가면 넷 다. rentMin 500,000 → {a,c}, rentMax 로 가면 {b,d}. areaMin 40 ↔ areaMax 는 {b,d} ↔ {a,c},
     * canParking ↔ shortLease 는 {b,d} ↔ {c,d}. 필터가 아예 빠지면 넷 다 — 어느 줄도 넷 다가 아니다.
     */
    @ParameterizedTest(name = "{0}={1}")
    @CsvSource({
            "depositMin, 5000000,  f-b|f-c|f-d|f-far, f-b|f-c|f-d",
            "depositMax, 10000000, f-a|f-b|f-far,     f-a|f-b",
            "rentMin,    500000,   f-a|f-c,           f-a|f-c",
            "rentMax,    300000,   f-b|f-d|f-far,     f-b|f-d",
            "areaMin,    40,       f-b|f-d|f-far,     f-b|f-d",
            "areaMax,    35,       f-a|f-c,           f-a|f-c",
            "canParking, true,     f-b|f-d|f-far,     f-b|f-d",
            "shortLease, true,     f-c|f-d|f-far,     f-c|f-d"
    })
    @DisplayName("필터 하나씩 — 반경 없이 · 반경 있이, 추천순 · 최신순 네 목록 쿼리가 모두 정확한 매물 집합을 준다")
    void each_filter_reaches_every_list_query(String name, String value, String everywhere, String nearby)
            throws Exception {
        for (String sort : SORTS) {
            mockMvc.perform(list(false, sort).param(name, value))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content[*].listingUuid")
                            .value(containsInAnyOrder(everywhere.split("\\|"))));
            mockMvc.perform(list(true, sort).param(name, value))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content[*].listingUuid")
                            .value(containsInAnyOrder(nearby.split("\\|"))));
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

    private void save(String uuid, int northMeters, long deposit, long monthlyRent, String area,
                      boolean canParking, boolean shortLease) {
        BigDecimal lat = LAT.add(BigDecimal.valueOf(northMeters * LAT_PER_M)).setScale(7, RoundingMode.HALF_UP);
        listingRepository.saveAndFlush(Listing.builder()
                // listing_no 는 VARCHAR(20) 이라 uuid 를 그대로 붙이면 넘친다.
                .uuid(uuid).listingNo("LS-261013-" + Integer.toHexString(uuid.hashCode()).toUpperCase())
                .officeId(office).createdByUserId(10L)
                .category(ListingCategory.ONE_TWO_ROOM).roomType(RoomType.ONE_ROOM)
                .title("테스트").sellingType(SellingType.MONTHLY_RENT)
                .deposit(deposit).monthlyRent(monthlyRent)
                .exclusiveArea(new BigDecimal(area)).floorType(FloorType.GROUND)
                .canParking(canParking).shortLease(shortLease)
                .residentRegistration(AvailabilityAnswer.ASK).depositInsurance(AvailabilityAnswer.ASK)
                .realLat(lat).realLng(LNG).displayLat(lat).displayLng(LNG)
                .addressJibun("서울특별시 서대문구 창천동")
                .postedAt(LocalDateTime.of(2026, 10, 1, 0, 0))
                .status(ListingStatus.ACTIVE).build());
    }
}
