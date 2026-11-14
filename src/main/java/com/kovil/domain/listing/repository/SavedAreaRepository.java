package com.kovil.domain.listing.repository;

import com.kovil.domain.listing.entity.SavedArea;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SavedAreaRepository extends JpaRepository<SavedArea, Long> {

    List<SavedArea> findByUserIdOrderByCreatedAtDescIdDesc(Long userId);

    long countByUserId(Long userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM SavedArea a WHERE a.uuid = :uuid AND a.userId = :userId")
    int deleteByUuidAndUserId(@Param("uuid") String uuid, @Param("userId") Long userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM SavedArea a WHERE a.userId = :userId")
    int deleteAllByUserId(@Param("userId") Long userId);
}