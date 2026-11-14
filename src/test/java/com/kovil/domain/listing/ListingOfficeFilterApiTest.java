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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.math.RoundingMode;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * "이 사무소의 다른 방" — 상세의 office.officeUuid 를 목록 필터로 넘기는 흐름을 **진짜 쿼리(서브쿼리 · 개수 쿼리) · 진짜 보안 필터**로 태운다(H2).
 * ★ 없는 사무소와 광고할 수 없는 사무소(부문 정지 · 대표 정지 · 대표 탈퇴)는 같은 빈 페이지다 — 정지 사실을 드러내지 않는다.
 * ★ 개수(totalElements)는 개수 쿼리가 따로 센다 — size=1 로 불러 첫 페이지에서도 개수 쿼리가 돌게 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ListingOfficeFilterApiTest {

    private static final String PATH = "/api/v1/listings";
    private static final BigDecimal LAT = new BigDecimal("37.5600000");
    private static final BigDecimal LNG = new BigDecimal("126.9400000");

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired BrokerageOfficeRepository officeRepository;
    @Autowired BrokerProfileRepository profileRepository;
    @Autowired PartnerDivisionRepository divisionRepository;
    @Autowired ListingRepository listingRepository;

    @BeforeEach
    void setUp() {
        Long a = officeOf("of-a", "99999-2026-00141", PartnerDivisionStatus.APPROVED, UserStatus.ACTIVE, false);
        Long b = officeOf("of-b", "99999-2026-00142", PartnerDivisionStatus.APPROVED, UserStatus.ACTIVE, false);
        Long divisionSuspended = officeOf("of-c", "99999-2026-00143", PartnerDivisionStatus.SUSPENDED, UserStatus.ACTIVE, false);
        Long userSuspended = officeOf("of-d", "99999-2026-00144", PartnerDivisionStatus.APPROVED, UserStatus.SUSPENDED, false);
        Long withdrawn = officeOf("of-e", "99999-2026-00145", PartnerDivisionStatus.APPROVED, UserStatus.ACTIVE, true);

        save("a-1", a, ListingStatus.ACTIVE, SellingType.MONTHLY_RENT, 0);
        save("a-2", a, ListingStatus.RESERVED, SellingType.MONTHLY_RENT, 300);
        save("a-3", a, ListingStatus.COMPLETED, SellingType.MONTHLY_RENT, 0);
        save("a-4", a, ListingStatus.ACTIVE, SellingType.LEASE, 2_000);
        save("b-1", b, ListingStatus.ACTIVE, SellingType.MONTHLY_RENT, 0);
        save("c-1", divisionSuspended, ListingStatus.ACTIVE, SellingType.MONTHLY_RENT, 0);
        save("d-1", userSuspended, ListingStatus.ACTIVE, SellingType.MONTHLY_RENT, 0);
        save("e-1", withdrawn, ListingStatus.ACTIVE, SellingType.MONTHLY_RENT, 0);
    }

    private static MockHttpServletRequestBuilder ofOffice(String officeUuid) {
        return get(PATH).param("officeUuid", officeUuid).param("size", "50");
    }

    @Test
    @DisplayName("상세의 office.officeUuid 를 넘기면 그 사무소의 공개 · 예약 중 매물만 — 거래 완료 · 다른 사무소는 빠진다")
    void detail_office_uuid_lists_that_office() throws Exception {
        mockMvc.perform(get(PATH + "/a-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.office", aMapWithSize(5)))
                .andExpect(jsonPath("$.data.office.officeUuid").value("of-a"));

        mockMvc.perform(ofOffice("of-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.content[*].listingUuid").value(containsInAnyOrder("a-1", "a-2", "a-4")));
        mockMvc.perform(ofOffice("of-b"))
                .andExpect(jsonPath("$.data.content[*].listingUuid").value(containsInAnyOrder("b-1")));
    }

    @Test
    @DisplayName("정렬을 바꿔도 같은 사무소 매물만 — 추천순 · 최신순 · 가격순")
    void every_sort_keeps_the_office_filter() throws Exception {
        for (String sort : new String[]{"RECOMMENDED", "LATEST", "PRICE_ASC", "AREA_DESC"}) {
            mockMvc.perform(ofOffice("of-a").param("sort", sort))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(3))
                    .andExpect(jsonPath("$.data.content[*].listingUuid").value(containsInAnyOrder("a-1", "a-2", "a-4")));
        }
    }

    @Test
    @DisplayName("개수는 사무소로 거른 수다 — 한 장에 1건씩 불러도 totalElements 3")
    void total_counts_only_that_office() throws Exception {
        for (String sort : new String[]{"RECOMMENDED", "LATEST"}) {
            mockMvc.perform(get(PATH).param("officeUuid", "of-a").param("size", "1").param("sort", sort))
                    .andExpect(jsonPath("$.data.content", hasSize(1)))
                    .andExpect(jsonPath("$.data.totalElements").value(3));
        }
    }

    @Test
    @DisplayName("없는 · 광고할 수 없는 사무소(부문 정지 · 대표 정지 · 대표 탈퇴)는 모두 같은 빈 페이지 200")
    void unknown_and_unadvertisable_offices_are_empty() throws Exception {
        for (String uuid : new String[]{"of-none", "of-c", "of-d", "of-e", "%", "of-a' OR '1'='1"}) {
            mockMvc.perform(ofOffice(uuid))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(0))
                    .andExpect(jsonPath("$.data.content", hasSize(0)));
        }
    }

    @Test
    @DisplayName("빈 값 · 공백은 필터 없음 — 공개 매물 전체, 앞뒤 공백은 뗀다")
    void blank_is_no_filter() throws Exception {
        for (String blank : new String[]{"", "   "}) {
            mockMvc.perform(ofOffice(blank))
                    .andExpect(jsonPath("$.data.totalElements").value(4))
                    .andExpect(jsonPath("$.data.content[*].listingUuid")
                            .value(containsInAnyOrder("a-1", "a-2", "a-4", "b-1")));
        }
        mockMvc.perform(ofOffice("  of-a  "))
                .andExpect(jsonPath("$.data.totalElements").value(3));
    }

    @Test
    @DisplayName("다른 필터와 AND — 전세만 고르면 그 사무소 전세만")
    void and_with_filters() throws Exception {
        mockMvc.perform(ofOffice("of-a").param("sellingTypes", "LEASE"))
                .andExpect(jsonPath("$.data.content[*].listingUuid").value(containsInAnyOrder("a-4")));
        mockMvc.perform(ofOffice("of-b").param("sellingTypes", "LEASE"))
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    @DisplayName("반경 · 그린 구역과 AND — 원 · 구역 안의 다른 사무소 매물은 빠진다(추천순 · 최신순)")
    void and_with_radius_and_polygon() throws Exception {
        String square = point(-400, -400) + "," + point(-400, 400) + "," + point(400, 400) + "," + point(400, -400);
        for (String sort : new String[]{"RECOMMENDED", "LATEST"}) {
            mockMvc.perform(ofOffice("of-a").param("sort", sort)
                            .param("lat", LAT.toPlainString()).param("lng", LNG.toPlainString()).param("radius", "500"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(2))
                    .andExpect(jsonPath("$.data.content[*].listingUuid").value(containsInAnyOrder("a-1", "a-2")));
            mockMvc.perform(ofOffice("of-a").param("sort", sort).param("polygon", square))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(2))
                    .andExpect(jsonPath("$.data.content[*].listingUuid").value(containsInAnyOrder("a-1", "a-2")));
        }
    }

    // 반경 쿼리 둘도 개수 쿼리를 따로 가진다 — 원 안의 다른 사무소 매물(b-1)이 개수에 섞이면 3 이 된다.
    @Test
    @DisplayName("반경과 함께 1건씩 불러도 개수는 그 사무소의 원 안 매물 수 2(추천순 · 최신순)")
    void radius_total_counts_only_that_office() throws Exception {
        for (String sort : new String[]{"RECOMMENDED", "LATEST"}) {
            mockMvc.perform(get(PATH).param("officeUuid", "of-a")
                            .param("lat", LAT.toPlainString()).param("lng", LNG.toPlainString()).param("radius", "500")
                            .param("size", "1").param("sort", sort))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content", hasSize(1)))
                    .andExpect(jsonPath("$.data.totalElements").value(2))
                    .andExpect(jsonPath("$.data.totalPages").value(2));
        }
    }

    // 지도는 사무소 필터를 받지 않는다 — 붙여 보내도 무시되고 같은 응답이다.
    @Test
    @DisplayName("지도는 officeUuid 를 받지 않는다")
    void map_ignores_office() throws Exception {
        String[] box = {"37.50", "126.88", "37.62", "127.00"};
        String plain = mockMvc.perform(get(PATH + "/map").param("swLat", box[0]).param("swLng", box[1])
                        .param("neLat", box[2]).param("neLng", box[3]))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String withOffice = mockMvc.perform(get(PATH + "/map").param("swLat", box[0]).param("swLng", box[1])
                        .param("neLat", box[2]).param("neLng", box[3]).param("officeUuid", "of-b"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(withOffice).isEqualTo(plain);
        assertThat(plain).contains("a-1", "b-1");
    }

    private static String point(int northMeters, int eastMeters) {
        double lat = LAT.doubleValue() + northMeters / 111_195.08;
        double lng = LNG.doubleValue() + eastMeters / (111_195.08 * Math.cos(Math.toRadians(37.56)));
        return String.format(Locale.ROOT, "%.7f:%.7f", lat, lng);
    }

    private Long officeOf(String uuid, String regNo, PartnerDivisionStatus divisionStatus, UserStatus userStatus,
                          boolean withdrawn) {
        User representative = User.builder()
                .uuid("u-" + uuid).name("홍길동")
                .nickname("u-" + uuid).nicknameNormalized("u-" + uuid).isNicknameCustom(false)
                .preferredLanguage("ko").status(userStatus).role(UserRole.PARTNER)
                .build();
        if (withdrawn) {
            representative.withdraw(LocalDateTime.of(2026, 10, 2, 0, 0));
        }
        Long representativeId = userRepository.save(representative).getId();
        Long officeId = officeRepository.save(BrokerageOffice.builder().uuid(uuid).regNo(regNo)
                .name("테스트공인중개사사무소").ceoName("홍길동")
                .address("서울특별시 서대문구").tel("02-000-0000").build()).getId();
        profileRepository.save(BrokerProfile.builder().userId(representativeId).officeId(officeId).build());
        divisionRepository.save(PartnerDivision.builder().uuid("pd-" + representativeId).userId(representativeId)
                .division(PartnerDivisionType.REAL_ESTATE_BROKER).status(divisionStatus)
                .submittedAt(LocalDateTime.of(2026, 10, 1, 0, 0)).build());
        return officeId;
    }

    private void save(String uuid, Long officeId, ListingStatus status, SellingType type, int northMeters) {
        BigDecimal lat = LAT.add(BigDecimal.valueOf(northMeters / 111_195.08)).setScale(7, RoundingMode.HALF_UP);
        listingRepository.saveAndFlush(Listing.builder()
                .uuid(uuid).listingNo("LS-261013-" + Integer.toHexString(uuid.hashCode()).toUpperCase())
                .officeId(officeId).createdByUserId(10L)
                .category(ListingCategory.ONE_TWO_ROOM).roomType(RoomType.ONE_ROOM)
                .title("테스트").sellingType(type)
                .deposit(3_000_000L).monthlyRent(type == SellingType.LEASE ? 0L : 300_000L)
                .exclusiveArea(new BigDecimal("26.44")).floorType(FloorType.GROUND)
                .residentRegistration(AvailabilityAnswer.ASK).depositInsurance(AvailabilityAnswer.ASK)
                .realLat(lat).realLng(LNG).displayLat(lat).displayLng(LNG)
                .addressJibun("서울특별시 서대문구 창천동")
                .postedAt(LocalDateTime.of(2026, 10, 1, 0, 0))
                .status(status).build());
    }
}
