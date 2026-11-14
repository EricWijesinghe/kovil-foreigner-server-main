package com.kovil.domain.community.repository;

import com.kovil.domain.community.entity.CommunityCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface CommunityCategoryRepository extends JpaRepository<CommunityCategory, Long> {

    List<CommunityCategory> findByActiveTrueOrderBySortOrderAscIdAsc();

    Optional<CommunityCategory> findByCodeAndActiveTrue(String code);

    // 재정렬 스코프 잠금 조회 — 검증 통과~커밋 사이에 끼어드는 동시 create가 순열 검증을
    // 무의미하게 만들지 않도록, 스코프 전 행을 잠그고 검증·재부여한다(행 수 ≤100이라 비용 무시 가능).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT cc FROM CommunityCategory cc")
    List<CommunityCategory> findAllForReorder();

    // 생성 기본 순서 — "새 행은 맨 아래"(기본값 0은 기존 1..N보다 앞, 즉 새치기였다).
    @Query("SELECT COALESCE(MAX(cc.sortOrder), 0) FROM CommunityCategory cc")
    int maxSortOrder();
}
