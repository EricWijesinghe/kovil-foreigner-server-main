package com.kovil.domain.listing.repository;

import com.kovil.common.config.JpaAuditingConfig;
import com.kovil.domain.listing.entity.BrokerProfile;
import com.kovil.domain.listing.entity.BrokerageOffice;
import com.kovil.domain.listing.entity.Listing;
import com.kovil.domain.listing.entity.ListingDescriptionTranslation;
import com.kovil.domain.listing.entity.ListingMaintenance;
import com.kovil.domain.listing.entity.ListingMaintenanceInclude;
import com.kovil.domain.listing.entity.ListingOption;
import com.kovil.domain.listing.entity.ListingSafety;
import com.kovil.domain.listing.enums.AvailabilityAnswer;
import com.kovil.domain.listing.enums.FixedDetailType;
import com.kovil.domain.listing.enums.FloorBand;
import com.kovil.domain.listing.enums.FloorType;
import com.kovil.domain.listing.enums.ListingCategory;
import com.kovil.domain.listing.enums.ListingStatus;
import com.kovil.domain.listing.enums.MaintenanceChargeType;
import com.kovil.domain.listing.enums.MaintenanceItemCode;
import com.kovil.domain.listing.enums.RoomType;
import com.kovil.domain.listing.enums.SellingType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import jakarta.persistence.Column;
import jakarta.persistence.EntityManager;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/*
 * 매물 도메인의 **매핑과 파생 쿼리를 진짜 DB에** 붙여 고정한다.
 *
 * ★ 컴파일이 통과해도 매핑은 검증되지 않는다 — 컬럼명이 하나 어긋나거나 복합키 설정이
 *   틀리면 **부팅 시점에** 죽는다. 그것을 잡아 주는 곳이 여기뿐이다.
 *
 * ★ 그리고 "어느 축으로 집는가"는 정책이라 목으로는 애초에 검증 대상이 아니다.
 */
@DataJpaTest
@Import(JpaAuditingConfig.class)
class ListingMappingTest {

    @Autowired BrokerageOfficeRepository officeRepository;
    @Autowired BrokerProfileRepository profileRepository;
    @Autowired ListingRepository listingRepository;
    @Autowired ListingMaintenanceRepository maintenanceRepository;
    @Autowired ListingMaintenanceIncludeRepository includeRepository;
    @Autowired ListingOptionRepository optionRepository;
    @Autowired ListingSafetyRepository safetyRepository;
    @Autowired ListingDescriptionTranslationRepository translationRepository;
    @Autowired EntityManager entityManager;

    private Long officeId;
    private Long otherOfficeId;

    @BeforeEach
    void setUp() {
        officeId = officeRepository.save(office("o-1", "99999-2026-00002")).getId();
        otherOfficeId = officeRepository.save(office("o-2", "99999-2026-00001")).getId();
    }

    @Test
    @DisplayName("사무소·중개사·매물이 매핑되고 저장된다")
    void persists_core_graph() {
        profileRepository.save(BrokerProfile.builder().userId(10L).officeId(officeId).build());
        Listing saved = listingRepository.save(listing("l-1", officeId, ListingStatus.ACTIVE));

        assertThat(saved.getId()).isNotNull();
        assertThat(profileRepository.findByUserId(10L)).isPresent();
        assertThat(listingRepository.findByUuid("l-1")).isPresent();
    }

    /*
     * ★ 중개사 화면의 범위는 office 다. 이 축이 user 로 바뀌면 같은 사무소의 다른 계정이
     *   올린 매물이 사라져, 대표자가 바뀐 사무소의 매물이 통째로 안 보이게 된다.
     */
    @Test
    @DisplayName("매물은 사무소 축으로 집힌다 — 남의 사무소 것은 빠진다")
    void scopes_by_office_not_user() {
        listingRepository.save(listing("l-1", officeId, ListingStatus.ACTIVE));
        listingRepository.save(listing("l-2", officeId, ListingStatus.DRAFT));
        listingRepository.save(listing("l-3", otherOfficeId, ListingStatus.ACTIVE));

        var mine = listingRepository.findByOfficeIdAndStatusIn(
                officeId, List.of(ListingStatus.ACTIVE, ListingStatus.DRAFT), PageRequest.of(0, 10));

        assertThat(mine.getContent()).extracting(Listing::getUuid)
                .containsExactlyInAnyOrder("l-1", "l-2");
    }

