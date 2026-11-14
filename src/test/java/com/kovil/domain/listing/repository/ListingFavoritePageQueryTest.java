package com.kovil.domain.listing.repository;

import com.kovil.common.config.JpaAuditingConfig;
import com.kovil.domain.listing.entity.BrokerProfile;
import com.kovil.domain.listing.entity.BrokerageOffice;
import com.kovil.domain.listing.entity.Listing;
import com.kovil.domain.listing.entity.ListingFavorite;
import com.kovil.domain.listing.enums.*;
import com.kovil.domain.partner.entity.PartnerDivision;
import com.kovil.domain.partner.enums.PartnerDivisionStatus;
import com.kovil.domain.partner.enums.PartnerDivisionType;
import com.kovil.domain.partner.repository.PartnerDivisionRepository;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * 내 찜 목록의 두 쿼리 — 찜 페이지(findPageByUserId)와 공개 판정(findPublicIdsIn).
 * JPQL 은 부팅 때 파싱만 되고, 정렬 · 조건이 틀리면 "순서가 흔들리거나 내려간 매물이 카드로 나간다"로 조용히 나타난다.
 */
@DataJpaTest
@Import(JpaAuditingConfig.class)
class ListingFavoritePageQueryTest {

    private static final List<ListingStatus> VISIBLE =
            List.of(ListingStatus.ACTIVE, ListingStatus.RESERVED);

    @Autowired ListingFavoriteRepository favoriteRepository;
    @Autowired ListingRepository listingRepository;
    @Autowired BrokerageOfficeRepository officeRepository;
    @Autowired BrokerProfileRepository profileRepository;
    @Autowired PartnerDivisionRepository divisionRepository;
    @Autowired UserRepository userRepository;

    @Nested
    @DisplayName("찜 페이지")
    class FavoritePage {

        private static final Long ME = 1L;
        private static final Long OTHER = 2L;

        // 매물 id 는 찜 테이블만 본다 — 이 쿼리는 매물을 조인하지 않는다.
        @BeforeEach
        void setUp() {
            favorite(ME, 101L, LocalDateTime.of(2026, 10, 1, 9, 0));
            favorite(ME, 102L, LocalDateTime.of(2026, 10, 3, 9, 0));
            favorite(ME, 103L, LocalDateTime.of(2026, 10, 3, 9, 0));
            favorite(ME, 104L, LocalDateTime.of(2026, 10, 2, 9, 0));
            favorite(OTHER, 105L, LocalDateTime.of(2026, 10, 5, 9, 0));
        }

        @Test
        @DisplayName("최근에 담은 순이고, 같은 시각이면 매물 id 가 큰 쪽이 먼저다")
        void recent_first_and_ties_broken_by_listing_id() {
            Page<ListingFavorite> page = favoriteRepository.findPageByUserId(ME, PageRequest.of(0, 20));

            assertThat(page.getContent()).extracting(ListingFavorite::getListingId)
                    .containsExactly(103L, 102L, 104L, 101L);
        }

        // 탈퇴 정리는 이 잠금 읽기로 지울 매물을 고른다 — 남의 찜이 섞이면 남의 찜 수까지 깎는다.
        @Test
        @DisplayName("탈퇴 정리용 잠금 읽기는 내 찜의 매물 id 만 고른다")
        void lock_read_returns_only_my_listing_ids() {
            assertThat(favoriteRepository.findListingIdsByUserIdForUpdate(ME))
                    .containsExactlyInAnyOrder(101L, 102L, 103L, 104L);
            assertThat(favoriteRepository.findListingIdsByUserIdForUpdate(99L)).isEmpty();
        }

        /*
         * ★ 페이지 크기를 전체보다 작게 둔다. 첫 페이지에 다 들어오면 Spring Data 가 개수 쿼리를 건너뛰고
         *   내용 건수로 total 을 채워, 개수 쿼리에서 userId 조건이 빠져도 초록으로 지나간다.
         */
        @Test
        @DisplayName("남의 찜은 섞이지 않고, 개수 쿼리도 내 것만 센다")
        void only_my_favorites_are_counted() {
            Page<ListingFavorite> page = favoriteRepository.findPageByUserId(ME, PageRequest.of(0, 2));

            assertThat(page.getTotalElements()).isEqualTo(4);
            assertThat(favoriteRepository.findPageByUserId(ME, PageRequest.of(0, 20)).getContent())
                    .extracting(ListingFavorite::getListingId).doesNotContain(105L);
        }

        /*
         * ★ 같은 시각 두 건이 페이지 경계에 걸쳐도 두 번 나오거나 빠지지 않아야 한다 —
         *   담은 시각만으로 정렬하면 DB 가 동점의 순서를 페이지마다 다르게 고를 수 있다.
         */
        @Test
        @DisplayName("페이지를 넘겨도 같은 매물이 두 번 나오거나 빠지지 않는다")
        void paging_is_stable_across_ties() {
            List<Long> first = favoriteRepository.findPageByUserId(ME, PageRequest.of(0, 1))
                    .map(ListingFavorite::getListingId).getContent();
            List<Long> second = favoriteRepository.findPageByUserId(ME, PageRequest.of(1, 1))
                    .map(ListingFavorite::getListingId).getContent();

            assertThat(first).containsExactly(103L);
            assertThat(second).containsExactly(102L);
        }

        @Test
        @DisplayName("범위를 넘긴 페이지는 비어 있어도 전체 개수를 그대로 준다")
        void page_beyond_range_keeps_total() {
            Page<ListingFavorite> page = favoriteRepository.findPageByUserId(ME, PageRequest.of(5, 20));

            assertThat(page.getContent()).isEmpty();
            assertThat(page.getTotalElements()).isEqualTo(4);
        }
    }

