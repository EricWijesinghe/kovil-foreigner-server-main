package com.kovil.domain.listing.repository;

import com.kovil.domain.listing.entity.ListingMaintenanceItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ListingMaintenanceItemRepository extends JpaRepository<ListingMaintenanceItem, Long> {

    List<ListingMaintenanceItem> findByListingId(Long listingId);

    void deleteByListingId(Long listingId);

    @Query("SELECT DISTINCT i.listingId FROM ListingMaintenanceItem i " +
            "WHERE i.listingId IN :listingIds AND i.actualCost IS TRUE")
    List<Long> findListingIdsWithActualCost(@Param("listingIds") Collection<Long> listingIds);
}
