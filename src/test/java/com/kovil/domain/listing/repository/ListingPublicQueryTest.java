package com.kovil.domain.listing.repository;

import com.kovil.common.config.JpaAuditingConfig;
import com.kovil.domain.listing.entity.BrokerProfile;
import com.kovil.domain.listing.entity.BrokerageOffice;
import com.kovil.domain.listing.entity.Listing;
import com.kovil.domain.listing.enums.*;
import com.kovil.domain.partner.entity.BrokerageOfficeChangeRequest;
import com.kovil.domain.partner.entity.PartnerDivision;
import com.kovil.domain.partner.enums.OfficeChangeRequestStatus;
import com.kovil.domain.partner.enums.PartnerDivisionStatus;
import com.kovil.domain.partner.enums.PartnerDivisionType;
import com.kovil.domain.partner.repository.BrokerageOfficeChangeRequestRepository;
import com.kovil.domain.partner.repository.PartnerDivisionRepository;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Page;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * 공개 목록 쿼리. **컴파일로는 검증되지 않는다** — JPQL 은 부팅 시점에 파싱되고,
 * 조건 하나가 틀리면 "틀린 매물이 나가거나 맞는 매물이 안 나온다"로 조용히 나타난다.
 */
@DataJpaTest
@Import(JpaAuditingConfig.class)
class ListingPublicQueryTest {

    private static final List<ListingStatus> VISIBLE =
            List.of(ListingStatus.ACTIVE, ListingStatus.RESERVED);
    private static final List<SellingType> ALL_SELLING = List.of(SellingType.values());
    private static final List<RoomType> ALL_ROOM = List.of(RoomType.values());
    private static final List<FloorType> ALL_FLOOR = List.of(FloorType.values());

    @Autowired ListingRepository listingRepository;
    @Autowired BrokerageOfficeRepository officeRepository;
    @Autowired BrokerProfileRepository profileRepository;
    @Autowired PartnerDivisionRepository divisionRepository;
    @Autowired UserRepository userRepository;
    @Autowired BrokerageOfficeChangeRequestRepository changeRequestRepository;

    private Long activeOffice;
    private Long suspendedOffice;

    @BeforeEach
    void setUp() {
        activeOffice = officeOf("o-1", "99999-2026-00002", PartnerDivisionStatus.APPROVED);
        suspendedOffice = officeOf("o-2", "99999-2026-00001", PartnerDivisionStatus.SUSPENDED);
    }

    /*
     * ★★ 부문이 멈추면 그 사무소 매물이 **전부** 빠져야 한다. 조회 조건으로 거르지 않고 배치로 내리면
     *    배치가 늦거나 실패한 사이 광고가 계속 나간다 — 정지된 중개사의 광고가 떠 있는 것은
     *    자율규약 위반이다. 승인 · 정지의 축은 사무소가 아니라 **대표자의 공인중개사 부문**이다.
     */
    @Test
    @DisplayName("대표자의 공인중개사 부문이 정지된 사무소의 매물은 목록에서 빠진다")
    void hides_listings_of_suspended_division() {
        save("l-1", activeOffice, ListingStatus.ACTIVE);
        save("l-2", suspendedOffice, ListingStatus.ACTIVE);

        assertThat(query().getContent()).extracting(Listing::getUuid).containsExactly("l-1");
        assertThat(listingRepository.findPublicByUuidIn(List.of("l-1", "l-2"), VISIBLE))
                .extracting(Listing::getUuid).containsExactly("l-1");
    }

