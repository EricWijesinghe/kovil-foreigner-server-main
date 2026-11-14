package com.kovil.domain.listing.repository;

import com.kovil.domain.listing.entity.ListingMaintenance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ListingMaintenanceRepository extends JpaRepository<ListingMaintenance, Long> {

    // 목록 조립용 — 매물마다 한 건씩 N+1 로 긁지 않는다.
    List<ListingMaintenance> findByListingIdIn(List<Long> listingIds);
}
