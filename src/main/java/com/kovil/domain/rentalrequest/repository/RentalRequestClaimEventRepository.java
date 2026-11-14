package com.kovil.domain.rentalrequest.repository;

import com.kovil.domain.rentalrequest.entity.RentalRequestClaimEvent;
import com.kovil.domain.rentalrequest.enums.RentalRequestClaimEventType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;


public interface RentalRequestClaimEventRepository extends JpaRepository<RentalRequestClaimEvent, Long> {
    List<RentalRequestClaimEvent> findByRentalRequestIdOrderByIdAsc(Long rentalRequestId);

    @Query("SELECT new com.kovil.domain.rentalrequest.repository.RentalRequestLastRelease(e.rentalRequestId, MAX(e.occurredAt)) "
            + "FROM RentalRequestClaimEvent e WHERE e.rentalRequestId IN :requestIds AND e.eventType IN :types "
            + "GROUP BY e.rentalRequestId")
    List<RentalRequestLastRelease> findLastReleases(@Param("requestIds") Collection<Long> requestIds,
                                                    @Param("types") Collection<RentalRequestClaimEventType> types);
}
