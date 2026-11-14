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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * 내 찜 목록을 **진짜 보안 필터 · 진짜 쿼리 · 진짜 직렬화**로 한 번에 태운다.
 * 조립 테스트(목)와 쿼리 테스트(저장소)는 각자 반쪽만 본다 — 둘이 맞물리는지는 여기서만 보인다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MyListingFavoritesApiTest {

    private static final String PATH = "/api/v1/me/listing-favorites";
    private static final String REAL_LAT = "37.4862378";

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired UserRepository userRepository;
    @Autowired BrokerageOfficeRepository officeRepository;
    @Autowired BrokerProfileRepository profileRepository;
    @Autowired PartnerDivisionRepository divisionRepository;
    @Autowired ListingRepository listingRepository;
    @Autowired ListingFavoriteRepository favoriteRepository;

    private User me;
    private String bearer;

    @BeforeEach
    void setUp() {
        me = userRepository.save(User.builder()
                .uuid("fav-api-me").status(UserStatus.ACTIVE).role(UserRole.USER)
                .preferredLanguage("ko").build());
        bearer = "Bearer " + jwtTokenProvider.issueAccessToken(me.getUuid(), UserRole.USER);
    }

    // 찜은 사람에게 붙는 데이터다 — 비로그인에게는 읽을 찜이 없다.
    @Test
    @DisplayName("비로그인은 401 이다")
    void anonymous_is_unauthorized() throws Exception {
        mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized());
    }

    /*
     * ★ 먼저 저장한 매물(작은 id)을 나중에 담는다 — 담은 순서와 id 순서를 엇갈리게 해야
     *   "최근에 담은 순" 이 "id 내림차순" 과 구분된다.
     */
    @Test
    @DisplayName("최근에 담은 순으로, 공개 매물은 카드 · 내려간 매물은 brief 로 온다")
    void returns_recent_first_with_public_card_and_gone_brief() throws Exception {
        Long approved = officeOf("fav-o-ok", "99999-2026-00021", PartnerDivisionStatus.APPROVED);
        Long suspended = officeOf("fav-o-ng", "99999-2026-00022", PartnerDivisionStatus.SUSPENDED);
        Long gone = save("fav-gone", suspended, ListingStatus.ACTIVE);
        Long open = save("fav-open", approved, ListingStatus.ACTIVE);
        favorite(open, LocalDateTime.of(2026, 10, 8, 14, 20));
        favorite(gone, LocalDateTime.of(2026, 10, 9, 9, 5));

        String body = call()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.content[0].listingUuid").value("fav-gone"))
                .andExpect(jsonPath("$.data.content[0].available").value(false))
                .andExpect(jsonPath("$.data.content[0].brief.listingNo").isNotEmpty())
                .andExpect(jsonPath("$.data.content[0].brief.monthlyRent").value(300_000))
                .andExpect(jsonPath("$.data.content[0].brief.lat").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].brief.title").doesNotExist())
                .andExpect(jsonPath("$.data.content[1].listingUuid").value("fav-open"))
                .andExpect(jsonPath("$.data.content[1].available").value(true))
                .andExpect(jsonPath("$.data.content[1].listing.listingUuid").value("fav-open"))
                .andExpect(jsonPath("$.data.content[1].listing.favorited").value(true))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        // ★ 실좌표는 공개 카드에도 brief 에도 나가지 않는다.
        assertThat(body).doesNotContain(REAL_LAT);
    }

    /*
     * ★ 같은 승인 사무소라도 매물 상태가 가른다 — 예약 중은 공개 상세가 열리므로 카드, 거래 완료는 내려간 매물이다.
     *   공개 판정에 넘기는 상태를 좁히거나 넓히면 여기서 깨진다.
     */
    @Test
    @DisplayName("승인 사무소의 예약 중 매물은 카드, 거래 완료 매물은 brief 로 온다")
    void listing_status_decides_availability() throws Exception {
        Long approved = officeOf("fav-o-st", "99999-2026-00024", PartnerDivisionStatus.APPROVED);
        favorite(save("fav-reserved", approved, ListingStatus.RESERVED), LocalDateTime.of(2026, 10, 9, 9, 0));
        favorite(save("fav-completed", approved, ListingStatus.COMPLETED), LocalDateTime.of(2026, 10, 8, 9, 0));

        call().andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].listingUuid").value("fav-reserved"))
                .andExpect(jsonPath("$.data.content[0].available").value(true))
                .andExpect(jsonPath("$.data.content[1].listingUuid").value("fav-completed"))
                .andExpect(jsonPath("$.data.content[1].available").value(false));
    }

    /*
     * ★★ 공개 여부는 담을 때가 아니라 **볼 때** 판정한다. 부문이 정지된 사이에는 내려간 매물로 보이고,
     *    복구하면 같은 찜이 그대로 카드로 돌아와야 한다 — 매물 상태는 정지 · 복구 어느 쪽에서도 바뀌지 않는다.
     */
    @Test
    @DisplayName("부문이 정지되면 available=false, 복구하면 다시 true")
    void division_suspension_flips_available_and_reinstatement_restores() throws Exception {
        Long office = officeOf("fav-o-flip", "99999-2026-00023", PartnerDivisionStatus.APPROVED);
        favorite(save("fav-flip", office, ListingStatus.ACTIVE), LocalDateTime.of(2026, 10, 8, 9, 0));
        PartnerDivision division = divisionOf(office);

        call().andExpect(jsonPath("$.data.content[0].available").value(true));

        division.suspend(1L, "허위 매물", LocalDateTime.of(2026, 10, 10, 9, 0));
        divisionRepository.saveAndFlush(division);
        call().andExpect(jsonPath("$.data.content[0].available").value(false))
                .andExpect(jsonPath("$.data.totalElements").value(1));

        division.reinstate(1L, LocalDateTime.of(2026, 10, 11, 9, 0));
        divisionRepository.saveAndFlush(division);
        call().andExpect(jsonPath("$.data.content[0].available").value(true));
    }

    @Test
    @DisplayName("찜이 없으면 빈 페이지다")
    void no_favorites_is_an_empty_page() throws Exception {
        call().andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0))
                .andExpect(jsonPath("$.data.content").isEmpty());
    }

    // ───────────────────────── 픽스처

    private ResultActions call() throws Exception {
        return mockMvc.perform(get(PATH).param("page", "0").param("size", "20")
                .header("Authorization", bearer));
    }

    private void favorite(Long listingId, LocalDateTime at) {
        favoriteRepository.saveAndFlush(ListingFavorite.builder()
                .userId(me.getId()).listingId(listingId).createdAt(at).build());
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

    private PartnerDivision divisionOf(Long officeId) {
        Long userId = profileRepository.findByOfficeId(officeId).get(0).getUserId();
        return divisionRepository.findByUserIdAndDivision(userId, PartnerDivisionType.REAL_ESTATE_BROKER)
                .orElseThrow();
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
                .residentRegistration(AvailabilityAnswer.ASK)
                .depositInsurance(AvailabilityAnswer.ASK)
                .realLat(new BigDecimal(REAL_LAT)).realLng(new BigDecimal("126.9331220"))
                .displayLat(new BigDecimal("37.4862552")).displayLng(new BigDecimal("126.9332423"))
                .addressJibun("서울특별시 관악구 신림동")
                .postedAt(LocalDateTime.of(2026, 10, 1, 0, 0))
                .status(status).build()).getId();
    }
}
