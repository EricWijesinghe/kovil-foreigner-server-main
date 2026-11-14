package com.kovil.domain.listing.repository;

import com.kovil.domain.listing.entity.ListingPriceHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ListingPriceHistoryRepository extends JpaRepository<ListingPriceHistory, Long> {

    // 신고 대응 화면 — 오래된 순으로 읽어야 "얼마에서 얼마로"가 읽힌다.
    List<ListingPriceHistory> findByListingIdOrderByChangedAtAsc(Long listingId);

    // 직전 가격. 같은 값이면 행을 더하지 않기 위한 비교 대상이다.
    Optional<ListingPriceHistory> findTopByListingIdOrderByChangedAtDesc(Long listingId);
}
