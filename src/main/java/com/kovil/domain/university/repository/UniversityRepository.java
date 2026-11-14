package com.kovil.domain.university.repository;

import com.kovil.domain.university.entity.University;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface UniversityRepository extends JpaRepository<University, Long> {

    @Query("SELECT u FROM University u WHERE u.isActive = true "
            + "AND u.lat BETWEEN :swLat AND :neLat AND u.lng BETWEEN :swLng AND :neLng "
            + "ORDER BY (u.lat - :centerLat) * (u.lat - :centerLat) "
            + "+ (u.lng - :centerLng) * (u.lng - :centerLng) * :lngWeight, u.code")
    List<University> findActiveWithin(@Param("swLat") BigDecimal swLat, @Param("swLng") BigDecimal swLng,
                                      @Param("neLat") BigDecimal neLat, @Param("neLng") BigDecimal neLng,
                                      @Param("centerLat") BigDecimal centerLat, @Param("centerLng") BigDecimal centerLng,
                                      @Param("lngWeight") BigDecimal lngWeight, Pageable pageable);

    @Query("SELECT u FROM University u WHERE u.isActive = true "
            + "AND (LOWER(u.nameKo) LIKE :contains ESCAPE '!' OR LOWER(u.nameEn) LIKE :contains ESCAPE '!') "
            + "ORDER BY CASE WHEN LOWER(u.nameKo) = :exact OR LOWER(u.nameEn) = :exact THEN 0 "
            + "WHEN LOWER(u.nameKo) LIKE :prefix ESCAPE '!' OR LOWER(u.nameEn) LIKE :prefix ESCAPE '!' THEN 1 ELSE 2 END, "
            + "u.code")
    List<University> searchActive(@Param("exact") String exact, @Param("prefix") String prefix,
                                  @Param("contains") String contains, Pageable pageable);
}