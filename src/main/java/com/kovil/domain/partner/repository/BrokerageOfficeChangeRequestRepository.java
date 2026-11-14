package com.kovil.domain.partner.repository;

import com.kovil.domain.partner.entity.BrokerageOfficeChangeRequest;
import com.kovil.domain.partner.enums.OfficeChangeRequestStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BrokerageOfficeChangeRequestRepository extends JpaRepository<BrokerageOfficeChangeRequest, Long> {

    Optional<BrokerageOfficeChangeRequest> findByUuid(String uuid);

    /*
     * 그 사무소의 대기 건 — **잠금 읽기**다. 사무소 행을 잠근 뒤에 부른다.
     * ★ 일반 SELECT 면 사무소 잠금을 기다린 뒤에도 트랜잭션 첫 읽기 시점의 스냅숏을 본다(InnoDB REPEATABLE READ) —
     *   앞사람이 방금 만든 대기 건이 안 보여 "대기 건 없음"으로 읽고 하나를 더 만든다. 잠금 읽기는 최신 커밋을 읽는다.
     * ★ REPEATABLE READ 에서 대기 건이 없으면 이 읽기가 빈 인덱스 구간에 갭 잠금을 건다 — 다른 사무소의 첫 제출과 서로의 INSERT 를
     *   막아 교착한다. 그래서 제출은 READ COMMITTED 로 부른다(PartnerService.submitRealEstateBroker). 탈퇴 정리도 이걸 부르지만
     *   INSERT 하지 않으므로 갭 잠금은 남의 INSERT 를 잠깐 기다리게 할 뿐 교착을 만들지 않는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM BrokerageOfficeChangeRequest r WHERE r.officeId = :officeId "
            + "AND r.status = com.kovil.domain.partner.enums.OfficeChangeRequestStatus.PENDING")
    Optional<BrokerageOfficeChangeRequest> findPendingForUpdate(@Param("officeId") Long officeId);

    // 심사 화면의 "같은 번호의 다른 신청" — 그 번호를 바꿀 번호로 둔 대기 건(먼저 낸 것부터).
    List<BrokerageOfficeChangeRequest> findByRegNoAndStatusOrderBySubmittedAtAscIdAsc(String regNo,
                                                                                   OfficeChangeRequestStatus status);

    // 파트너 화면의 "최근 변경 신청" — 대기 · 반려(사유) · 승인 중 가장 최근 한 건.
    Optional<BrokerageOfficeChangeRequest> findFirstByOfficeIdOrderByIdDesc(Long officeId);

    // 변경 심사 대기열 — 오래 기다린 제출이 먼저. 대기 중에 다시 낸 건은 제출 시각이 새로 찍혀 뒤로 간다.
    @Query("SELECT r FROM BrokerageOfficeChangeRequest r WHERE r.status = :status "
            + "ORDER BY r.submittedAt ASC, r.id ASC")
    Page<BrokerageOfficeChangeRequest> findQueue(@Param("status") OfficeChangeRequestStatus status,
                                                 Pageable pageable);
}
