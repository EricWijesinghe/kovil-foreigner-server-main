package com.kovil.domain.listing.repository;

import com.kovil.domain.listing.entity.ListingBlockLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ListingBlockLogRepository extends JpaRepository<ListingBlockLog, Long> {

    List<ListingBlockLog> findByListingIdOrderByIdDesc(Long listingId);
}