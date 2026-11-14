package com.kovil.domain.verification.repository;

import com.kovil.domain.verification.entity.ResidentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ResidentStatusRepository extends JpaRepository<ResidentStatus, Long> {

    List<ResidentStatus> findByActiveTrueOrderBySortOrderAscCodeAsc();

    // 비활성 신분으로는 새 제출을 시작할 수 없다(기존 승인 건은 유지된다).
    Optional<ResidentStatus> findByCodeAndActiveTrue(String code);

    List<ResidentStatus> findAllByOrderBySortOrderAscIdAsc();

    boolean existsByCode(String code);

    Optional<ResidentStatus> findByCode(String code);

    // 재정렬 스코프 잠금 조회 — 검증 통과~커밋 사이에 끼어드는 동시 create가 순열 검증을
    // 무의미하게 만들지 않도록, 스코프 전 행을 잠그고 검증·재부여한다(행 수 ≤100이라 비용 무시 가능).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT rs FROM ResidentStatus rs")
    List<ResidentStatus> findAllForReorder();

    // 생성 기본 순서 — "새 행은 맨 아래"(기본값 0은 기존 1..N보다 앞, 즉 새치기였다).
    @Query("SELECT COALESCE(MAX(rs.sortOrder), 0) FROM ResidentStatus rs")
    int maxSortOrder();
}