    /*
     * ★★ 번역 캐시의 유일한 질문. 셋 중 하나라도 빠지면 틀린 번역이 나간다.
     *     특히 버전을 빼면 중개사가 설명을 고쳐도 옛 번역이 계속 나가서
     *     유학생이 **존재하지 않는 조건**을 읽는다.
     */
    @Test
    @DisplayName("번역 캐시는 (매물·언어·원문버전) 셋이 다 맞아야 히트한다")
    void translation_cache_needs_all_three_keys() {
        Long id = listingRepository.save(listing("l-1", officeId, ListingStatus.ACTIVE)).getId();
        translationRepository.save(ListingDescriptionTranslation.builder()
                .listingId(id).languageCode("en").sourceVersion(1)
                .content("Resident registration is possible").engine("x")
                .createdAt(LocalDateTime.now()).build());

        assertThat(translationRepository
                .findByListingIdAndLanguageCodeAndSourceVersion(id, "en", 1)).isPresent();
        // 버전이 오르면 미스 — 설명이 바뀌었다는 뜻이다
        assertThat(translationRepository
                .findByListingIdAndLanguageCodeAndSourceVersion(id, "en", 2)).isEmpty();
        // 다른 언어도 미스
        assertThat(translationRepository
                .findByListingIdAndLanguageCodeAndSourceVersion(id, "vi", 1)).isEmpty();
    }

    // 복합키(listing_id + code) 두 테이블이 실제로 붙는지. @IdClass 설정이 틀리면 여기서 죽는다.
    @Test
    @DisplayName("옵션·안전시설·관리비 포함항목의 복합키가 매핑된다")
    void persists_composite_key_children() {
        Long id = listingRepository.save(listing("l-1", officeId, ListingStatus.ACTIVE)).getId();

        optionRepository.save(ListingOption.builder().listingId(id).code("AIR_CONDITIONER").build());
        optionRepository.save(ListingOption.builder().listingId(id).code("WASHING_MACHINE").build());
        safetyRepository.save(ListingSafety.builder().listingId(id).code("CCTV").build());
        includeRepository.save(ListingMaintenanceInclude.builder()
                .listingId(id).itemCode(MaintenanceItemCode.COMMON).build());

        assertThat(optionRepository.findByListingId(id)).hasSize(2);
        assertThat(safetyRepository.findByListingId(id)).hasSize(1);
        assertThat(includeRepository.findByListingId(id)).hasSize(1);
    }

    // 관리비는 listing_id 가 PK 인 1:1이다. 공유 PK 매핑이 틀리면 여기서 죽는다.
    @Test
    @DisplayName("관리비는 매물당 한 행이고 분기 판정이 엔티티에 산다")
    void persists_maintenance_as_one_row() {
        Long id = listingRepository.save(listing("l-1", officeId, ListingStatus.ACTIVE)).getId();
        maintenanceRepository.save(ListingMaintenance.builder()
                .listingId(id)
                .chargeType(MaintenanceChargeType.FIXED_FEE)
                .fixedDetailType(FixedDetailType.BELOW_THRESHOLD)
                .totalCost(50_000L).reasonCode("E06").build());

        ListingMaintenance found = maintenanceRepository.findById(id).orElseThrow();
        // 10만원 미만 분기는 비목 테이블을 읽지 않는다 — 사유 코드 하나뿐이다
        assertThat(found.hasItemBreakdown()).isFalse();
    }

