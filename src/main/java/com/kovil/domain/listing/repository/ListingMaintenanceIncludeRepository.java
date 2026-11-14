package com.kovil.domain.listing.repository;

import com.kovil.domain.listing.entity.ListingMaintenanceInclude;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ListingMaintenanceIncludeRepository
        extends JpaRepository<ListingMaintenanceInclude, ListingMaintenanceInclude.Key> {

    List<ListingMaintenanceInclude> findByListingId(Long listingId);

    void deleteByListingId(Long listingId);
}
