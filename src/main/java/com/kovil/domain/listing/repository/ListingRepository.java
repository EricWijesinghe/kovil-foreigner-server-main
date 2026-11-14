package com.kovil.domain.listing.repository;

import com.kovil.domain.listing.entity.Listing;
import com.kovil.domain.listing.enums.AvailabilityAnswer;
import com.kovil.domain.listing.enums.FloorType;
import com.kovil.domain.listing.enums.ListingCategory;
import com.kovil.domain.listing.enums.ListingStatus;
import com.kovil.domain.listing.enums.RoomType;
import com.kovil.domain.listing.enums.SellingType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.math.BigDecimal;

public interface ListingRepository extends JpaRepository<Listing, Long> {


    Optional<Listing> findByUuid(String uuid);

    Page<Listing> findByOfficeIdAndStatusIn(
            Long officeId, Collection<ListingStatus> statuses, Pageable pageable);

    @Query(value = "SELECT l FROM Listing l " +
            "WHERE l.officeId = :officeId AND l.status IN :statuses " +
            "ORDER BY CASE WHEN l.expiresAt IS NULL THEN 1 ELSE 0 END, " +
            "l.expiresAt ASC, l.id DESC",
            countQuery = "SELECT COUNT(l) FROM Listing l WHERE l.officeId = :officeId " +
                    "AND l.status IN :statuses")
    Page<Listing> findByOfficeIdAndStatusInExpiringFirst(
            @Param("officeId") Long officeId,
            @Param("statuses") Collection<ListingStatus> statuses,
            Pageable pageable
    );

    @Query("SELECT new com.kovil.domain.listing.repository.ListingStatusCount(l.status, COUNT(l)) "
            + "FROM Listing l WHERE l.officeId = :officeId GROUP BY l.status")
    List<ListingStatusCount> countByOfficeIdGroupByStatus(@Param("officeId") Long officeId);

    long countByOfficeIdAndStatusAndExpiresAtGreaterThanEqual(
            Long officeId,
            ListingStatus status,
            LocalDateTime threshold
    );

    Page<Listing> findByOfficeId(Long officeId, Pageable pageable);

    long countByOfficeIdAndStatusIn(Long officeId, Collection<ListingStatus> statuses);

    List<Listing> findAllByOfficeIdAndStatusIn(Long officeId, Collection<ListingStatus> statuses);

    List<Listing> findAllByRegionIdInAndStatusIn(Collection<Long> regionIds, Collection<ListingStatus> statuses);

    List<Listing> findAllByRegionIdInAndStatusAndExpiresAtGreaterThanEqual(
            Collection<Long> regionIds, ListingStatus status, LocalDateTime expiresAt);

