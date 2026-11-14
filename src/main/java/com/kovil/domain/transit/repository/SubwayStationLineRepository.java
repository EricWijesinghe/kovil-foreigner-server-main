package com.kovil.domain.transit.repository;

import com.kovil.domain.transit.entity.SubwayStationLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface SubwayStationLineRepository extends JpaRepository<SubwayStationLine, Long> {

    @Query("SELECT new com.kovil.domain.transit.repository.SubwayStationLineRow("
            + "sl.stationId, l.code, l.nameKo, l.nameEn, l.color) "
            + "FROM SubwayStationLine sl JOIN SubwayLine l ON l.id = sl.lineId "
            + "WHERE sl.stationId IN :stationIds AND l.isActive = true "
            + "ORDER BY sl.stationId, sl.sortOrder, l.sortOrder")
    List<SubwayStationLineRow> findLinesOf(@Param("stationIds") Collection<Long> stationIds);
}