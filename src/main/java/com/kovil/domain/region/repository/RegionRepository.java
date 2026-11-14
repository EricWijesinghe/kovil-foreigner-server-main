package com.kovil.domain.region.repository;

import com.kovil.domain.region.entity.Region;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.Collection;
import java.util.List;

public interface RegionRepository extends JpaRepository<Region, Long> {

    List<Region> findByIsActiveTrueOrderBySortOrderAscCodeAsc();

    List<Region> findByCodeInAndIsActiveTrue(Collection<String> codes);

    List<Region> findByIdIn(Collection<Long> ids);

    List<Region> findByIdInAndIsActiveTrue(Collection<Long> ids);

    List<Region> findByParentId(Long parentId);

    List<Region> findAllByOrderBySortOrderAscIdAsc();

    @Query("SELECT r FROM Region r, Region p WHERE p.id = r.parentId AND p.isActive = true AND r.isActive = true "
            + "AND (LOWER(r.nameKo) LIKE :contains ESCAPE '!' OR LOWER(r.nameEn) LIKE :contains ESCAPE '!') "
            + "ORDER BY CASE WHEN LOWER(r.nameKo) = :exact OR LOWER(r.nameEn) = :exact THEN 0 "
            + "WHEN LOWER(r.nameKo) LIKE :prefix ESCAPE '!' OR LOWER(r.nameEn) LIKE :prefix ESCAPE '!' THEN 1 ELSE 2 END, "
            + "p.sortOrder, p.code, r.sortOrder, r.code")
    List<Region> searchOpenDistricts(@Param("exact") String exact, @Param("prefix") String prefix,
                                     @Param("contains") String contains, Pageable pageable);

    boolean existsByCode(String code);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            "SELECT r FROM Region r WHERE (:parentId IS NULL AND r.parentId IS NULL) "
            + "OR (:parentId IS NOT NULL AND r.parentId = :parentId)")
    List<Region> findSiblingsForReorder(
            @Param("parentId") Long parentId);

    @Query(
            "SELECT COALESCE(MAX(r.sortOrder), 0) FROM Region r "
            + "WHERE (:parentId IS NULL AND r.parentId IS NULL) "
            + "OR (:parentId IS NOT NULL AND r.parentId = :parentId)")
    int maxSortOrderInScope(
            @Param("parentId") Long parentId);
}
