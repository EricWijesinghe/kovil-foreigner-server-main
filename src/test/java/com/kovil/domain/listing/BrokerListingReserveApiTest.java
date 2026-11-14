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
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * 예약 중 전환과 중개사 목록 칸을 **진짜 보안 필터 · 진짜 DB** 로 태운다.
 * ★ 예약 중은 공개 상태다 — 전환 뒤에도 유학생 상세에 보여야 하고, 기한(expiresAt) · 처음 게시 시각이 그대로여야 한다.
 *   기한을 다시 찍으면 예약 토글만으로 게시 기간을 무한히 늘릴 수 있다.
 * ★ 지역 없는 초안만 있는 페이지는 지역 코드 표가 빈 Map.of() 다 — get(null) 이 NPE 를 던지는 함정을 따로 박는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BrokerListingReserveApiTest {

    private static final String PATH = "/api/v1/broker/listings";
    private static final LocalDateTime POSTED = LocalDateTime.of(2026, 10, 1, 9, 0);
    private static final LocalDateTime EXPIRES = LocalDateTime.of(2099, 11, 1, 9, 0);

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired UserRepository userRepository;
    @Autowired BrokerageOfficeRepository officeRepository;
    @Autowired BrokerProfileRepository profileRepository;
    @Autowired PartnerDivisionRepository divisionRepository;
    @Autowired ListingRepository listingRepository;
    @Autowired RegionRepository regionRepository;

    private String bearer;
    private Long officeId;

    @BeforeEach
    void setUp() {
        User broker = broker("rs-broker");
        officeId = office("rs-office", "99999-2026-00101", broker);
        bearer = "Bearer " + jwtTokenProvider.issueAccessToken(broker.getUuid(), UserRole.PARTNER,
                Set.of(PartnerDivisionType.REAL_ESTATE_BROKER));
    }

    @Test
    @DisplayName("예약 중으로 → RESERVED, 기한 · 게시 시각은 그대로, 유학생 상세에 그대로 보인다")
    void reserve_keeps_listing_public_and_dates() throws Exception {
        Long id = save("rs-open", officeId, ListingStatus.ACTIVE, null, false, false);

        mockMvc.perform(post(PATH + "/{uuid}/reserve", "rs-open").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RESERVED"));

        Listing saved = listingRepository.findById(id).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(ListingStatus.RESERVED);
        assertThat(saved.getExpiresAt()).isEqualTo(EXPIRES);
        assertThat(saved.getPostedAt()).isEqualTo(POSTED);

        mockMvc.perform(get("/api/v1/listings/{uuid}", "rs-open"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RESERVED"));
    }

    @Test
    @DisplayName("예약 해제 → ACTIVE, 기한 · 게시 시각은 그대로")
    void unreserve_goes_back_to_active() throws Exception {
        Long id = save("rs-held", officeId, ListingStatus.RESERVED, null, false, false);

        mockMvc.perform(post(PATH + "/{uuid}/unreserve", "rs-held").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        Listing saved = listingRepository.findById(id).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(ListingStatus.ACTIVE);
        assertThat(saved.getExpiresAt()).isEqualTo(EXPIRES);
        assertThat(saved.getPostedAt()).isEqualTo(POSTED);
    }

    @Test
    @DisplayName("게시 중이 아니면 예약 409 LISTING_NOT_ACTIVE, 예약 중이 아니면 해제 409 LISTING_NOT_RESERVED — 상태는 그대로")
    void transition_grid() throws Exception {
        for (ListingStatus from : EnumSet.complementOf(EnumSet.of(ListingStatus.ACTIVE))) {
            String uuid = "rs-a-" + from.name().toLowerCase();
            Long id = save(uuid, officeId, from, null, false, false);
            mockMvc.perform(post(PATH + "/{uuid}/reserve", uuid).header("Authorization", bearer))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorCode").value("LISTING_NOT_ACTIVE"));
            assertThat(listingRepository.findById(id).orElseThrow().getStatus()).as("reserve %s", from).isEqualTo(from);
        }
        for (ListingStatus from : EnumSet.complementOf(EnumSet.of(ListingStatus.RESERVED))) {
            String uuid = "rs-u-" + from.name().toLowerCase();
            Long id = save(uuid, officeId, from, null, false, false);
            mockMvc.perform(post(PATH + "/{uuid}/unreserve", uuid).header("Authorization", bearer))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorCode").value("LISTING_NOT_RESERVED"));
            assertThat(listingRepository.findById(id).orElseThrow().getStatus()).as("unreserve %s", from).isEqualTo(from);
        }
    }

    @Test
    @DisplayName("다른 사무소 매물은 403 LISTING_ACCESS_DENIED · 없는 매물 404 · 유학생은 403")
    void scope_and_guard() throws Exception {
        Long otherOffice = office("rs-other-office", "99999-2026-00102", broker("rs-other"));
        Long id = save("rs-theirs", otherOffice, ListingStatus.ACTIVE, null, false, false);

        mockMvc.perform(post(PATH + "/{uuid}/reserve", "rs-theirs").header("Authorization", bearer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("LISTING_ACCESS_DENIED"));
        assertThat(listingRepository.findById(id).orElseThrow().getStatus()).isEqualTo(ListingStatus.ACTIVE);

        // 해제도 같은 문을 지나야 한다 — 남의 예약 매물을 게시 중으로 되돌리면 그 사무소의 예약 표시가 사라진다.
        Long held = save("rs-theirs-held", otherOffice, ListingStatus.RESERVED, null, false, false);
        mockMvc.perform(post(PATH + "/{uuid}/unreserve", "rs-theirs-held").header("Authorization", bearer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("LISTING_ACCESS_DENIED"));
        assertThat(listingRepository.findById(held).orElseThrow().getStatus()).isEqualTo(ListingStatus.RESERVED);

        mockMvc.perform(post(PATH + "/{uuid}/unreserve", "no-such").header("Authorization", bearer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("LISTING_NOT_FOUND"));

        User student = userRepository.save(User.builder().uuid("rs-student")
                .status(UserStatus.ACTIVE).role(UserRole.USER).preferredLanguage("ko").build());
        mockMvc.perform(post(PATH + "/{uuid}/reserve", "rs-theirs")
                        .header("Authorization", "Bearer " + jwtTokenProvider.issueAccessToken(student.getUuid(), UserRole.USER)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("목록 항목에 분리형 · 복층 · 지역 코드가 실린다 — 닫힌 지역도 코드는 나온다")
    void list_carries_division_duplex_and_region_code() throws Exception {
        Long open = regionRepository.saveAndFlush(Region.builder()
                .code("11620").nameKo("관악구").nameEn("Gwanak-gu").build()).getId();
        Long closed = regionRepository.saveAndFlush(Region.builder()
                .code("11650").nameKo("서초구").nameEn("Seocho-gu").isActive(false).build()).getId();
        save("rs-split", officeId, ListingStatus.ACTIVE, open, true, false);
        save("rs-loft", officeId, ListingStatus.HIDDEN, closed, false, true);
        save("rs-draft", officeId, ListingStatus.DRAFT, null, false, false);

        mockMvc.perform(get(PATH).param("sort", "CREATED_DESC").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(3))
                .andExpect(jsonPath("$.data.content[?(@.listingUuid=='rs-split')].division").value(true))
                .andExpect(jsonPath("$.data.content[?(@.listingUuid=='rs-split')].duplex").value(false))
                .andExpect(jsonPath("$.data.content[?(@.listingUuid=='rs-split')].regionCode").value("11620"))
                .andExpect(jsonPath("$.data.content[?(@.listingUuid=='rs-split')].deposit").value(3_000_000))
                .andExpect(jsonPath("$.data.content[?(@.listingUuid=='rs-split')].monthlyRent").value(300_000))
                .andExpect(jsonPath("$.data.content[?(@.listingUuid=='rs-split')].postedAt").value("2026-10-01T09:00:00"))
                .andExpect(jsonPath("$.data.content[?(@.listingUuid=='rs-split')].expiresAt").value("2099-11-01T09:00:00"))
                .andExpect(jsonPath("$.data.content[?(@.listingUuid=='rs-loft')].division").value(false))
                .andExpect(jsonPath("$.data.content[?(@.listingUuid=='rs-loft')].duplex").value(true))
                .andExpect(jsonPath("$.data.content[?(@.listingUuid=='rs-loft')].regionCode").value("11650"));
    }

    @Test
    @DisplayName("지역 없는 초안만 있는 페이지도 200 이고 지역 코드는 null 이다")
    void page_of_regionless_drafts_only() throws Exception {
        save("rs-draft-only", officeId, ListingStatus.DRAFT, null, false, false);

        mockMvc.perform(get(PATH).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].listingUuid").value("rs-draft-only"))
                .andExpect(jsonPath("$.data.content[0].regionCode").doesNotExist());
    }

    private User broker(String uuid) {
        return userRepository.save(User.builder()
                .uuid(uuid).name("홍길동")
                .nickname(uuid).nicknameNormalized(uuid).isNicknameCustom(false)
                .preferredLanguage("ko").status(UserStatus.ACTIVE).role(UserRole.PARTNER).build());
    }

    private Long office(String uuid, String regNo, User representative) {
        Long id = officeRepository.save(BrokerageOffice.builder().uuid(uuid).regNo(regNo)
                .name("테스트공인중개사사무소").ceoName("홍길동")
                .address("서울특별시 관악구").tel("02-000-0000").build()).getId();
        profileRepository.save(BrokerProfile.builder().userId(representative.getId()).officeId(id).build());
        divisionRepository.save(PartnerDivision.builder().uuid("pd-" + uuid).userId(representative.getId())
                .division(PartnerDivisionType.REAL_ESTATE_BROKER).status(PartnerDivisionStatus.APPROVED)
                .submittedAt(LocalDateTime.of(2026, 10, 1, 0, 0)).build());
        return id;
    }

    private Long save(String uuid, Long office, ListingStatus status, Long regionId,
                      boolean division, boolean duplex) {
        return listingRepository.saveAndFlush(Listing.builder()
                .uuid(uuid).listingNo("LS-261013-" + Integer.toHexString(uuid.hashCode()).toUpperCase())
                .officeId(office).createdByUserId(10L)
                .category(ListingCategory.ONE_TWO_ROOM).roomType(RoomType.ONE_ROOM)
                .division(division).duplex(duplex)
                .title("테스트").sellingType(SellingType.MONTHLY_RENT)
                .deposit(3_000_000L).monthlyRent(300_000L)
                .exclusiveArea(new BigDecimal("26.44")).floorType(FloorType.GROUND)
                .residentRegistration(AvailabilityAnswer.ASK).depositInsurance(AvailabilityAnswer.ASK)
                .realLat(new BigDecimal("37.4862378")).realLng(new BigDecimal("126.9331220"))
                .displayLat(new BigDecimal("37.4862552")).displayLng(new BigDecimal("126.9332423"))
                .addressJibun("서울특별시 관악구 신림동")
                .regionId(regionId)
                .postedAt(status == ListingStatus.DRAFT ? null : POSTED)
                .expiresAt(status == ListingStatus.DRAFT ? null : EXPIRES)
                .status(status).build()).getId();
    }
}
