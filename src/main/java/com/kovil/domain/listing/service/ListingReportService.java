package com.kovil.domain.listing.service;

import com.kovil.domain.listing.dto.request.ListingReportCreateRequest;
import com.kovil.domain.listing.entity.Listing;
import com.kovil.domain.listing.entity.ListingReport;
import com.kovil.domain.listing.enums.ListingReportStatus;
import com.kovil.domain.listing.enums.ListingStatus;
import com.kovil.domain.listing.exception.ListingException;
import com.kovil.domain.listing.repository.ListingReportRepository;
import com.kovil.domain.listing.repository.ListingRepository;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.service.UserQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static com.kovil.domain.listing.exception.ListingErrorCode.LISTING_NOT_FOUND;
import static com.kovil.domain.listing.exception.ListingErrorCode.LISTING_REPORT_DUPLICATE;

@Slf4j
@Service
@RequiredArgsConstructor
public class ListingReportService {

    private static final List<ListingStatus> VISIBLE =
            List.of(ListingStatus.ACTIVE, ListingStatus.RESERVED);

    private final UserQueryService userQueryService;
    private final ListingRepository listingRepository;
    private final ListingReportRepository reportRepository;

    @Transactional
    public void report(String userUuid, String listingUuid, ListingReportCreateRequest request) {
        User reporter = userQueryService.getActiveUser(userUuid);
        Listing listing = listingRepository.findPublicByUuid(listingUuid, VISIBLE)
                .orElseThrow(() -> new ListingException(LISTING_NOT_FOUND));
        if (reportRepository.existsByListingIdAndReporterUserId(listing.getId(), reporter.getId())) {
            throw new ListingException(LISTING_REPORT_DUPLICATE);
        }
        try {
            reportRepository.saveAndFlush(ListingReport.builder()
                    .listingId(listing.getId())
                    .reporterUserId(reporter.getId())
                    .reasonCode(request.reasonCode())
                    .detail(request.detail() == null || request.detail().isBlank()
                            ? null : request.detail().strip())
                    .status(ListingReportStatus.PENDING)
                    .build());
        } catch (DataIntegrityViolationException e) {
            throw new ListingException(LISTING_REPORT_DUPLICATE);
        }
        log.info("[listing] 신고 no={} reason={}", listing.getListingNo(), request.reasonCode());
    }
}
