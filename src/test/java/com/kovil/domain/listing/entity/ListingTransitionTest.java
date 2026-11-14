package com.kovil.domain.listing.entity;

import com.kovil.domain.listing.enums.AvailabilityAnswer;
import com.kovil.domain.listing.enums.FloorType;
import com.kovil.domain.listing.enums.ListingCategory;
import com.kovil.domain.listing.enums.ListingStatus;
import com.kovil.domain.listing.enums.RoomType;
import com.kovil.domain.listing.enums.SellingType;
import com.kovil.domain.listing.exception.ListingErrorCode;
import com.kovil.domain.listing.exception.ListingException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 상태 전이는 엔티티가 소유한다 — 서비스가 status 를 직접 대입하면 전이가 흩어져 추적이 안 된다.
// 여기가 그 전이를 고정하는 자리다.
class ListingTransitionTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 5, 12, 0);
    private static final LocalDateTime MONTH_LATER = NOW.plusMonths(1);

    @Nested
    @DisplayName("게시")
    class Publish {

        @Test
        @DisplayName("초안과 내린 것은 올릴 수 있다")
        void publishes_from_draft_and_hidden() {
            for (ListingStatus from : new ListingStatus[]{
                    ListingStatus.DRAFT, ListingStatus.HIDDEN}) {
                Listing listing = listing(from);
                listing.publish(NOW, MONTH_LATER);
                assertThat(listing.getStatus()).as("%s", from).isEqualTo(ListingStatus.ACTIVE);
                assertThat(listing.getExpiresAt()).isEqualTo(MONTH_LATER);
            }
        }

        /*
         * ★★ **행동 변경 (2026-10-05, 적대적 리뷰 + 실측).** 전에는 EXPIRED 도 publish 로 올릴 수
         *    있었다. 그런데 연장 7일 창 판정은 **renew 에만** 있어서, 창이 닫힌 매물이
         *    `renew` 409 / `publish` 200 으로 갈렸다 — 만료 +8일 매물을 publish 로 되살리고
         *    expires_at 을 한 달 밀 수 있었다(실측 확인). 광고 기한의 자동 종료가 통째로 우회된다.
         *
         *    같은 전이에 가드가 다른 두 경로를 두지 않는다 — **만료된 매물은 renew 만** 받는다.
         *    에러 문구("초안이거나 내린 매물만 게시할 수 있습니다")도 처음부터 이쪽이 맞았다.
         */
        @Test
        @DisplayName("만료된 매물은 publish 로 올릴 수 없다 — renew 만 받는다(연장 창이 거기 있다)")
        void cannot_publish_expired_listing() {
            assertThatThrownBy(() -> listing(ListingStatus.EXPIRED).publish(NOW, MONTH_LATER))
                    .isInstanceOf(ListingException.class)
                    .satisfies(e -> assertThat(((ListingException) e).getErrorCode().getCode())
                            .isEqualTo("LISTING_NOT_PUBLISHABLE"));
        }

        /*
         * ★ 둘을 막지 않으면 정책이 무너진다 — ARCHIVED 는 연장 창이 닫힌 것이고
         *   BLOCKED 는 관리자가 허위매물로 막은 것이다. publish 로 되살아나면 둘 다 의미가 없다.
         */
        @Test
        @DisplayName("ARCHIVED·BLOCKED 는 중개사가 다시 올릴 수 없다")
        void cannot_publish_archived_or_blocked() {
            for (ListingStatus from : new ListingStatus[]{ListingStatus.ARCHIVED, ListingStatus.BLOCKED}) {
                assertThatThrownBy(() -> listing(from).publish(NOW, MONTH_LATER))
                        .as("%s", from)
                        .isInstanceOf(ListingException.class)
                        .satisfies(e -> assertThat(((ListingException) e).getErrorCode().getCode())
                                .isEqualTo(ListingErrorCode.LISTING_NOT_PUBLISHABLE.name()));
            }
        }

        // 내렸다 다시 올려도 "언제 처음 광고했나"는 남아야 한다 — 분쟁에서 그게 기준이다.
        @Test
        @DisplayName("재게시해도 최초 게시 시각은 덮이지 않는다")
        void keeps_first_posted_at() {
            Listing listing = listing(ListingStatus.HIDDEN);
            listing.publish(NOW, MONTH_LATER);
            listing.unpublish();
            listing.publish(NOW.plusDays(10), MONTH_LATER.plusDays(10));

            assertThat(listing.getPostedAt()).isEqualTo(NOW);
        }
    }

    @Nested
    @DisplayName("연장")
    class Renew {

        @Test
        @DisplayName("만료 상태에서 창이 열려 있으면 연장된다")
        void renews_within_window() {
            Listing listing = listing(ListingStatus.EXPIRED);
            listing.renew(MONTH_LATER, true);

            assertThat(listing.getStatus()).isEqualTo(ListingStatus.ACTIVE);
            assertThat(listing.getExpiresAt()).isEqualTo(MONTH_LATER);
        }

        /*
         * ★ 배치가 아직 ARCHIVED 로 옮기지 못한 사이에도 연장이 막혀야 한다.
         *   엔티티는 시계를 모르므로 호출부가 창 판정을 넘긴다 — 이 인자가 없으면
         *   "7일 지났는데 배치가 안 돌아서 연장되는" 구멍이 생긴다.
         */
        @Test
        @DisplayName("창이 닫혔으면 배치 전이라도 연장되지 않는다")
        void rejects_when_window_closed() {
            assertThatThrownBy(() -> listing(ListingStatus.EXPIRED).renew(MONTH_LATER, false))
                    .isInstanceOf(ListingException.class)
                    .satisfies(e -> assertThat(((ListingException) e).getErrorCode().getCode())
                            .isEqualTo(ListingErrorCode.LISTING_NOT_RENEWABLE.name()));
        }

        @Test
        @DisplayName("만료되지 않은 매물은 연장 대상이 아니다")
        void rejects_when_not_expired() {
            assertThatThrownBy(() -> listing(ListingStatus.ACTIVE).renew(MONTH_LATER, true))
                    .isInstanceOf(ListingException.class);
        }
    }

    @Nested
    @DisplayName("설명 버전")
    class DescriptionVersion {

        /*
         * ★★ 번역 캐시의 생명줄. 안 올리면 **낡은 번역이 영원히 나가고**,
         *     과하게 올리면 멀쩡한 번역이 통째로 날아간다.
         */
        @Test
        @DisplayName("설명이 바뀌면 버전이 오른다")
        void bumps_on_change() {
            Listing listing = listing(ListingStatus.DRAFT);
            assertThat(listing.getDescriptionVersion()).isEqualTo(1);

            listing.changeDescription("전입신고 가능합니다");
            assertThat(listing.getDescriptionVersion()).isEqualTo(2);
        }

        @Test
        @DisplayName("같은 내용으로 다시 저장하면 버전이 그대로다 — 멀쩡한 번역을 날리지 않는다")
        void does_not_bump_on_same_text() {
            Listing listing = listing(ListingStatus.DRAFT);
            listing.changeDescription("전입신고 가능합니다");
            listing.changeDescription("전입신고 가능합니다");

            assertThat(listing.getDescriptionVersion()).isEqualTo(2);
        }

        @Test
        @DisplayName("설명을 지워도 버전이 오른다 — 번역본이 남아 있으면 안 된다")
        void bumps_when_cleared() {
            Listing listing = listing(ListingStatus.DRAFT);
            listing.changeDescription("전입신고 가능합니다");
            listing.changeDescription(null);

            assertThat(listing.getDescriptionVersion()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("내림·완료")
    class Withdraw {

        @Test
        @DisplayName("공개 중인 매물만 내리거나 완료할 수 있다")
        void only_public_can_be_withdrawn() {
            Listing active = listing(ListingStatus.ACTIVE);
            active.unpublish();
            assertThat(active.getStatus()).isEqualTo(ListingStatus.HIDDEN);

            Listing reserved = listing(ListingStatus.RESERVED);
            reserved.complete();
            assertThat(reserved.getStatus()).isEqualTo(ListingStatus.COMPLETED);

            assertThatThrownBy(() -> listing(ListingStatus.DRAFT).unpublish())
                    .isInstanceOf(ListingException.class);
        }
    }

    /*
     * ★ 지역 닫힘은 공개 중인 것과 **연장을 기다리던(EXPIRED)** 것을 내림으로 보존한다(PO 확정 2026-10-06).
     *   EXPIRED 를 빼면 연장이 막힌 채 7일 뒤 되살릴 수 없게 끝난다. 초안·내림·완료·차단·종료는 광고도 연장 대상도 아니다.
     */
    @Nested
    @DisplayName("지역 닫힘")
    class RegionClosure {

        @Test
        @DisplayName("공개 중(ACTIVE·RESERVED)과 연장 대기(EXPIRED)를 HIDDEN 으로 보존한다")
        void hides_public_and_expired() {
            for (ListingStatus s : new ListingStatus[]{ListingStatus.ACTIVE, ListingStatus.RESERVED, ListingStatus.EXPIRED}) {
                Listing listing = listing(s);
                listing.hideForRegionClosure();
                assertThat(listing.getStatus()).as("%s", s).isEqualTo(ListingStatus.HIDDEN);
            }
        }

        @Test
        @DisplayName("초안·내림·완료·차단·종료는 받지 않는다")
        void rejects_the_rest() {
            for (ListingStatus s : new ListingStatus[]{ListingStatus.DRAFT, ListingStatus.HIDDEN,
                    ListingStatus.COMPLETED, ListingStatus.BLOCKED, ListingStatus.ARCHIVED}) {
                assertThatThrownBy(() -> listing(s).hideForRegionClosure()).as("%s", s)
                        .isInstanceOf(ListingException.class);
            }
        }
    }

    private Listing listing(ListingStatus status) {
        return Listing.builder()
                .uuid("l-1").listingNo("LS-261005-ABC123")
                .officeId(1L).createdByUserId(10L)
                .category(ListingCategory.ONE_TWO_ROOM).roomType(RoomType.ONE_ROOM)
                .title("테스트 매물")
                .sellingType(SellingType.MONTHLY_RENT).deposit(3_000_000L).monthlyRent(300_000L)
                .exclusiveArea(new BigDecimal("26.44"))
                .floorType(FloorType.GROUND)
                .residentRegistration(AvailabilityAnswer.ASK).depositInsurance(AvailabilityAnswer.ASK)
                .realLat(new BigDecimal("37.4862378")).realLng(new BigDecimal("126.9331220"))
                .displayLat(new BigDecimal("37.4862552")).displayLng(new BigDecimal("126.9332423"))
                .addressJibun("서울특별시 관악구 신림동")
                .status(status)
                .build();
    }
}