    //   "최근 바뀐 순" 이 "최근 조회된 순" 이 돼서 조회수 증가 시점에 updatedAt 을 바꾸지 않도록 수정
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Listing l SET l.favoriteCount = l.favoriteCount - 1, l.updatedAt = l.updatedAt "
            + "WHERE l.id IN :ids AND l.favoriteCount > 0")
    int decrementFavoriteCount(@Param("ids") Collection<Long> ids);

    String PUBLIC_WHERE = """
            FROM Listing l
            WHERE l.status IN :visibleStatuses
              AND EXISTS (SELECT p.id FROM BrokerProfile p, PartnerDivision d, User u
                          WHERE p.officeId = l.officeId AND d.userId = p.userId
                            AND d.division = com.kovil.domain.partner.enums.PartnerDivisionType.REAL_ESTATE_BROKER
                            AND d.status = com.kovil.domain.partner.enums.PartnerDivisionStatus.APPROVED
                            AND u.id = p.userId AND u.deletedAt IS NULL
                            AND u.status = com.kovil.domain.user.enums.UserStatus.ACTIVE)
              AND (:category IS NULL OR l.category = :category)
              AND l.sellingType IN :sellingTypes
              AND l.roomType IN :roomTypes
              AND l.floorType IN :floorTypes
              AND (:depositMin IS NULL OR l.deposit >= :depositMin)
              AND (:depositMax IS NULL OR l.deposit <= :depositMax)
              AND (:rentMin IS NULL OR l.monthlyRent >= :rentMin)
              AND (:rentMax IS NULL OR l.monthlyRent <= :rentMax)
              AND (:areaMin IS NULL OR l.exclusiveArea >= :areaMin)
              AND (:areaMax IS NULL OR l.exclusiveArea <= :areaMax)
              AND (:canParking = FALSE OR l.canParking = TRUE)
              AND (:shortLease = FALSE OR l.shortLease = TRUE)
              AND (:residentRegistration IS NULL OR l.residentRegistration = :residentRegistration)
              AND (:regionId IS NULL OR l.regionId = :regionId)
              AND (:swLat IS NULL OR (l.displayLat BETWEEN :swLat AND :neLat
                                  AND l.displayLng BETWEEN :swLng AND :neLng))
            """;

    // 지도 핀도 findPublicRecommended 를 같이 쓰지만 지도 컨트롤러가 officeUuid 를 늘 null 로 넘긴다.
    // 지도에 사무소 필터를 열면 핀만 걸리고 개수(countPublic) · 묶음은 안 걸린다 — 그 쿼리들에는 이 조각이 없다.
    // 없는 uuid 는 서브쿼리가 비어 0건, 광고할 수 없는 사무소는 PUBLIC_WHERE 가 이미 뺀다 — 둘 다 빈 페이지.
    String OF_OFFICE = """
              AND (:officeUuid IS NULL OR l.officeId IN (SELECT o.id FROM BrokerageOffice o WHERE o.uuid = :officeUuid))
            """;

    @Query(value = "SELECT l " + PUBLIC_WHERE + OF_OFFICE
            + " ORDER BY CASE WHEN l.residentRegistration = "
            + "com.kovil.domain.listing.enums.AvailabilityAnswer.POSSIBLE "
            + "THEN 0 ELSE 1 END, l.postedAt DESC, l.id DESC",
            countQuery = "SELECT COUNT(l) " + PUBLIC_WHERE + OF_OFFICE)
    Page<Listing> findPublicRecommended(
            @Param("visibleStatuses") Collection<ListingStatus> visibleStatuses,
            @Param("category") ListingCategory category,
            @Param("sellingTypes") Collection<SellingType> sellingTypes,
            @Param("roomTypes") Collection<RoomType> roomTypes,
            @Param("floorTypes") Collection<FloorType> floorTypes,
            @Param("depositMin") Long depositMin, @Param("depositMax") Long depositMax,
            @Param("rentMin") Long rentMin, @Param("rentMax") Long rentMax,
            @Param("areaMin") BigDecimal areaMin, @Param("areaMax") BigDecimal areaMax,
            @Param("canParking") boolean canParking, @Param("shortLease") boolean shortLease,
            @Param("residentRegistration") AvailabilityAnswer residentRegistration,
            @Param("regionId") Long regionId,
            @Param("officeUuid") String officeUuid,
            @Param("swLat") BigDecimal swLat, @Param("swLng") BigDecimal swLng,
            @Param("neLat") BigDecimal neLat, @Param("neLng") BigDecimal neLng,
            Pageable pageable);

    @Query(value = "SELECT l " + PUBLIC_WHERE + OF_OFFICE, countQuery = "SELECT COUNT(l) " + PUBLIC_WHERE + OF_OFFICE)
    Page<Listing> findPublic(
            @Param("visibleStatuses") Collection<ListingStatus> visibleStatuses,
            @Param("category") ListingCategory category,
            @Param("sellingTypes") Collection<SellingType> sellingTypes,
            @Param("roomTypes") Collection<RoomType> roomTypes,
            @Param("floorTypes") Collection<FloorType> floorTypes,
            @Param("depositMin") Long depositMin, @Param("depositMax") Long depositMax,
            @Param("rentMin") Long rentMin, @Param("rentMax") Long rentMax,
            @Param("areaMin") BigDecimal areaMin, @Param("areaMax") BigDecimal areaMax,
            @Param("canParking") boolean canParking, @Param("shortLease") boolean shortLease,
            @Param("residentRegistration") AvailabilityAnswer residentRegistration,
            @Param("regionId") Long regionId,
            @Param("officeUuid") String officeUuid,
            @Param("swLat") BigDecimal swLat, @Param("swLng") BigDecimal swLng,
            @Param("neLat") BigDecimal neLat, @Param("neLng") BigDecimal neLng,
            Pageable pageable
    );

    String WITHIN_RADIUS = """
              AND (l.displayLat - :centerLat) * (l.displayLat - :centerLat)
                  + (l.displayLng - :centerLng) * (l.displayLng - :centerLng) * :lngScaleSq <= :radiusDegSq
            """;

    @Query(value = "SELECT l " + PUBLIC_WHERE + OF_OFFICE + WITHIN_RADIUS
            + " ORDER BY CASE WHEN l.residentRegistration = "
            + "com.kovil.domain.listing.enums.AvailabilityAnswer.POSSIBLE "
            + "THEN 0 ELSE 1 END, l.postedAt DESC, l.id DESC",
            countQuery = "SELECT COUNT(l) " + PUBLIC_WHERE + OF_OFFICE + WITHIN_RADIUS)
    Page<Listing> findPublicRecommendedWithin(
            @Param("visibleStatuses") Collection<ListingStatus> visibleStatuses,
            @Param("category") ListingCategory category,
            @Param("sellingTypes") Collection<SellingType> sellingTypes,
            @Param("roomTypes") Collection<RoomType> roomTypes,
            @Param("floorTypes") Collection<FloorType> floorTypes,
            @Param("depositMin") Long depositMin, @Param("depositMax") Long depositMax,
            @Param("rentMin") Long rentMin, @Param("rentMax") Long rentMax,
            @Param("areaMin") BigDecimal areaMin, @Param("areaMax") BigDecimal areaMax,
            @Param("canParking") boolean canParking, @Param("shortLease") boolean shortLease,
            @Param("residentRegistration") AvailabilityAnswer residentRegistration,
            @Param("regionId") Long regionId,
            @Param("officeUuid") String officeUuid,
            @Param("swLat") BigDecimal swLat, @Param("swLng") BigDecimal swLng,
            @Param("neLat") BigDecimal neLat, @Param("neLng") BigDecimal neLng,
            @Param("centerLat") BigDecimal centerLat, @Param("centerLng") BigDecimal centerLng,
            @Param("lngScaleSq") BigDecimal lngScaleSq, @Param("radiusDegSq") BigDecimal radiusDegSq,
            Pageable pageable
    );

    @Query(value = "SELECT l " + PUBLIC_WHERE + OF_OFFICE + WITHIN_RADIUS,
            countQuery = "SELECT COUNT(l) " + PUBLIC_WHERE + OF_OFFICE + WITHIN_RADIUS)
    Page<Listing> findPublicWithin(
            @Param("visibleStatuses") Collection<ListingStatus> visibleStatuses,
            @Param("category") ListingCategory category,
            @Param("sellingTypes") Collection<SellingType> sellingTypes,
            @Param("roomTypes") Collection<RoomType> roomTypes,
            @Param("floorTypes") Collection<FloorType> floorTypes,
            @Param("depositMin") Long depositMin, @Param("depositMax") Long depositMax,
            @Param("rentMin") Long rentMin, @Param("rentMax") Long rentMax,
            @Param("areaMin") BigDecimal areaMin, @Param("areaMax") BigDecimal areaMax,
            @Param("canParking") boolean canParking, @Param("shortLease") boolean shortLease,
            @Param("residentRegistration") AvailabilityAnswer residentRegistration,
            @Param("regionId") Long regionId,
            @Param("officeUuid") String officeUuid,
            @Param("swLat") BigDecimal swLat, @Param("swLng") BigDecimal swLng,
            @Param("neLat") BigDecimal neLat, @Param("neLng") BigDecimal neLng,
            @Param("centerLat") BigDecimal centerLat, @Param("centerLng") BigDecimal centerLng,
            @Param("lngScaleSq") BigDecimal lngScaleSq, @Param("radiusDegSq") BigDecimal radiusDegSq,
            Pageable pageable
    );

    @Query("SELECT new com.kovil.domain.listing.repository.ListingPoint(l.id, l.displayLat, l.displayLng) "
            + PUBLIC_WHERE + OF_OFFICE
            + " ORDER BY CASE WHEN l.residentRegistration = "
            + "com.kovil.domain.listing.enums.AvailabilityAnswer.POSSIBLE "
            + "THEN 0 ELSE 1 END, l.postedAt DESC, l.id DESC")
    List<ListingPoint> findPublicPointsRecommended(
            @Param("visibleStatuses") Collection<ListingStatus> visibleStatuses,
            @Param("category") ListingCategory category,
            @Param("sellingTypes") Collection<SellingType> sellingTypes,
            @Param("roomTypes") Collection<RoomType> roomTypes,
            @Param("floorTypes") Collection<FloorType> floorTypes,
            @Param("depositMin") Long depositMin,
            @Param("depositMax") Long depositMax,
            @Param("rentMin") Long rentMin,
            @Param("rentMax") Long rentMax,
            @Param("areaMin") BigDecimal areaMin,
            @Param("areaMax") BigDecimal areaMax,
            @Param("canParking") boolean canParking,
            @Param("shortLease") boolean shortLease,
            @Param("residentRegistration") AvailabilityAnswer residentRegistration,
            @Param("regionId") Long regionId,
            @Param("officeUuid") String officeUuid,
            @Param("swLat") BigDecimal swLat,
            @Param("swLng") BigDecimal swLng,
            @Param("neLat") BigDecimal neLat,
            @Param("neLng") BigDecimal neLng,
            Pageable pageable
    );


    @Query("SELECT new com.kovil.domain.listing.repository.ListingPoint(l.id, l.displayLat, l.displayLng) "
            + PUBLIC_WHERE + OF_OFFICE)
    List<ListingPoint> findPublicPoints(
            @Param("visibleStatuses") Collection<ListingStatus> visibleStatuses,
            @Param("category") ListingCategory category,
            @Param("sellingTypes") Collection<SellingType> sellingTypes,
            @Param("roomTypes") Collection<RoomType> roomTypes,
            @Param("floorTypes") Collection<FloorType> floorTypes,
            @Param("depositMin") Long depositMin,
            @Param("depositMax") Long depositMax,
            @Param("rentMin") Long rentMin,
            @Param("rentMax") Long rentMax,
            @Param("areaMin") BigDecimal areaMin,
            @Param("areaMax") BigDecimal areaMax,
            @Param("canParking") boolean canParking,
            @Param("shortLease") boolean shortLease,
            @Param("residentRegistration") AvailabilityAnswer residentRegistration,
            @Param("regionId") Long regionId,
            @Param("officeUuid") String officeUuid,
            @Param("swLat") BigDecimal swLat,
            @Param("swLng") BigDecimal swLng,
            @Param("neLat") BigDecimal neLat,
            @Param("neLng") BigDecimal neLng,
            Pageable pageable
    );

    @Query("SELECT COUNT(l) " + PUBLIC_WHERE)
    long countPublic(
            @Param("visibleStatuses") Collection<ListingStatus> visibleStatuses,
            @Param("category") ListingCategory category,
            @Param("sellingTypes") Collection<SellingType> sellingTypes,
            @Param("roomTypes") Collection<RoomType> roomTypes,
            @Param("floorTypes") Collection<FloorType> floorTypes,
            @Param("depositMin") Long depositMin, @Param("depositMax") Long depositMax,
            @Param("rentMin") Long rentMin, @Param("rentMax") Long rentMax,
            @Param("areaMin") BigDecimal areaMin, @Param("areaMax") BigDecimal areaMax,
            @Param("canParking") boolean canParking, @Param("shortLease") boolean shortLease,
            @Param("residentRegistration") AvailabilityAnswer residentRegistration,
            @Param("regionId") Long regionId,
            @Param("swLat") BigDecimal swLat, @Param("swLng") BigDecimal swLng,
            @Param("neLat") BigDecimal neLat, @Param("neLng") BigDecimal neLng);

    @Query("SELECT new com.kovil.domain.listing.repository.RegionCluster("
            + "l.regionId, COUNT(l), AVG(l.displayLat), AVG(l.displayLng)) " + PUBLIC_WHERE
            + " AND l.regionId IS NOT NULL GROUP BY l.regionId")
    List<RegionCluster> aggregatePublicByRegion(
            @Param("visibleStatuses") Collection<ListingStatus> visibleStatuses,
            @Param("category") ListingCategory category,
            @Param("sellingTypes") Collection<SellingType> sellingTypes,
            @Param("roomTypes") Collection<RoomType> roomTypes,
            @Param("floorTypes") Collection<FloorType> floorTypes,
            @Param("depositMin") Long depositMin, @Param("depositMax") Long depositMax,
            @Param("rentMin") Long rentMin, @Param("rentMax") Long rentMax,
            @Param("areaMin") BigDecimal areaMin, @Param("areaMax") BigDecimal areaMax,
            @Param("canParking") boolean canParking, @Param("shortLease") boolean shortLease,
            @Param("residentRegistration") AvailabilityAnswer residentRegistration,
            @Param("regionId") Long regionId,
            @Param("swLat") BigDecimal swLat, @Param("swLng") BigDecimal swLng,
            @Param("neLat") BigDecimal neLat, @Param("neLng") BigDecimal neLng);

    @Query("SELECT new com.kovil.domain.listing.repository.RegionPriceStat("
            + "l.regionId, COUNT(l), AVG(l.deposit), AVG(l.monthlyRent), AVG(l.displayLat), AVG(l.displayLng)) "
            + PUBLIC_WHERE
            + " AND l.regionId IN :regionIds GROUP BY l.regionId")
    List<RegionPriceStat> aggregatePublicPricesByRegion(
            @Param("visibleStatuses") Collection<ListingStatus> visibleStatuses,
            @Param("category") ListingCategory category,
            @Param("sellingTypes") Collection<SellingType> sellingTypes,
            @Param("roomTypes") Collection<RoomType> roomTypes,
            @Param("floorTypes") Collection<FloorType> floorTypes,
            @Param("depositMin") Long depositMin, @Param("depositMax") Long depositMax,
            @Param("rentMin") Long rentMin, @Param("rentMax") Long rentMax,
            @Param("areaMin") BigDecimal areaMin, @Param("areaMax") BigDecimal areaMax,
            @Param("canParking") boolean canParking, @Param("shortLease") boolean shortLease,
            @Param("residentRegistration") AvailabilityAnswer residentRegistration,
            @Param("regionId") Long regionId,
            @Param("swLat") BigDecimal swLat, @Param("swLng") BigDecimal swLng,
            @Param("neLat") BigDecimal neLat, @Param("neLng") BigDecimal neLng,
            @Param("regionIds") Collection<Long> regionIds);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Listing l SET l.favoriteCount = "
            + "(SELECT COUNT(f) FROM ListingFavorite f WHERE f.listingId = l.id), l.updatedAt = l.updatedAt "
            + "WHERE l.id = :id")
    void syncFavoriteCount(@Param("id") Long id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Listing l SET l.inquiryCount = l.inquiryCount + 1, l.updatedAt = l.updatedAt WHERE l.id = :id")
    void increaseInquiryCount(@Param("id") Long id);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE Listing l SET l.viewCount = l.viewCount + 1, l.updatedAt = l.updatedAt WHERE l.id = :id")
    void increaseViewCount(@Param("id") Long id);

    @Modifying(flushAutomatically = true)
    @Query("UPDATE Listing l SET l.updatedAt = :at WHERE l.id = :id")
    void touchUpdatedAt(@Param("id") Long id, @Param("at") LocalDateTime at);

    @Query("SELECT l FROM Listing l "
            + "WHERE l.uuid = :uuid AND l.status IN :visibleStatuses "
            + "AND EXISTS (SELECT p.id FROM BrokerProfile p, PartnerDivision d, User u "
            + "            WHERE p.officeId = l.officeId AND d.userId = p.userId "
            + "              AND d.division = com.kovil.domain.partner.enums.PartnerDivisionType.REAL_ESTATE_BROKER "
            + "              AND d.status = com.kovil.domain.partner.enums.PartnerDivisionStatus.APPROVED "
            + "              AND u.id = p.userId AND u.deletedAt IS NULL "
            + "              AND u.status = com.kovil.domain.user.enums.UserStatus.ACTIVE)")
    Optional<Listing> findPublicByUuid(@Param("uuid") String uuid,
                                       @Param("visibleStatuses") Collection<ListingStatus> visibleStatuses);

    @Query("SELECT l.id FROM Listing l "
            + "WHERE l.id IN :ids AND l.status IN :visibleStatuses "
            + "AND EXISTS (SELECT p.id FROM BrokerProfile p, PartnerDivision d, User u "
            + "            WHERE p.officeId = l.officeId AND d.userId = p.userId "
            + "              AND d.division = com.kovil.domain.partner.enums.PartnerDivisionType.REAL_ESTATE_BROKER "
            + "              AND d.status = com.kovil.domain.partner.enums.PartnerDivisionStatus.APPROVED "
            + "              AND u.id = p.userId AND u.deletedAt IS NULL "
            + "              AND u.status = com.kovil.domain.user.enums.UserStatus.ACTIVE)")
    List<Long> findPublicIdsIn(@Param("ids") Collection<Long> ids,
                               @Param("visibleStatuses") Collection<ListingStatus> visibleStatuses);

    @Query("SELECT l FROM Listing l "
            + "WHERE l.uuid IN :uuids AND l.status IN :visibleStatuses "
            + "AND EXISTS (SELECT p.id FROM BrokerProfile p, PartnerDivision d, User u "
            + "            WHERE p.officeId = l.officeId AND d.userId = p.userId "
            + "              AND d.division = com.kovil.domain.partner.enums.PartnerDivisionType.REAL_ESTATE_BROKER "
            + "              AND d.status = com.kovil.domain.partner.enums.PartnerDivisionStatus.APPROVED "
            + "              AND u.id = p.userId AND u.deletedAt IS NULL "
            + "              AND u.status = com.kovil.domain.user.enums.UserStatus.ACTIVE)")
    List<Listing> findPublicByUuidIn(@Param("uuids") Collection<String> uuids,
                                     @Param("visibleStatuses") Collection<ListingStatus> visibleStatuses);

    List<Listing> findByStatusInAndExpiresAtBefore(Collection<ListingStatus> statuses, LocalDateTime at);

    List<Listing> findByStatusAndExpiresAtBefore(ListingStatus status, LocalDateTime graceDeadline);
}