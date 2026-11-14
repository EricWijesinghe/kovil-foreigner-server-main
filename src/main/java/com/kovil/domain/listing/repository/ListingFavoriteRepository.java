package com.kovil.domain.listing.repository;

import com.kovil.domain.listing.entity.ListingFavorite;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface ListingFavoriteRepository
        extends JpaRepository<ListingFavorite, ListingFavorite.Key> {

    boolean existsByUserIdAndListingId(Long userId, Long listingId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "INSERT INTO listing_favorites (user_id, listing_id, created_at) "
            + "VALUES (:userId, :listingId, :createdAt) "
            + "ON DUPLICATE KEY UPDATE listing_id = listing_id", nativeQuery = true)
    int insertIfAbsent(@Param("userId") Long userId,
                       @Param("listingId") Long listingId,
                       @Param("createdAt") LocalDateTime createdAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM ListingFavorite f WHERE f.userId = :userId AND f.listingId = :listingId")
    int deleteOne(@Param("userId") Long userId, @Param("listingId") Long listingId);

    @Query("SELECT f.listingId FROM ListingFavorite f "
            + "WHERE f.userId = :userId AND f.listingId IN :listingIds")
    List<Long> findFavoritedListingIds(@Param("userId") Long userId,
                                       @Param("listingIds") Collection<Long> listingIds);

    @Query(value = "SELECT f FROM ListingFavorite f WHERE f.userId = :userId " +
            "ORDER BY f.createdAt DESC, f.listingId DESC",
    countQuery = "SELECT COUNT(f) FROM ListingFavorite f WHERE f.userId = :userId")
    Page<ListingFavorite> findPageByUserId(@Param("userId") Long userId, Pageable pageable);

    // 탈퇴 정리 전용 잠금 읽기 — 일반 읽기는 탈퇴 트랜잭션에 이미 고정된 스냅샷을 봐서, 다른 기기에서 방금 담은 찜을 못 본다.
    @Query(value = "SELECT listing_id FROM listing_favorites WHERE user_id = :userId FOR UPDATE", nativeQuery = true)
    List<Long> findListingIdsByUserIdForUpdate(@Param("userId") Long userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM ListingFavorite f WHERE f.userId = :userId")
    int deleteAllByUserId(@Param("userId") Long userId);
}