    // 승인된 부문만 광고를 낸다 — 심사 중 · 반려 · 정지 어느 것도 공개되지 않는다.
    @ParameterizedTest
    @EnumSource(value = PartnerDivisionStatus.class, names = "APPROVED", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("승인이 아닌 부문의 사무소는 목록 · 상세 · 지도 집계 · 게시 관문 어디에도 나오지 않는다")
    void only_approved_division_advertises(PartnerDivisionStatus status) {
        Long office = officeOf("o-x", "99999-2026-00009", status);
        save("l-x", office, ListingStatus.ACTIVE);

        assertThat(query().getContent()).extracting(Listing::getUuid).doesNotContain("l-x");
        assertThat(listingRepository.findPublicByUuid("l-x", VISIBLE)).isEmpty();
        assertThat(listingRepository.findPublicByUuidIn(List.of("l-x"), VISIBLE)).isEmpty();
        assertThat(countAll()).isZero();
        assertThat(officeRepository.isAdvertisable(office)).isFalse();
    }

    @Test
    @DisplayName("승인된 부문의 사무소는 목록 · 상세 · 지도 집계 · 게시 관문이 모두 연다")
    void approved_division_advertises_everywhere() {
        save("l-ok", activeOffice, ListingStatus.ACTIVE);

        assertThat(listingRepository.findPublicByUuid("l-ok", VISIBLE)).isPresent();
        assertThat(listingRepository.findPublicByUuidIn(List.of("l-ok"), VISIBLE))
                .extracting(Listing::getUuid).containsExactly("l-ok");
        assertThat(countAll()).isEqualTo(1);
        assertThat(officeRepository.isAdvertisable(activeOffice)).isTrue();
    }

    /*
     * ★ 대표자가 탈퇴하면 사무소 연결(broker_profiles)이 지워진다. 부문 행은 이력으로 남아 APPROVED 일 수
     *   있는데, 그 행만 보고 광고를 열면 **관리할 사람 없는 광고**가 뜬다. 조건은 연결을 거쳐야 한다.
     */
    @Test
    @DisplayName("대표자 연결이 없는 사무소는 부문이 승인으로 남아 있어도 광고하지 못한다")
    void office_without_representative_is_hidden() {
        Long orphan = officeRepository.save(office("o-orphan", "99999-2026-00008")).getId();
        divisionRepository.save(division(108L, PartnerDivisionStatus.APPROVED));
        save("l-orphan", orphan, ListingStatus.ACTIVE);

        assertThat(listingRepository.findPublicByUuid("l-orphan", VISIBLE)).isEmpty();
        assertThat(listingRepository.findPublicByUuidIn(List.of("l-orphan"), VISIBLE)).isEmpty();
        assertThat(officeRepository.isAdvertisable(orphan)).isFalse();
    }

    /*
     * ★★ 회원 정지는 관리자가 회원 화면에서 거는 처분이라 부문을 건드리지 않는다. 부문만 보면 정지된 사람의 광고가
     *    계속 나가고 문의로 번호가 나간다 — 대표자 계정 상태도 같은 조건으로 본다.
     *    정지를 풀면 **그대로** 돌아와야 한다 — 그래서 매물 상태는 건드리지 않고 조회 조건으로만 거른다.
     */
    @Test
    @DisplayName("대표자 계정이 정지되면 목록 · 상세 · 지도 집계 · 게시 관문이 닫히고, 정지를 풀면 그대로 돌아온다")
    void suspended_account_hides_and_unsuspend_restores() {
        save("l-acc", activeOffice, ListingStatus.ACTIVE);
        User representative = representativeOf(activeOffice);

        representative.suspend("허위 매물", LocalDateTime.of(2026, 10, 6, 9, 0));
        userRepository.saveAndFlush(representative);

        assertThat(query().getContent()).extracting(Listing::getUuid).doesNotContain("l-acc");
        assertThat(listingRepository.findPublicByUuid("l-acc", VISIBLE)).isEmpty();
        assertThat(listingRepository.findPublicByUuidIn(List.of("l-acc"), VISIBLE)).isEmpty();
        assertThat(countAll()).isZero();
        assertThat(officeRepository.isAdvertisable(activeOffice)).isFalse();

        representative.unsuspend();
        userRepository.saveAndFlush(representative);

        assertThat(query().getContent()).extracting(Listing::getUuid).containsExactly("l-acc");
        assertThat(listingRepository.findPublicByUuid("l-acc", VISIBLE)).isPresent();
        assertThat(listingRepository.findPublicByUuidIn(List.of("l-acc"), VISIBLE))
                .extracting(Listing::getUuid).containsExactly("l-acc");
        assertThat(countAll()).isEqualTo(1);
        assertThat(officeRepository.isAdvertisable(activeOffice)).isTrue();
        assertThat(listingRepository.findByUuid("l-acc")).get()
                .extracting(Listing::getStatus).isEqualTo(ListingStatus.ACTIVE);
    }

    /*
     * ★★ 변경 신청 중에도 매물은 공개 그대로다(PO 확정 2026-10-07 — "재심사를 받을 때 모든 기능할 수 있게"). 공개 조회 ·
     *    게시 관문은 부문 상태만 보고, 변경 신청은 부문도 사무소도 건드리지 않는다. 화면에 나가는 사무소 정보는 승인 전까지
     *    지금 값이다. 누가 공개 조건에 "대기 중인 변경 신청 없음"을 끼워 넣거나, 변경 신청이 부문을 대기로 돌리면 여기서 깨진다.
     */
    @Test
    @DisplayName("사무소 변경 신청이 대기 중이어도 목록 · 상세 · 지도 집계 · 게시 관문이 그대로 열린다")
    void pending_office_change_keeps_listings_public() {
        save("l-change", activeOffice, ListingStatus.ACTIVE);
        Long representativeId = profileRepository.findByOfficeId(activeOffice).get(0).getUserId();
        changeRequestRepository.saveAndFlush(BrokerageOfficeChangeRequest.builder().uuid("cr-1")
                .officeId(activeOffice).requestedBy(representativeId)
                .regNo("99999-2026-00019").name("새상호공인중개사사무소").ceoName("김철수").address("서울특별시 동작구")
                .licenseFileId(66L).status(OfficeChangeRequestStatus.PENDING)
                .submittedAt(LocalDateTime.of(2026, 10, 7, 9, 0)).build());

        assertThat(query().getContent()).extracting(Listing::getUuid).containsExactly("l-change");
        assertThat(listingRepository.findPublicByUuid("l-change", VISIBLE)).isPresent();
        assertThat(countAll()).isEqualTo(1);
        assertThat(officeRepository.isAdvertisable(activeOffice)).isTrue();
        BrokerageOffice office = officeRepository.findById(activeOffice).orElseThrow();
        assertThat(office.getRegNo()).isEqualTo("99999-2026-00002");
        assertThat(office.getName()).isEqualTo("테스트공인중개사사무소");
        assertThat(divisionRepository.findByUserIdAndDivision(representativeId, PartnerDivisionType.REAL_ESTATE_BROKER))
                .get().extracting(PartnerDivision::getStatus).isEqualTo(PartnerDivisionStatus.APPROVED);
    }

    // 탈퇴하면 보통 대표자 연결이 함께 지워지지만, 연결이 남아 있어도 탈퇴 계정의 광고는 나가지 않는다.
    @Test
    @DisplayName("대표자 계정이 탈퇴했으면 연결 · 부문이 남아 있어도 광고하지 못한다")
    void withdrawn_account_is_hidden() {
        save("l-gone", activeOffice, ListingStatus.ACTIVE);
        User representative = representativeOf(activeOffice);

        representative.withdraw(LocalDateTime.of(2026, 10, 6, 9, 0));
        userRepository.saveAndFlush(representative);

        assertThat(listingRepository.findPublicByUuid("l-gone", VISIBLE)).isEmpty();
        assertThat(listingRepository.findPublicByUuidIn(List.of("l-gone"), VISIBLE)).isEmpty();
        assertThat(countAll()).isZero();
        assertThat(officeRepository.isAdvertisable(activeOffice)).isFalse();
    }

    // 끝난 광고가 떠 있으면 그게 곧 허위매물이다.
    @Test
    @DisplayName("공개 상태(ACTIVE·RESERVED)만 나온다")
    void shows_only_public_statuses() {
        save("l-active", activeOffice, ListingStatus.ACTIVE);
        save("l-reserved", activeOffice, ListingStatus.RESERVED);
        save("l-draft", activeOffice, ListingStatus.DRAFT);
        save("l-expired", activeOffice, ListingStatus.EXPIRED);
        save("l-archived", activeOffice, ListingStatus.ARCHIVED);
        save("l-blocked", activeOffice, ListingStatus.BLOCKED);

        assertThat(query().getContent()).extracting(Listing::getUuid)
                .containsExactlyInAnyOrder("l-active", "l-reserved");
    }

    /*
     * ★ 우리가 다방과 다른 지점. 전입신고가 되는 집이 먼저 와야 한다 —
     *   유학생에게는 외국인등록증 주소지가 걸린 **체류 행정**의 문제다.
     */
    @Test
    @DisplayName("추천순은 전입신고 가능한 매물을 먼저 올린다")
    void recommended_puts_resident_registration_first() {
        saveWith("l-old-possible", LocalDateTime.of(2026, 1, 1, 0, 0), AvailabilityAnswer.POSSIBLE);
        saveWith("l-new-ask", LocalDateTime.of(2026, 10, 1, 0, 0), AvailabilityAnswer.ASK);
        saveWith("l-new-possible", LocalDateTime.of(2026, 10, 2, 0, 0), AvailabilityAnswer.POSSIBLE);

        assertThat(query().getContent()).extracting(Listing::getUuid)
                .containsExactly("l-new-possible", "l-old-possible", "l-new-ask");
    }

    // bbox 는 display 좌표로 건다 — 실좌표로 걸면 응답에 실좌표가 따라 나갈 길이 생긴다.
    @Test
    @DisplayName("bbox 밖의 매물은 빠진다")
    void filters_by_bounding_box() {
        saveAt("l-in", new BigDecimal("37.4862552"), new BigDecimal("126.9332423"));
        saveAt("l-out", new BigDecimal("35.1000000"), new BigDecimal("129.0000000"));

        var page = listingRepository.findPublicRecommended(VISIBLE, null,
                ALL_SELLING, ALL_ROOM, ALL_FLOOR, null, null, null, null, null, null,
                false, false, null, null, null,
                new BigDecimal("37.45"), new BigDecimal("126.90"),
                new BigDecimal("37.50"), new BigDecimal("126.95"),
                PageRequest.of(0, 20));

        assertThat(page.getContent()).extracting(Listing::getUuid).containsExactly("l-in");
    }

    @Test
    @DisplayName("가격·면적 범위 필터가 동작한다")
    void filters_by_price_and_area() {
        savePriced("l-cheap", 1_000_000L, 200_000L, new BigDecimal("20"));
        savePriced("l-mid", 5_000_000L, 500_000L, new BigDecimal("30"));
        savePriced("l-expensive", 50_000_000L, 1_500_000L, new BigDecimal("80"));

        var page = listingRepository.findPublicRecommended(VISIBLE, null,
                ALL_SELLING, ALL_ROOM, ALL_FLOOR,
                null, 10_000_000L, null, 800_000L, new BigDecimal("25"), null,
                false, false, null, null, null, null, null, null, null, PageRequest.of(0, 20));

        assertThat(page.getContent()).extracting(Listing::getUuid).containsExactly("l-mid");
    }

    /*
     * ★ LATEST 의 기준이 posted_at 이라는 것을 고정한다. 갱신 시각으로 바뀌면
     *   중개사가 버튼 한 번으로 상단을 차지하는 bump 가 되살아난다(다방이 그 상태다).
     */
    @Test
    @DisplayName("최신순은 최초 게시 시각 기준이다")
    void latest_sorts_by_posted_at() {
        saveWith("l-first", LocalDateTime.of(2026, 1, 1, 0, 0), AvailabilityAnswer.ASK);
        saveWith("l-second", LocalDateTime.of(2026, 5, 1, 0, 0), AvailabilityAnswer.ASK);

        var page = listingRepository.findPublic(VISIBLE, null,
                ALL_SELLING, ALL_ROOM, ALL_FLOOR, null, null, null, null, null, null,
                false, false, null, null, null, null, null, null, null,
                PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "postedAt")));

