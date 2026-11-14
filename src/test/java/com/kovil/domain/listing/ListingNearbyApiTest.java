package com.kovil.domain.listing;

import com.kovil.domain.listing.entity.BrokerProfile;
import com.kovil.domain.listing.entity.BrokerageOffice;
import com.kovil.domain.listing.entity.Listing;
import com.kovil.domain.listing.enums.*;
import com.kovil.domain.listing.repository.BrokerProfileRepository;
import com.kovil.domain.listing.repository.BrokerageOfficeRepository;
import com.kovil.domain.listing.repository.ListingRepository;
import com.kovil.domain.listing.service.ListingNearbyProperties;
import com.kovil.domain.partner.entity.PartnerDivision;
import com.kovil.domain.partner.enums.PartnerDivisionStatus;
import com.kovil.domain.partner.enums.PartnerDivisionType;
import com.kovil.domain.partner.repository.PartnerDivisionRepository;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * 주변시설을 **진짜 보안 필터 · 진짜 라우팅 · 진짜 DB** 로 태운다(벤더는 테스트 프로필의 가짜).
 * ★ 가짜는 중심 좌표에 0.001 을 더한 곳을 돌려준다 — 그래서 응답 좌표를 보면 중심이
 *   **흐린 좌표인지 실좌표인지** 가려진다(둘은 이 픽스처에서 일부러 다르다).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ListingNearbyApiTest {

    private static final String PATH = "/api/v1/listings/{uuid}/nearby";

    @Autowired MockMvc mockMvc;
    @Autowired EntityManager entityManager;
    @Autowired UserRepository userRepository;
    @Autowired BrokerageOfficeRepository officeRepository;
    @Autowired BrokerProfileRepository profileRepository;
    @Autowired PartnerDivisionRepository divisionRepository;
    @Autowired ListingRepository listingRepository;
    @Autowired ListingNearbyProperties nearbyProperties;

    private Long open;
    private Long approvedOffice;

    @BeforeEach
    void setUp() {
        Long approved = officeOf("nb-o-ok", "99999-2026-00061", PartnerDivisionStatus.APPROVED);
        Long suspended = officeOf("nb-o-ng", "99999-2026-00062", PartnerDivisionStatus.SUSPENDED);
        approvedOffice = approved;
        open = save("nb-open", approved, ListingStatus.ACTIVE);
        save("nb-reserved", approved, ListingStatus.RESERVED);
        save("nb-done", approved, ListingStatus.COMPLETED);
        save("nb-suspended", suspended, ListingStatus.ACTIVE);
    }

    @Test
    @DisplayName("비로그인으로 열리고, 카테고리 8개가 순서대로 · 가까운 곳은 흐린 좌표 둘레다")
    void anonymous_gets_eight_categories_around_display_coordinates() throws Exception {
        mockMvc.perform(get(PATH, "nb-open"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("OK"))
                .andExpect(jsonPath("$.data.radius").value(500))
                .andExpect(jsonPath("$.data.categories[*].code")
                        .value(contains("CS2", "CE7", "BK9", "PO3", "MT1", "HP8", "PM9", "SW8")))
                .andExpect(jsonPath("$.data.categories[0].count").value(2))
                .andExpect(jsonPath("$.data.categories[0].nearest.length()").value(2))
                .andExpect(jsonPath("$.data.categories[0].nearest[0].name").value("CS2 가까운 곳"))
                // 흐린 위도 37.4862552 + 0.001 — 실위도(37.4862378)였다면 37.4872378 이 나온다.
                .andExpect(jsonPath("$.data.categories[0].nearest[0].lat").value(37.4872552))
                .andExpect(jsonPath("$.data.categories[0].nearest[0].lng").value(126.9332423));
    }

    @Test
    @DisplayName("응답 어디에도 시간 · 거리 · 실좌표가 없다")
    void no_time_distance_or_real_coordinates() throws Exception {
        mockMvc.perform(get(PATH, "nb-open"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.categories[0].nearest[0].distance").doesNotExist())
                .andExpect(content().string(not(containsString("37.4862378"))))
                .andExpect(content().string(not(containsString("126.9331220"))));
    }

    @Test
    @DisplayName("예약 중 매물도 공개라 주변시설이 열린다")
    void reserved_is_public() throws Exception {
        mockMvc.perform(get(PATH, "nb-reserved"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("OK"));
    }

    @Test
    @DisplayName("거래 완료 · 정지된 사무소 · 없는 매물은 404 LISTING_NOT_FOUND — 공개 상세와 같은 기준")
    void non_public_is_not_found() throws Exception {
        for (String uuid : new String[]{"nb-done", "nb-suspended", "no-such"}) {
            mockMvc.perform(get(PATH, uuid))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.errorCode").value("LISTING_NOT_FOUND"));
        }
    }

    // 서비스가 공개 상태 목록을 따로 들고 있어 ListingStatus 의 공개 판정이 바뀌어도 여기로 번지지 않는다.
    // 승인된 사무소 매물이라 사무소 조건으로 걸러지는 것이 아니다 — 상태만으로 404 여야 한다.
    @Test
    @DisplayName("초안 · 숨김 · 관리자 차단 · 만료 · 보관 매물도 404 LISTING_NOT_FOUND 다")
    void other_non_public_statuses_are_not_found() throws Exception {
        save("nb-draft", approvedOffice, ListingStatus.DRAFT);
        save("nb-hidden", approvedOffice, ListingStatus.HIDDEN);
        save("nb-blocked", approvedOffice, ListingStatus.BLOCKED);
        save("nb-expired", approvedOffice, ListingStatus.EXPIRED);
        save("nb-archived", approvedOffice, ListingStatus.ARCHIVED);

        for (String uuid : new String[]{"nb-draft", "nb-hidden", "nb-blocked", "nb-expired", "nb-archived"}) {
            mockMvc.perform(get(PATH, uuid))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.errorCode").value("LISTING_NOT_FOUND"));
        }
    }

    // 운영 기본값이 계약이다 — "가까운 순 최대 3곳" 은 어댑터가 이 size 를 그대로 넘기는 데 기대고,
    // 테스트 프로필의 가짜 벤더는 늘 2곳이라 3을 넘겨도 응답 모양으로는 드러나지 않는다.
    @Test
    @DisplayName("반경 500m · 가까운 곳 3곳이 기본값이다")
    void defaults_are_radius_500_and_three_nearest() {
        assertThat(nearbyProperties.radius()).isEqualTo(500);
        assertThat(nearbyProperties.nearestSize()).isEqualTo(3);
    }

    // 상세 GET 과 따로 부르는 호출이다 — 상세를 열 때마다 두 번 오르면 조회수가 부푼다.
    @Test
    @DisplayName("몇 번을 불러도 DB 의 조회수는 그대로다")
    void never_changes_view_count() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get(PATH, "nb-open")).andExpect(status().isOk());
        }
        entityManager.flush();
        entityManager.clear();

        assertThat(listingRepository.findById(open).orElseThrow().getViewCount()).isZero();
    }

    private Long officeOf(String uuid, String regNo, PartnerDivisionStatus status) {
        Long representativeId = userRepository.save(User.builder()
                .uuid("u-" + uuid).name("홍길동")
                .nickname("u-" + uuid).nicknameNormalized("u-" + uuid).isNicknameCustom(false)
                .preferredLanguage("ko").status(UserStatus.ACTIVE).role(UserRole.PARTNER)
                .build()).getId();
        Long officeId = officeRepository.save(BrokerageOffice.builder().uuid(uuid).regNo(regNo)
                .name("테스트공인중개사사무소").ceoName("홍길동")
                .address("서울특별시 관악구").tel("02-000-0000").build()).getId();
        profileRepository.save(BrokerProfile.builder().userId(representativeId).officeId(officeId).build());
        divisionRepository.save(PartnerDivision.builder().uuid("pd-" + representativeId).userId(representativeId)
                .division(PartnerDivisionType.REAL_ESTATE_BROKER).status(status)
                .submittedAt(LocalDateTime.of(2026, 10, 1, 0, 0)).build());
        return officeId;
    }

    private Long save(String uuid, Long officeId, ListingStatus status) {
        return listingRepository.saveAndFlush(Listing.builder()
                // listing_no 는 VARCHAR(20) 이라 uuid 를 그대로 붙이면 넘친다.
                .uuid(uuid).listingNo("LS-261012-" + Integer.toHexString(uuid.hashCode()).toUpperCase())
                .officeId(officeId).createdByUserId(10L)
                .category(ListingCategory.ONE_TWO_ROOM).roomType(RoomType.ONE_ROOM)
                .title("테스트").sellingType(SellingType.MONTHLY_RENT)
                .deposit(3_000_000L).monthlyRent(300_000L)
                .exclusiveArea(new BigDecimal("26.44")).floorType(FloorType.GROUND)
                .residentRegistration(AvailabilityAnswer.ASK).depositInsurance(AvailabilityAnswer.ASK)
                .realLat(new BigDecimal("37.4862378")).realLng(new BigDecimal("126.9331220"))
                .displayLat(new BigDecimal("37.4862552")).displayLng(new BigDecimal("126.9332423"))
                .addressJibun("서울특별시 관악구 신림동")
                .postedAt(LocalDateTime.of(2026, 10, 1, 0, 0))
                .status(status).build()).getId();
    }
}
