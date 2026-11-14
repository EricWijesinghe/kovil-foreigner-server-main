package com.kovil.domain.listing.repository;

import com.kovil.common.config.JpaAuditingConfig;
import com.kovil.domain.listing.entity.Listing;
import com.kovil.domain.listing.enums.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * ★★ 운영 MySQL 의 listings.updated_at 은 ON UPDATE CURRENT_TIMESTAMP(6) 이다(V24). 그래서 카운터(조회 · 문의 · 찜)
 *    UPDATE 가 그 칼럼을 그대로 두면 조회 한 번에 updated_at 이 바뀌고, 중개사 목록의 "최근 바뀐 순"이 "최근 조회된 순"이 된다.
 *    테스트 H2 는 엔티티로 테이블을 만들어 이 동작이 없다 — 그대로 두면 영원히 초록이다.
 *    그래서 여기서만 같은 ON UPDATE 를 걸어 MySQL 을 흉내 낸다. 대조군(셀프 대입 없는 UPDATE)이 실제로 바뀌는 것으로 흉내가 맞음을 먼저 보인다.
 *
 * ★ DDL 은 트랜잭션을 커밋해 버려 테스트 롤백이 안 된다 — 트랜잭션 없이 돌리고, 끝나면 컨텍스트(와 그 DB)를 버린다.
 */