    /*
     * ★ 중개사가 층을 숨기기로 했으면 응답에 실제 층이 나가면 안 된다.
     *   DB 가 막아 주지 않으므로 판정을 엔티티 한 군데에 두고 여기서 고정한다.
     */
    @Test
    @DisplayName("floor_band 가 있으면 노출용 층은 null 이다")
    void hides_exact_floor_when_band_is_set() {
        Listing hidden = listingRepository.save(listingAtFloor("l-1", 5, FloorBand.MID));
        Listing shown = listingRepository.save(listingAtFloor("l-2", 5, null));

        assertThat(hidden.hidesFloor()).isTrue();
        assertThat(hidden.exposedFloor()).isNull();
        assertThat(shown.exposedFloor()).isEqualTo(5);
    }

    // 게시된 적 있는 매물은 지울 수 없다 — 허위매물 분쟁의 증거다.
    @Test
    @DisplayName("DRAFT 만 삭제 가능하고 나머지는 아니다")
    void only_draft_is_deletable() {
        assertThat(ListingStatus.DRAFT.isDeletable()).isTrue();
        assertThat(ListingStatus.ACTIVE.isDeletable()).isFalse();
        assertThat(ListingStatus.HIDDEN.isDeletable()).isFalse();
        assertThat(ListingStatus.BLOCKED.isDeletable()).isFalse();
        assertThat(ListingStatus.ARCHIVED.isDeletable()).isFalse();
    }

    // 관리자가 막은 것을 중개사가 풀면 차단이 의미가 없다.
    /*
     * ★ EXPIRED 와 ARCHIVED 를 가른 값이 여기서 산다. 하나로 합치면 중개사 목록에서
     *   "7일 안에 연장해야 할 건"과 "이미 끝난 건"이 섞인다.
     */
    @Test
    @DisplayName("BLOCKED·ARCHIVED 는 중개사가 되올릴 수 없다")
    void broker_cannot_recover_blocked_or_archived() {
        assertThat(ListingStatus.HIDDEN.isBrokerRecoverable()).isTrue();
        assertThat(ListingStatus.EXPIRED.isBrokerRecoverable()).isTrue();
        assertThat(ListingStatus.BLOCKED.isBrokerRecoverable()).isFalse();
        assertThat(ListingStatus.ARCHIVED.isBrokerRecoverable()).isFalse();
    }

    // 만료·종료된 매물이 공개 목록에 남아 있으면 그게 곧 허위매물이다.
    @Test
    @DisplayName("공개되는 상태는 ACTIVE·RESERVED 뿐이다")
    void only_active_and_reserved_are_public() {
        assertThat(ListingStatus.ACTIVE.isVisibleToPublic()).isTrue();
        assertThat(ListingStatus.RESERVED.isVisibleToPublic()).isTrue();
        for (ListingStatus s : new ListingStatus[]{ListingStatus.DRAFT, ListingStatus.COMPLETED,
                ListingStatus.EXPIRED, ListingStatus.HIDDEN, ListingStatus.BLOCKED, ListingStatus.ARCHIVED}) {
            assertThat(s.isVisibleToPublic()).as("%s", s).isFalse();
        }
    }

