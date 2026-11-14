package com.kovil.domain.listing.service;

import com.kovil.common.dto.response.PageResponse;
import com.kovil.domain.file.repository.StoredFileRepository;
import com.kovil.domain.listing.dto.response.AdminListingDetailResponse;
import com.kovil.domain.listing.dto.response.AdminListingReportResponse;
import com.kovil.domain.listing.entity.BrokerageOffice;
import com.kovil.domain.listing.entity.Listing;
import com.kovil.domain.listing.entity.ListingBlockLog;
import com.kovil.domain.listing.entity.ListingImage;
import com.kovil.domain.listing.entity.ListingReport;
import com.kovil.domain.listing.enums.ListingBlockAction;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.kovil.domain.listing.exception.ListingErrorCode.LISTING_NOT_BLOCKABLE;
import static com.kovil.domain.listing.exception.ListingErrorCode.LISTING_NOT_FOUND;
import static com.kovil.domain.listing.exception.ListingErrorCode.LISTING_REPORT_NOT_FOUND;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminListingModerationService {

    private final UserQueryService userQueryService;
    private final UserRepository userRepository;
    private final ListingRepository listingRepository;
    private final BrokerageOfficeRepository officeRepository;
    private final ListingImageRepository imageRepository;
    private final StoredFileRepository storedFileRepository;
    private final ListingReportRepository reportRepository;
    private final ListingBlockLogRepository blockLogRepository;
    private final ListingAlertRecipients alertRecipients;
    private final NotificationPublisher notificationPublisher;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PageResponse<AdminListingReportResponse> findQueue(ListingReportStatus status, Pageable pageable) {
        Page<ListingReport> page = status == ListingReportStatus.PENDING
                ? reportRepository.findByStatusOrderByIdAsc(status, pageable)
                : reportRepository.findByStatusOrderByIdDesc(status, pageable);
        List<ListingReport> rows = page.getContent();

        List<Long> listingIds = rows.stream().map(ListingReport::getListingId).distinct().toList();
        Map<Long, Listing> listings = listingIds.isEmpty() ? Map.of()
                : listingRepository.findAllById(listingIds).stream()
                .collect(Collectors.toMap(Listing::getId, Function.identity()));
        Map<Long, Long> pendingCounts = listingIds.isEmpty() ? Map.of()
                : reportRepository.countByStatusAndListingIds(ListingReportStatus.PENDING, listingIds).stream()
                .collect(Collectors.toMap(row -> (Long) row[0], row -> (Long) row[1]));
        Map<Long, String> names = namesOf(rows.stream().map(ListingReport::getHandledBy));

        List<AdminListingReportResponse> body = rows.stream().map(report -> {
            Listing listing = listings.get(report.getListingId());
            return new AdminListingReportResponse(
                    report.getUuid(),
                    listing.getUuid(), listing.getListingNo(), listing.getTitle(), listing.getStatus(),
                    report.getReasonCode(), report.getDetail(),
                    pendingCounts.getOrDefault(report.getListingId(), 0L),
                    report.getStatus(), report.getCreatedAt(),
                    nameOf(names, report.getHandledBy()), report.getHandledAt());
        }).toList();
        return PageResponse.from(new PageImpl<>(body, pageable, page.getTotalElements()));
    }

    @Transactional(readOnly = true)
    public AdminListingDetailResponse getDetail(String listingUuid) {
        Listing listing = requireListing(listingUuid);
        BrokerageOffice office = officeRepository.findById(listing.getOfficeId())
                .orElseThrow(() -> new ListingException(LISTING_NOT_FOUND));
        List<ListingReport> reports = reportRepository.findTop100ByListingIdOrderByIdDesc(listing.getId());
        List<ListingBlockLog> history = blockLogRepository.findByListingIdOrderByIdDesc(listing.getId());
        Map<Long, String> names = namesOf(Stream.concat(
                reports.stream().map(ListingReport::getHandledBy),
                history.stream().map(ListingBlockLog::getAdminUserId)));

        return new AdminListingDetailResponse(
                listing.getUuid(), listing.getListingNo(), listing.getStatus(),
                listing.getTitle(), listing.getDescription(),
                listing.getSellingType(), listing.getDeposit(), listing.getMonthlyRent(),
                listing.getAddressRoad(), listing.getAddressJibun(),
                imageUrlsOf(listing.getId()),
                listing.getPostedAt(), listing.getExpiresAt(),
                new AdminListingDetailResponse.Office(
                        office.getUuid(), office.getName(), office.getRegNo(), office.getTel(),
                        officeRepository.isAdvertisable(office.getId())),
                reportRepository.countByListingId(listing.getId()),
                reportRepository.countByListingIdAndStatus(listing.getId(), ListingReportStatus.PENDING),
                reports.stream().map(report -> new AdminListingDetailResponse.Report(
                                report.getUuid(), report.getReasonCode(), report.getDetail(), report.getStatus(),
                                report.getCreatedAt(), nameOf(names, report.getHandledBy()), report.getHandledAt()))
                        .toList(),
                history.stream().map(entry -> new AdminListingDetailResponse.BlockLog(
                                entry.getAction(), entry.getReason(), nameOf(names, entry.getAdminUserId()),
                                entry.getCreatedAt()))
                        .toList());
    }

    @Transactional
    public void block(String adminUuid, String listingUuid, String reason) {
        User admin = userQueryService.getActiveUser(adminUuid);
        Listing listing = requireListing(listingUuid);
        LocalDateTime now = LocalDateTime.now(clock);
        if (listing.getStatus() == ListingStatus.EXPIRED && listing.getExpiresAt() != null
                && now.isAfter(listing.getExpiresAt().plusDays(BrokerListingService.RENEWAL_GRACE_DAYS))) {
            throw new ListingException(LISTING_NOT_BLOCKABLE);
        }

        listing.block();

        listingRepository.flush();
        List<ListingReport> pending =
                reportRepository.findByListingIdAndStatusForUpdate(listing.getId(), ListingReportStatus.PENDING);
        pending.forEach(report -> report.resolve(admin.getId(), now));
        blockLogRepository.save(ListingBlockLog.builder()
                .listingId(listing.getId())
                .action(ListingBlockAction.BLOCK)
                .reason(reason.strip())
                .adminUserId(admin.getId())
                .build());

        for (Long recipient : alertRecipients.of(listing)) {
            notificationPublisher.publish(recipient, NotiType.LISTING_BLOCKED,
                    "LISTING_BLOCKED:listing:" + listing.getUuid() + ":" + now + ":user:" + recipient,
                    Map.of("0", listing.getListingNo()),
                    "/broker/listings/" + listing.getUuid());
        }
        log.info("[listing] 관리자 차단 no={} 함께 닫은 신고={}건", listing.getListingNo(), pending.size());
    }

    @Transactional
    public void unblock(String adminUuid, String listingUuid) {
        User admin = userQueryService.getActiveUser(adminUuid);
        Listing listing = requireListing(listingUuid);

        listing.unblock();
        blockLogRepository.save(ListingBlockLog.builder()
                .listingId(listing.getId())
                .action(ListingBlockAction.UNBLOCK)
                .adminUserId(admin.getId())
                .build());
        log.info("[listing] 관리자 차단 해제 no={}", listing.getListingNo());
    }

    @Transactional
    public void dismiss(String adminUuid, String reportUuid) {
        User admin = userQueryService.getActiveUser(adminUuid);
        ListingReport report = reportRepository.findByUuidForUpdate(reportUuid)
                .orElseThrow(() -> new ListingException(LISTING_REPORT_NOT_FOUND));
        report.dismiss(admin.getId(), LocalDateTime.now(clock));
        log.info("[listing] 신고 기각 report={}", reportUuid);
    }

    private Listing requireListing(String listingUuid) {
        return listingRepository.findByUuid(listingUuid)
                .orElseThrow(() -> new ListingException(LISTING_NOT_FOUND));
    }

    private Map<Long, String> namesOf(Stream<Long> userIds) {
        List<Long> ids = userIds.filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return userRepository.findAllById(ids).stream()
                .filter(user -> user.getName() != null)
                .collect(Collectors.toMap(User::getId, User::getName));
    }

    private static String nameOf(Map<Long, String> names, Long userId) {
        return userId == null ? null : names.get(userId);
    }

    private List<String> imageUrlsOf(Long listingId) {
        List<Long> fileIds = imageRepository.findByListingIdOrderBySortOrderAsc(listingId).stream()
                .map(ListingImage::getFileId).toList();
        if (fileIds.isEmpty()) {
            return List.of();
        }
        Map<Long, String> urlById = new HashMap<>();
        storedFileRepository.findByIdInAndDeletedAtIsNull(fileIds)
                .forEach(file -> urlById.put(file.getId(), file.getFileUrl()));
        return fileIds.stream().map(urlById::get).filter(Objects::nonNull).toList();
    }
}