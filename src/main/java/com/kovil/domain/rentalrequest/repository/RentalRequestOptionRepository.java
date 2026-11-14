package com.kovil.domain.rentalrequest.repository;

import com.kovil.domain.rentalrequest.entity.RentalRequestOption;
import com.kovil.domain.rentalrequest.enums.RentalRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Set;

public interface RentalRequestOptionRepository extends JpaRepository<RentalRequestOption, Long> {

    List<RentalRequestOption> findByRentalRequestIdOrderByIdAsc(Long rentalRequestId);

    // 정보 축(강제 아님, V15) — 관리자 화면이 "진행 중 요청이 답해 둔 질문"임을 알려줄 때 쓴다.
    // 열린(SUBMITTED·IN_PROGRESS) 요청의 답만 센다.
    @Query("SELECT DISTINCT o.requestOptionId FROM RentalRequestOption o WHERE o.requestOptionId IN :ids "
            + "AND EXISTS (SELECT 1 FROM RentalRequest r WHERE r.id = o.rentalRequestId AND r.status IN :statuses)")
    Set<Long> findAnsweredOptionIdsByStatusIn(
            @Param("ids") Collection<Long> ids,
            @Param("statuses") Collection<RentalRequestStatus> statuses);

    List<RentalRequestOption> findByRentalRequestIdInOrderByIdAsc(Collection<Long> rentalRequestIds);

    void deleteByRentalRequestId(Long rentalRequestId);
}
