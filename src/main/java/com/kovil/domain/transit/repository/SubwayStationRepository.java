package com.kovil.domain.transit.repository;

import com.kovil.domain.transit.entity.SubwayStation;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface SubwayStationRepository extends JpaRepository<SubwayStation, Long> {

    @Query("SELECT s FROM SubwayStation s WHERE s.isActive = true "
            + "AND s.lat BETWEEN :swLat AND :neLat AND s.lng BETWEEN :swLng AND :neLng "
            + "ORDER BY (s.lat - :centerLat) * (s.lat - :centerLat) "
            + "+ (s.lng - :centerLng) * (s.lng - :centerLng) * :lngWeight, s.code")
    List<SubwayStation> findActiveWithin(@Param("swLat") BigDecimal swLat, @Param("swLng") BigDecimal swLng,
                                         @Param("neLat") BigDecimal neLat, @Param("neLng") BigDecimal neLng,
                                         @Param("centerLat") BigDecimal centerLat, @Param("centerLng") BigDecimal centerLng,
                                         @Param("lngWeight") BigDecimal lngWeight, Pageable pageable);

    @Query("SELECT s FROM SubwayStation s WHERE s.isActive = true "
            + "AND (LOWER(s.nameKo) LIKE :contains ESCAPE '!' OR LOWER(s.nameEn) LIKE :contains ESCAPE '!') "
            + "ORDER BY CASE WHEN LOWER(s.nameKo) = :exact OR LOWER(s.nameEn) = :exact THEN 0 "
            + "WHEN LOWER(s.nameKo) LIKE :prefix ESCAPE '!' OR LOWER(s.nameEn) LIKE :prefix ESCAPE '!' THEN 1 ELSE 2 END, "
            + "s.code")
    List<SubwayStation> searchActive(@Param("exact") String exact, @Param("prefix") String prefix,
                                     @Param("contains") String contains, Pageable pageable);
}