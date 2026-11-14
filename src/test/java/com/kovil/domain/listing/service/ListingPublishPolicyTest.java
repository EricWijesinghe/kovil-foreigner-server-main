package com.kovil.domain.listing.service;

import com.kovil.domain.listing.entity.Listing;
import com.kovil.domain.listing.entity.ListingMaintenance;
import com.kovil.domain.listing.enums.AvailabilityAnswer;
import com.kovil.domain.listing.enums.FloorType;
import com.kovil.domain.listing.enums.ListingCategory;
import com.kovil.domain.listing.enums.ListingStatus;
import com.kovil.domain.listing.enums.MaintenanceChargeType;
import com.kovil.domain.listing.enums.RoomType;
import com.kovil.domain.listing.enums.SellingType;
import com.kovil.domain.listing.exception.ListingException;
import com.kovil.domain.listing.repository.ListingImageRepository;
import com.kovil.domain.listing.repository.ListingMaintenanceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/*
 * 게시 관문. **등록은 느슨하고 게시가 막는다**(PO 확정 2026-10-05).
 *
 * ★ 네 가지를 각각 따로 고정하는 이유: 하나로 뭉뚱그리면 나중에 조건을 지울 때
 *   어느 보호가 사라지는지 아무도 모른다. 각 테스트가 "이걸 풀면 무엇이 나가는가"를 적는다.
 */
class ListingPublishPolicyTest {

    private ListingImageRepository imageRepository;
    private ListingMaintenanceRepository maintenanceRepository;
    private ListingRegionResolver regionResolver;
    private ListingPublishPolicy policy;

    @BeforeEach
    void setUp() {
        imageRepository = mock(ListingImageRepository.class);
        maintenanceRepository = mock(ListingMaintenanceRepository.class);
        regionResolver = mock(ListingRegionResolver.class);
        // 기본은 지역이 갖춰진 상태 — 지역 관문을 시험하는 테스트만 false 로 바꾼다.
        lenient().when(regionResolver.isPublishable(any())).thenReturn(true);
        policy = new ListingPublishPolicy(imageRepository, maintenanceRepository, regionResolver);
    }

    /*
     * ★ 게시하려면 서비스 지역의 시군구가 있어야 한다(PO 확정 2026-10-06). 지도 묶음이 시군구
     *   단위라 지역 없는 매물은 넓은 지도에서 사라지고 지역 필터로도 못 찾는다.
     *   "저장 시점"이 아니라 **지금** 활성인지 본다 — 그 지역이 닫혔으면 게시도 닫혀야 한다.
     */
    @Test
    @DisplayName("서비스 지역의 시군구가 없으면 게시할 수 없다")
    void requires_an_active_district() {
        allPresent();   // 다른 셋은 갖춘다 — 지역만 빠진 상태를 본다
        Listing listing = complete();
        given(regionResolver.isPublishable(listing.getRegionId())).willReturn(false);

        assertThatThrownBy(() -> policy.requirePublishable(listing))
                .isInstanceOf(ListingException.class)
                .satisfies(e -> assertThat(((ListingException) e).getErrorCode().getCode())
                        .isEqualTo("PUBLISH_REGION_REQUIRED"));
    }

    @Test
    @DisplayName("다섯이 다 갖춰지면 게시된다")
    void passes_when_complete() {
        allPresent();
        assertThatCode(() -> policy.requirePublishable(complete())).doesNotThrowAnyException();
    }

    // 사진 없는 매물은 목록에서 빈칸으로 보여 목록 전체의 인상을 깎는다.
    @Test
    @DisplayName("사진이 없으면 막는다")
    void rejects_without_photo() {
        allPresent();
        given(imageRepository.countByListingId(any())).willReturn(0L);
        assertCode(complete(), "PUBLISH_PHOTO_REQUIRED");
    }

    // 관리비를 비우면 「중개대상물 표시·광고 명시사항 세부기준」 미충족 광고가 나간다.
    @Test
    @DisplayName("관리비 정보가 없으면 막는다 — 법정 표시 의무다")
    void rejects_without_maintenance() {
        allPresent();
        given(maintenanceRepository.findById(any())).willReturn(Optional.empty());
        assertCode(complete(), "PUBLISH_MAINTENANCE_REQUIRED");
    }

    // 설명이 없으면 번역 기능이 무의미해지고, 외국인에게는 정보가 거의 없는 매물이 된다.
    @Test
    @DisplayName("상세설명이 없거나 공백뿐이면 막는다")
    void rejects_without_description() {
        allPresent();
        assertCode(listing(null, AvailabilityAnswer.POSSIBLE), "PUBLISH_DESCRIPTION_REQUIRED");
        assertCode(listing("   ", AvailabilityAnswer.POSSIBLE), "PUBLISH_DESCRIPTION_REQUIRED");
    }

    /*
     * ★ 유학생에게는 생존 조건이다 — 전입신고가 안 되면 외국인등록증 주소지를 올릴 수 없어
     *   체류 행정이 막힌다. ASK 로 게시하게 두면 중개사가 묻지 않는다.
     */
    @Test
    @DisplayName("전입신고 여부가 ASK 면 막는다 — 중개사가 임대인에게 묻게 만드는 장치다")
    void rejects_when_resident_registration_unknown() {
        allPresent();
        assertCode(listing("전입신고 가능합니다", AvailabilityAnswer.ASK),
                "PUBLISH_RESIDENT_REGISTRATION_REQUIRED");
    }

    @Test
    @DisplayName("전입신고 '불가'는 게시된다 — 막는 것이 아니라 밝히게 하는 것이다")
    void allows_explicit_impossible() {
        allPresent();
        assertThatCode(() -> policy.requirePublishable(
                listing("상세설명", AvailabilityAnswer.IMPOSSIBLE))).doesNotThrowAnyException();
    }

    private void allPresent() {
        given(imageRepository.countByListingId(any())).willReturn(3L);
        given(maintenanceRepository.findById(any())).willReturn(Optional.of(
                ListingMaintenance.builder().listingId(1L)
                        .chargeType(MaintenanceChargeType.FIXED_FEE).totalCost(50_000L).build()));
    }

    private void assertCode(Listing listing, String expected) {
        assertThatThrownBy(() -> policy.requirePublishable(listing))
                .isInstanceOf(ListingException.class)
                .satisfies(e -> assertThat(((ListingException) e).getErrorCode().getCode())
                        .isEqualTo(expected));
    }

    private Listing complete() {
        return listing("전입신고 가능한 깔끔한 원룸입니다.", AvailabilityAnswer.POSSIBLE);
    }

    private Listing listing(String description, AvailabilityAnswer residentRegistration) {
        return Listing.builder()
                .id(1L).uuid("l-1").listingNo("LS-261005-ABC123")
                .officeId(1L).createdByUserId(10L)
                .category(ListingCategory.ONE_TWO_ROOM).roomType(RoomType.ONE_ROOM)
                .title("테스트 매물").description(description)
                .sellingType(SellingType.MONTHLY_RENT).deposit(3_000_000L).monthlyRent(300_000L)
                .exclusiveArea(new BigDecimal("26.44")).floorType(FloorType.GROUND)
                .residentRegistration(residentRegistration)
                .depositInsurance(AvailabilityAnswer.ASK)
                .realLat(new BigDecimal("37.4862378")).realLng(new BigDecimal("126.9331220"))
                .displayLat(new BigDecimal("37.4862552")).displayLng(new BigDecimal("126.9332423"))
                .addressJibun("서울특별시 관악구 신림동").regionId(38L).status(ListingStatus.DRAFT)
                .build();
    }
}
