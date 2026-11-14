package com.kovil.domain.listing.service;

import com.kovil.common.exception.CommonErrorCode;
import com.kovil.common.util.BusinessNumberGenerator;
import com.kovil.common.util.BusinessNumberPrefix;
import com.kovil.common.dto.response.PageResponse;
import com.kovil.domain.listing.dto.request.BrokerListingSaveRequest;
import com.kovil.domain.listing.dto.response.BrokerListingResponse;
import com.kovil.domain.listing.dto.response.BrokerListingStatusCountsResponse;
import com.kovil.domain.listing.dto.response.BrokerListingSummaryResponse;
import com.kovil.domain.listing.entity.*;
import com.kovil.domain.listing.enums.*;
import com.kovil.domain.file.enums.FilePurpose;
import com.kovil.domain.file.entity.StoredFile;
import com.kovil.domain.file.service.FileDeletionService;
import com.kovil.domain.listing.exception.ListingException;
import com.kovil.domain.listing.repository.*;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.repository.UserRepository;
import com.kovil.domain.user.service.UserQueryService;
import com.kovil.domain.file.repository.StoredFileRepository;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static com.kovil.domain.listing.exception.ListingErrorCode.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BrokerListingService {

    private static final int MAX_IMAGES = 10;

    private static final int LISTING_MONTHS = 1;

    public static final int RENEWAL_GRACE_DAYS = 7;

    private static final Set<ListingStatus> EDITABLE = EnumSet.of(
            ListingStatus.DRAFT, ListingStatus.ACTIVE, ListingStatus.RESERVED,
            ListingStatus.HIDDEN, ListingStatus.EXPIRED);

    private final UserQueryService userQueryService;
    private final UserRepository userRepository;
    private final ListingRepository listingRepository;
    private final ListingMaintenanceRepository maintenanceRepository;
    private final ListingMaintenanceItemRepository itemRepository;
    private final ListingMaintenanceIncludeRepository includeRepository;
    private final ListingOptionRepository optionRepository;
    private final ListingSafetyRepository safetyRepository;
    private final ListingImageRepository imageRepository;
    private final ListingPriceHistoryRepository priceHistoryRepository;
    private final ListingAccessPolicy accessPolicy;
    private final ListingPublishPolicy publishPolicy;
    private final DisplayCoordinateGenerator coordinateGenerator;
    private final BusinessNumberGenerator businessNumberGenerator;
    private final Clock clock;
    private final StoredFileRepository storedFileRepository;
    private final EntityManager entityManager;
    private final ListingRegionResolver regionResolver;
    private final FileDeletionService fileDeletionService;

    // ───────────────────────── 조회

    public PageResponse<BrokerListingSummaryResponse> findMine(
            String userUuid, Collection<ListingStatus> statuses, BrokerListingSort sort, Pageable pageable) {
        BrokerProfile profile = accessPolicy.requireProfile(userIdOf(userUuid));

        Collection<ListingStatus> filter = (statuses == null || statuses.isEmpty())
                ? EnumSet.allOf(ListingStatus.class) : statuses;

        BrokerListingSort order = sort == null ? BrokerListingSort.UPDATED_DESC : sort;

        Page<Listing> page = order == BrokerListingSort.EXPIRES_ASC ?
                listingRepository.findByOfficeIdAndStatusInExpiringFirst(profile.getOfficeId(), filter, pageable)
                : listingRepository.findByOfficeIdAndStatusIn(
                profile.getOfficeId(),
                filter,
                PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sortOf(order)));

        Map<Long, String> thumbnails = thumbnailsOf(page.getContent());
        Map<Long, String> regionCodes = regionResolver.codesOf(
                page.getContent()
                        .stream()
                        .map(Listing::getRegionId)
                        .toList()
        );

        return PageResponse.from(
                page.map(
                        listing -> BrokerListingSummaryResponse.of(
                                listing,
                                thumbnails.get(listing.getId()),
                                listing.getRegionId() == null ?
                                        null :
                                        regionCodes.get(listing.getRegionId())
                        )
                )
        );
    }

    public BrokerListingStatusCountsResponse countMine(
            String userUuid
    ) {
        BrokerProfile profile = accessPolicy.requireProfile(userIdOf(userUuid));
        Map<ListingStatus, Long> byStatus = new EnumMap<>(ListingStatus.class);

        for (ListingStatus status : ListingStatus.values()) {
            byStatus.put(status, 0L);
        }

        listingRepository.countByOfficeIdGroupByStatus(profile.getOfficeId())
                .forEach(count -> byStatus.put(count.status(), count.count()));

        long renewable = listingRepository
                .countByOfficeIdAndStatusAndExpiresAtGreaterThanEqual(
                        profile.getOfficeId(),
                        ListingStatus.EXPIRED,
                        LocalDateTime.now(clock).minusDays(RENEWAL_GRACE_DAYS)
                );

        return new BrokerListingStatusCountsResponse(byStatus, renewable);
    }

    public BrokerListingResponse get(String userUuid, String listingUuid) {
        Listing listing = requireOwned(userUuid, listingUuid);
        return assemble(listing);
    }

    // ───────────────────────── 등록·수정 (등록은 항상 DRAFT)

    @Transactional
    public BrokerListingResponse create(String userUuid, BrokerListingSaveRequest request) {
        User broker = userQueryService.getActiveUser(userUuid);
        userRepository.lockSharedById(broker.getId());
        BrokerageOffice office = accessPolicy.requireAdvertisableOffice(broker.getId());
        validate(request);

        BigDecimal[] display = coordinateGenerator.obfuscate(request.lat(), request.lng());
        Listing listing = listingRepository.save(Listing.builder()
                .uuid(UUID.randomUUID().toString())
                .listingNo(businessNumberGenerator.generate(BusinessNumberPrefix.LISTING))
                .officeId(office.getId())
                .createdByUserId(broker.getId())
                .category(request.category()).roomType(request.roomType())
                .division(request.divisionOrFalse()).duplex(request.duplexOrFalse())
                .title(request.title()).description(request.description())
                .sellingType(request.sellingType())
                .deposit(request.deposit())
                .monthlyRent(request.monthlyRent() == null ? 0L : request.monthlyRent())
                .exclusiveArea(request.exclusiveArea())
                .supplyArea(request.supplyArea()).contractArea(request.contractArea())
                .floorType(request.floorType() == null ? FloorType.GROUND : request.floorType())
                .floor(request.floor()).floorBand(request.floorBand())
                .buildingFloor(request.buildingFloor()).basementFloor(request.basementFloor())
                .direction(request.direction()).directionBase(request.directionBase())
                .heating(request.heating()).entranceType(request.entranceType())
                .canParking(request.canParkingOrFalse()).parkingNum(request.parkingNum())
                .hasElevator(request.hasElevator()).householdNum(request.householdNum())
                .buildingApprovalDate(request.buildingApprovalDate())
                .moveInImmediate(request.moveInImmediateOrFalse()).moveInDate(request.moveInDate())
                .residentRegistration(orAsk(request.residentRegistration()))
                .depositInsurance(orAsk(request.depositInsurance()))
                .shortLease(request.shortLeaseOrFalse())
                .realLat(request.lat()).realLng(request.lng())
                .displayLat(display[0]).displayLng(display[1])
                .regionId(regionResolver.resolveIdForSave(request.regionCode(), request.dongName(),
                        request.addressJibun(), request.addressRoad(), null))
                .dongName(request.dongName())
                .addressJibun(request.addressJibun()).addressRoad(request.addressRoad())
                .showDetailAddress(request.showDetailAddressOrFalse())
                .contactSmsNumber(request.contactSmsNumber())
                .contactCallNumber(request.contactCallNumber())
                .status(ListingStatus.DRAFT)
                .build());

        replaceChildren(listing.getId(), request, broker.getId());
        return assembleAfterFlush(listing);
    }

    @Transactional
    public BrokerListingResponse update(String userUuid, String listingUuid,
                                        BrokerListingSaveRequest request) {
        User broker = userQueryService.getActiveUser(userUuid);
        Listing listing = requireOwned(broker, listingUuid);

        if (!EDITABLE.contains(listing.getStatus())) {
            throw new ListingException(LISTING_NOT_EDITABLE);
        }
        // 폼을 연 뒤 매물이 바뀌었으면(다른 기기 저장 · 만료 배치 · 지역 닫힘 · 차단 해제) 통째 덮지 않음.
        if (request.version() != null && !request.version().equals(listing.getVersion())) {
            throw new ListingException(CommonErrorCode.CONFLICT);
        }
        validate(request);
        // 자식(사진 · 옵션 · 관리비)만 바뀐 저장도 version 이 올라야 다른 기기가 알아챔. 그 자리에서 올리고 행을 잠금 —
        // 읽은 뒤 다른 커밋이 끼었으면 여기서 낙관적 잠금 실패(409).
        entityManager.lock(listing, LockModeType.PESSIMISTIC_FORCE_INCREMENT);

        recordPriceIfChanged(listing, broker.getId(), request);

        listing.changeDescription(request.description());
        listing.applyEditable(request, coordinatesChanged(listing, request)
                        ? coordinateGenerator.obfuscate(request.lat(), request.lng()) : null,
                regionResolver.resolveIdForSave(request.regionCode(), request.dongName(),
                        request.addressJibun(), request.addressRoad(), listing.getRegionId()));

        replaceChildren(listing.getId(), request, broker.getId());
        listingRepository.touchUpdatedAt(listing.getId(), LocalDateTime.now(clock));

        if (listing.getStatus().isVisibleToPublic()) {
            publishPolicy.requirePublishable(listing);
        }
        return assembleAfterFlush(listing);
    }

    // ───────────────────────── 상태 전이

    @Transactional
    public BrokerListingResponse publish(String userUuid, String listingUuid) {
        User broker = userQueryService.getActiveUser(userUuid);
        accessPolicy.requireAdvertisableOffice(broker.getId());
        Listing listing = requireOwned(broker, listingUuid);

        publishPolicy.requirePublishable(listing);
        LocalDateTime now = LocalDateTime.now(clock);
        listing.publish(now, now.plusMonths(LISTING_MONTHS));

        recordPriceIfChanged(listing, broker.getId(), null);
        return assembleAfterFlush(listing);
    }

    @Transactional
    public BrokerListingResponse unpublish(String userUuid, String listingUuid) {
        Listing listing = requireOwned(userUuid, listingUuid);
        listing.unpublish();
        return assembleAfterFlush(listing);
    }

    @Transactional
    public BrokerListingResponse complete(String userUuid, String listingUuid) {
        Listing listing = requireOwned(userUuid, listingUuid);
        listing.complete();
        return assembleAfterFlush(listing);
    }

    @Transactional
    public BrokerListingResponse reserve(String userUuid, String listingUuid) {
        Listing listing = requireOwned(userUuid, listingUuid);
        listing.reserve();
        return assembleAfterFlush(listing);
    }

    @Transactional
    public BrokerListingResponse unreserve(String userUuid, String listingUuid) {
        Listing listing = requireOwned(userUuid, listingUuid);
        listing.unreserve();
        return assembleAfterFlush(listing);
    }

    @Transactional
    public BrokerListingResponse renew(String userUuid, String listingUuid) {
        User broker = userQueryService.getActiveUser(userUuid);
        accessPolicy.requireAdvertisableOffice(broker.getId());
        Listing listing = requireOwned(broker, listingUuid);

        publishPolicy.requirePublishable(listing);

        LocalDateTime now = LocalDateTime.now(clock);
        boolean withinWindow = listing.getExpiresAt() != null
                && !now.isAfter(listing.getExpiresAt().plusDays(RENEWAL_GRACE_DAYS));
        listing.renew(now.plusMonths(LISTING_MONTHS), withinWindow);
        return assembleAfterFlush(listing);
    }

    /*
     * 삭제. **DRAFT 만** — 게시된 적이 있으면 허위매물 분쟁의 증거라 행을 지울 수 없다.
     *
     * ★ 자식 행은 FK CASCADE 가 지우지만 files 행은 남음(FK RESTRICT). 그래서 지우기 전에 사진 파일을 모아 두고
     *   다른 매물에 안 붙은 것만 폐기 표시함.
     */
    @Transactional
    public void delete(String userUuid, String listingUuid) {
        Listing listing = requireOwned(userUuid, listingUuid);
        if (!listing.getStatus().isDeletable()) {
            throw new ListingException(LISTING_NOT_DELETABLE);
        }
        List<Long> fileIds = imageRepository.findByListingIdOrderBySortOrderAsc(listing.getId()).stream()
                .map(ListingImage::getFileId).toList();
        listingRepository.delete(listing);
        discardDetached(listing.getId(), fileIds, List.of());
    }

    // ───────────────────────── 내부

    private Sort sortOf(BrokerListingSort sort) {
        Sort byIdDesc = Sort.by(Sort.Direction.DESC, "id");
        return switch (sort) {
            case UPDATED_DESC -> Sort.by(Sort.Direction.DESC, "updatedAt").and(byIdDesc);
            case CREATED_DESC -> Sort.by(Sort.Direction.DESC, "createdAt").and(byIdDesc);
            case POSTED_DESC -> Sort.by(Sort.Direction.DESC, "postedAt").and(byIdDesc);
            case EXPIRES_ASC -> throw new IllegalArgumentException("EXPIRES_ASC 는 Repository 쿼리가 정렬");
        };
    }

    private Long userIdOf(String userUuid) {
        return userQueryService.getActiveUser(userUuid).getId();
    }

    private Listing requireOwned(String userUuid, String listingUuid) {
        return requireOwned(userQueryService.getActiveUser(userUuid), listingUuid);
    }

    private Listing requireOwned(User broker, String listingUuid) {
        Listing listing = listingRepository.findByUuid(listingUuid)
                .orElseThrow(() -> new ListingException(LISTING_NOT_FOUND));
        accessPolicy.requireOwnership(accessPolicy.requireProfile(broker.getId()), listing);
        return listing;
    }

    private void validate(BrokerListingSaveRequest request) {
        if (request.floor() != null && request.floorBand() != null) {
            throw new ListingException(FLOOR_INPUT_INVALID);
        }
        requireNotSmaller(request.supplyArea(), request.exclusiveArea());
        requireNotSmaller(request.contractArea(), request.supplyArea());
        requireNotSmaller(request.contractArea(), request.exclusiveArea());

        if (request.imageFileUuids() != null && request.imageFileUuids().size() > MAX_IMAGES) {
            throw new ListingException(LISTING_IMAGE_LIMIT_EXCEEDED);
        }

        // 비목마다 한 행(uq_lmi). 겹치면 두 번째 INSERT 가 유니크 위반이고 처리기가 없어 500 이 됨 — 저장 전에 거절.
        if (request.maintenance() != null && request.maintenance().items() != null) {
            List<BrokerListingSaveRequest.ItemPart> items = request.maintenance().items();
            if (items.stream().map(BrokerListingSaveRequest.ItemPart::itemCode).distinct().count() < items.size()) {
                throw new ListingException(CommonErrorCode.INVALID_REQUEST);
            }
        }
    }

    private void requireNotSmaller(BigDecimal bigger, BigDecimal smaller) {
        if (bigger != null && smaller != null && bigger.compareTo(smaller) < 0) {
            throw new ListingException(AREA_RANGE_INVALID);
        }
    }

    private boolean coordinatesChanged(Listing listing, BrokerListingSaveRequest request) {
        return listing.getRealLat().compareTo(request.lat()) != 0
                || listing.getRealLng().compareTo(request.lng()) != 0;
    }

    private void recordPriceIfChanged(Listing listing, Long changedBy, BrokerListingSaveRequest request) {
        var type = request == null ? listing.getSellingType() : request.sellingType();
        var deposit = request == null ? listing.getDeposit() : request.deposit();
        var rent = request == null ? listing.getMonthlyRent()
                : (request.monthlyRent() == null ? 0L : request.monthlyRent());

        boolean same = priceHistoryRepository
                .findTopByListingIdOrderByChangedAtDesc(listing.getId())
                .map(last -> last.samePriceAs(type, deposit, rent))
                .orElse(false);
        if (same) {
            return;
        }
        priceHistoryRepository.save(ListingPriceHistory.builder()
                .listingId(listing.getId()).sellingType(type)
                .deposit(deposit).monthlyRent(rent)
                .changedBy(changedBy).changedAt(LocalDateTime.now(clock))
                .build());
    }

    private void replaceChildren(Long listingId, BrokerListingSaveRequest request, Long uploaderId) {
        List<Long> previousFileIds = imageRepository.findByListingIdOrderBySortOrderAsc(listingId).stream()
                .map(ListingImage::getFileId).toList();
        Set<String> alreadyAttached = attachedFileUuids(previousFileIds);

        itemRepository.deleteByListingId(listingId);
        includeRepository.deleteByListingId(listingId);
        optionRepository.deleteByListingId(listingId);
        safetyRepository.deleteByListingId(listingId);
        imageRepository.deleteByListingId(listingId);
        entityManager.flush();

        var m = request.maintenance();

        if (m == null) {
            maintenanceRepository.findById(listingId).ifPresent(maintenanceRepository::delete);
        }
        if (m != null) {
            maintenanceRepository.save(ListingMaintenance.builder()
                    .listingId(listingId).chargeType(m.chargeType())
                    .fixedDetailType(m.fixedDetailType()).totalCost(m.totalCost())
                    .reasonCode(m.reasonCode()).reasonEtcDesc(m.reasonEtcDesc())
                    .standardType(m.standardType()).standardEtcDesc(m.standardEtcDesc())
                    .build());
            if (m.items() != null) {
                m.items().forEach(i -> itemRepository.save(ListingMaintenanceItem.builder()
                        .listingId(listingId).itemCode(i.itemCode())
                        .actualCost(i.actualCostOrFalse()).cost(i.cost()).etcDesc(i.etcDesc()).build()));
            }
            if (m.includes() != null) {
                m.includes().forEach(code -> includeRepository.save(
                        ListingMaintenanceInclude.builder().listingId(listingId).itemCode(code).build()));
            }
        }
        if (request.optionCodes() != null) {
            request.optionCodes().forEach(code -> optionRepository.save(
                    ListingOption.builder().listingId(listingId).code(code).build()));
        }
        if (request.safetyCodes() != null) {
            request.safetyCodes().forEach(code -> safetyRepository.save(
                    ListingSafety.builder().listingId(listingId).code(code).build()));
        }

        List<Long> attachedFileIds = new ArrayList<>();
        if (request.imageFileUuids() != null) {
            List<String> uuids = request.imageFileUuids();
            for (int i = 0; i < uuids.size(); i++) {
                String uuid = uuids.get(i);
                Long fileId = alreadyAttached.contains(uuid)
                        ? requireListingImage(uuid)
                        : requireOwnListingImage(uuid, uploaderId);
                imageRepository.save(ListingImage.builder()
                        .listingId(listingId).fileId(fileId).sortOrder(i).build());
                attachedFileIds.add(fileId);
            }
        }
        discardDetached(listingId, previousFileIds, attachedFileIds);
    }

    // 빠진 사진은 폐기 표시만 함 — 스토리지는 정리 배치가 나중에 만지므로 이 트랜잭션이 롤백되면(게시 관문 등) 표시도 되돌아감.
    // 다른 매물에 아직 붙은 파일은 남김. 파일 행을 먼저 잠그고 잠금 읽기로 다시 봄 — 같은 파일을 새로 붙이는 저장(파일 공유 잠금)과
    // 줄 세워, 그사이 다른 매물에 붙은 사진을 버리지 않음.
    private void discardDetached(Long listingId, Collection<Long> before, Collection<Long> after) {
        Set<Long> detached = new HashSet<>(before);
        detached.removeAll(after);
        if (detached.isEmpty()) {
            return;
        }
        storedFileRepository.findAllByIdForUpdate(detached);
        imageRepository.findAttachedElsewhereForShare(detached, listingId)
                .forEach(image -> detached.remove(image.getFileId()));
        if (!detached.isEmpty()) {
            fileDeletionService.discardByIds(detached);
        }
    }

    private Long requireOwnListingImage(String fileUuid, Long uploaderId) {
        return storedFileRepository.findForShareByUuid(fileUuid)
                .filter(f -> f.getPurpose() == FilePurpose.LISTING_IMAGE)
                .filter(f -> uploaderId.equals(f.getUploaderId()))
                .map(StoredFile::getId)
                .orElseThrow(() -> new ListingException(LISTING_IMAGE_INVALID));
    }

    private Long requireListingImage(String fileUuid) {
        return storedFileRepository.findByUuidAndDeletedAtIsNull(fileUuid)
                .filter(f -> f.getPurpose() == FilePurpose.LISTING_IMAGE)
                .map(StoredFile::getId)
                .orElseThrow(() -> new ListingException(LISTING_IMAGE_INVALID));
    }

    private Set<String> attachedFileUuids(List<Long> fileIds) {
        if (fileIds.isEmpty()) {
            return Set.of();
        }
        return storedFileRepository.findByIdInAndDeletedAtIsNull(fileIds).stream()
                .map(StoredFile::getUuid).collect(Collectors.toSet());
    }


    private Map<Long, String> thumbnailsOf(List<Listing> listings) {
        if (listings.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> firstFile = new HashMap<>();
        imageRepository.findByListingIdInOrderBySortOrderAsc(
                        listings.stream().map(Listing::getId).toList())
                .forEach(image -> firstFile.putIfAbsent(image.getListingId(), image.getFileId()));
        Map<Long, String> urlById = urlsByFileId(firstFile.values());
        Map<Long, String> out = new HashMap<>();
        firstFile.forEach((listingId, fileId) -> out.put(listingId, urlById.get(fileId)));
        return out;
    }

    private Map<Long, String> urlsByFileId(Collection<Long> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> out = new HashMap<>();
        storedFileRepository.findByIdInAndDeletedAtIsNull(new HashSet<>(fileIds))
                .forEach(f -> out.put(f.getId(), f.getFileUrl()));
        return out;
    }

    // 응답의 version 이 커밋 뒤 값이 되게 먼저 flush. 상태 전이는 여기 전까지 flush 가 없어 한 칸 낮은 값이 나가고,
    // 앱이 그 값으로 폼을 열면 다음 저장이 거짓 409 가 됨.
    private BrokerListingResponse assembleAfterFlush(Listing listing) {
        entityManager.flush();
        return assemble(listing);
    }

    private BrokerListingResponse assemble(Listing listing) {
        Long id = listing.getId();
        var items = itemRepository.findByListingId(id).stream()
                .map(i -> new BrokerListingResponse.ItemPart(
                        i.getItemCode(), i.isActualCost(), i.getCost(), i.getEtcDesc()))
                .toList();
        List<MaintenanceItemCode> includes = includeRepository.findByListingId(id).stream()
                .map(ListingMaintenanceInclude::getItemCode).toList();

        return BrokerListingResponse.of(listing, regionResolver.codeOf(listing.getRegionId()),
                regionResolver.decidedByAddress(listing.getDongName(), listing.getAddressJibun(), listing.getAddressRoad()),
                BrokerListingResponse.MaintenancePart.of(
                        maintenanceRepository.findById(id).orElse(null), items, includes),
                optionRepository.findByListingId(id).stream().map(ListingOption::getCode).toList(),
                safetyRepository.findByListingId(id).stream().map(ListingSafety::getCode).toList(),
                imagePartsOf(id));
    }

    private List<BrokerListingResponse.ImagePart> imagePartsOf(Long listingId) {
        List<ListingImage> images = imageRepository.findByListingIdOrderBySortOrderAsc(listingId);
        Map<Long, StoredFile> byId = new HashMap<>();
        storedFileRepository.findByIdInAndDeletedAtIsNull(
                        images.stream().map(ListingImage::getFileId).toList())
                .forEach(f -> byId.put(f.getId(), f));
        return images.stream()
                .map(i -> {
                    StoredFile f = byId.get(i.getFileId());
                    return new BrokerListingResponse.ImagePart(
                            f == null ? null : f.getUuid(),
                            f == null ? null : f.getFileUrl(),
                            i.getSortOrder());
                })
                .toList();
    }

    private AvailabilityAnswer orAsk(AvailabilityAnswer value) {
        return value == null ? AvailabilityAnswer.ASK : value;
    }
}
