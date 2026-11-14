package com.kovil.domain.community.repository;

import com.kovil.domain.community.entity.UserBlock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Set;

public interface UserBlockRepository extends JpaRepository<UserBlock, Long> {

    boolean existsByBlockerIdAndBlockedUserId(Long blockerId, Long blockedUserId);

    // 멱등 삽입 — 동시 이중 차단의 유니크 충돌을 예외 없이 0행으로 받는다(uq_ub_pair).
    @Modifying
    @Query(value = "INSERT IGNORE INTO user_blocks (blocker_id, blocked_user_id) VALUES (:blockerId, :blockedUserId)",
            nativeQuery = true)
    int insertIgnore(@Param("blockerId") Long blockerId, @Param("blockedUserId") Long blockedUserId);

    @Query("SELECT b.blockedUserId FROM UserBlock b WHERE b.blockerId = :blockerId")
    Set<Long> findBlockedUserIds(@Param("blockerId") Long blockerId);
}
