package com.kovil.domain.partner.repository;

import com.kovil.domain.partner.entity.SuspendedRegNumber;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface SuspendedRegNumberRepository extends JpaRepository<SuspendedRegNumber, Long> {

    /*
     * 심사 상세의 정지 이력 — 그 번호들의 정지 **또는** 그 사무소 자신의 정지, 최근 정지가 먼저.
     * 번호만 보면 정지됐던 사무소가 번호를 바꾼 뒤 자기 경고가 사라지고, 사무소만 보면 같은 번호로 온 다른 사람의 신청에서
     * 이력이 끊긴다. regNos 는 비어 있지 않게 부른다(IN () 를 피한다).
     */
    @Query("SELECT s FROM SuspendedRegNumber s WHERE s.regNo IN :regNos OR s.officeId = :officeId "
            + "ORDER BY s.suspendedAt DESC, s.id DESC")
    List<SuspendedRegNumber> findHistory(@Param("regNos") Collection<String> regNos,
                                         @Param("officeId") Long officeId);

    // 목록 한 페이지의 경고 표시를 한 번에 — 행마다 물으면 페이지 크기만큼 쿼리가 는다.
    @Query("SELECT DISTINCT s.regNo FROM SuspendedRegNumber s WHERE s.regNo IN :regNos")
    List<String> findRegNosIn(@Param("regNos") Collection<String> regNos);

    // 같은 목적의 사무소 쪽 — 이 중 정지된 적이 있는 사무소.
    @Query("SELECT DISTINCT s.officeId FROM SuspendedRegNumber s WHERE s.officeId IN :officeIds")
    List<Long> findOfficeIdsIn(@Param("officeIds") Collection<Long> officeIds);
}