    /*
     * ★★ 같은 매물에 두 쓰기가 겹치면 **늦은 쪽을 거절**한다(PO 확정 2026-10-06). 버전이 없을 때는 나중 쓰기가
     *    앞 쓰기를 통째로 덮었다 — 지역 닫힘이 방금 커밋된 거래완료를 HIDDEN 으로 되돌리고, 가격 수정이 옛 가격으로
     *    돌아가 가격 이력과 어긋났다. 목이 아니라 실제 JPA 가 버전을 비교하는지를 본다.
     */
    @Test
    @DisplayName("읽어 둔 사이 다른 쓰기가 커밋된 매물을 저장하면 거절된다 — 덮어쓰지 않는다")
    void stale_write_is_rejected() {
        Long id = listingRepository.saveAndFlush(listing("l-v", officeId, ListingStatus.ACTIVE)).getId();
        entityManager.clear();

        Listing stale = listingRepository.findById(id).orElseThrow();
        entityManager.detach(stale);
        Listing fresh = listingRepository.findById(id).orElseThrow();
        fresh.complete();
        listingRepository.saveAndFlush(fresh);

        stale.hideForRegionClosure();
        assertThatThrownBy(() -> listingRepository.saveAndFlush(stale))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    /*
     * ★ 카운터는 벌크 UPDATE 로만 바뀐다. 엔티티 저장이 카운터 칼럼까지 쓰면, 카운터를 올리기 전에 읽어 둔 매물을
     *   저장하는 순간 그 사이 올라간 조회수 · 문의 수를 옛 값으로 되돌린다.
     */
    @Test
    @DisplayName("엔티티 저장은 그 사이 벌크로 올라간 카운터를 덮지 않는다")
    void entity_save_does_not_overwrite_counters() {
        Long id = listingRepository.saveAndFlush(listing("l-c", officeId, ListingStatus.ACTIVE)).getId();
        entityManager.clear();
        Listing loaded = listingRepository.findById(id).orElseThrow();
        entityManager.detach(loaded);

        listingRepository.increaseInquiryCount(id);
        loaded.unpublish();
        listingRepository.saveAndFlush(loaded);
        entityManager.clear();

        Listing after = listingRepository.findById(id).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(ListingStatus.HIDDEN);
        assertThat(after.getInquiryCount()).isEqualTo(1L);
    }

    // 위 실 DB 테스트는 문의 수 하나로 증명한다 — 나머지 둘(조회 · 찜)도 같은 보호를 받는지 칼럼 정의로 박는다.
    @Test
    @DisplayName("세 카운터 칼럼 모두 엔티티 저장이 쓰지 않는다(updatable=false)")
    void all_three_counters_are_not_updatable() throws NoSuchFieldException {
        for (String field : new String[]{"viewCount", "inquiryCount", "favoriteCount"}) {
            Column column = Listing.class.getDeclaredField(field)
                    .getAnnotation(Column.class);
            assertThat(column.updatable()).as(field).isFalse();
        }
    }

    private BrokerageOffice office(String uuid, String regNo) {
        return BrokerageOffice.builder()
                .uuid(uuid).regNo(regNo).name("테스트공인중개사사무소").ceoName("홍길동")
                .address("서울특별시 관악구 신림동").tel("02-000-0000").build();
    }

    // 층만 다른 매물. @SuperBuilder 에 toBuilder 를 켜지 않았으므로(기존 엔티티 관례) 따로 만든다.
    private Listing listingAtFloor(String uuid, Integer floor, FloorBand band) {
        return baseListing(uuid, officeId, ListingStatus.ACTIVE)
                .floor(floor).floorBand(band).build();
    }

    private Listing listing(String uuid, Long office, ListingStatus status) {
        return baseListing(uuid, office, status).build();
    }

    private Listing.ListingBuilder<?, ?> baseListing(String uuid, Long office, ListingStatus status) {
        return Listing.builder()
                .uuid(uuid).listingNo("LS-261005-" + uuid)
                .officeId(office).createdByUserId(10L)
                .category(ListingCategory.ONE_TWO_ROOM).roomType(RoomType.ONE_ROOM)
                .title("테스트 매물")
                .sellingType(SellingType.MONTHLY_RENT).deposit(3_000_000L).monthlyRent(300_000L)
                .exclusiveArea(new BigDecimal("26.44"))
                .floorType(FloorType.GROUND)
                .residentRegistration(AvailabilityAnswer.ASK)
                .depositInsurance(AvailabilityAnswer.ASK)
                .realLat(new BigDecimal("37.4862378")).realLng(new BigDecimal("126.9331220"))
                .displayLat(new BigDecimal("37.4862552")).displayLng(new BigDecimal("126.9332423"))
                .addressJibun("서울특별시 관악구 신림동")
                .status(status);
    }
}
