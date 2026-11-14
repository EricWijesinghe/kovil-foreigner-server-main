package com.kovil.domain.listing.service;

import com.kovil.common.util.BusinessNumberGenerator;
import com.kovil.domain.listing.dto.request.BrokerListingSaveRequest;
import com.kovil.domain.listing.dto.response.BrokerListingResponse;
import com.kovil.domain.listing.entity.*;
import com.kovil.domain.listing.enums.*;
import com.kovil.domain.listing.exception.ListingErrorCode;
import com.kovil.domain.listing.exception.ListingException;
import com.kovil.domain.listing.repository.*;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.repository.UserRepository;
import com.kovil.domain.user.service.UserQueryService;
import com.kovil.domain.file.entity.StoredFile;
import com.kovil.domain.file.enums.FilePurpose;
import com.kovil.domain.file.repository.StoredFileRepository;
import com.kovil.domain.file.service.FileDeletionService;
import com.kovil.domain.listing.dto.response.BrokerListingSummaryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.Optional;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.*;

/*
 * 서비스에서 **조용히 틀릴 수 있는 것**만 고정한다.
 * 상태 전이는 ListingTransitionTest, 게시 관문은 ListingPublishPolicyTest 가 맡는다.
 */
class BrokerListingServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 5, 12, 0);
    private static final BigDecimal LAT = new BigDecimal("37.4862378");
    private static final BigDecimal LNG = new BigDecimal("126.9331220");

    private UserQueryService userQueryService;
    private UserRepository userRepository;
    private ListingRepository listingRepository;
    private ListingPriceHistoryRepository priceHistoryRepository;
    private ListingAccessPolicy accessPolicy;
    private DisplayCoordinateGenerator coordinateGenerator;
    private ListingMaintenanceRepository maintenanceRepository;
    private ListingMaintenanceItemRepository itemRepository;
    private ListingMaintenanceIncludeRepository includeRepository;
    private ListingOptionRepository optionRepository;
    private ListingSafetyRepository safetyRepository;
    private ListingImageRepository imageRepository;
    private EntityManager entityManager;
    private ListingPublishPolicy publishPolicy;
    private StoredFileRepository storedFileRepository;
    private ListingRegionResolver regionResolver;
    private FileDeletionService fileDeletionService;
    private BrokerListingService service;

    @BeforeEach
    void setUp() {
        userQueryService = mock(UserQueryService.class);
        userRepository = mock(UserRepository.class);
        listingRepository = mock(ListingRepository.class);
        priceHistoryRepository = mock(ListingPriceHistoryRepository.class);
        accessPolicy = mock(ListingAccessPolicy.class);
        coordinateGenerator = mock(DisplayCoordinateGenerator.class);

        maintenanceRepository = mock(ListingMaintenanceRepository.class);
        itemRepository = mock(ListingMaintenanceItemRepository.class);
        includeRepository = mock(ListingMaintenanceIncludeRepository.class);
        optionRepository = mock(ListingOptionRepository.class);
        safetyRepository = mock(ListingSafetyRepository.class);
        imageRepository = mock(ListingImageRepository.class);
        entityManager = mock(EntityManager.class);
        publishPolicy = mock(ListingPublishPolicy.class);
        storedFileRepository = mock(StoredFileRepository.class);
        regionResolver = mock(ListingRegionResolver.class);
        fileDeletionService = mock(FileDeletionService.class);

        service = new BrokerListingService(
                userQueryService, userRepository, listingRepository,
                maintenanceRepository, itemRepository,
                includeRepository, optionRepository,
                safetyRepository, imageRepository,
                priceHistoryRepository, accessPolicy, publishPolicy,
                coordinateGenerator, mock(BusinessNumberGenerator.class),
                Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC),
                storedFileRepository, entityManager, regionResolver, fileDeletionService);

        User broker = User.builder().id(10L).uuid("u-1").build();
        given(userQueryService.getActiveUser("u-1")).willReturn(broker);
        lenient().when(accessPolicy.requireAdvertisableOffice(10L)).thenReturn(
                BrokerageOffice.builder().id(1L).uuid("o-1").regNo("99999-2026-00002")
                        .name("측정공인중개사사무소").ceoName("홍길동")
                        .address("서울특별시 관악구").tel("02-000-0000").build());
        lenient().when(coordinateGenerator.obfuscate(any(), any()))
                .thenReturn(new BigDecimal[]{LAT, LNG});
        given(accessPolicy.requireProfile(10L)).willReturn(
                BrokerProfile.builder().userId(10L).officeId(1L).build());
    }

    /*
     * ★★ 실측 2026-10-05 에서 잡은 두 결함을 박는다. 둘 다 **단위 테스트 1,117개를 전부 통과한
     *    상태**에서 운영 모양(실 MySQL)으로 띄우자마자 터졌다.
     */
    @Nested
    @DisplayName("자식 전량 교체 — 실측에서 터진 자리")
    class ReplaceChildren {

        /*
         * ★★ Hibernate 의 액션 큐는 INSERT 를 DELETE 보다 **먼저** 내보낸다.
         *    flush 가 없으면 같은 관리비 항목을 유지한 채 수정하는 순간
         *    uq_lmi(listing_id, item_code) 에 걸려 **수정이 통째로 500** 이 된다.
         *    목 리포지토리는 유니크 제약이 없어 이 사고를 재현하지 못한다 —
         *    그래서 "순서"를 직접 단언한다.
         */
        @Test
        @DisplayName("삭제가 전부 끝나고 flush 한 뒤에야 저장이 시작된다")
        void deletes_are_flushed_before_inserts() {
            Listing saved = existing();
            given(listingRepository.save(any())).willReturn(saved);

            givenOwnListingImage("f-1", 11L);
            service.create("u-1", requestWithImages(List.of("f-1")));

            InOrder order = inOrder(itemRepository, includeRepository, optionRepository,
                    safetyRepository, imageRepository, entityManager);
            order.verify(itemRepository).deleteByListingId(any());
            order.verify(includeRepository).deleteByListingId(any());
            order.verify(optionRepository).deleteByListingId(any());
            order.verify(safetyRepository).deleteByListingId(any());
            order.verify(imageRepository).deleteByListingId(any());
            order.verify(entityManager).flush();
            order.verify(imageRepository).save(any());
        }

        /*
         * ★★ 가장 비쌌던 결함: 사진 목록(당시 이름 imageFileIds)이 **개수 검증만 받고 버려졌다.**
         *    사진이 영원히 0장이라 게시 관문(PUBLISH_PHOTO_REQUIRED)을 아무도 통과하지 못했고,
         *    그 결과 **어떤 매물도 공개되지 않았다.** 요청을 받아 놓고 저장하지 않는 필드는
         *    컴파일도 되고 테스트도 통과한다 — 그래서 저장 호출 자체를 박는다.
         */
        @Test
        @DisplayName("사진 uuid 를 받으면 순서를 붙여 실제로 저장한다")
        void image_file_ids_are_persisted_in_order() {
            Listing saved = existing();
            given(listingRepository.save(any())).willReturn(saved);

            givenOwnListingImage("f-1", 11L);
            givenOwnListingImage("f-2", 22L);
            givenOwnListingImage("f-3", 33L);

            service.create("u-1", requestWithImages(List.of("f-1", "f-2", "f-3")));

            ArgumentCaptor<ListingImage> captor = ArgumentCaptor.forClass(ListingImage.class);
            verify(imageRepository, times(3)).save(captor.capture());
            assertThat(captor.getAllValues())
                    .extracting(ListingImage::getFileId, ListingImage::getSortOrder)
                    .containsExactly(tuple(11L, 0), tuple(22L, 1), tuple(33L, 2));
        }

        // 관리비를 비운 수정은 덮어쓸 것이 없다 — 지우지 않으면 화면에서 지운 금액이 광고에 남는다.
        @Test
        @DisplayName("관리비를 비우면 기존 관리비 행을 지운다")
        void clearing_maintenance_removes_the_row() {
            Listing saved = existing();
            given(listingRepository.save(any())).willReturn(saved);
            ListingMaintenance stale = ListingMaintenance.builder()
                    .listingId(1L).chargeType(MaintenanceChargeType.FIXED_FEE).build();
            given(maintenanceRepository.findById(1L)).willReturn(Optional.of(stale));

            service.create("u-1", request(3_000_000L, 300_000L));

            verify(maintenanceRepository).delete(stale);
        }
    }

    /*
     * ★★ 적대적 실측 2026-10-05 에서 깨진 자리. 게시 관문 4종을 **수정으로 전부 우회**할 수 있었고,
     *    매물은 ACTIVE 인 채 공개 목록에 그대로 있었다 — 사진 0장짜리 광고가 떠 있었다.
     */
    /*
     * ★★ 적대적 리뷰 2026-10-05 가 찾은 두 자리. 둘 다 **아무 테스트도 묻지 않던** 구멍이다.
     */
    /*
     * ★★ 실측 2026-10-05 에서만 드러난 결함. 레코드의 원시 boolean 은 **생략된 속성을 null 로 받아**
     *    Jackson 이 터뜨린다 — Swagger 는 선택이라고 적는데 실제로는 400 이고 메시지에 필드명이 없다.
     *    단위 테스트는 생성자를 직접 부르므로 **이 경로를 절대 지나지 않는다.**
     *    그래서 "null 이 들어와도 false 로 읽힌다"를 여기서 박는다.
     */
    /*
     * ★★ 매물 INSERT 의 FK 검사는 사무소 → users 순으로 잠근다. 작성자 users 행을 그보다 먼저 잡지 않으면
     *    users → 사무소로 잡는 변경 승인 · 변경 신청 제출 · 탈퇴 · 정지와 교착한다(MySQL 9.5 재현 — 승인이 1213 으로 졌다).
     *    목 리포지토리는 잠금을 재현하지 못하므로 "순서"와 "공유 잠금 쿼리"를 직접 단언한다.
     */
    @Nested
    @DisplayName("잠금 순서 — 작성자 users 를 사무소보다 먼저")
    class LockOrder {

        @Test
        @DisplayName("작성자 행(id)을 공유 잠금으로 잡은 뒤에 사무소를 읽고 INSERT 한다")
        void author_row_is_locked_before_the_office_and_the_insert() {
            Listing saved = existing();
            given(listingRepository.save(any())).willReturn(saved);

            service.create("u-1", request(3_000_000L, 300_000L));

            InOrder order = inOrder(userQueryService, userRepository, accessPolicy, listingRepository);
            order.verify(userQueryService).getActiveUser("u-1");
            order.verify(userRepository).lockSharedById(10L);
            order.verify(accessPolicy).requireAdvertisableOffice(10L);
            order.verify(listingRepository).save(any());
        }

        @Test
        @DisplayName("배타 잠금(정지 · 탈퇴 · 변경 승인이 쓰는 FOR UPDATE)으로 잡지 않는다 — 지역 닫힘과 교착한다")
        void does_not_take_an_exclusive_lock_on_the_author() {
            Listing saved = existing();
            given(listingRepository.save(any())).willReturn(saved);

            service.create("u-1", request(3_000_000L, 300_000L));

            verify(userRepository).lockSharedById(10L);
            verify(userRepository, never()).findAuthSnapshotForUpdate(any());
            verify(userRepository, never()).findIdByUuidForUpdate(any());
        }
    }

    @Nested
    @DisplayName("불린 생략 — 실측에서만 드러난 자리")
    class OmittedBooleans {

        @Test
        @DisplayName("불린을 비워 보내도(null) false 로 읽고 등록이 성공한다")
        void null_booleans_default_to_false() {
            Listing saved = existing();
            given(listingRepository.save(any())).willReturn(saved);

            BrokerListingSaveRequest r = requestWithNullBooleans();
            assertThat(r.divisionOrFalse()).isFalse();
            assertThat(r.duplexOrFalse()).isFalse();
            assertThat(r.canParkingOrFalse()).isFalse();
            assertThat(r.moveInImmediateOrFalse()).isFalse();
            assertThat(r.shortLeaseOrFalse()).isFalse();
            assertThat(r.showDetailAddressOrFalse()).isFalse();

            service.create("u-1", r);

            verify(listingRepository).save(any());
        }
    }

    @Nested
    @DisplayName("사진 파일 검증 · 수정 가능 상태")
    class FileAndStateGates {

        /*
         * ★★ 용도 검증이 없으면 `VERIFICATION_DOC`(여권 사진 등 **민감**) uuid 를 넣어
         *    매물 사진으로 둘 수 있다 — 공개 응답이 그 URL 을 싣는 순간 신분증이 광고로 나간다.
         */
        @Test
        @DisplayName("용도가 매물 사진이 아닌 파일은 붙지 않는다")
        void refuses_a_file_with_another_purpose() {
            Listing saved = existing();
            given(listingRepository.save(any())).willReturn(saved);
            givenFile("f-x", 99L, FilePurpose.VERIFICATION_DOC, 10L);

            assertThatThrownBy(() -> service.create("u-1", requestWithImages(List.of("f-x"))))
                    .isInstanceOf(ListingException.class)
                    .satisfies(e -> assertThat(((ListingException) e).getErrorCode().getCode())
                            .isEqualTo("LISTING_IMAGE_INVALID"));
            verify(imageRepository, never()).save(any());
        }

        // 업로더 축이 없으면 남이 올린 사진을 자기 광고에 붙일 수 있다.
        @Test
        @DisplayName("남이 올린 파일은 붙지 않는다")
        void refuses_someone_elses_file() {
            Listing saved = existing();
            given(listingRepository.save(any())).willReturn(saved);
            givenFile("f-y", 98L, FilePurpose.LISTING_IMAGE, 77L);

            assertThatThrownBy(() -> service.create("u-1", requestWithImages(List.of("f-y"))))
                    .isInstanceOf(ListingException.class);
            verify(imageRepository, never()).save(any());
        }

        /*
         * ★★ **범위 축은 사무소다**(`ListingAccessPolicy` 가 못 박았다 — 올린 사람이 아니다).
         *    자식 전량 교체라 수정은 기존 사진 uuid 를 다시 보내는데, 그 파일의 uploader 는
         *    **처음 올린 사람**이다. 편집자와 같은지 물으면 ① 같은 사무소 동료가 올린 사진이
         *    붙은 매물을 아무도 수정할 수 없고 ② 대표자가 교체되면 그 사무소 옛 매물이 통째로
         *    수정 불가가 된다. 사진을 빼도 게시 관문이 막아 **어느 쪽으로도 막힌다**
         *    (적대적 리뷰 2차 2026-10-06 — 파일 3중 검증을 넣으며 만든 구멍).
         */
        @Test
        @DisplayName("이미 붙어 있던 사진은 다른 계정이 수정해도 통과한다 — 범위는 사무소다")
        void already_attached_photos_survive_an_edit_by_a_colleague() {
            Listing listing = existing();
            // 사진은 **다른 사람**(userId 77)이 올렸고 이미 이 매물에 붙어 있다.
            givenFile("f-old", 11L, FilePurpose.LISTING_IMAGE, 77L);
            given(imageRepository.findByListingIdOrderBySortOrderAsc(1L)).willReturn(
                    List.of(ListingImage.builder().listingId(1L).fileId(11L).sortOrder(0).build()));
            given(storedFileRepository.findByIdInAndDeletedAtIsNull(List.of(11L)))
                    .willReturn(List.of(storedFile("f-old", 11L,
                            FilePurpose.LISTING_IMAGE, 77L)));

            service.update("u-1", "l-1", requestWithImages(List.of("f-old")));

            ArgumentCaptor<ListingImage> captor = ArgumentCaptor.forClass(ListingImage.class);
            verify(imageRepository).save(captor.capture());
            assertThat(captor.getValue().getFileId()).isEqualTo(11L);
        }

        // 새로 붙이는 파일은 여전히 자기 것이어야 한다 — 범위를 넓히는 것이 아니다.
        @Test
        @DisplayName("새로 붙이는 사진은 여전히 자기 것이어야 한다")
        void newly_attached_photos_still_require_ownership() {
            existing();
            givenFile("f-new", 12L, FilePurpose.LISTING_IMAGE, 77L);
            given(imageRepository.findByListingIdOrderBySortOrderAsc(1L)).willReturn(List.of());

            assertThatThrownBy(() -> service.update("u-1", "l-1", requestWithImages(List.of("f-new"))))
                    .isInstanceOf(ListingException.class)
                    .satisfies(e -> assertThat(((ListingException) e).getErrorCode().getCode())
                            .isEqualTo("LISTING_IMAGE_INVALID"));
        }

        @Test
        @DisplayName("없는 파일 uuid 는 붙지 않는다")
        void refuses_an_unknown_file() {
            Listing saved = existing();
            given(listingRepository.save(any())).willReturn(saved);

            assertThatThrownBy(() -> service.create("u-1", requestWithImages(List.of("f-none"))))
                    .isInstanceOf(ListingException.class);
        }

        /*
         * ★★ 광고는 허위매물 분쟁의 증거다. 차단·완료·종료된 매물의 내용이 사후에 바뀌면
         *    "그때 뭐라고 적혀 있었나"를 답할 수 없다. 가드가 없던 동안
         *    LISTING_NOT_EDITABLE 은 한 번도 던져지지 않는 죽은 코드였다.
         */
        @Test
        @DisplayName("차단·완료·종료된 매물은 수정할 수 없다")
        void refuses_to_edit_a_record_state() {
            for (ListingStatus blocked : new ListingStatus[]{
                    ListingStatus.BLOCKED, ListingStatus.COMPLETED, ListingStatus.ARCHIVED}) {
                Listing listing = existing();
                forceStatus(listing, blocked);
                assertThatThrownBy(() -> service.update("u-1", "l-1", request(3_000_000L, 300_000L)))
                        .as("%s", blocked)
                        .isInstanceOf(ListingException.class)
                        .satisfies(e -> assertThat(((ListingException) e).getErrorCode().getCode())
                                .isEqualTo("LISTING_NOT_EDITABLE"));
            }
        }

    }

    @Nested
    @DisplayName("게시 관문은 수정으로 우회되지 않는다")
    class PublishGateSurvivesUpdate {

        @Test
        @DisplayName("공개 중인 매물을 수정하면 게시 조건을 다시 검사한다")
        void public_listing_is_revalidated_on_update() {
            Listing listing = existing();
            listing.publish(NOW, NOW.plusMonths(1));
            willThrow(new ListingException(ListingErrorCode.PUBLISH_PHOTO_REQUIRED))
                    .given(publishPolicy).requirePublishable(listing);

            assertThatThrownBy(() -> service.update("u-1", "l-1", request(3_000_000L, 300_000L)))
                    .isInstanceOf(ListingException.class)
                    .satisfies(e -> assertThat(((ListingException) e).getErrorCode().getCode())
                            .isEqualTo("PUBLISH_PHOTO_REQUIRED"));
        }

        /*
         * ★★ **연장도 게시다.** publish 에서 EXPIRED 를 빼자 renew 가 공개로 돌아오는
         *    유일한 경로가 됐는데 거기에 관문이 없었다 — 만료된 사이에 사진·설명을 비우고
         *    연장하면 빈 광고가 공개로 돌아온다. 광고 기한 규칙상 모든 매물이 한 달마다 EXPIRED 를
         *    지나므로 특수 상태가 아니다(적대적 리뷰 2026-10-06 — publish 를 좁힐 때 생긴 구멍).
         */
        @Test
        @DisplayName("연장할 때도 게시 조건을 다시 검사한다 — EXPIRED 경유 우회를 막는다")
        void renew_revalidates_the_publish_gate() {
            Listing listing = existing();
            forceStatus(listing, ListingStatus.EXPIRED);
            willThrow(new ListingException(ListingErrorCode.PUBLISH_PHOTO_REQUIRED))
                    .given(publishPolicy).requirePublishable(listing);

            assertThatThrownBy(() -> service.renew("u-1", "l-1"))
                    .isInstanceOf(ListingException.class)
                    .satisfies(e -> assertThat(((ListingException) e).getErrorCode().getCode())
                            .isEqualTo("PUBLISH_PHOTO_REQUIRED"));
            assertThat(listing.getStatus()).isEqualTo(ListingStatus.EXPIRED);
        }

        // 공개 전에는 느슨하다 — 중개사가 현장에서 나눠 채우는 흐름이 정상이다.
        @Test
        @DisplayName("DRAFT 는 수정할 때 게시 조건을 묻지 않는다")
        void draft_is_not_revalidated() {
            existing();   // DRAFT 상태로 돌려준다

            service.update("u-1", "l-1", request(3_000_000L, 300_000L));

            verify(publishPolicy, never()).requirePublishable(any());
        }
    }

    @Nested
    @DisplayName("가격 이력")
    class PriceHistory {

        /*
         * ★★ 「온라인 부동산 광고 자율규약」이 "가격이 사실과 다른 매물"을 거짓매물로 본다.
         *     신고가 오면 이 이력이 **유일한 답변 근거**다 — 안 남으면 답할 수 없다.
         */
        @Test
        @DisplayName("가격이 바뀌면 그 시각의 가격이 기록된다")
        void records_when_price_changes() {
            Listing listing = existing();
            given(priceHistoryRepository.findTopByListingIdOrderByChangedAtDesc(1L))
                    .willReturn(Optional.of(history(3_000_000L, 300_000L)));

            service.update("u-1", "l-1", request(5_000_000L, 400_000L));

            ArgumentCaptor<ListingPriceHistory> saved = ArgumentCaptor.forClass(ListingPriceHistory.class);
            verify(priceHistoryRepository).save(saved.capture());
            assertThat(saved.getValue().getDeposit()).isEqualTo(5_000_000L);
            assertThat(saved.getValue().getMonthlyRent()).isEqualTo(400_000L);
            assertThat(saved.getValue().getChangedBy()).isEqualTo(10L);
            assertThat(listing.getDeposit()).isEqualTo(5_000_000L);
        }

        // 같은 값을 쌓으면 이력이 노이즈가 되어 정작 바뀐 지점을 못 찾는다.
        @Test
        @DisplayName("가격이 그대로면 기록하지 않는다")
        void skips_when_price_unchanged() {
            existing();
            given(priceHistoryRepository.findTopByListingIdOrderByChangedAtDesc(1L))
                    .willReturn(Optional.of(history(3_000_000L, 300_000L)));

            service.update("u-1", "l-1", request(3_000_000L, 300_000L));

            verify(priceHistoryRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("표시 좌표")
    class DisplayCoordinates {

        /*
         * ★ 수정할 때마다 다시 흐리면 **같은 매물이 지도에서 움직인다** —
         *   이용자는 그걸 버그로 읽고, 매물을 다시 찾지 못한다.
         */
        @Test
        @DisplayName("좌표가 그대로면 흐린 좌표를 다시 만들지 않는다")
        void keeps_display_coordinates_when_location_unchanged() {
            existing();
            service.update("u-1", "l-1", request(3_000_000L, 300_000L));

            verify(coordinateGenerator, never()).obfuscate(any(), any());
        }

        @Test
        @DisplayName("좌표가 바뀌면 다시 흐린다")
        void regenerates_when_location_moves() {
            Listing listing = existing();
            given(coordinateGenerator.obfuscate(any(), any()))
                    .willReturn(new BigDecimal[]{new BigDecimal("37.5000000"), new BigDecimal("127.0000000")});

            service.update("u-1", "l-1", requestAt(new BigDecimal("37.4900000"), LNG));

            verify(coordinateGenerator).obfuscate(any(), any());
            assertThat(listing.getDisplayLat()).isEqualByComparingTo("37.5000000");
        }
    }

    /*
     * 낡은 폼 감지. PUT 은 옵션 · 사진 · 관리비를 통째로 갈아 끼우므로, 폼을 연 뒤 바뀐 매물을 그대로 덮으면
     * 다른 기기에서 넣은 사진 · 관리비가 소리 없이 사라진다. 실제 잠금 · 증가는 실 MySQL 테스트가 본다 — 여기서는 순서 · 배선.
     */
    @Nested
    @DisplayName("version — 낡은 폼의 저장")
    class Version {

        private Listing existingAt(long version) {
            Listing listing = existing();
            ReflectionTestUtils.setField(listing, "version", version);
            return listing;
        }

        @Test
        @DisplayName("본문 version 이 지금과 다르면 409 CONFLICT — 가격 · 자식 행 · 잠금 어느 것도 건드리지 않는다")
        void stale_version_is_rejected_before_anything_changes() {
            Listing listing = existingAt(4L);

            assertThatThrownBy(() -> service.update("u-1", "l-1", requestWithVersion(3L)))
                    .isInstanceOf(ListingException.class)
                    .satisfies(e -> assertThat(((ListingException) e).getErrorCode().getCode()).isEqualTo("CONFLICT"));

            assertThat(listing.getDeposit()).isEqualTo(3_000_000L);
            verify(priceHistoryRepository, never()).save(any());
            verify(imageRepository, never()).deleteByListingId(any());
            verify(entityManager, never()).lock(any(), any());
        }

        // 자식만 바뀐 저장은 listings 행이 더티가 아니라 version 이 안 오른다 — 강제로 올려야 다른 기기가 알아챈다.
        @Test
        @DisplayName("같으면 저장하고 version 을 강제로 올린다(자식만 바뀐 저장도)")
        void matching_version_saves_and_forces_increment() {
            Listing listing = existingAt(4L);

            service.update("u-1", "l-1", requestWithVersion(4L));

            verify(entityManager).lock(listing, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
        }

        @Test
        @DisplayName("version 을 비우면 검사 없이 저장한다(옛 앱) — 그래도 version 은 올린다")
        void missing_version_skips_the_check() {
            Listing listing = existingAt(4L);

            service.update("u-1", "l-1", requestWithVersion(null));

            verify(entityManager).lock(listing, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
        }

        // 차단 · 거래 완료 · 종료는 그 코드가 더 정확하다 — 다시 불러와도 고칠 수 없다.
        @Test
        @DisplayName("고칠 수 없는 상태면 version 이 달라도 LISTING_NOT_EDITABLE 이 먼저다")
        void not_editable_wins_over_conflict() {
            Listing listing = existingAt(4L);
            forceStatus(listing, ListingStatus.BLOCKED);

            assertThatThrownBy(() -> service.update("u-1", "l-1", requestWithVersion(3L)))
                    .satisfies(e -> assertThat(((ListingException) e).getErrorCode().getCode())
                            .isEqualTo("LISTING_NOT_EDITABLE"));
        }

        // 낡은 폼이면 400 보다 409 가 맞다 — 다시 불러오면 그 400 이 사라질 수도 있다.
        @Test
        @DisplayName("version 비교가 입력 검증보다 먼저다")
        void conflict_comes_before_validation() {
            existingAt(4L);
            BrokerListingSaveRequest r = requestWithVersion(3L);
            BrokerListingSaveRequest invalid = withFloorAndBand(r);

            assertThatThrownBy(() -> service.update("u-1", "l-1", invalid))
                    .satisfies(e -> assertThat(((ListingException) e).getErrorCode().getCode()).isEqualTo("CONFLICT"));
        }

        @Test
        @DisplayName("등록은 version 을 무시한다")
        void create_ignores_version() {
            Listing saved = existing();
            given(listingRepository.save(any())).willReturn(saved);

            service.create("u-1", requestWithVersion(999L));

            verify(listingRepository).save(any());
            verify(entityManager, never()).lock(any(), any());
        }

        private void assertFlushedBeforeAssembling() {
            InOrder order = inOrder(entityManager, itemRepository);
            order.verify(entityManager).flush();
            order.verify(itemRepository).findByListingId(1L);
        }

        // 상태 전이는 assemble 전에 flush 가 없었다 — 응답 version 이 커밋 뒤 값보다 한 칸 낮으면 다음 저장이 거짓 409 다.
        // 전이 여섯 모두 — 하나라도 assemble 로 되돌리면 걸린다.
        @Test
        @DisplayName("예약 응답은 flush 뒤에 조립한다 — 응답 version 이 커밋 뒤 값이다")
        void reserve_flushes_before_assembling() {
            forceStatus(existingAt(4L), ListingStatus.ACTIVE);
            service.reserve("u-1", "l-1");
            assertFlushedBeforeAssembling();
        }

        @Test
        @DisplayName("예약 해제 응답도 flush 뒤에 조립한다")
        void unreserve_flushes_before_assembling() {
            forceStatus(existingAt(4L), ListingStatus.RESERVED);
            service.unreserve("u-1", "l-1");
            assertFlushedBeforeAssembling();
        }

        @Test
        @DisplayName("게시 응답도 flush 뒤에 조립한다")
        void publish_flushes_before_assembling() {
            existingAt(4L);
            service.publish("u-1", "l-1");
            assertFlushedBeforeAssembling();
        }

        @Test
        @DisplayName("내리기 응답도 flush 뒤에 조립한다")
        void unpublish_flushes_before_assembling() {
            forceStatus(existingAt(4L), ListingStatus.ACTIVE);
            service.unpublish("u-1", "l-1");
            assertFlushedBeforeAssembling();
        }

        @Test
        @DisplayName("거래 완료 응답도 flush 뒤에 조립한다")
        void complete_flushes_before_assembling() {
            forceStatus(existingAt(4L), ListingStatus.ACTIVE);
            service.complete("u-1", "l-1");
            assertFlushedBeforeAssembling();
        }

        @Test
        @DisplayName("연장 응답도 flush 뒤에 조립한다")
        void renew_flushes_before_assembling() {
            Listing listing = existingAt(4L);
            forceStatus(listing, ListingStatus.EXPIRED);
            ReflectionTestUtils.setField(listing, "expiresAt", NOW.minusDays(1));
            service.renew("u-1", "l-1");
            assertFlushedBeforeAssembling();
        }
    }

    /*
     * 빠진 사진은 그 자리에서 폐기 표시 — 안 하면 R2 에 영구로 남고 공개 URL 이라 주소를 가진 사람은 계속 본다.
     * 같은 파일이 다른 매물에 붙어 있으면 남긴다(file_id 는 유일하지 않다).
     */
    @Nested
    @DisplayName("빠진 사진 폐기 표시")
    class DetachedPhotos {

        private void attached(long... fileIds) {
            List<ListingImage> rows = new ArrayList<>();
            for (int i = 0; i < fileIds.length; i++) {
                rows.add(ListingImage.builder().listingId(1L).fileId(fileIds[i]).sortOrder(i).build());
            }
            given(imageRepository.findByListingIdOrderBySortOrderAsc(1L)).willReturn(rows);
        }

        @Test
        @DisplayName("수정에서 뺀 사진만 폐기 표시하고, 남긴 사진은 건드리지 않는다")
        void edit_discards_only_the_removed_photo() {
            existing();
            attached(11L, 22L);
            givenFile("f-11", 11L, FilePurpose.LISTING_IMAGE, 10L);
            given(storedFileRepository.findByIdInAndDeletedAtIsNull(any())).willReturn(List.of(
                    storedFile("f-11", 11L, FilePurpose.LISTING_IMAGE, 10L),
                    storedFile("f-22", 22L, FilePurpose.LISTING_IMAGE, 10L)));
            given(imageRepository.findAttachedElsewhereForShare(any(), eq(1L))).willReturn(List.of());

            service.update("u-1", "l-1", requestWithImages(List.of("f-11")));

            verify(fileDeletionService).discardByIds(Set.of(22L));
            // 파일 행 잠금 → 잠금 읽기 → 폐기 순서여야 그사이 다른 매물에 붙인 저장과 줄 선다.
            InOrder order = inOrder(storedFileRepository, imageRepository, fileDeletionService);
            order.verify(storedFileRepository).findAllByIdForUpdate(Set.of(22L));
            order.verify(imageRepository).findAttachedElsewhereForShare(Set.of(22L), 1L);
            order.verify(fileDeletionService).discardByIds(Set.of(22L));
        }

        @Test
        @DisplayName("다른 매물에도 붙은 사진은 한쪽에서 빼도 남긴다")
        void keeps_a_photo_attached_elsewhere() {
            existing();
            attached(22L);
            given(storedFileRepository.findByIdInAndDeletedAtIsNull(any())).willReturn(List.of(
                    storedFile("f-22", 22L, FilePurpose.LISTING_IMAGE, 10L)));
            given(imageRepository.findAttachedElsewhereForShare(any(), eq(1L))).willReturn(List.of(
                    ListingImage.builder().listingId(2L).fileId(22L).sortOrder(0).build()));

            service.update("u-1", "l-1", requestWithImages(List.of()));

            verify(fileDeletionService, never()).discardByIds(any());
        }

        @Test
        @DisplayName("사진을 바꾸지 않은 저장은 폐기를 부르지 않는다")
        void unchanged_photos_discard_nothing() {
            existing();
            attached(11L);
            givenFile("f-11", 11L, FilePurpose.LISTING_IMAGE, 10L);
            given(storedFileRepository.findByIdInAndDeletedAtIsNull(any())).willReturn(List.of(
                    storedFile("f-11", 11L, FilePurpose.LISTING_IMAGE, 10L)));

            service.update("u-1", "l-1", requestWithImages(List.of("f-11")));

            verify(fileDeletionService, never()).discardByIds(any());
        }

        @Test
        @DisplayName("초안을 지우면 그 사진을 폐기 표시한다 — 다른 매물에 붙은 것은 남긴다")
        void deleting_a_draft_discards_its_photos() {
            Listing listing = existing();
            attached(11L, 22L);
            given(imageRepository.findAttachedElsewhereForShare(any(), eq(1L))).willReturn(List.of(
                    ListingImage.builder().listingId(2L).fileId(22L).sortOrder(0).build()));

            service.delete("u-1", "l-1");

            verify(listingRepository).delete(listing);
            verify(fileDeletionService).discardByIds(Set.of(11L));
        }
    }

    @Nested
    @DisplayName("입력 검증")
    class Validation {

        // 둘 다 받으면 "층을 숨겼다"는 의사가 모호해진다.
        @Test
        @DisplayName("정확한 층과 밴드를 같이 보내면 거절한다")
        void rejects_both_floor_and_band() {
            existing();
            assertThatThrownBy(() -> service.update("u-1", "l-1",
                    requestWithFloor(5, FloorBand.MID)))
                    .isInstanceOf(ListingException.class)
                    .satisfies(e -> assertThat(((ListingException) e).getErrorCode().getCode())
                            .isEqualTo("FLOOR_INPUT_INVALID"));
        }

        // 다방은 안 막아서 전용 23m² / 공급 316.8m² 인 매물이 떠 있다(실측).
        @Test
        @DisplayName("공급면적이 전용면적보다 작으면 거절한다")
        void rejects_inverted_area() {
            existing();
            assertThatThrownBy(() -> service.update("u-1", "l-1",
                    requestWithAreas(new BigDecimal("30"), new BigDecimal("20"))))
                    .isInstanceOf(ListingException.class)
                    .satisfies(e -> assertThat(((ListingException) e).getErrorCode().getCode())
                            .isEqualTo("AREA_RANGE_INVALID"));
        }

        // 운영 DB 의 uq_lmi 는 H2 에 없다(엔티티로 테이블을 만든다) — 그래서 저장 전 검증으로 막고 여기서 고정한다.
        @Test
        @DisplayName("관리비 세부 내역에 같은 비목이 두 번이면 400 INVALID_REQUEST — 아무것도 저장하지 않는다")
        void rejects_duplicated_maintenance_item() {
            assertThatThrownBy(() -> service.create("u-1",
                    requestWithItems(MaintenanceItemCode.WATER, MaintenanceItemCode.WATER)))
                    .isInstanceOf(ListingException.class)
                    .satisfies(e -> assertThat(((ListingException) e).getErrorCode().getCode())
                            .isEqualTo("INVALID_REQUEST"));

            verify(listingRepository, never()).save(any());
            verify(itemRepository, never()).save(any());
        }

        @Test
        @DisplayName("수정도 같은 비목 두 번은 거절하고 가격 · 자식 행을 건드리지 않는다")
        void rejects_duplicated_maintenance_item_on_update() {
            existing();
            assertThatThrownBy(() -> service.update("u-1", "l-1",
                    requestWithItems(MaintenanceItemCode.GAS, MaintenanceItemCode.WATER, MaintenanceItemCode.GAS)))
                    .isInstanceOf(ListingException.class);

            verify(priceHistoryRepository, never()).save(any());
            verify(itemRepository, never()).save(any());
        }

        @Test
        @DisplayName("비목이 서로 다르면 통과한다")
        void accepts_distinct_maintenance_items() {
            Listing saved = existing();
            given(listingRepository.save(any())).willReturn(saved);

            service.create("u-1", requestWithItems(MaintenanceItemCode.WATER, MaintenanceItemCode.GAS));

            verify(itemRepository, times(2)).save(any());
        }
    }

    /*
     * ★ 공개 계약은 법정동코드, 저장은 id 다(클라이언트가 얻을 수 있는 값이 code 뿐이다). 그 변환이 서비스에서 빠지면 중개사가 지역을 바꿔 저장해도
     *   200 이 오는데 DB 와 응답은 옛 지역 그대로이고, 등록이 지역을 버리면 모든 매물이 게시에서 막힌다.
     *   리졸버 규칙 자체는 ListingRegionResolverTest 가 고정한다 — 여기서는 **배선**만 본다.
     */
    @Nested
    @DisplayName("지역 배선 — 코드로 받고 id 로 저장하고 코드로 돌려준다")
    class RegionWiring {

        @Test
        @DisplayName("등록은 리졸버가 주소 · 보낸 코드로 정한 id 로 저장한다")
        void create_stores_the_resolved_region() {
            given(regionResolver.resolveIdForSave("11620", null, BASE_JIBUN, null, null)).willReturn(38L);
            given(listingRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            service.create("u-1", requestInRegion("11620"));

            ArgumentCaptor<Listing> saved = ArgumentCaptor.forClass(Listing.class);
            verify(listingRepository).save(saved.capture());
            assertThat(saved.getValue().getRegionId()).isEqualTo(38L);
        }

        @Test
        @DisplayName("수정은 지금 지역과 함께 리졸버에 묻고, 그 답으로 바꾼다")
        void update_stores_the_resolved_region() {
            Listing listing = existing();
            given(regionResolver.resolveIdForSave("11110", null, BASE_JIBUN, null, null)).willReturn(18L);

            service.update("u-1", "l-1", requestInRegion("11110"));

            assertThat(listing.getRegionId()).isEqualTo(18L);
        }

        @Test
        @DisplayName("응답의 regionCode 는 저장된 id 를 코드로 되돌린 값이다")
        void response_carries_the_code() {
            Listing listing = existing();
            given(regionResolver.resolveIdForSave("11620", null, BASE_JIBUN, null, null)).willReturn(38L);
            given(regionResolver.codeOf(38L)).willReturn("11620");

            BrokerListingResponse out = service.update("u-1", "l-1", requestInRegion("11620"));

            assertThat(out.regionCode()).isEqualTo("11620");
            assertThat(listing.getRegionId()).isEqualTo(38L);
        }

        // 주소로 못 가르는 매물이 닫힌 지역에 있을 때 같은 코드를 되보내면 그대로 둬야 한다 — 그 판정에 지금 지역이 필요하다.
        // 지금 지역 대신 null 을 넘기면 닫힌 지역 매물의 가격 하나 고치기도 LISTING_REGION_INVALID 가 된다.
        @Test
        @DisplayName("수정은 저장된 매물의 지금 지역을 리졸버에 넘긴다")
        void update_passes_the_current_region() {
            Listing listing = existing();
            ReflectionTestUtils.setField(listing, "regionId", 20L);
            given(regionResolver.resolveIdForSave("11620", null, BASE_JIBUN, null, 20L)).willReturn(20L);

            service.update("u-1", "l-1", requestInRegion("11620"));

            verify(regionResolver).resolveIdForSave("11620", null, BASE_JIBUN, null, 20L);
            assertThat(listing.getRegionId()).isEqualTo(20L);
        }

        // 앱은 이 값으로 지역 칸을 잠근다 — 저장된 매물의 주소로 묻지 않으면 주소를 바꾼 직후의 응답이 옛 판정을 싣는다.
        @Test
        @DisplayName("응답의 regionFromAddress 는 저장된 매물의 주소로 판정한다")
        void response_says_whether_the_address_decides_the_region() {
            existing();
            given(regionResolver.decidedByAddress(null, BASE_JIBUN, null)).willReturn(true);

            assertThat(service.update("u-1", "l-1", requestInRegion("11620")).regionFromAddress()).isTrue();

            given(regionResolver.decidedByAddress(null, BASE_JIBUN, null)).willReturn(false);
            assertThat(service.update("u-1", "l-1", requestInRegion("11620")).regionFromAddress()).isFalse();
        }

        // 게시의 주 진입점이 관문을 부르지 않으면 지역 없는 초안·사진 0장 초안이 그대로 공개된다.
        @Test
        @DisplayName("게시는 게시 관문을 그 매물로 부른다")
        void publish_runs_the_gate() {
            Listing listing = existing();
            forceStatus(listing, ListingStatus.HIDDEN);

            service.publish("u-1", "l-1");

            verify(publishPolicy).requirePublishable(listing);
        }
    }

    /*
     * ★★ 서비스 → 접근 정책 **배선**을 박는다. 정책의 판정은 ListingAccessPolicyTest 가 고정하지만,
     *    이 클래스는 정책 목을 "통과"로만 세워 두어서 **서비스가 검사를 빼먹어도 아무것도 깨지지 않았다.**
     *    조작마다 따로 본다 — 하나로 뭉치면 어느 조작이 검사를 잃었는지 보이지 않는다.
     */
    @Nested
    @DisplayName("소유 검사 배선 — 다른 사무소 매물이면 아무것도 바뀌지 않는다")
    class OwnershipWiring {

        private Listing listing;

        @BeforeEach
        void denyOwnership() {
            listing = existing();
            forceStatus(listing, ListingStatus.ACTIVE);
            willThrow(new ListingException(ListingErrorCode.LISTING_ACCESS_DENIED))
                    .given(accessPolicy).requireOwnership(any(), eq(listing));
        }

        @Test
        @DisplayName("열어 볼 수 없다")
        void get() {
            assertDenied(() -> service.get("u-1", "l-1"));
        }

        @Test
        @DisplayName("고칠 수 없다 — 가격이 그대로다")
        void update() {
            assertDenied(() -> service.update("u-1", "l-1", request(5_000_000L, 500_000L)));
            assertThat(listing.getDeposit()).isEqualTo(3_000_000L);
            verify(priceHistoryRepository, never()).save(any());
        }

        @Test
        @DisplayName("내릴 수 없다")
        void unpublish() {
            assertDenied(() -> service.unpublish("u-1", "l-1"));
            assertThat(listing.getStatus()).isEqualTo(ListingStatus.ACTIVE);
        }

        @Test
        @DisplayName("거래완료로 바꿀 수 없다")
        void complete() {
            assertDenied(() -> service.complete("u-1", "l-1"));
            assertThat(listing.getStatus()).isEqualTo(ListingStatus.ACTIVE);
        }

        @Test
        @DisplayName("게시할 수 없다 — 게시 관문까지 가지 않는다")
        void publish() {
            forceStatus(listing, ListingStatus.HIDDEN);
            assertDenied(() -> service.publish("u-1", "l-1"));
            assertThat(listing.getStatus()).isEqualTo(ListingStatus.HIDDEN);
            verify(publishPolicy, never()).requirePublishable(any());
        }

        @Test
        @DisplayName("연장할 수 없다")
        void renew() {
            forceStatus(listing, ListingStatus.EXPIRED);
            assertDenied(() -> service.renew("u-1", "l-1"));
            assertThat(listing.getStatus()).isEqualTo(ListingStatus.EXPIRED);
        }

        @Test
        @DisplayName("지울 수 없다")
        void delete() {
            forceStatus(listing, ListingStatus.DRAFT);
            assertDenied(() -> service.delete("u-1", "l-1"));
            verify(listingRepository, never()).delete(any());
        }

        private void assertDenied(ThrowableAssert.ThrowingCallable call) {
            assertThatThrownBy(call).isInstanceOf(ListingException.class)
                    .satisfies(e -> assertThat(((ListingException) e).getErrorCode().getCode())
                            .isEqualTo("LISTING_ACCESS_DENIED"));
        }
    }

    /*
     * ★ 정지·미승인 사무소는 **새 광고를 내는 길**(등록·게시·연장)만 막힌다.
     *   내리기·거래완료는 막지 않는다 — 정지된 사무소가 광고를 거두지도 못하면 그게 더 나쁘다.
     */
    @Nested
    @DisplayName("광고 가능 사무소 배선 — 정지된 사무소는 새 광고를 못 낸다")
    class OfficeGateWiring {

        @BeforeEach
        void suspendOffice() {
            given(accessPolicy.requireAdvertisableOffice(10L))
                    .willThrow(new ListingException(ListingErrorCode.OFFICE_NOT_ACTIVE));
        }

        @Test
        @DisplayName("등록할 수 없다 — 아무것도 저장되지 않는다")
        void create() {
            assertSuspended(() -> service.create("u-1", request(3_000_000L, 300_000L)));
            verify(listingRepository, never()).save(any());
        }

        @Test
        @DisplayName("게시할 수 없다")
        void publish() {
            Listing listing = existing();
            forceStatus(listing, ListingStatus.HIDDEN);
            assertSuspended(() -> service.publish("u-1", "l-1"));
            assertThat(listing.getStatus()).isEqualTo(ListingStatus.HIDDEN);
        }

        @Test
        @DisplayName("연장할 수 없다")
        void renew() {
            Listing listing = existing();
            forceStatus(listing, ListingStatus.EXPIRED);
            assertSuspended(() -> service.renew("u-1", "l-1"));
            assertThat(listing.getStatus()).isEqualTo(ListingStatus.EXPIRED);
        }

        @Test
        @DisplayName("내리기는 된다 — 정지돼도 광고를 거둘 수는 있어야 한다")
        void unpublish_still_works() {
            Listing listing = existing();
            forceStatus(listing, ListingStatus.ACTIVE);
            service.unpublish("u-1", "l-1");
            assertThat(listing.getStatus()).isEqualTo(ListingStatus.HIDDEN);
        }

        private void assertSuspended(ThrowableAssert.ThrowingCallable call) {
            assertThatThrownBy(call).isInstanceOf(ListingException.class)
                    .satisfies(e -> assertThat(((ListingException) e).getErrorCode().getCode())
                            .isEqualTo("OFFICE_NOT_ACTIVE"));
        }
    }

    /*
     * ★ 내 매물 목록은 **사람이 아니라 사무소**로 찾는다 — 동료가 올린 매물도 같이 관리한다.
     *   사용자 id(10)와 사무소 id(1)를 다른 수로 둬서 둘을 바꿔 끼우면 깨진다.
     */
    @Nested
    @DisplayName("내 매물 목록 — 사무소 단위")
    class FindMineScope {

        // ★ "전체"는 빈 IN 이 아니라 모든 상태다 — 빈 IN 은 벤더마다 다르게 깨진다.
        @Test
        @DisplayName("상태를 안 주면 프로필의 사무소 id 로 모든 상태를 찾는다")
        void all_statuses_by_office() {
            given(listingRepository.findByOfficeIdAndStatusIn(eq(1L), any(), any()))
                    .willReturn(Page.empty());

            service.findMine("u-1", null, null, PageRequest.of(0, 20));

            verify(listingRepository).findByOfficeIdAndStatusIn(
                    eq(1L), eq(EnumSet.allOf(ListingStatus.class)), any());
        }

        @Test
        @DisplayName("상태를 주면 같은 사무소 id 로 그 상태만 찾는다")
        void some_statuses_by_office() {
            List<ListingStatus> statuses = List.of(ListingStatus.ACTIVE);
            given(listingRepository.findByOfficeIdAndStatusIn(eq(1L), eq(statuses), any()))
                    .willReturn(Page.empty());

            service.findMine("u-1", statuses, null, PageRequest.of(0, 20));

            verify(listingRepository).findByOfficeIdAndStatusIn(eq(1L), eq(statuses), any());
        }

        // 지역 코드는 한 페이지에 한 번 묻는다 — 행마다 codeOf 를 부르면 페이지 크기만큼 쿼리가 는다(기능 테스트로는 안 보인다).
        @Test
        @DisplayName("지역 코드는 페이지 단위로 한 번에 묻고, 지역 없는 초안은 null 이다")
        void region_codes_in_one_batch() {
            Listing withRegion = Listing.builder().id(11L).uuid("l-11").regionId(20L).status(ListingStatus.ACTIVE).build();
            Listing noRegion = Listing.builder().id(12L).uuid("l-12").status(ListingStatus.DRAFT).build();
            given(listingRepository.findByOfficeIdAndStatusIn(eq(1L), any(), any()))
                    .willReturn(new PageImpl<>(List.of(withRegion, noRegion)));
            given(regionResolver.codesOf(any())).willReturn(Map.of(20L, "11620"));

            var out = service.findMine("u-1", null, null, PageRequest.of(0, 20));

            assertThat(out.content())
                    .extracting(BrokerListingSummaryResponse::regionCode)
                    .containsExactly("11620", null);
            verify(regionResolver, times(1)).codesOf(any());
            verify(regionResolver, never()).codeOf(any());
        }
    }

    /*
     * ★ 상태 전이는 쓰기 트랜잭션이어야 한다. 빠지면 클래스 기본값 readOnly 가 걸려 응답은 바뀐 상태인데 DB 는 그대로다.
     *   API 테스트는 바깥 @Transactional 에 참여해 readOnly 가 적용되지 않으므로 이 구멍을 못 본다 — 선언 자체를 고정한다.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"publish", "unpublish", "complete", "reserve", "unreserve", "renew"})
    @DisplayName("상태 전이 메서드는 읽기 전용이 아닌 트랜잭션이다")
    void transitions_are_writable_transactions(String method) throws Exception {
        Transactional tx = BrokerListingService.class
                .getMethod(method, String.class, String.class)
                .getAnnotation(Transactional.class);

        assertThat(tx).as(method).isNotNull();
        assertThat(tx.readOnly()).as(method).isFalse();
    }

    /*
     * ★ 정렬은 서버가 enum 에서 만든다. 모든 정렬 끝에 id 내림차순이 붙어야 한다 —
     *   동점에서 순서가 흔들리면 페이지를 넘길 때 같은 매물이 두 번 나오거나 사라진다.
     */
    @Nested
    @DisplayName("내 매물 목록 — 정렬")
    class FindMineSort {

        @Test
        @DisplayName("정렬을 안 주면 최근 바뀐 순(updatedAt 내림차순, 동점은 id 내림차순)이다")
        void default_is_updated_desc() {
            assertThat(sortFor(null)).isEqualTo(Sort.by(
                    Sort.Order.desc("updatedAt"),
                    Sort.Order.desc("id")));
        }

        @Test
        @DisplayName("최근 등록 · 최근 게시는 각자의 칼럼 내림차순이고 동점은 id 내림차순이다")
        void created_and_posted_sorts() {
            assertThat(sortFor(BrokerListingSort.CREATED_DESC)).isEqualTo(Sort.by(
                    Sort.Order.desc("createdAt"),
                    Sort.Order.desc("id")));
            assertThat(sortFor(BrokerListingSort.POSTED_DESC)).isEqualTo(Sort.by(
                    Sort.Order.desc("postedAt"),
                    Sort.Order.desc("id")));
        }

        @Test
        @DisplayName("정렬을 실어도 요청한 페이지 번호 · 크기는 그대로다")
        void keeps_page_and_size() {
            given(listingRepository.findByOfficeIdAndStatusIn(eq(1L), any(), any()))
                    .willReturn(Page.empty());

            service.findMine("u-1", null, BrokerListingSort.CREATED_DESC,
                    PageRequest.of(2, 5));

            ArgumentCaptor<Pageable> pageable =
                    ArgumentCaptor.forClass(Pageable.class);
            verify(listingRepository).findByOfficeIdAndStatusIn(eq(1L), any(), pageable.capture());
            assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
            assertThat(pageable.getValue().getPageSize()).isEqualTo(5);
        }

        // 만료 임박 순은 초안(만료 시각 없음)을 뒤로 보내야 해서 정렬이 박힌 저장소 쿼리로 간다 — Sort 를 실으면 ORDER BY 가 겹친다.
        @Test
        @DisplayName("만료 임박 순은 정렬이 박힌 저장소 쿼리로 가고, 거기엔 정렬을 싣지 않는다")
        void expires_asc_uses_dedicated_query() {
            given(listingRepository.findByOfficeIdAndStatusInExpiringFirst(eq(1L), any(), any()))
                    .willReturn(Page.empty());

            service.findMine("u-1", List.of(ListingStatus.EXPIRED), BrokerListingSort.EXPIRES_ASC,
                    PageRequest.of(0, 20));

            ArgumentCaptor<Pageable> pageable =
                    ArgumentCaptor.forClass(Pageable.class);
            verify(listingRepository).findByOfficeIdAndStatusInExpiringFirst(
                    eq(1L), eq(List.of(ListingStatus.EXPIRED)), pageable.capture());
            assertThat(pageable.getValue().getSort().isUnsorted()).isTrue();
            verify(listingRepository, never()).findByOfficeIdAndStatusIn(any(), any(), any());
        }

        private Sort sortFor(BrokerListingSort sort) {
            given(listingRepository.findByOfficeIdAndStatusIn(eq(1L), any(), any()))
                    .willReturn(Page.empty());
            service.findMine("u-1", null, sort, PageRequest.of(0, 20));
            ArgumentCaptor<Pageable> pageable =
                    ArgumentCaptor.forClass(Pageable.class);
            verify(listingRepository, atLeastOnce()).findByOfficeIdAndStatusIn(eq(1L), any(), pageable.capture());
            return pageable.getValue().getSort();
        }
    }

    @Nested
    @DisplayName("내 매물 상태별 개수")
    class CountMine {

        // 홈은 상태마다 숫자를 그린다 — 매물이 없는 상태도 0 으로 있어야 화면이 빈칸을 그리지 않는다.
        @Test
        @DisplayName("상태 8개가 0 이어도 전부 실리고, 있는 상태는 센 값이 들어간다")
        void every_status_is_present() {
            given(listingRepository.countByOfficeIdGroupByStatus(1L)).willReturn(List.of(
                    new ListingStatusCount(ListingStatus.ACTIVE, 12L),
                    new ListingStatusCount(ListingStatus.DRAFT, 2L)));

            var out = service.countMine("u-1");

            assertThat(out.byStatus()).containsOnlyKeys(ListingStatus.values());
            assertThat(out.byStatus().get(ListingStatus.ACTIVE)).isEqualTo(12L);
            assertThat(out.byStatus().get(ListingStatus.DRAFT)).isEqualTo(2L);
            assertThat(out.byStatus().get(ListingStatus.BLOCKED)).isZero();
        }

        /*
         * ★ "연장할 수 있는" 의 경계는 연장(renew)과 같아야 한다 — 만료 + 7일까지(같으면 포함).
         *   홈 숫자는 2 라는데 연장 버튼이 막히거나, 그 반대가 되면 안 된다.
         */
        @Test
        @DisplayName("연장 가능 수는 EXPIRED 중 만료 시각이 지금 - 7일 이후인 것만 센다 — 연장 판정과 같은 경계")
        void renewable_uses_the_renewal_window() {
            given(listingRepository.countByOfficeIdAndStatusAndExpiresAtGreaterThanEqual(
                    eq(1L), eq(ListingStatus.EXPIRED), any())).willReturn(2L);

            var out = service.countMine("u-1");

            assertThat(out.renewable()).isEqualTo(2L);
            verify(listingRepository).countByOfficeIdAndStatusAndExpiresAtGreaterThanEqual(
                    1L, ListingStatus.EXPIRED, NOW.minusDays(BrokerListingService.RENEWAL_GRACE_DAYS));
        }
    }

    // ★ 사진 · 옵션 · 관리비만 고친 수정은 매물 행이 안 바뀐다 — 그래도 "최근 바뀐 순"에 올라야 한다.
    @Test
    @DisplayName("수정을 저장하면 매물 행이 안 바뀌어도 updated_at 을 지금으로 찍는다")
    void update_touches_updated_at() {
        existing();

        service.update("u-1", "l-1", request(3_000_000L, 300_000L));

        verify(listingRepository).touchUpdatedAt(1L, NOW);
    }

    // ── 픽스처

    private static final String BASE_JIBUN = "서울특별시 관악구 신림동";

    private BrokerListingSaveRequest requestInRegion(String regionCode) {
        BrokerListingSaveRequest r = request(3_000_000L, 300_000L);
        return new BrokerListingSaveRequest(
                r.category(), r.roomType(), r.division(), r.duplex(), r.title(), r.description(),
                r.sellingType(), r.deposit(), r.monthlyRent(), r.exclusiveArea(), r.supplyArea(), r.contractArea(),
                r.floorType(), r.floor(), r.floorBand(), r.buildingFloor(), r.basementFloor(),
                r.direction(), r.directionBase(), r.heating(), r.entranceType(),
                r.canParking(), r.parkingNum(), r.hasElevator(), r.householdNum(), r.buildingApprovalDate(),
                r.moveInImmediate(), r.moveInDate(),
                r.residentRegistration(), r.depositInsurance(), r.shortLease(),
                r.lat(), r.lng(), regionCode, r.dongName(), r.addressJibun(), r.addressRoad(), r.showDetailAddress(),
                r.contactSmsNumber(), r.contactCallNumber(), r.maintenance(), r.optionCodes(), r.safetyCodes(),
                r.imageFileUuids(), null);
    }

    // 자기가 올린 매물 사진 파일 하나를 세운다 — 3중 검증(존재·용도·업로더)을 통과하는 모양.
    private void givenOwnListingImage(String uuid, Long id) {
        givenFile(uuid, id, FilePurpose.LISTING_IMAGE, 10L);
    }

    private StoredFile storedFile(
            String uuid, Long id,
            FilePurpose purpose, Long uploaderId) {
        return StoredFile.builder()
                .id(id).uuid(uuid).purpose(purpose).uploaderId(uploaderId)
                .storageKey("listing-images/" + uuid).contentType("image/jpeg").byteSize(1024L)
                .fileUrl("https://file.example.test/" + uuid + ".jpg")
                .build();
    }

    private void givenFile(String uuid, Long id,
                           FilePurpose purpose,
                           Long uploaderId) {
        StoredFile file =
                StoredFile.builder()
                        .id(id).uuid(uuid)
                        .purpose(purpose)
                        .uploaderId(uploaderId)
                        .storageKey("listing-images/" + uuid).contentType("image/jpeg").byteSize(1024L)
                        .fileUrl("https://file.example.test/" + uuid + ".jpg")
                        .build();
        lenient().when(storedFileRepository.findByUuidAndDeletedAtIsNull(uuid))
                .thenReturn(Optional.of(file));
        lenient().when(storedFileRepository.findForShareByUuid(uuid))
                .thenReturn(Optional.of(file));
    }

    private Listing existing() {
        Listing listing = Listing.builder()
                .id(1L).uuid("l-1").listingNo("LS-261005-ABC123")
                .officeId(1L).createdByUserId(10L)
                .category(ListingCategory.ONE_TWO_ROOM).roomType(RoomType.ONE_ROOM)
                .title("테스트").description("설명")
                .sellingType(SellingType.MONTHLY_RENT).deposit(3_000_000L).monthlyRent(300_000L)
                .exclusiveArea(new BigDecimal("26.44")).floorType(FloorType.GROUND)
                .residentRegistration(AvailabilityAnswer.ASK).depositInsurance(AvailabilityAnswer.ASK)
                .realLat(LAT).realLng(LNG)
                .displayLat(new BigDecimal("37.4862552")).displayLng(new BigDecimal("126.9332423"))
                .addressJibun("서울특별시 관악구 신림동").status(ListingStatus.DRAFT)
                .build();
        given(listingRepository.findByUuid("l-1")).willReturn(Optional.of(listing));
        return listing;
    }

    private ListingPriceHistory history(Long deposit, Long rent) {
        return ListingPriceHistory.builder().listingId(1L)
                .sellingType(SellingType.MONTHLY_RENT).deposit(deposit).monthlyRent(rent)
                .changedBy(10L).changedAt(NOW.minusDays(1)).build();
    }

    private BrokerListingSaveRequest request(Long deposit, Long rent) {
        return base(deposit, rent, LAT, LNG, null, null, new BigDecimal("26.44"), null);
    }

    private BrokerListingSaveRequest requestAt(BigDecimal lat, BigDecimal lng) {
        return base(3_000_000L, 300_000L, lat, lng, null, null, new BigDecimal("26.44"), null);
    }

    private BrokerListingSaveRequest requestWithFloor(Integer floor, FloorBand band) {
        return base(3_000_000L, 300_000L, LAT, LNG, floor, band, new BigDecimal("26.44"), null);
    }

    private BrokerListingSaveRequest requestWithAreas(BigDecimal exclusive, BigDecimal supply) {
        return base(3_000_000L, 300_000L, LAT, LNG, null, null, exclusive, supply);
    }

    private BrokerListingSaveRequest requestWithImages(List<String> fileUuids) {
        BrokerListingSaveRequest r = request(3_000_000L, 300_000L);
        return new BrokerListingSaveRequest(
                r.category(), r.roomType(), r.division(), r.duplex(), r.title(), r.description(),
                r.sellingType(), r.deposit(), r.monthlyRent(),
                r.exclusiveArea(), r.supplyArea(), r.contractArea(),
                r.floorType(), r.floor(), r.floorBand(), r.buildingFloor(), r.basementFloor(),
                r.direction(), r.directionBase(), r.heating(), r.entranceType(),
                r.canParking(), r.parkingNum(), r.hasElevator(), r.householdNum(),
                r.buildingApprovalDate(), r.moveInImmediate(), r.moveInDate(),
                r.residentRegistration(), r.depositInsurance(), r.shortLease(),
                r.lat(), r.lng(), r.regionCode(), r.dongName(), r.addressJibun(), r.addressRoad(),
                r.showDetailAddress(), r.contactSmsNumber(), r.contactCallNumber(),
                r.maintenance(), r.optionCodes(), r.safetyCodes(), fileUuids, null);
    }

    private BrokerListingSaveRequest requestWithVersion(Long version) {
        BrokerListingSaveRequest r = request(3_000_000L, 300_000L);
        return new BrokerListingSaveRequest(
                r.category(), r.roomType(), r.division(), r.duplex(), r.title(), r.description(),
                r.sellingType(), r.deposit(), r.monthlyRent(),
                r.exclusiveArea(), r.supplyArea(), r.contractArea(),
                r.floorType(), r.floor(), r.floorBand(), r.buildingFloor(), r.basementFloor(),
                r.direction(), r.directionBase(), r.heating(), r.entranceType(),
                r.canParking(), r.parkingNum(), r.hasElevator(), r.householdNum(),
                r.buildingApprovalDate(), r.moveInImmediate(), r.moveInDate(),
                r.residentRegistration(), r.depositInsurance(), r.shortLease(),
                r.lat(), r.lng(), r.regionCode(), r.dongName(), r.addressJibun(), r.addressRoad(),
                r.showDetailAddress(), r.contactSmsNumber(), r.contactCallNumber(),
                r.maintenance(), r.optionCodes(), r.safetyCodes(), r.imageFileUuids(), version);
    }

    private BrokerListingSaveRequest withFloorAndBand(BrokerListingSaveRequest r) {
        return new BrokerListingSaveRequest(
                r.category(), r.roomType(), r.division(), r.duplex(), r.title(), r.description(),
                r.sellingType(), r.deposit(), r.monthlyRent(),
                r.exclusiveArea(), r.supplyArea(), r.contractArea(),
                r.floorType(), 5, FloorBand.MID, r.buildingFloor(), r.basementFloor(),
                r.direction(), r.directionBase(), r.heating(), r.entranceType(),
                r.canParking(), r.parkingNum(), r.hasElevator(), r.householdNum(),
                r.buildingApprovalDate(), r.moveInImmediate(), r.moveInDate(),
                r.residentRegistration(), r.depositInsurance(), r.shortLease(),
                r.lat(), r.lng(), r.regionCode(), r.dongName(), r.addressJibun(), r.addressRoad(),
                r.showDetailAddress(), r.contactSmsNumber(), r.contactCallNumber(),
                r.maintenance(), r.optionCodes(), r.safetyCodes(), r.imageFileUuids(), r.version());
    }

    private BrokerListingSaveRequest requestWithItems(MaintenanceItemCode... codes) {
        BrokerListingSaveRequest r = request(3_000_000L, 300_000L);
        BrokerListingSaveRequest.MaintenancePart maintenance = new BrokerListingSaveRequest.MaintenancePart(
                MaintenanceChargeType.FIXED_FEE, null, 100_000L, null, null, null, null,
                Arrays.stream(codes)
                        .map(code -> new BrokerListingSaveRequest.ItemPart(code, false, 10_000L, null)).toList(),
                null);
        return new BrokerListingSaveRequest(
                r.category(), r.roomType(), r.division(), r.duplex(), r.title(), r.description(),
                r.sellingType(), r.deposit(), r.monthlyRent(),
                r.exclusiveArea(), r.supplyArea(), r.contractArea(),
                r.floorType(), r.floor(), r.floorBand(), r.buildingFloor(), r.basementFloor(),
                r.direction(), r.directionBase(), r.heating(), r.entranceType(),
                r.canParking(), r.parkingNum(), r.hasElevator(), r.householdNum(),
                r.buildingApprovalDate(), r.moveInImmediate(), r.moveInDate(),
                r.residentRegistration(), r.depositInsurance(), r.shortLease(),
                r.lat(), r.lng(), r.regionCode(), r.dongName(), r.addressJibun(), r.addressRoad(),
                r.showDetailAddress(), r.contactSmsNumber(), r.contactCallNumber(),
                maintenance, r.optionCodes(), r.safetyCodes(), r.imageFileUuids(), null);
    }

    // 모든 불린을 null 로 둔 요청 — 클라이언트가 그 필드를 생략했을 때 서버가 받는 모양.
    // 상태를 강제로 세운다 — 전이 메서드로는 도달 못 하는 상태(BLOCKED·ARCHIVED)를 테스트하려면 필요하다.
    private static void forceStatus(Listing listing, ListingStatus status) {
        try {
            var f = Listing.class.getDeclaredField("status");
            f.setAccessible(true);
            f.set(listing, status);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private BrokerListingSaveRequest requestWithNullBooleans() {
        return new BrokerListingSaveRequest(
                ListingCategory.ONE_TWO_ROOM, RoomType.ONE_ROOM, null, null,
                "테스트", "설명",
                SellingType.MONTHLY_RENT, 3_000_000L, 300_000L,
                new BigDecimal("26.44"), null, null,
                FloorType.GROUND, null, null, null, null,
                null, null, null, null,
                null, null, null, null,
                null, null, null,
                AvailabilityAnswer.ASK, AvailabilityAnswer.ASK, null,
                LAT, LNG, null, null, "서울특별시 관악구 신림동", null, null,
                null, null, null, List.of(), List.of(), List.of(), null);
    }

    private BrokerListingSaveRequest base(Long deposit, Long rent, BigDecimal lat, BigDecimal lng,
                                          Integer floor, FloorBand band,
                                          BigDecimal exclusive, BigDecimal supply) {
        return new BrokerListingSaveRequest(
                ListingCategory.ONE_TWO_ROOM, RoomType.ONE_ROOM, false, false,
                "테스트", "설명",
                SellingType.MONTHLY_RENT, deposit, rent,
                exclusive, supply, null,
                FloorType.GROUND, floor, band, null, null,
                null, null, null, null,
                false, null, null, null, null,
                false, null,
                AvailabilityAnswer.ASK, AvailabilityAnswer.ASK, false,
                lat, lng, null, null, "서울특별시 관악구 신림동", null, false,
                null, null, null, List.of(), List.of(), List.of(), null);
    }
}
