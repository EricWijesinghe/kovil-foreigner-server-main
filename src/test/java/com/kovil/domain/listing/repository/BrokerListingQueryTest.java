package com.kovil.domain.listing.repository;

import com.kovil.common.config.JpaAuditingConfig;
import com.kovil.domain.listing.entity.Listing;
import com.kovil.domain.listing.enums.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * 중개사 매물 목록의 정렬 · 상태별 개수 쿼리. JPQL 은 부팅 때 파싱만 되고,
 * NULL 의 자리나 경계값이 틀리면 "초안이 맨 위에 뜨거나 연장 가능 숫자가 하나 어긋난다"로 조용히 나타난다.
 */
@DataJpaTest
@Import(JpaAuditingConfig.class)
class BrokerListingQueryTest {

    private static final Long OFFICE = 1L;
    private static final Long OTHER_OFFICE = 2L;
    private static final List<ListingStatus> ALL = List.copyOf(EnumSet.allOf(ListingStatus.class));

    @Autowired ListingRepository listingRepository;
    @Autowired EntityManager entityManager;

    /*
     * ★ 오름차순에서 MySQL 은 NULL 을 앞에 둔다 — 그대로면 만료 시각이 없는 초안이 "만료 임박" 맨 위에 뜬다.
     *   같은 만료 시각이면 id 가 큰 쪽이 먼저다(페이지를 넘겨도 순서가 흔들리지 않게).
     */
    @Test
    @DisplayName("만료 임박 순은 만료가 이른 것부터, 같으면 id 큰 쪽 먼저, 만료 시각이 없는 초안은 맨 뒤다")
    void expiring_first_puts_drafts_last() {
        Long later = save("e-later", OFFICE, ListingStatus.ACTIVE, null, LocalDateTime.of(2026, 10, 20, 0, 0));
        Long draft = save("e-draft", OFFICE, ListingStatus.DRAFT, null, null);
        Long earlyA = save("e-early-a", OFFICE, ListingStatus.EXPIRED, null, LocalDateTime.of(2026, 10, 10, 0, 0));
        Long earlyB = save("e-early-b", OFFICE, ListingStatus.EXPIRED, null, LocalDateTime.of(2026, 10, 10, 0, 0));
        save("e-other", OTHER_OFFICE, ListingStatus.ACTIVE, null, LocalDateTime.of(2026, 10, 1, 0, 0));

        List<Long> ids = listingRepository.findByOfficeIdAndStatusInExpiringFirst(OFFICE, ALL, PageRequest.of(0, 20))
                .map(Listing::getId).getContent();

        assertThat(ids).containsExactly(earlyB, earlyA, later, draft);
    }

    /*
     * ★ 만료 임박 순의 개수 쿼리는 값 쿼리와 따로 손으로 쓴 문자열이라 술어가 빠져도 부팅 · 실행 오류가 없다.
     *   Spring Data 는 첫 페이지 결과가 size 보다 적으면 개수 쿼리를 건너뛰므로, size 를 결과 수보다 작게 줘야 그 문자열이 돈다.
     */
    @Test
    @DisplayName("만료 임박 순의 전체 개수도 내 사무소 · 고른 상태만 센다")
    void expiring_first_count_scopes_office_and_status() {
        save("n-active", OFFICE, ListingStatus.ACTIVE, null, LocalDateTime.of(2026, 10, 20, 0, 0));
        save("n-draft", OFFICE, ListingStatus.DRAFT, null, null);
        save("n-expired-a", OFFICE, ListingStatus.EXPIRED, null, LocalDateTime.of(2026, 10, 10, 0, 0));
        save("n-expired-b", OFFICE, ListingStatus.EXPIRED, null, LocalDateTime.of(2026, 10, 11, 0, 0));
        save("n-other-active", OTHER_OFFICE, ListingStatus.ACTIVE, null, LocalDateTime.of(2026, 10, 1, 0, 0));
        save("n-other-expired", OTHER_OFFICE, ListingStatus.EXPIRED, null, LocalDateTime.of(2026, 10, 1, 0, 0));

        Page<Listing> all = listingRepository.findByOfficeIdAndStatusInExpiringFirst(OFFICE, ALL, PageRequest.of(0, 2));
        assertThat(all.getContent()).hasSize(2);
        assertThat(all.getTotalElements()).isEqualTo(4);

        Page<Listing> expired = listingRepository.findByOfficeIdAndStatusInExpiringFirst(
                OFFICE, List.of(ListingStatus.EXPIRED), PageRequest.of(0, 1));
        assertThat(expired.getContent()).hasSize(1);
        assertThat(expired.getTotalElements()).isEqualTo(2);
    }

