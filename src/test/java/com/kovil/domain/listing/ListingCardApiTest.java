package com.kovil.domain.listing;

import com.kovil.domain.listing.entity.*;
import com.kovil.domain.listing.enums.*;
import com.kovil.domain.listing.repository.*;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * 공개 목록 카드의 보강 칸을 **진짜 쿼리 · 진짜 직렬화 · 진짜 언어 해석**으로 태운다.
 * 실비 판정 JPQL 과 지역 이름의 Accept-Language 규칙은 목 테스트로는 보이지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ListingCardApiTest {

    private static final String PATH = "/api/v1/listings";

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired BrokerageOfficeRepository officeRepository;
    @Autowired BrokerProfileRepository profileRepository;
    @Autowired PartnerDivisionRepository divisionRepository;
    @Autowired ListingRepository listingRepository;
    @Autowired ListingMaintenanceRepository maintenanceRepository;
    @Autowired ListingMaintenanceItemRepository itemRepository;
    @Autowired RegionRepository regionRepository;

    /*
     * card-a: 예약 중 · 서대문구 · 정액(세부내역) 7만 + 실비 비목 · 보증보험 가능 · 전입신고 가능(추천순 맨 앞)
     * card-b: 게시 중 · 지역 없는 옛 매물 · 관리비 확인불가
     */
    @BeforeEach
    void setUp() {
        Long office = approvedOffice();
        Long region = regionRepository.saveAndFlush(Region.builder()
                .code("11410").nameKo("서대문구").nameEn("Seodaemun-gu").build()).getId();

        Long a = listingRepository.saveAndFlush(base("card-a", office)
                .status(ListingStatus.RESERVED).regionId(region).postedAt(LocalDateTime.of(2026, 10, 6, 10, 12))
                .residentRegistration(AvailabilityAnswer.POSSIBLE)
                .depositInsurance(AvailabilityAnswer.POSSIBLE).build()).getId();
        maintenanceRepository.saveAndFlush(ListingMaintenance.builder().listingId(a)
                .chargeType(MaintenanceChargeType.FIXED_FEE).fixedDetailType(FixedDetailType.WITH_DETAILS)
                .totalCost(70_000L).build());
        itemRepository.saveAndFlush(ListingMaintenanceItem.builder().listingId(a)
                .itemCode(MaintenanceItemCode.ELECTRIC).actualCost(true).build());
        itemRepository.saveAndFlush(ListingMaintenanceItem.builder().listingId(a)
                .itemCode(MaintenanceItemCode.COMMON).actualCost(false).cost(70_000L).build());

        Long b = listingRepository.saveAndFlush(base("card-b", office)
                .status(ListingStatus.ACTIVE).postedAt(LocalDateTime.of(2026, 10, 1, 9, 0))
                .residentRegistration(AvailabilityAnswer.ASK)
                .depositInsurance(AvailabilityAnswer.ASK).build()).getId();
        maintenanceRepository.saveAndFlush(ListingMaintenance.builder().listingId(b)
                .chargeType(MaintenanceChargeType.UNABLE_CHECK).build());
    }

    @Test
    @DisplayName("카드에 예약 중 · 최초 게시 시각 · 구 코드와 이름 · 관리비 분기 · + α · 보증보험이 실린다")
    void card_carries_the_new_fields() throws Exception {
        mockMvc.perform(get(PATH).header("Accept-Language", "ko"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].listingUuid").value("card-a"))
                .andExpect(jsonPath("$.data.content[0].status").value("RESERVED"))
                .andExpect(jsonPath("$.data.content[0].postedAt").value("2026-10-06T10:12:00"))
                .andExpect(jsonPath("$.data.content[0].regionCode").value("11410"))
                .andExpect(jsonPath("$.data.content[0].regionName").value("서대문구"))
                .andExpect(jsonPath("$.data.content[0].maintenanceCost").value(70_000))
                .andExpect(jsonPath("$.data.content[0].maintenanceChargeType").value("FIXED_FEE"))
                .andExpect(jsonPath("$.data.content[0].maintenanceActualCost").value(true))
                .andExpect(jsonPath("$.data.content[0].depositInsurance").value("POSSIBLE"))
                .andExpect(jsonPath("$.data.content[1].listingUuid").value("card-b"))
                .andExpect(jsonPath("$.data.content[1].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.content[1].maintenanceChargeType").value("UNABLE_CHECK"))
                .andExpect(jsonPath("$.data.content[1].maintenanceActualCost").value(false));
    }

    // 지역 API 와 같은 규칙 — 한국어가 아니면 영어 하나다. 제3언어(베트남어)도 영어로 받는다.
    @Test
    @DisplayName("구 이름은 한국어가 아닌 요청이면 영어다 — 영어 · 베트남어 모두")
    void region_name_follows_accept_language() throws Exception {
        for (String language : new String[]{"en", "vi"}) {
            mockMvc.perform(get(PATH).header("Accept-Language", language))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content[0].regionName").value("Seodaemun-gu"));
        }
    }

    // ───────────────────────── 픽스처

    private Long approvedOffice() {
        Long representativeId = userRepository.save(User.builder()
                .uuid("u-card-office").name("홍길동")
                .nickname("u-card-office").nicknameNormalized("u-card-office").isNicknameCustom(false)
                .preferredLanguage("ko").status(UserStatus.ACTIVE).role(UserRole.PARTNER)
                .build()).getId();
        Long officeId = officeRepository.save(BrokerageOffice.builder().uuid("card-office").regNo("99999-2026-00031")
                .name("테스트공인중개사사무소").ceoName("홍길동")
                .address("서울특별시 서대문구").tel("02-000-0000").build()).getId();
        profileRepository.save(BrokerProfile.builder().userId(representativeId).officeId(officeId).build());
        divisionRepository.save(PartnerDivision.builder().uuid("pd-" + representativeId).userId(representativeId)
                .division(PartnerDivisionType.REAL_ESTATE_BROKER).status(PartnerDivisionStatus.APPROVED)
                .submittedAt(LocalDateTime.of(2026, 10, 1, 0, 0)).build());
        return officeId;
    }

    private Listing.ListingBuilder<?, ?> base(String uuid, Long officeId) {
        return Listing.builder()
                // listing_no 는 VARCHAR(20) 이라 uuid 를 그대로 붙이면 넘친다.
                .uuid(uuid).listingNo("LS-261012-" + Integer.toHexString(uuid.hashCode()).toUpperCase())
                .officeId(officeId).createdByUserId(10L)
                .category(ListingCategory.ONE_TWO_ROOM).roomType(RoomType.ONE_ROOM)
                .title("테스트").sellingType(SellingType.MONTHLY_RENT)
                .deposit(3_000_000L).monthlyRent(300_000L)
                .exclusiveArea(new BigDecimal("26.44")).floorType(FloorType.GROUND)
                .realLat(new BigDecimal("37.4862378")).realLng(new BigDecimal("126.9331220"))
                .displayLat(new BigDecimal("37.4862552")).displayLng(new BigDecimal("126.9332423"))
                .addressJibun("서울특별시 서대문구 창천동");
    }
}
