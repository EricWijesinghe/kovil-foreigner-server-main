package com.kovil.domain.listing.repository;

import com.kovil.domain.listing.entity.ListingReport;
import com.kovil.domain.listing.enums.ListingReportStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ListingReportRepository extends JpaRepository<ListingReport, Long> {

    boolean existsByListingIdAndReporterUserId(Long listingId, Long reporterUserId);

    Page<ListingReport> findByStatusOrderByIdAsc(ListingReportStatus status, Pageable pageable);

    Page<ListingReport> findByStatusOrderByIdDesc(ListingReportStatus status, Pageable pageable);

    @Query("SELECT r.listingId, COUNT(r) FROM ListingReport r "
            + "WHERE r.status = :status AND r.listingId IN :listingIds GROUP BY r.listingId")
    List<Object[]> countByStatusAndListingIds(@Param("status") ListingReportStatus status,
                                              @Param("listingIds") Collection<Long> listingIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM ListingReport r WHERE r.uuid = :uuid")
    Optional<ListingReport> findByUuidForUpdate(@Param("uuid") String uuid);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM ListingReport r WHERE r.listingId = :listingId AND r.status = :status")
    List<ListingReport> findByListingIdAndStatusForUpdate(@Param("listingId") Long listingId,
                                                          @Param("status") ListingReportStatus status);

    List<ListingReport> findTop100ByListingIdOrderByIdDesc(Long listingId);

    long countByListingId(Long listingId);

    long countByListingIdAndStatus(Long listingId, ListingReportStatus status);
}