    // 서비스가 만드는 Sort 그대로 — 게시한 적 없는 초안(postedAt NULL)은 내림차순에서 맨 뒤다.
    @Test
    @DisplayName("최근 게시 순에서 게시한 적 없는 초안은 맨 뒤다")
    void posted_desc_puts_drafts_last() {
        Long older = save("p-older", OFFICE, ListingStatus.ACTIVE, LocalDateTime.of(2026, 10, 1, 0, 0), null);
        Long draft = save("p-draft", OFFICE, ListingStatus.DRAFT, null, null);
        Long newer = save("p-newer", OFFICE, ListingStatus.ACTIVE, LocalDateTime.of(2026, 10, 5, 0, 0), null);

        List<Long> ids = listingRepository.findByOfficeIdAndStatusIn(OFFICE, ALL, PageRequest.of(0, 20,
                        Sort.by(Sort.Direction.DESC, "postedAt").and(Sort.by(Sort.Direction.DESC, "id"))))
                .map(Listing::getId).getContent();

        assertThat(ids).containsExactly(newer, older, draft);
    }

    @Test
    @DisplayName("상태별 개수는 내 사무소 매물만 상태로 묶어 센다")
    void counts_by_status_for_my_office() {
        save("c-1", OFFICE, ListingStatus.ACTIVE, null, null);
        save("c-2", OFFICE, ListingStatus.ACTIVE, null, null);
        save("c-3", OFFICE, ListingStatus.DRAFT, null, null);
        save("c-4", OTHER_OFFICE, ListingStatus.ACTIVE, null, null);

        List<ListingStatusCount> counts = listingRepository.countByOfficeIdGroupByStatus(OFFICE);

        assertThat(counts).containsExactlyInAnyOrder(
                new ListingStatusCount(ListingStatus.ACTIVE, 2L),
                new ListingStatusCount(ListingStatus.DRAFT, 1L));
    }

    /*
     * ★ 경계는 연장(renew)과 같다 — 만료 시각이 기준과 같으면 센다(연장도 그때까지 된다), 1초라도 이르면 안 센다.
     *   게시 중인 매물은 만료 시각이 기준 이후여도 "연장 가능"이 아니다.
     */
    @Test
    @DisplayName("연장 가능 수는 EXPIRED 이고 만료 시각이 기준 이상인 내 사무소 매물만 센다 — 경계 포함")
    void renewable_count_boundary() {
        LocalDateTime threshold = LocalDateTime.of(2026, 10, 5, 12, 0);
        save("r-edge", OFFICE, ListingStatus.EXPIRED, null, threshold);
        save("r-inside", OFFICE, ListingStatus.EXPIRED, null, threshold.plusDays(3));
        save("r-closed", OFFICE, ListingStatus.EXPIRED, null, threshold.minusSeconds(1));
        save("r-active", OFFICE, ListingStatus.ACTIVE, null, threshold.plusDays(3));
        save("r-other", OTHER_OFFICE, ListingStatus.EXPIRED, null, threshold.plusDays(3));

        long renewable = listingRepository.countByOfficeIdAndStatusAndExpiresAtGreaterThanEqual(
                OFFICE, ListingStatus.EXPIRED, threshold);

        assertThat(renewable).isEqualTo(2);
    }

    @Test
    @DisplayName("수정 저장이 찍는 updated_at 은 그 매물에만 지정한 시각으로 들어간다")
    void touch_sets_updated_at() {
        Long target = save("t-target", OFFICE, ListingStatus.ACTIVE, null, null);
        Long other = save("t-other", OFFICE, ListingStatus.ACTIVE, null, null);
        LocalDateTime at = LocalDateTime.of(2030, 1, 1, 9, 0);

        listingRepository.touchUpdatedAt(target, at);
        entityManager.clear();

        assertThat(listingRepository.findById(target).orElseThrow().getUpdatedAt()).isEqualTo(at);
        assertThat(listingRepository.findById(other).orElseThrow().getUpdatedAt()).isNotEqualTo(at);
    }

    private Long save(String uuid, Long officeId, ListingStatus status, LocalDateTime postedAt, LocalDateTime expiresAt) {
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
                .postedAt(postedAt).expiresAt(expiresAt)
                .status(status).build()).getId();
    }
}
