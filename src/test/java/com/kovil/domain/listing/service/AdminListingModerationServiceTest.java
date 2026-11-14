package com.kovil.domain.listing.service;

import com.kovil.domain.file.repository.StoredFileRepository;
import com.kovil.domain.listing.dto.response.AdminListingReportResponse;
import com.kovil.domain.listing.entity.Listing;
import com.kovil.domain.listing.entity.ListingBlockLog;
import com.kovil.domain.listing.entity.ListingReport;
import com.kovil.domain.listing.enums.ListingBlockAction;
import com.kovil.domain.listing.enums.ListingReportReason;
import com.kovil.domain.listing.enums.ListingReportStatus;
import com.kovil.domain.listing.enums.ListingStatus;
import com.kovil.domain.listing.exception.ListingException;
import com.kovil.domain.listing.repository.BrokerageOfficeRepository;
import com.kovil.domain.listing.repository.ListingBlockLogRepository;
import com.kovil.domain.listing.repository.ListingImageRepository;
import com.kovil.domain.listing.repository.ListingReportRepository;
import com.kovil.domain.listing.repository.ListingRepository;
import com.kovil.domain.notification.enums.NotiType;
import com.kovil.domain.notification.service.NotificationPublisher;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.repository.UserRepository;
import com.kovil.domain.user.service.UserQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/*
 * 관리자 매물 처리의 분기 격자.
 * ★ 차단 알림에 사유가 실리면 PO 결정(사유는 관리자만)이 깨진다 — 알림 인자를 통째로 비교해 박는다.
 * ★ 거래 완료 · 자동 종료를 막으면 안 된다 — 해제가 HIDDEN 으로 돌아가 끝난 매물을 다시 게시할 수 있게 된다.
 * ★ 미처리 신고만 있는 페이지는 처리자 이름 표가 빈 Map.of() 다 — get(null) 이 NPE 를 던지는 함정을 따로 박는다.
 */
class AdminListingModerationServiceTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 12, 18, 0);

    private final UserQueryService userQueryService = mock(UserQueryService.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final ListingRepository listingRepository = mock(ListingRepository.class);
    private final BrokerageOfficeRepository officeRepository = mock(BrokerageOfficeRepository.class);
    private final ListingImageRepository imageRepository = mock(ListingImageRepository.class);
    private final StoredFileRepository storedFileRepository = mock(StoredFileRepository.class);
    private final ListingReportRepository reportRepository = mock(ListingReportRepository.class);
    private final ListingBlockLogRepository blockLogRepository = mock(ListingBlockLogRepository.class);
    private final ListingAlertRecipients alertRecipients = mock(ListingAlertRecipients.class);
    private final NotificationPublisher notificationPublisher = mock(NotificationPublisher.class);
    private AdminListingModerationService service;

    @BeforeEach
    void setUp() {
        service = new AdminListingModerationService(userQueryService, userRepository, listingRepository,
                officeRepository, imageRepository, storedFileRepository, reportRepository, blockLogRepository,
                alertRecipients, notificationPublisher, Clock.fixed(NOW.atZone(SEOUL).toInstant(), SEOUL));
        when(userQueryService.getActiveUser("admin")).thenReturn(User.builder().id(1L).uuid("admin").build());
    }

    private Listing listing(ListingStatus status) {
        Listing listing = Listing.builder().id(70L).uuid("l-1").listingNo("LS-261012-AAAAAA")
                .officeId(5L).createdByUserId(9L).title("테스트").status(status).build();
        when(listingRepository.findByUuid("l-1")).thenReturn(Optional.of(listing));
        return listing;
    }

    private static ListingReport pending(Long id) {
        return ListingReport.builder().id(id).uuid("r-" + id).listingId(70L).reporterUserId(100L + id)
                .reasonCode(ListingReportReason.FAKE_LISTING).status(ListingReportStatus.PENDING).build();
    }

    private static String codeOf(Throwable e) {
        return ((ListingException) e).getErrorCode().getCode();
    }

    @Nested
    @DisplayName("차단")
    class Block {

        @Test
        @DisplayName("대기 신고를 전부 닫고 이력을 남기고 중개사마다 알린다 — 알림에 사유가 없다")
        void block_closes_pending_logs_and_notifies_without_reason() {
            Listing listing = listing(ListingStatus.ACTIVE);
            ListingReport first = pending(1L);
            ListingReport second = pending(2L);
            when(reportRepository.findByListingIdAndStatusForUpdate(70L, ListingReportStatus.PENDING))
                    .thenReturn(List.of(first, second));
            when(alertRecipients.of(listing)).thenReturn(List.of(9L, 11L));

            service.block("admin", "l-1", "  사진과 실제 방이 다릅니다  ");

            assertThat(listing.getStatus()).isEqualTo(ListingStatus.BLOCKED);
            InOrder lockOrder = inOrder(listingRepository, reportRepository);
            lockOrder.verify(listingRepository).flush();
            lockOrder.verify(reportRepository).findByListingIdAndStatusForUpdate(70L, ListingReportStatus.PENDING);
            for (ListingReport report : List.of(first, second)) {
                assertThat(report.getStatus()).isEqualTo(ListingReportStatus.RESOLVED);
                assertThat(report.getHandledBy()).isEqualTo(1L);
                assertThat(report.getHandledAt()).isEqualTo(NOW);
            }
            ArgumentCaptor<ListingBlockLog> saved = ArgumentCaptor.forClass(ListingBlockLog.class);
            verify(blockLogRepository).save(saved.capture());
            assertThat(saved.getValue().getListingId()).isEqualTo(70L);
            assertThat(saved.getValue().getAction()).isEqualTo(ListingBlockAction.BLOCK);
            assertThat(saved.getValue().getReason()).isEqualTo("사진과 실제 방이 다릅니다");
            assertThat(saved.getValue().getAdminUserId()).isEqualTo(1L);

            for (Long recipient : List.of(9L, 11L)) {
                verify(notificationPublisher).publish(eq(recipient), eq(NotiType.LISTING_BLOCKED),
                        eq("LISTING_BLOCKED:listing:l-1:" + NOW + ":user:" + recipient),
                        eq(Map.of("0", "LS-261012-AAAAAA")),
                        eq("/broker/listings/l-1"));
            }
        }

        @ParameterizedTest(name = "{0} → BLOCKED")
        @EnumSource(value = ListingStatus.class, names = {"DRAFT", "ACTIVE", "RESERVED", "HIDDEN", "EXPIRED"})
        @DisplayName("공개 · 예약 중 · 초안 · 내림 · 만료 어디서든 막는다")
        void blockable_states(ListingStatus from) {
            Listing listing = listing(from);
            when(reportRepository.findByListingIdAndStatusForUpdate(anyLong(), any())).thenReturn(List.of());
            when(alertRecipients.of(listing)).thenReturn(List.of());

            service.block("admin", "l-1", "사유");

            assertThat(listing.getStatus()).isEqualTo(ListingStatus.BLOCKED);
        }

        @ParameterizedTest(name = "{0} → 409 LISTING_NOT_BLOCKABLE")
        @EnumSource(value = ListingStatus.class, names = {"COMPLETED", "ARCHIVED", "BLOCKED"})
        @DisplayName("거래 완료 · 자동 종료 · 이미 차단은 409 — 신고 · 이력 · 알림 모두 손대지 않는다")
        void not_blockable_states(ListingStatus from) {
            Listing listing = listing(from);

            assertThatThrownBy(() -> service.block("admin", "l-1", "사유"))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo("LISTING_NOT_BLOCKABLE"));
            assertThat(listing.getStatus()).isEqualTo(from);
            verify(reportRepository, never()).findByListingIdAndStatusForUpdate(anyLong(), any());
            verify(blockLogRepository, never()).save(any());
            verifyNoInteractions(notificationPublisher);
        }

        /*
         * ★ 차단 해제는 늘 HIDDEN 으로 돌린다 — 연장 창(만료 + 7일)이 닫힌 만료 매물까지 차단을 받아 주면,
         *   해제 뒤 중개사가 게시해 끝난 광고가 새 한 달로 되살아난다(연장은 409 였을 매물). 창의 경계는 연장과 같다.
         */
        @Test
        @DisplayName("만료 매물은 연장 창(만료 + 7일) 안에서만 막고, 창이 닫혔으면 409 — 신고 · 이력 · 알림 모두 손대지 않는다")
        void expired_outside_renewal_window_is_not_blockable() {
            Listing closed = listing(ListingStatus.EXPIRED);
            ReflectionTestUtils.setField(closed, "expiresAt", NOW.minusDays(7).minusSeconds(1));

            assertThatThrownBy(() -> service.block("admin", "l-1", "사유"))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo("LISTING_NOT_BLOCKABLE"));
            assertThat(closed.getStatus()).isEqualTo(ListingStatus.EXPIRED);
            verify(reportRepository, never()).findByListingIdAndStatusForUpdate(anyLong(), any());
            verify(blockLogRepository, never()).save(any());
            verifyNoInteractions(notificationPublisher);

            Listing edge = listing(ListingStatus.EXPIRED);
            ReflectionTestUtils.setField(edge, "expiresAt", NOW.minusDays(7));
            when(reportRepository.findByListingIdAndStatusForUpdate(anyLong(), any())).thenReturn(List.of());
            when(alertRecipients.of(edge)).thenReturn(List.of());

            service.block("admin", "l-1", "사유");

            assertThat(edge.getStatus()).isEqualTo(ListingStatus.BLOCKED);
        }

        @Test
        @DisplayName("없는 매물은 404")
        void unknown_listing() {
            when(listingRepository.findByUuid("nope")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.block("admin", "nope", "사유"))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo("LISTING_NOT_FOUND"));
        }
    }

    @Nested
    @DisplayName("해제")
    class Unblock {

        @Test
        @DisplayName("BLOCKED → HIDDEN, 해제 이력(사유 없음)을 남기고 알리지 않는다")
        void unblock_goes_hidden() {
            Listing listing = listing(ListingStatus.BLOCKED);

            service.unblock("admin", "l-1");

            assertThat(listing.getStatus()).isEqualTo(ListingStatus.HIDDEN);
            ArgumentCaptor<ListingBlockLog> saved = ArgumentCaptor.forClass(ListingBlockLog.class);
            verify(blockLogRepository).save(saved.capture());
            assertThat(saved.getValue().getAction()).isEqualTo(ListingBlockAction.UNBLOCK);
            assertThat(saved.getValue().getReason()).isNull();
            assertThat(saved.getValue().getAdminUserId()).isEqualTo(1L);
            verifyNoInteractions(notificationPublisher);
        }

        // 게시된 적 없는 초안을 막았다 풀어도 HIDDEN 이다 — 차단 이력이 남은 매물은 증거라 지울 수 없어야 한다.
        @Test
        @DisplayName("게시된 적 없는 매물도 HIDDEN 으로 돌아간다")
        void unblock_never_posted_goes_hidden_too() {
            Listing listing = Listing.builder().id(70L).uuid("l-1").status(ListingStatus.BLOCKED).build();
            when(listingRepository.findByUuid("l-1")).thenReturn(Optional.of(listing));

            service.unblock("admin", "l-1");

            assertThat(listing.getPostedAt()).isNull();
            assertThat(listing.getStatus()).isEqualTo(ListingStatus.HIDDEN);
        }

        @ParameterizedTest(name = "{0} → 409 LISTING_NOT_BLOCKED")
        @EnumSource(value = ListingStatus.class, names = "BLOCKED", mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("차단된 매물이 아니면 409 이고 이력을 남기지 않는다")
        void not_blocked(ListingStatus from) {
            Listing listing = listing(from);

            assertThatThrownBy(() -> service.unblock("admin", "l-1"))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo("LISTING_NOT_BLOCKED"));
            assertThat(listing.getStatus()).isEqualTo(from);
            verify(blockLogRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("기각")
    class Dismiss {

        @Test
        @DisplayName("그 신고 한 건만 DISMISSED — 처리자 · 시각을 남긴다")
        void dismiss_one() {
            ListingReport report = pending(1L);
            when(reportRepository.findByUuidForUpdate("r-1")).thenReturn(Optional.of(report));

            service.dismiss("admin", "r-1");

            assertThat(report.getStatus()).isEqualTo(ListingReportStatus.DISMISSED);
            assertThat(report.getHandledBy()).isEqualTo(1L);
            assertThat(report.getHandledAt()).isEqualTo(NOW);
            verifyNoInteractions(listingRepository, notificationPublisher);
        }

        @Test
        @DisplayName("없는 신고 404 · 처리된 신고 409")
        void unknown_and_handled() {
            when(reportRepository.findByUuidForUpdate("nope")).thenReturn(Optional.empty());
            ListingReport handled = pending(2L);
            handled.resolve(1L, NOW);
            when(reportRepository.findByUuidForUpdate("r-2")).thenReturn(Optional.of(handled));

            assertThatThrownBy(() -> service.dismiss("admin", "nope"))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo("LISTING_REPORT_NOT_FOUND"));
            assertThatThrownBy(() -> service.dismiss("admin", "r-2"))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo("LISTING_REPORT_ALREADY_HANDLED"));
            assertThat(handled.getStatus()).isEqualTo(ListingReportStatus.RESOLVED);
        }
    }

    @Nested
    @DisplayName("대기열")
    class Queue {

        @Test
        @DisplayName("PENDING 은 오래된 순, 처리된 이력은 최신부터 읽는다")
        void ordering_axis_by_status() {
            PageRequest page = PageRequest.of(0, 20);
            when(reportRepository.findByStatusOrderByIdAsc(any(), any())).thenReturn(new PageImpl<>(List.of()));
            when(reportRepository.findByStatusOrderByIdDesc(any(), any())).thenReturn(new PageImpl<>(List.of()));

            service.findQueue(ListingReportStatus.PENDING, page);
            verify(reportRepository).findByStatusOrderByIdAsc(ListingReportStatus.PENDING, page);
            verify(reportRepository, never()).findByStatusOrderByIdDesc(any(), any());

            service.findQueue(ListingReportStatus.DISMISSED, page);
            verify(reportRepository).findByStatusOrderByIdDesc(ListingReportStatus.DISMISSED, page);
        }

        @Test
        @DisplayName("미처리 신고만 있는 페이지 — 처리자 이름은 null 이고 같은 매물 대기 수가 실린다")
        void pending_only_page_has_null_handler() {
            PageRequest page = PageRequest.of(0, 20);
            Listing listing = Listing.builder().id(70L).uuid("l-1").listingNo("LS-261012-AAAAAA")
                    .title("테스트").status(ListingStatus.ACTIVE).build();
            when(reportRepository.findByStatusOrderByIdAsc(ListingReportStatus.PENDING, page))
                    .thenReturn(new PageImpl<>(List.of(pending(1L)), page, 1));
            when(listingRepository.findAllById(any())).thenReturn(List.of(listing));
            when(reportRepository.countByStatusAndListingIds(eq(ListingReportStatus.PENDING), anyCollection()))
                    .thenReturn(Collections.singletonList(new Object[]{70L, 3L}));

            List<AdminListingReportResponse> rows = service.findQueue(ListingReportStatus.PENDING, page).content();

            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).listingUuid()).isEqualTo("l-1");
            assertThat(rows.get(0).listingStatus()).isEqualTo(ListingStatus.ACTIVE);
            assertThat(rows.get(0).sameListingPendingCount()).isEqualTo(3L);
            assertThat(rows.get(0).handledByName()).isNull();
            verifyNoInteractions(userRepository);
        }

        @Test
        @DisplayName("처리자 이름 — 있으면 싣고, 이름 없는 계정이면 null(지어내지 않는다)")
        void handler_names() {
            PageRequest page = PageRequest.of(0, 20);
            Listing listing = Listing.builder().id(70L).uuid("l-1").listingNo("LS-261012-AAAAAA")
                    .title("테스트").status(ListingStatus.BLOCKED).build();
            ListingReport named = pending(1L);
            named.resolve(1L, NOW);
            ListingReport nameless = pending(2L);
            nameless.dismiss(2L, NOW);
            when(reportRepository.findByStatusOrderByIdDesc(ListingReportStatus.RESOLVED, page))
                    .thenReturn(new PageImpl<>(List.of(named, nameless), page, 2));
            when(listingRepository.findAllById(any())).thenReturn(List.of(listing));
            when(reportRepository.countByStatusAndListingIds(any(), anyCollection())).thenReturn(List.of());
            when(userRepository.findAllById(any())).thenReturn(List.of(
                    User.builder().id(1L).uuid("admin").name("운영자").build(),
                    User.builder().id(2L).uuid("admin-2").build()));

            List<AdminListingReportResponse> rows = service.findQueue(ListingReportStatus.RESOLVED, page).content();

            assertThat(rows).extracting(AdminListingReportResponse::handledByName).containsExactly("운영자", null);
            assertThat(rows).extracting(AdminListingReportResponse::sameListingPendingCount).containsOnly(0L);
        }
    }
}
