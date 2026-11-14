package com.kovil.domain.listing.service;

import com.kovil.common.dto.response.PageResponse;
import com.kovil.domain.listing.dto.request.ListingPolygon;
import com.kovil.domain.listing.dto.request.ListingRadius;
import com.kovil.domain.listing.dto.request.ListingSearchCondition;
import com.kovil.domain.listing.dto.response.*;
import com.kovil.domain.listing.entity.*;
import com.kovil.domain.listing.enums.ListingSort;
import com.kovil.domain.listing.enums.ListingStatus;
import com.kovil.domain.listing.enums.MaintenanceItemCode;
import com.kovil.domain.listing.enums.SellingType;
import com.kovil.domain.listing.exception.ListingException;
import com.kovil.domain.listing.repository.*;
import com.kovil.domain.region.entity.Region;
import com.kovil.domain.region.repository.RegionRepository;
import com.kovil.domain.user.service.UserQueryService;
import com.kovil.domain.file.repository.StoredFileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.kovil.common.exception.CommonErrorCode.INVALID_REQUEST;
import static com.kovil.domain.listing.exception.ListingErrorCode.LISTING_NOT_FOUND;
import static com.kovil.domain.listing.exception.ListingErrorCode.LISTING_POLYGON_TOO_LARGE;
import static com.kovil.domain.listing.exception.ListingErrorCode.LISTING_RADIUS_INVALID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ListingQueryService {

    private static final List<ListingStatus> VISIBLE =
            List.of(ListingStatus.ACTIVE, ListingStatus.RESERVED);

    private static final int PIN_THRESHOLD = 500;
    private static final int SUMMARY_LIMIT = 50;
    private static final int MIN_SAMPLE_SIZE = 5;
    private static final int POLYGON_CANDIDATE_LIMIT = 5_000;

    private final UserQueryService userQueryService;
    private final ListingRepository listingRepository;
    private final BrokerageOfficeRepository officeRepository;
    private final ListingMaintenanceRepository maintenanceRepository;
    private final ListingMaintenanceItemRepository itemRepository;
    private final ListingMaintenanceIncludeRepository includeRepository;
    private final ListingOptionRepository optionRepository;
    private final ListingSafetyRepository safetyRepository;
    private final ListingImageRepository imageRepository;
    private final ListingFavoriteRepository favoriteRepository;
    private final RegionRepository regionRepository;
    private final StoredFileRepository storedFileRepository;

    // ───────────────────────── 목록

    public PageResponse<ListingSummaryResponse> search(String viewerUuid,
                                                       ListingSearchCondition condition,
                                                       ListingRadius radius,
                                                       ListingPolygon polygon,
                                                       ListingSort sort, int page, int size) {
        if (radius != null && polygon != null) {
            throw new ListingException(LISTING_RADIUS_INVALID);
        }
        if (polygon != null) {
            return searchInPolygon(viewerUuid, condition, polygon, sort, page, size);
        }
        Page<Listing> found = (sort == null || sort == ListingSort.RECOMMENDED)
                ? call(condition, radius, PageRequest.of(page, size), true)
                : call(condition, radius, PageRequest.of(page, size, sortOf(sort)), false);

        List<Listing> content = found.getContent();
        Map<Long, ListingSummaryResponse> cards = cardsOf(
                content, thumbnailsOf(content), favoritedIdsOf(viewerUuid, content)
        );
        return PageResponse.from(found.map(l -> cards.get(l.getId())));
    }

    private PageResponse<ListingSummaryResponse> searchInPolygon(String viewerUuid, ListingSearchCondition condition,
                                                                 ListingPolygon polygon, ListingSort sort,
                                                                 int page, int size) {
        boolean box = condition.hasBoundingBox();
        BigDecimal swLat = box ? condition.swLat().max(polygon.minLat()) : polygon.minLat();
        BigDecimal swLng = box ? condition.swLng().max(polygon.minLng()) : polygon.minLng();
        BigDecimal neLat = box ? condition.neLat().min(polygon.maxLat()) : polygon.maxLat();
        BigDecimal neLng = box ? condition.neLng().min(polygon.maxLng()) : polygon.maxLng();

        List<ListingPoint> candidates = candidatesOf(condition, swLat, swLng, neLat, neLng, sort);
        if (candidates.size() > POLYGON_CANDIDATE_LIMIT) {
            throw new ListingException(LISTING_POLYGON_TOO_LARGE);
        }
        List<Long> inside = candidates.stream()
                .filter(p -> polygon.contains(p.lat(), p.lng()))
                .map(ListingPoint::id)
                .toList();

        int from = (int) Math.min((long) page * size, inside.size());
        int to = Math.min(from + size, inside.size());
        List<Listing> content = inOrder(inside.subList(from, to));
        Map<Long, ListingSummaryResponse> cards = cardsOf(
                content, thumbnailsOf(content), favoritedIdsOf(viewerUuid, content)
        );
        List<ListingSummaryResponse> items = content.stream().map(l -> cards.get(l.getId())).toList();
        return PageResponse.from(new PageImpl<>(items, PageRequest.of(page, size), inside.size()));
    }

    private List<ListingPoint> candidatesOf(ListingSearchCondition condition,
                                            BigDecimal swLat, BigDecimal swLng, BigDecimal neLat, BigDecimal neLng,
                                            ListingSort sort) {
        if (sort == null || sort == ListingSort.RECOMMENDED) {
            return listingRepository.findPublicPointsRecommended(VISIBLE, condition.category(),
                    condition.sellingTypes(), condition.roomTypes(), condition.floorTypes(),
                    condition.depositMin(), condition.depositMax(), condition.rentMin(), condition.rentMax(),
                    condition.areaMin(), condition.areaMax(), condition.canParking(), condition.shortLease(),
                    condition.residentRegistration(), condition.regionId(), condition.officeUuid(),
                    swLat, swLng, neLat, neLng,
                    PageRequest.of(0, POLYGON_CANDIDATE_LIMIT + 1));
        }
        return listingRepository.findPublicPoints(VISIBLE, condition.category(),
                condition.sellingTypes(), condition.roomTypes(), condition.floorTypes(),
                condition.depositMin(), condition.depositMax(), condition.rentMin(), condition.rentMax(),
                condition.areaMin(), condition.areaMax(), condition.canParking(), condition.shortLease(),
                condition.residentRegistration(), condition.regionId(), condition.officeUuid(),
                swLat, swLng, neLat, neLng,
                PageRequest.of(0, POLYGON_CANDIDATE_LIMIT + 1, sortOf(sort)));
    }

    private List<Listing> inOrder(List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, Listing> byId = listingRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Listing::getId, Function.identity()));
        return ids.stream().map(byId::get).filter(Objects::nonNull).toList();
    }

    // ───────────────────────── 지도

    public ListingMapResponse map(ListingSearchCondition condition) {
        long total = listingRepository.countPublic(VISIBLE, condition.category(),
                condition.sellingTypes(), condition.roomTypes(), condition.floorTypes(),
                condition.depositMin(), condition.depositMax(), condition.rentMin(), condition.rentMax(),
                condition.areaMin(), condition.areaMax(), condition.canParking(), condition.shortLease(),
                condition.residentRegistration(), condition.regionId(),
                condition.effectiveSwLat(), condition.effectiveSwLng(), condition.effectiveNeLat(), condition.effectiveNeLng());

        if (total > PIN_THRESHOLD) {
            List<RegionCluster> clusters = listingRepository.aggregatePublicByRegion(
                    VISIBLE, condition.category(), condition.sellingTypes(), condition.roomTypes(), condition.floorTypes(),
                    condition.depositMin(), condition.depositMax(), condition.rentMin(), condition.rentMax(),
                    condition.areaMin(), condition.areaMax(), condition.canParking(), condition.shortLease(),
                    condition.residentRegistration(), condition.regionId(),
                    condition.effectiveSwLat(), condition.effectiveSwLng(), condition.effectiveNeLat(), condition.effectiveNeLng());
            return ListingMapResponse.clusters(total, withRegionNames(clusters));
        }

        List<Listing> found = call(condition, null, PageRequest.of(0, PIN_THRESHOLD), true).getContent();
        Map<Long, Region> regions = regionsOf(found);

        List<ListingMapResponse.Pin> pins = found.stream()
                .map(l -> {
                    Region region = l.getRegionId() == null ? null : regions.get(l.getRegionId());
                    return new ListingMapResponse.Pin(
                            l.getUuid(),
                            l.getDisplayLat(),
                            l.getDisplayLng(),
                            l.getDeposit(),
                            l.getMonthlyRent(),
                            l.getSellingType().name(),
                            region == null ? null : region.getCode());
                })
                .toList();

        return ListingMapResponse.pins(pins);
    }

    public ListingMarketPriceResponse marketPrices(ListingSearchCondition c, SellingType sellingType) {
        List<RegionCluster> inView = listingRepository.aggregatePublicByRegion(VISIBLE, c.category(),
                c.sellingTypes(), c.roomTypes(), c.floorTypes(),
                c.depositMin(), c.depositMax(), c.rentMin(), c.rentMax(),
                c.areaMin(), c.areaMax(), c.canParking(), c.shortLease(),
                c.residentRegistration(), c.regionId(),
                c.effectiveSwLat(), c.effectiveSwLng(), c.effectiveNeLat(), c.effectiveNeLng());

        if (inView.isEmpty()) {
            return new ListingMarketPriceResponse(MIN_SAMPLE_SIZE, List.of());
        }

        List<Long> regionIds = inView.stream().map(RegionCluster::regionId).toList();
        Map<Long, RegionPriceStat> stats = listingRepository.aggregatePublicPricesByRegion(VISIBLE, c.category(),
                        c.sellingTypes(), c.roomTypes(), c.floorTypes(),
                        c.depositMin(), c.depositMax(), c.rentMin(), c.rentMax(),
                        c.areaMin(), c.areaMax(), c.canParking(), c.shortLease(),
                        c.residentRegistration(), c.regionId(),
                        null, null, null, null, regionIds)
                .stream()
                .collect(Collectors.toMap(RegionPriceStat::regionId, Function.identity()));
        Map<Long, Region> regions = regionRepository.findByIdIn(regionIds).stream()
                .collect(Collectors.toMap(Region::getId, Function.identity()));

        List<ListingMarketPriceResponse.Region> rows = inView.stream()
                .sorted(Comparator.comparingLong(RegionCluster::count).reversed()
                        .thenComparing(RegionCluster::regionId))
                .map(v -> priceRow(v, stats.get(v.regionId()), regions.get(v.regionId()), sellingType))
                .toList();
        return new ListingMarketPriceResponse(MIN_SAMPLE_SIZE, rows);
    }

    @Transactional(readOnly = true)
    public PageResponse<ListingFavoriteItem> findMyFavorites(
            String userUuid,
            Pageable pageable
    ) {
        Long userId = userQueryService.getActiveUser(userUuid).getId();
        Page<ListingFavorite> favorites = favoriteRepository
                .findPageByUserId(userId, pageable);

        List<Long> ids = favorites.getContent().stream()
                .map(ListingFavorite::getListingId)
                .toList();

        Map<Long, Listing> byId = listingRepository.findAllById(ids)
                .stream()
                .collect(Collectors.toMap(Listing::getId, Function.identity()));

        Set<Long> publicIds = ids.isEmpty() ? Set.of() : new HashSet<>(listingRepository.findPublicIdsIn(ids, VISIBLE));

        List<Listing> listings = new ArrayList<>(byId.values());

        Map<Long, String> thumbnails = thumbnailsOf(listings);
        Map<Long, ListingSummaryResponse> cards = cardsOf(
                listings.stream().filter(l -> publicIds.contains(l.getId())).toList(),
                thumbnails,
                publicIds
        );

        return PageResponse.from(favorites.map(favorite -> {
            Listing listing = byId.get(favorite.getListingId());
            if (publicIds.contains(listing.getId())) {
                return ListingFavoriteItem.available(listing, favorite.getCreatedAt(), cards.get(listing.getId()));
            }
            String thumbnail = listing.getStatus() == ListingStatus.BLOCKED ? null : thumbnails.get(listing.getId());
            return ListingFavoriteItem.gone(listing, favorite.getCreatedAt(), thumbnail);
        }));
    }

    // 묶음 summaries

    public ListingSummaryBatchResponse summaries(String viewerUuid, List<String> listingUuids) {
        if (listingUuids == null || listingUuids.isEmpty() || listingUuids.size() > SUMMARY_LIMIT) {
            throw new ListingException(INVALID_REQUEST);
        }

        List<String> requested = listingUuids.stream().distinct().toList();

        List<Listing> listings = listingRepository.findPublicByUuidIn(requested, VISIBLE);
        Map<Long, ListingSummaryResponse> cards = cardsOf(
                listings, thumbnailsOf(listings), favoritedIdsOf(viewerUuid, listings)
        );
        Map<String, ListingSummaryResponse> byUuid = listings.stream()
                .collect(Collectors.toMap(Listing::getUuid, l -> cards.get(l.getId())));

        List<ListingSummaryResponse> items = new ArrayList<>();
        List<String> missing = new ArrayList<>();

        for (String uuid : requested) {
            ListingSummaryResponse card = byUuid.get(uuid);
            if (card == null) {
                missing.add(uuid);
            } else {
                items.add(card);
            }
        }

        return new ListingSummaryBatchResponse(items, missing);
    }

    // ───────────────────────── 상세

    @Transactional
    public ListingDetailResponse getDetail(String viewerUuid, String listingUuid) {
        Listing listing = listingRepository.findPublicByUuid(listingUuid, VISIBLE)
                .orElseThrow(() -> new ListingException(LISTING_NOT_FOUND));
        BrokerageOffice office = officeRepository.findById(listing.getOfficeId())
                .orElseThrow(() -> new ListingException(LISTING_NOT_FOUND));

        listingRepository.increaseViewCount(listing.getId());

        Long id = listing.getId();
        List<ListingDetailResponse.ItemView> items = itemRepository.findByListingId(id).stream()
                .map(i -> new ListingDetailResponse.ItemView(
                        i.getItemCode(), i.isActualCost(), i.getCost(), i.getEtcDesc()))
                .toList();
        List<MaintenanceItemCode> includes = includeRepository.findByListingId(id).stream()
                .map(ListingMaintenanceInclude::getItemCode).toList();
        ListingMaintenance m = maintenanceRepository.findById(id).orElse(null);

        return ListingDetailResponse.of(listing, office,
                m == null ? null : new ListingDetailResponse.MaintenanceView(
                        m.getChargeType(), m.getFixedDetailType(), m.getTotalCost(),
                        m.getReasonCode(), m.getReasonEtcDesc(),
                        m.getStandardType(), m.getStandardEtcDesc(), items, includes),
                optionRepository.findByListingId(id).stream().map(ListingOption::getCode).toList(),
                safetyRepository.findByListingId(id).stream().map(ListingSafety::getCode).toList(),
                imageUrlsOf(id),
                isFavorited(viewerUuid, id));
    }

    // ───────────────────────── 내부

    private Page<Listing> call(
            ListingSearchCondition condition,
            ListingRadius radius,
            Pageable pageable,
            boolean recommended
    ) {
        if (radius != null) {
            return callWithin(condition, radius, pageable, recommended);
        }

        if (recommended) {
            return listingRepository.findPublicRecommended(VISIBLE, condition.category(),
                    condition.sellingTypes(), condition.roomTypes(), condition.floorTypes(),
                    condition.depositMin(), condition.depositMax(), condition.rentMin(), condition.rentMax(),
                    condition.areaMin(), condition.areaMax(), condition.canParking(), condition.shortLease(),
                    condition.residentRegistration(), condition.regionId(), condition.officeUuid(),
                    condition.effectiveSwLat(), condition.effectiveSwLng(), condition.effectiveNeLat(), condition.effectiveNeLng(),
                    pageable);
        }
        return listingRepository.findPublic(VISIBLE, condition.category(),
                condition.sellingTypes(), condition.roomTypes(), condition.floorTypes(),
                condition.depositMin(), condition.depositMax(), condition.rentMin(), condition.rentMax(),
                condition.areaMin(), condition.areaMax(), condition.canParking(), condition.shortLease(),
                condition.residentRegistration(), condition.regionId(), condition.officeUuid(),
                condition.effectiveSwLat(), condition.effectiveSwLng(), condition.effectiveNeLat(), condition.effectiveNeLng(),
                pageable);
    }

    private Page<Listing> callWithin(ListingSearchCondition condition, ListingRadius radius, Pageable pageable, boolean recommended) {
        boolean box = condition.hasBoundingBox();
        BigDecimal swLat = box ? condition.swLat().max(radius.minLat()) : radius.minLat();
        BigDecimal swLng = box ? condition.swLng().max(radius.minLng()) : radius.minLng();
        BigDecimal neLat = box ? condition.neLat().min(radius.maxLat()) : radius.maxLat();
        BigDecimal neLng = box ? condition.neLng().min(radius.maxLng()) : radius.maxLng();
        if (recommended) {
            return listingRepository.findPublicRecommendedWithin(VISIBLE, condition.category(),
                    condition.sellingTypes(), condition.roomTypes(), condition.floorTypes(),
                    condition.depositMin(), condition.depositMax(), condition.rentMin(), condition.rentMax(),
                    condition.areaMin(), condition.areaMax(), condition.canParking(), condition.shortLease(),
                    condition.residentRegistration(), condition.regionId(), condition.officeUuid(),
                    swLat, swLng, neLat, neLng,
                    radius.lat(), radius.lng(), radius.lngScaleSq(), radius.radiusDegSq(), pageable);
        }
        return listingRepository.findPublicWithin(VISIBLE, condition.category(),
                condition.sellingTypes(), condition.roomTypes(), condition.floorTypes(),
                condition.depositMin(), condition.depositMax(), condition.rentMin(), condition.rentMax(),
                condition.areaMin(), condition.areaMax(), condition.canParking(), condition.shortLease(),
                condition.residentRegistration(), condition.regionId(), condition.officeUuid(),
                swLat, swLng, neLat, neLng,
                radius.lat(), radius.lng(), radius.lngScaleSq(), radius.radiusDegSq(), pageable);
    }

    private Sort sortOf(ListingSort sort) {
        return switch (sort) {
            case LATEST -> Sort.by(Sort.Direction.DESC, "postedAt").and(byIdDesc());
            case PRICE_ASC -> Sort.by(Sort.Direction.ASC, "deposit", "monthlyRent").and(byIdDesc());
            case PRICE_DESC -> Sort.by(Sort.Direction.DESC, "deposit", "monthlyRent").and(byIdDesc());
            case AREA_DESC -> Sort.by(Sort.Direction.DESC, "exclusiveArea").and(byIdDesc());
            case RECOMMENDED -> byIdDesc();
        };
    }

    private Sort byIdDesc() {
        return Sort.by(Sort.Direction.DESC, "id");
    }

    private Map<Long, ListingSummaryResponse> cardsOf(
            List<Listing> listings,
            Map<Long, String> thumbnails,
            Set<Long> favoritedIds
    ) {
        if (listings.isEmpty()) {
            return Map.of();
        }

        List<Long> ids = listings.stream()
                .map(Listing::getId)
                .toList();

        Map<Long, ListingMaintenance> maintenance = maintenanceRepository.findByListingIdIn(ids)
                .stream()
                .collect(Collectors.toMap(ListingMaintenance::getListingId, Function.identity()));
        Set<Long> actualCost = new HashSet<>(itemRepository.findListingIdsWithActualCost(ids));
        Map<Long, Region> regions = regionsOf(listings);

        Map<Long, ListingSummaryResponse> cards = new HashMap<>();

        for (Listing listing : listings) {
            Region region = listing.getRegionId() == null ? null : regions.get(listing.getRegionId());
            cards.put(listing.getId(), ListingSummaryResponse.of(
                            listing, maintenance.get(listing.getId()), actualCost.contains(listing.getId()),
                            region == null ? null : region.getCode(),
                            region == null ? null : localizedName(region),
                            thumbnails.get(listing.getId()), favoritedIds.contains(listing.getId()
                            )
                    )
            );
        }
        return cards;
    }

    private Map<Long, Region> regionsOf(List<Listing> listings) {
        Set<Long> regionIds = listings.stream()
                .map(Listing::getRegionId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (regionIds.isEmpty()) {
            return Map.of();
        }

        return regionRepository.findByIdIn(regionIds).stream()
                .collect(Collectors.toMap(Region::getId, Function.identity()));
    }

    private String localizedName(Region region) {
        boolean korean = Locale.KOREAN.getLanguage().equals(LocaleContextHolder.getLocale().getLanguage());
        return korean ? region.getNameKo() : region.getNameEn();
    }

    private Map<Long, String> thumbnailsOf(List<Listing> listings) {
        if (listings.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> firstFile = new HashMap<>();
        imageRepository.findByListingIdInOrderBySortOrderAsc(
                        listings.stream().map(Listing::getId).toList())
                .forEach(image -> firstFile.putIfAbsent(image.getListingId(), image.getFileId()));
        Map<Long, String> urlById = new HashMap<>();
        if (!firstFile.isEmpty()) {
            storedFileRepository.findByIdInAndDeletedAtIsNull(new HashSet<>(firstFile.values()))
                    .forEach(f -> urlById.put(f.getId(), f.getFileUrl()));
        }
        Map<Long, String> out = new HashMap<>();
        firstFile.forEach((listingId, fileId) -> out.put(listingId, urlById.get(fileId)));
        return out;
    }

    private List<String> imageUrlsOf(Long listingId) {
        List<Long> fileIds = imageRepository.findByListingIdOrderBySortOrderAsc(listingId)
                .stream().map(ListingImage::getFileId).toList();
        if (fileIds.isEmpty()) {
            return List.of();
        }
        Map<Long, String> urlById = new HashMap<>();
        storedFileRepository.findByIdInAndDeletedAtIsNull(fileIds)
                .forEach(f -> urlById.put(f.getId(), f.getFileUrl()));
        return fileIds.stream().map(urlById::get).filter(Objects::nonNull).toList();
    }

    private Set<Long> favoritedIdsOf(String viewerUuid, List<Listing> listings) {
        if (viewerUuid == null || listings.isEmpty()) {
            return Set.of();
        }
        Long viewerId = userQueryService.getActiveUser(viewerUuid).getId();
        return new HashSet<>(favoriteRepository.findFavoritedListingIds(
                viewerId, listings.stream().map(Listing::getId).toList()));
    }

    private boolean isFavorited(String viewerUuid, Long listingId) {
        return viewerUuid != null && favoriteRepository.existsByUserIdAndListingId(
                userQueryService.getActiveUser(viewerUuid).getId(), listingId);
    }

    private List<ListingMapResponse.Cluster> withRegionNames(List<RegionCluster> clusters) {
        if (clusters.isEmpty()) {
            return List.of();
        }
        Map<Long, Region> byId = regionRepository
                .findByIdIn(clusters.stream().map(RegionCluster::regionId).toList())
                .stream().collect(Collectors.toMap(
                        Region::getId,
                        region -> region, (a, b) -> a));

        return clusters.stream()
                .map(c -> {
                    var region = byId.get(c.regionId());
                    return new ListingMapResponse.Cluster(
                            region == null ? null : region.getCode(),
                            region == null ? null : localizedName(region),
                            c.count(), c.lat(), c.lng());
                })
                .toList();
    }

    private ListingMarketPriceResponse.Region priceRow(RegionCluster inView, RegionPriceStat stat,
                                                       Region region, SellingType sellingType) {
        long sample = stat == null ? 0 : stat.sampleSize();
        boolean enough = sample >= MIN_SAMPLE_SIZE;
        return new ListingMarketPriceResponse.Region(
                region == null ? null : region.getCode(),
                region == null ? null : localizedName(region),
                stat == null || stat.avgLat() == null ? null : BigDecimal.valueOf(stat.avgLat()),
                stat == null || stat.avgLng() == null ? null : BigDecimal.valueOf(stat.avgLng()),
                inView.count(),
                sample,
                enough ? roundWon(stat.avgDeposit()) : null,
                enough && sellingType.hasMonthlyRent() ? roundWon(stat.avgMonthlyRent()) : null);
    }

    private static Long roundWon(Double average) {
        return average == null ? null : Math.round(average);
    }
}