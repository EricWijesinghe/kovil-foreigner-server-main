package com.kovil.domain.listing;

import com.kovil.domain.listing.entity.BrokerProfile;
import com.kovil.domain.listing.entity.BrokerageOffice;
import com.kovil.domain.listing.entity.Listing;
import com.kovil.domain.listing.entity.ListingFavorite;
import com.kovil.domain.listing.enums.*;
import com.kovil.domain.listing.repository.BrokerProfileRepository;
import com.kovil.domain.listing.repository.BrokerageOfficeRepository;
import com.kovil.domain.listing.repository.ListingFavoriteRepository;
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
import jakarta.persistence.EntityManager;
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
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * 묶음 요약을 **진짜 보안 필터 · 진짜 라우팅 · 진짜 DB** 로 태운다.
 * ★ /summaries 는 /{listingUuid} 와 같은 자리에 있다 — 리터럴 경로가 먼저 잡히지 않으면 "summaries" 라는 매물을
 *   찾다 404 가 나고, 그 경로는 조회수까지 올린다. 조회수는 목이 아니라 DB 값으로 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ListingSummariesApiTest {

    private static final String PATH = "/api/v1/listings/summaries";

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired EntityManager entityManager;
    @Autowired UserRepository userRepository;
    @Autowired BrokerageOfficeRepository officeRepository;
    @Autowired BrokerProfileRepository profileRepository;
    @Autowired PartnerDivisionRepository divisionRepository;
    @Autowired ListingRepository listingRepository;
    @Autowired ListingFavoriteRepository favoriteRepository;

    private Long open;
    private Long other;

    @BeforeEach
    void setUp() {
        Long approved = officeOf("sum-o-ok", "99999-2026-00051", PartnerDivisionStatus.APPROVED);
        Long suspended = officeOf("sum-o-ng", "99999-2026-00052", PartnerDivisionStatus.SUSPENDED);
        open = save("sum-open", approved, ListingStatus.ACTIVE);
        other = save("sum-reserved", approved, ListingStatus.RESERVED);
        save("sum-done", approved, ListingStatus.COMPLETED);
        save("sum-suspended", suspended, ListingStatus.ACTIVE);
    }

    @Test
    @DisplayName("비로그인으로 열리고, 요청 순서대로 공개 매물만 · 나머지는 missing 이다")
    void anonymous_gets_items_in_order_and_missing() throws Exception {
        mockMvc.perform(withUuids("sum-reserved", "sum-done", "sum-open", "sum-suspended", "no-such"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].listingUuid").value("sum-reserved"))
                .andExpect(jsonPath("$.data.items[0].status").value("RESERVED"))
                .andExpect(jsonPath("$.data.items[1].listingUuid").value("sum-open"))
                .andExpect(jsonPath("$.data.items[1].favorited").value(false))
                .andExpect(jsonPath("$.data.missing[0]").value("sum-done"))
                .andExpect(jsonPath("$.data.missing[1]").value("sum-suspended"))
                .andExpect(jsonPath("$.data.missing[2]").value("no-such"));
    }

    // ★ 이 엔드포인트가 있는 이유다 — 상세로 미리보기를 채우면 핀을 누를 때마다 조회수가 오른다.
    @Test
    @DisplayName("몇 번을 불러도 DB 의 조회수 · 문의 수는 그대로다")
    void never_changes_view_or_inquiry_count() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(withUuids("sum-open")).andExpect(status().isOk());
        }
        entityManager.flush();
        entityManager.clear();

        Listing after = listingRepository.findById(open).orElseThrow();
        assertThat(after.getViewCount()).isZero();
        assertThat(after.getInquiryCount()).isZero();
    }

    @Test
    @DisplayName("로그인이면 찜한 매물의 favorited 가 켜진다")
    void favorited_for_logged_in_viewer() throws Exception {
        User student = userRepository.save(User.builder()
                .uuid("sum-student").status(UserStatus.ACTIVE).role(UserRole.USER).preferredLanguage("ko").build());
        favoriteRepository.saveAndFlush(ListingFavorite.builder()
                .userId(student.getId()).listingId(other).createdAt(LocalDateTime.of(2026, 10, 12, 9, 0)).build());
        String bearer = "Bearer " + jwtTokenProvider.issueAccessToken(student.getUuid(), UserRole.USER);

        mockMvc.perform(withUuids("sum-open", "sum-reserved").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].favorited").value(false))
                .andExpect(jsonPath("$.data.items[1].favorited").value(true));
    }

    @Test
    @DisplayName("uuid 가 없거나 51개면 400 INVALID_REQUEST 다 — 잘라서 주지 않는다")
    void out_of_range_is_bad_request() throws Exception {
        mockMvc.perform(get(PATH))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));

        String[] fiftyOne = IntStream.range(0, 51).mapToObj(i -> "u-" + i).toArray(String[]::new);
        mockMvc.perform(withUuids(fiftyOne))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
    }

    private MockHttpServletRequestBuilder withUuids(String... uuids) {
        return get(PATH).param("uuids", uuids);
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