    @Nested
    @DisplayName("공개 판정")
    class PublicJudgement {

        private Long approvedOffice;
        private Long suspendedOffice;

        @BeforeEach
        void setUp() {
            approvedOffice = officeOf("o-ok", "99999-2026-00011", PartnerDivisionStatus.APPROVED);
            suspendedOffice = officeOf("o-ng", "99999-2026-00012", PartnerDivisionStatus.SUSPENDED);
        }

        @Test
        @DisplayName("넘긴 매물 중 공개(게시 중 · 예약 중)인 것만 돌려준다")
        void returns_only_public_ids() {
            Long active = save("f-active", approvedOffice, ListingStatus.ACTIVE);
            Long reserved = save("f-reserved", approvedOffice, ListingStatus.RESERVED);
            Long completed = save("f-completed", approvedOffice, ListingStatus.COMPLETED);
            Long suspended = save("f-suspended", suspendedOffice, ListingStatus.ACTIVE);

            assertThat(listingRepository.findPublicIdsIn(List.of(active, reserved, completed, suspended), VISIBLE))
                    .containsExactlyInAnyOrder(active, reserved);
        }

        @Test
        @DisplayName("넘기지 않은 공개 매물은 돌려주지 않는다")
        void ignores_ids_not_asked() {
            Long asked = save("f-asked", approvedOffice, ListingStatus.ACTIVE);
            save("f-not-asked", approvedOffice, ListingStatus.ACTIVE);

            assertThat(listingRepository.findPublicIdsIn(List.of(asked), VISIBLE)).containsExactly(asked);
        }

        /*
         * ★★ 부문 정지는 매물 상태를 건드리지 않는다 — 공개 조회가 부문 상태를 보고 거른다.
         *    찜 목록이 상태 컬럼만 보면 정지된 중개사의 광고가 찜 카드로 계속 나가고, 복구하면 그대로 돌아와야 한다.
         */
        @Test
        @DisplayName("대표자의 공인중개사 부문이 정지되면 빠지고, 복구하면 다시 공개다")
        void division_suspension_hides_and_reinstatement_restores() {
            Long id = save("f-div", approvedOffice, ListingStatus.ACTIVE);
            PartnerDivision division = divisionOf(approvedOffice);

            division.suspend(1L, "허위 매물", LocalDateTime.of(2026, 10, 10, 9, 0));
            divisionRepository.saveAndFlush(division);
            assertThat(listingRepository.findPublicIdsIn(List.of(id), VISIBLE)).isEmpty();

            division.reinstate(1L, LocalDateTime.of(2026, 10, 11, 9, 0));
            divisionRepository.saveAndFlush(division);
            assertThat(listingRepository.findPublicIdsIn(List.of(id), VISIBLE)).containsExactly(id);
        }

        @Test
        @DisplayName("대표자 계정이 정지되면 빠지고, 정지를 풀면 다시 공개다")
        void account_suspension_hides_and_unsuspend_restores() {
            Long id = save("f-acc", approvedOffice, ListingStatus.ACTIVE);
            User representative = representativeOf(approvedOffice);

            representative.suspend("허위 매물", LocalDateTime.of(2026, 10, 10, 9, 0));
            userRepository.saveAndFlush(representative);
            assertThat(listingRepository.findPublicIdsIn(List.of(id), VISIBLE)).isEmpty();

            representative.unsuspend();
            userRepository.saveAndFlush(representative);
            assertThat(listingRepository.findPublicIdsIn(List.of(id), VISIBLE)).containsExactly(id);
        }

        /*
         * ★★ 찜 목록의 공개 판정과 공개 상세는 **같은 조건**이어야 한다. 한쪽만 고치면
         *    찜에서는 카드로 보이는데 누르면 404 이거나, 반대로 열리는 매물이 찜에서는 내려간 것으로 보인다.
         */
        @ParameterizedTest
        @EnumSource(ListingStatus.class)
        @DisplayName("공개 판정은 어느 상태에서나 공개 상세(findPublicByUuid)와 같다")
        void agrees_with_public_detail(ListingStatus status) {
            Long onApproved = save("f-p-" + status, approvedOffice, status);
            Long onSuspended = save("f-s-" + status, suspendedOffice, status);

            List<Long> publicIds = listingRepository.findPublicIdsIn(List.of(onApproved, onSuspended), VISIBLE);

            assertThat(publicIds.contains(onApproved))
                    .isEqualTo(listingRepository.findPublicByUuid("f-p-" + status, VISIBLE).isPresent());
            assertThat(publicIds.contains(onSuspended))
                    .isEqualTo(listingRepository.findPublicByUuid("f-s-" + status, VISIBLE).isPresent());
        }
    }

    // ───────────────────────── 픽스처

    private void favorite(Long userId, Long listingId, LocalDateTime at) {
        favoriteRepository.saveAndFlush(ListingFavorite.builder()
                .userId(userId).listingId(listingId).createdAt(at).build());
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

    private User representativeOf(Long officeId) {
        Long userId = profileRepository.findByOfficeId(officeId).get(0).getUserId();
        return userRepository.findById(userId).orElseThrow();
    }

    private PartnerDivision divisionOf(Long officeId) {
        return divisionRepository.findByUserIdAndDivision(
                representativeOf(officeId).getId(), PartnerDivisionType.REAL_ESTATE_BROKER).orElseThrow();
    }

    private Long save(String uuid, Long officeId, ListingStatus status) {
        return listingRepository.save(Listing.builder()
                // listing_no 는 VARCHAR(20) 이라 uuid 를 그대로 붙이면 넘친다.
                .uuid(uuid).listingNo("LS-261012-" + Integer.toHexString(uuid.hashCode()).toUpperCase())
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
                .status(status).build()).getId();
    }
}
