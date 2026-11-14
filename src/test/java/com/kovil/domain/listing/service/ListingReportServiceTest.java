package com.kovil.domain.listing.service;

import com.kovil.domain.listing.dto.request.ListingReportCreateRequest;
import com.kovil.domain.listing.entity.Listing;
import com.kovil.domain.listing.entity.ListingReport;
import com.kovil.domain.listing.enums.ListingReportReason;
import com.kovil.domain.listing.enums.ListingReportStatus;
import com.kovil.domain.listing.exception.ListingException;
import com.kovil.domain.listing.repository.ListingReportRepository;
import com.kovil.domain.listing.repository.ListingRepository;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.service.UserQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/*
 * 신고 접수의 분기 격자 + 신고 행의 처리 규칙(13-2 가 쓴다).
 * ★ 동시 이중 제출은 서비스 검사(exists)를 둘 다 통과한다 — 그때는 DB 유니크 위반을 409 로 바꿔야 한다.
 *   500 이 나가면 앱이 "다시 시도"를 띄워 같은 신고를 또 보낸다.
 */
class ListingReportServiceTest {

    private UserQueryService userQueryService;
    private ListingRepository listingRepository;
    private ListingReportRepository reportRepository;
    private ListingReportService service;
    private final Listing listing = mock(Listing.class);

    @BeforeEach
    void setUp() {
        userQueryService = mock(UserQueryService.class);
        listingRepository = mock(ListingRepository.class);
        reportRepository = mock(ListingReportRepository.class);
        service = new ListingReportService(userQueryService, listingRepository, reportRepository);
        when(userQueryService.getActiveUser("student")).thenReturn(User.builder().id(7L).uuid("student").build());
        when(listing.getId()).thenReturn(70L);
        when(listing.getListingNo()).thenReturn("LS-261012-AAAAAA");
        when(listingRepository.findPublicByUuid(eq("open"), any())).thenReturn(Optional.of(listing));
        when(listingRepository.findPublicByUuid(eq("gone"), any())).thenReturn(Optional.empty());
    }

    private static ListingReportCreateRequest request() {
        return new ListingReportCreateRequest(ListingReportReason.FAKE_LISTING, null);
    }

    private static String codeOf(Throwable e) {
        return ((ListingException) e).getErrorCode().getCode();
    }

    @Test
    @DisplayName("공개 매물이 아니면 404 이고 행을 만들지 않는다")
    void not_public() {
        assertThatThrownBy(() -> service.report("student", "gone", request()))
                .isInstanceOf(ListingException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("LISTING_NOT_FOUND"));
        verify(reportRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("이미 신고했으면 409 이고 행을 만들지 않는다")
    void already_reported() {
        when(reportRepository.existsByListingIdAndReporterUserId(70L, 7L)).thenReturn(true);

        assertThatThrownBy(() -> service.report("student", "open", request()))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("LISTING_REPORT_DUPLICATE"));
        verify(reportRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("동시 이중 제출 — 검사를 통과했지만 DB 유니크에 걸리면 500 이 아니라 409")
    void concurrent_duplicate_is_conflict() {
        when(reportRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uq_lrep_listing_reporter"));

        assertThatThrownBy(() -> service.report("student", "open", request()))
                .isInstanceOf(ListingException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("LISTING_REPORT_DUPLICATE"));
    }

    @Nested
    @DisplayName("신고 행 처리 규칙")
    class Handling {

        private ListingReport pending() {
            return ListingReport.builder().listingId(70L).reporterUserId(7L)
                    .reasonCode(ListingReportReason.SCAM).status(ListingReportStatus.PENDING).build();
        }

        @Test
        @DisplayName("승인 · 기각은 처리자와 시각을 남긴다")
        void resolve_and_dismiss_record_handler() {
            LocalDateTime at = LocalDateTime.of(2026, 10, 12, 18, 0);
            ListingReport resolved = pending();
            resolved.resolve(1L, at);
            ListingReport dismissed = pending();
            dismissed.dismiss(2L, at);

            assertThat(resolved.getStatus()).isEqualTo(ListingReportStatus.RESOLVED);
            assertThat(resolved.getHandledBy()).isEqualTo(1L);
            assertThat(resolved.getHandledAt()).isEqualTo(at);
            assertThat(dismissed.getStatus()).isEqualTo(ListingReportStatus.DISMISSED);
            assertThat(dismissed.getHandledBy()).isEqualTo(2L);
        }

        @Test
        @DisplayName("처리된 신고는 다시 처리할 수 없다 — 409 LISTING_REPORT_ALREADY_HANDLED")
        void handled_is_final() {
            ListingReport report = pending();
            report.dismiss(1L, LocalDateTime.of(2026, 10, 12, 18, 0));

            assertThatThrownBy(() -> report.resolve(1L, LocalDateTime.now()))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo("LISTING_REPORT_ALREADY_HANDLED"));
            assertThatThrownBy(() -> report.dismiss(1L, LocalDateTime.now()))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo("LISTING_REPORT_ALREADY_HANDLED"));
            assertThat(report.getStatus()).isEqualTo(ListingReportStatus.DISMISSED);
        }
    }
}
