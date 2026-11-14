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
import com.kovil.infra.jwt.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * 중개사 매물 목록의 정렬 · 상태별 개수를 **진짜 보안 필터 · 진짜 라우팅 · 진짜 쿼리**로 태운다.
 * ★ /status-counts 는 /{listingUuid} 와 같은 자리에 있다 — 리터럴 경로가 먼저 잡히지 않으면 "status-counts" 라는
 *   매물을 찾다 404 가 난다. 컨트롤러 메서드 순서나 패턴을 바꿔도 컴파일은 된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BrokerListingApiTest {

    private static final String PATH = "/api/v1/broker/listings";

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired Clock clock;
    @Autowired UserRepository userRepository;
    @Autowired BrokerageOfficeRepository officeRepository;
    @Autowired BrokerProfileRepository profileRepository;
    @Autowired PartnerDivisionRepository divisionRepository;
    @Autowired ListingRepository listingRepository;

    private String bearer;
    private Long officeId;

    @BeforeEach
    void setUp() {
        User broker = userRepository.save(User.builder()
                .uuid("broker-api").name("홍길동")
                .nickname("broker-api").nicknameNormalized("broker-api").isNicknameCustom(false)
                .preferredLanguage("ko").status(UserStatus.ACTIVE).role(UserRole.PARTNER).build());
        officeId = officeRepository.save(BrokerageOffice.builder().uuid("broker-api-office").regNo("99999-2026-00041")
                .name("테스트공인중개사사무소").ceoName("홍길동")
                .address("서울특별시 관악구").tel("02-000-0000").build()).getId();
        profileRepository.save(BrokerProfile.builder().userId(broker.getId()).officeId(officeId).build());
        divisionRepository.save(PartnerDivision.builder().uuid("pd-broker-api").userId(broker.getId())
                .division(PartnerDivisionType.REAL_ESTATE_BROKER).status(PartnerDivisionStatus.APPROVED)
                .submittedAt(LocalDateTime.of(2026, 10, 1, 0, 0)).build());
        bearer = "Bearer " + jwtTokenProvider.issueAccessToken(broker.getUuid(), UserRole.PARTNER,
                Set.of(PartnerDivisionType.REAL_ESTATE_BROKER));
    }

    @Test
    @DisplayName("/status-counts 는 매물 상세가 아니라 상태별 개수로 간다 — 상태 8개 전부와 연장 가능 수")
    void status_counts_route_wins_over_listing_uuid() throws Exception {
        LocalDateTime now = LocalDateTime.now(clock);
        save("api-active", ListingStatus.ACTIVE, now.plusDays(20));
        save("api-draft", ListingStatus.DRAFT, null);
        save("api-renewable", ListingStatus.EXPIRED, now.minusDays(2));
        save("api-closed", ListingStatus.EXPIRED, now.minusDays(9));

        mockMvc.perform(get(PATH + "/status-counts").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.byStatus.ACTIVE").value(1))
                .andExpect(jsonPath("$.data.byStatus.DRAFT").value(1))
                .andExpect(jsonPath("$.data.byStatus.EXPIRED").value(2))
                .andExpect(jsonPath("$.data.byStatus.RESERVED").value(0))
                .andExpect(jsonPath("$.data.byStatus.ARCHIVED").value(0))
                .andExpect(jsonPath("$.data.byStatus.length()").value(8))
                .andExpect(jsonPath("$.data.renewable").value(1));
    }

    @Test
    @DisplayName("만료 임박 순으로 받으면 만료가 이른 것부터, 초안은 맨 뒤다")
    void expires_asc_over_http() throws Exception {
        LocalDateTime now = LocalDateTime.now(clock);
        save("api-later", ListingStatus.ACTIVE, now.plusDays(20));
        save("api-draft2", ListingStatus.DRAFT, null);
        save("api-sooner", ListingStatus.ACTIVE, now.plusDays(3));

        mockMvc.perform(get(PATH).param("sort", "EXPIRES_ASC").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].listingUuid").value("api-sooner"))
                .andExpect(jsonPath("$.data.content[1].listingUuid").value("api-later"))
                .andExpect(jsonPath("$.data.content[2].listingUuid").value("api-draft2"));
    }

    // 정렬 값은 enum 으로만 받는다 — 모르는 값이 칼럼 이름으로 흘러가지 않고 400 으로 끝난다.
    @Test
    @DisplayName("모르는 정렬 값은 400 INVALID_REQUEST 다")
    void unknown_sort_is_bad_request() throws Exception {
        mockMvc.perform(get(PATH).param("sort", "title").header("Authorization", bearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
    }

    private void save(String uuid, ListingStatus status, LocalDateTime expiresAt) {
        listingRepository.saveAndFlush(Listing.builder()
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
                .expiresAt(expiresAt)
                .status(status).build());
    }
}
