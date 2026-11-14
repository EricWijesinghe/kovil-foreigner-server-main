package com.kovil.domain.listing.repository;

import com.kovil.domain.listing.entity.ListingOption;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ListingOptionRepository extends JpaRepository<ListingOption, ListingOption.Key> {

    List<ListingOption> findByListingId(Long listingId);

    List<ListingOption> findByListingIdIn(List<Long> listingIds);

    void deleteByListingId(Long listingId);
}
