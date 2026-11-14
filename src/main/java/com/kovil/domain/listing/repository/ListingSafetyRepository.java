package com.kovil.domain.listing.repository;

import com.kovil.domain.listing.entity.ListingSafety;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ListingSafetyRepository extends JpaRepository<ListingSafety, ListingSafety.Key> {

    List<ListingSafety> findByListingId(Long listingId);

    void deleteByListingId(Long listingId);
}
