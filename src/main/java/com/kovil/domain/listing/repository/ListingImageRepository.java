package com.kovil.domain.listing.repository;

import com.kovil.domain.listing.entity.ListingImage;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface ListingImageRepository extends JpaRepository<ListingImage, Long> {

    List<ListingImage> findByListingIdOrderBySortOrderAsc(Long listingId);

    List<ListingImage> findByListingIdInOrderBySortOrderAsc(List<Long> listingIds);

    long countByListingId(Long listingId);

    /*
     * 전량 교체용. ★ 호출한 뒤 **반드시 flush 한다** — 안 하면 Hibernate 가 INSERT 를 먼저
     *   내보내고 uq_li(listing_id, sort_order) 에 걸린다. 같은 사고가
     *   listing_maintenance_items 에서 실제로 터졌다(실측 2026-10-05).
     */
    void deleteByListingId(Long listingId);

    // 같은 파일이 여러 매물에 붙을 수 있음(file_id 유일 아님) — 다른 매물에 아직 붙은 파일은 버리지 않음.
    // 잠금 읽기여야 함 — 일반 읽기는 트랜잭션 첫 읽기 때의 스냅샷이라 그사이 다른 매물에 붙은 것을 못 봄.
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT i FROM ListingImage i WHERE i.fileId IN :fileIds AND i.listingId <> :listingId")
    List<ListingImage> findAttachedElsewhereForShare(@Param("fileIds") Collection<Long> fileIds,
                                                     @Param("listingId") Long listingId);

    boolean existsByFileId(Long fileId);

    // 매물에 안 붙은 매물 사진 — 올리고 저장하지 않은 폼 · 응답 유실로 남은 확정 파일.
    @Query("SELECT f.id FROM StoredFile f WHERE f.purpose = com.kovil.domain.file.enums.FilePurpose.LISTING_IMAGE "
            + "AND f.status = com.kovil.domain.file.enums.FileStatus.ACTIVE AND f.createdAt < :before "
            + "AND NOT EXISTS (SELECT 1 FROM ListingImage i WHERE i.fileId = f.id) ORDER BY f.id")
    List<Long> findUnattachedImageFileIds(@Param("before") LocalDateTime before, Pageable pageable);
}