        assertThat(page.getContent()).extracting(Listing::getUuid)
                .containsExactly("l-second", "l-first");
    }

    // ── 픽스처

    private Page<Listing> query() {
        return listingRepository.findPublicRecommended(VISIBLE, null,
                ALL_SELLING, ALL_ROOM, ALL_FLOOR, null, null, null, null, null, null,
                false, false, null, null, null, null, null, null, null, PageRequest.of(0, 20));
    }

    private long countAll() {
        return listingRepository.countPublic(VISIBLE, null,
                ALL_SELLING, ALL_ROOM, ALL_FLOOR, null, null, null, null, null, null,
                false, false, null, null, null, null, null, null);
    }

    // 대표자 계정 + 사무소 + 대표자 연결 + 그 대표자의 공인중개사 부문 — 파트너 가입이 한 번에 만드는 행들이다.
    private Long officeOf(String uuid, String regNo, PartnerDivisionStatus status) {
        Long representativeId = userRepository.save(User.builder()
                .uuid("u-" + uuid).name("홍길동")
                .nickname("u-" + uuid).nicknameNormalized("u-" + uuid).isNicknameCustom(false)
                .preferredLanguage("ko").status(UserStatus.ACTIVE).role(UserRole.PARTNER)
                .build()).getId();
        Long officeId = officeRepository.save(office(uuid, regNo)).getId();
        profileRepository.save(BrokerProfile.builder().userId(representativeId).officeId(officeId).build());
        divisionRepository.save(division(representativeId, status));
        return officeId;
    }

    private User representativeOf(Long officeId) {
        Long userId = profileRepository.findByOfficeId(officeId).get(0).getUserId();
        return userRepository.findById(userId).orElseThrow();
    }

    private PartnerDivision division(Long userId, PartnerDivisionStatus status) {
        return PartnerDivision.builder().uuid("pd-" + userId).userId(userId)
                .division(PartnerDivisionType.REAL_ESTATE_BROKER).status(status)
                .submittedAt(LocalDateTime.of(2026, 10, 1, 0, 0)).build();
    }

    private BrokerageOffice office(String uuid, String regNo) {
        return BrokerageOffice.builder().uuid(uuid).regNo(regNo)
                .name("테스트공인중개사사무소").ceoName("홍길동")
                .address("서울특별시 관악구").tel("02-000-0000").build();
    }

    private void save(String uuid, Long officeId, ListingStatus status) {
        listingRepository.save(base(uuid, officeId, status).build());
    }

    private void saveWith(String uuid, LocalDateTime postedAt, AvailabilityAnswer rr) {
        listingRepository.save(base(uuid, activeOffice, ListingStatus.ACTIVE)
                .postedAt(postedAt).residentRegistration(rr).build());
    }

    private void saveAt(String uuid, BigDecimal lat, BigDecimal lng) {
        listingRepository.save(base(uuid, activeOffice, ListingStatus.ACTIVE)
                .displayLat(lat).displayLng(lng).build());
    }

    private void savePriced(String uuid, Long deposit, Long rent, BigDecimal area) {
        listingRepository.save(base(uuid, activeOffice, ListingStatus.ACTIVE)
                .deposit(deposit).monthlyRent(rent).exclusiveArea(area).build());
    }

    private Listing.ListingBuilder<?, ?> base(String uuid, Long officeId, ListingStatus status) {
        return Listing.builder()
                // listing_no 는 VARCHAR(20) 이라 uuid 를 그대로 붙이면 넘친다.
                .uuid(uuid).listingNo("LS-261005-" + Integer.toHexString(uuid.hashCode()).toUpperCase())
                .officeId(officeId).createdByUserId(10L)
                .category(ListingCategory.ONE_TWO_ROOM).roomType(RoomType.ONE_ROOM)
                .title("테스트").sellingType(SellingType.MONTHLY_RENT)
                .deposit(3_000_000L).monthlyRent(300_000L)
                .exclusiveArea(new BigDecimal("26.44")).floorType(FloorType.GROUND)
                .residentRegistration(AvailabilityAnswer.ASK)
                .depositInsurance(AvailabilityAnswer.ASK)
                .realLat(new BigDecimal("37.4862378")).realLng(new BigDecimal("126.9331220"))
                .displayLat(new BigDecimal("37.4862552")).displayLng(new BigDecimal("126.9332423"))
                .addressJibun("서울특별시 관악구 신림동")
                .postedAt(LocalDateTime.of(2026, 10, 1, 0, 0))
                .status(status);
    }
}