@DataJpaTest
@Import(JpaAuditingConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ListingCounterUpdatedAtTest {

    private static final LocalDateTime OLD = LocalDateTime.of(2020, 1, 1, 0, 0);

    @Autowired ListingRepository listingRepository;
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;

    @BeforeEach
    void emulateMySqlOnUpdate() {
        tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(s -> entityManager.createNativeQuery(
                "ALTER TABLE listings ALTER COLUMN updated_at SET ON UPDATE CURRENT_TIMESTAMP(6)").executeUpdate());
    }

    @Test
    @DisplayName("대조군 — 셀프 대입 없이 카운터만 올리면 updated_at 이 바뀐다(흉내가 MySQL 처럼 동작한다)")
    void plain_counter_update_bumps_updated_at() {
        Long id = savedAtOld("ctl");

        tx.executeWithoutResult(s -> entityManager.createNativeQuery(
                "UPDATE listings SET view_count = view_count + 1 WHERE id = :id").setParameter("id", id).executeUpdate());

        assertThat(updatedAtOf(id)).isNotEqualTo(OLD);
    }

    @Test
    @DisplayName("조회수 · 문의 수 · 찜 수 갱신은 updated_at 을 바꾸지 않는다")
    void counters_keep_updated_at() {
        Long id = savedAtOld("cnt");

        tx.executeWithoutResult(s -> listingRepository.increaseViewCount(id));
        tx.executeWithoutResult(s -> listingRepository.increaseInquiryCount(id));
        tx.executeWithoutResult(s -> listingRepository.syncFavoriteCount(id));
        assertThat(updatedAtOf(id)).isEqualTo(OLD);
        assertThat(countersOf(id)).containsExactly(1L, 1L);
    }

    // ★ 찜 수가 0 → 0 이면 행 값이 그대로라 H2 · MySQL 모두 ON UPDATE 를 걸지 않는다 — 위 테스트의 sync 는 그래서 공허하다.
    //   일부러 어긋난 값(5)을 심어 sync 가 실제로 행을 바꾸게 한다. updated_at 을 같이 지정해 심기 자체는 OLD 를 지킨다.
    @Test
    @DisplayName("찜 수 동기화가 실제로 값을 바꿔도 updated_at 은 그대로다")
    void sync_favorite_count_keeps_updated_at() {
        Long id = savedAtOld("syn");
        tx.executeWithoutResult(s -> entityManager.createNativeQuery(
                "UPDATE listings SET favorite_count = 5, updated_at = :old WHERE id = :id")
                .setParameter("old", Timestamp.valueOf(OLD)).setParameter("id", id).executeUpdate());
        assertThat(favoriteCountOf(id)).isEqualTo(5L);
        assertThat(updatedAtOf(id)).isEqualTo(OLD);

        tx.executeWithoutResult(s -> listingRepository.syncFavoriteCount(id));

        assertThat(favoriteCountOf(id)).isZero();
        assertThat(updatedAtOf(id)).isEqualTo(OLD);
    }

    @Test
    @DisplayName("대조군 — 셀프 대입 없이 찜 수를 행 수로 맞추면 updated_at 이 바뀐다")
    void plain_favorite_sync_bumps_updated_at() {
        Long id = savedAtOld("sct");
        tx.executeWithoutResult(s -> entityManager.createNativeQuery(
                "UPDATE listings SET favorite_count = 5, updated_at = :old WHERE id = :id")
                .setParameter("old", Timestamp.valueOf(OLD)).setParameter("id", id).executeUpdate());

        tx.executeWithoutResult(s -> entityManager.createNativeQuery(
                "UPDATE listings l SET favorite_count = "
                        + "(SELECT COUNT(*) FROM listing_favorites f WHERE f.listing_id = l.id) WHERE l.id = :id")
                .setParameter("id", id).executeUpdate());

        assertThat(favoriteCountOf(id)).isZero();
        assertThat(updatedAtOf(id)).isNotEqualTo(OLD);
    }

    @Test
    @DisplayName("탈퇴로 찜 수를 되돌릴 때도 updated_at 을 바꾸지 않는다")
    void decrement_keeps_updated_at() {
        Long id = savedAtOld("dec");
        tx.executeWithoutResult(s -> entityManager.createNativeQuery(
                "UPDATE listings SET favorite_count = 3, updated_at = :old WHERE id = :id")
                .setParameter("old", Timestamp.valueOf(OLD)).setParameter("id", id).executeUpdate());

        tx.executeWithoutResult(s -> listingRepository.decrementFavoriteCount(List.of(id)));

        assertThat(updatedAtOf(id)).isEqualTo(OLD);
    }

    // 사진 · 옵션 · 관리비만 고친 수정이 쓰는 찍기는 반대로 지정한 시각이 그대로 들어가야 한다.
    @Test
    @DisplayName("수정 저장의 찍기는 ON UPDATE 가 걸려 있어도 지정한 시각이 들어간다")
    void touch_writes_the_given_time() {
        Long id = savedAtOld("tch");
        LocalDateTime at = LocalDateTime.of(2030, 1, 1, 9, 0);

        tx.executeWithoutResult(s -> listingRepository.touchUpdatedAt(id, at));

        assertThat(updatedAtOf(id)).isEqualTo(at);
    }

    private Long savedAtOld(String uuid) {
        Long id = listingRepository.saveAndFlush(Listing.builder()
                .uuid("cu-" + uuid).listingNo("LS-261012-CU" + uuid.toUpperCase())
                .officeId(1L).createdByUserId(10L)
                .category(ListingCategory.ONE_TWO_ROOM).roomType(RoomType.ONE_ROOM)
                .title("테스트").sellingType(SellingType.MONTHLY_RENT)
                .deposit(3_000_000L).monthlyRent(300_000L)
                .exclusiveArea(new BigDecimal("26.44")).floorType(FloorType.GROUND)
                .residentRegistration(AvailabilityAnswer.ASK).depositInsurance(AvailabilityAnswer.ASK)
                .realLat(new BigDecimal("37.4862378")).realLng(new BigDecimal("126.9331220"))
                .displayLat(new BigDecimal("37.4862552")).displayLng(new BigDecimal("126.9332423"))
                .addressJibun("서울특별시 관악구 신림동")
                .status(ListingStatus.ACTIVE).build()).getId();
        tx.executeWithoutResult(s -> listingRepository.touchUpdatedAt(id, OLD));
        assertThat(updatedAtOf(id)).isEqualTo(OLD);
        return id;
    }

    private LocalDateTime updatedAtOf(Long id) {
        Object value = tx.execute(s -> entityManager.createNativeQuery(
                "SELECT updated_at FROM listings WHERE id = :id").setParameter("id", id).getSingleResult());
        return value instanceof Timestamp ts ? ts.toLocalDateTime() : (LocalDateTime) value;
    }

    private long favoriteCountOf(Long id) {
        Object value = tx.execute(s -> entityManager.createNativeQuery(
                "SELECT favorite_count FROM listings WHERE id = :id").setParameter("id", id).getSingleResult());
        return ((Number) value).longValue();
    }

    private List<Long> countersOf(Long id) {
        Object[] row = (Object[]) tx.execute(s -> entityManager.createNativeQuery(
                "SELECT view_count, inquiry_count FROM listings WHERE id = :id").setParameter("id", id).getSingleResult());
        return List.of(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
    }
}
