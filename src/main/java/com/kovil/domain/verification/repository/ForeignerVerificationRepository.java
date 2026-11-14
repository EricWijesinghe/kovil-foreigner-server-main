package com.kovil.domain.verification.repository;

import com.kovil.domain.verification.entity.ForeignerVerification;
import com.kovil.domain.verification.enums.VerificationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;

public interface ForeignerVerificationRepository extends JpaRepository<ForeignerVerification, Long> {

    // ★ INV-02 — 판정은 언제나 유저별 최신 행 1건이다. 여러 행을 합산하지 않는다.
    Optional<ForeignerVerification> findFirstByUserIdOrderByIdDesc(Long userId);

    Optional<ForeignerVerification> findByUuid(String uuid);

    // FR-901 심사 대기열. 오래 기다린 건이 먼저 나와야 한다 — 신청 순서가 곧 처리 순서다.
    // residentStatusCode는 화면 필터다 — 신분마다 볼 서류가 달라 묶어서 처리하는 편이 빠르다.
    @Query(
            "SELECT v FROM ForeignerVerification v "
            + "WHERE v.status = :status "
            + "AND (:residentStatusCode IS NULL OR v.residentStatusCode = :residentStatusCode) "
            + "ORDER BY v.id ASC")
    Page<ForeignerVerification> findQueue(
            @Param("status")
            VerificationStatus status,
            @Param("residentStatusCode") String residentStatusCode,
            Pageable pageable);

    // 재신청 이력. 최신이 먼저 — 화면은 "이전 반려 사유"를 위에서부터 읽는다.
    List<ForeignerVerification> findByUserIdOrderByIdDesc(Long userId);

    // 재신청 배지의 판정값. 이 행보다 앞선 신청이 몇 건이었나.
    long countByUserIdAndIdLessThan(Long userId, Long id);
}
