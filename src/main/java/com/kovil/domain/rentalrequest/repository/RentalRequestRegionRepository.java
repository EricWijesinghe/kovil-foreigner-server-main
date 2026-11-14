package com.kovil.domain.rentalrequest.repository;

import com.kovil.domain.rentalrequest.entity.RentalRequestRegion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface RentalRequestRegionRepository extends JpaRepository<RentalRequestRegion, Long> {

    List<RentalRequestRegion> findByRentalRequestIdOrderByIdAsc(Long rentalRequestId);

    List<RentalRequestRegion> findByRentalRequestIdInOrderByIdAsc(Collection<Long> rentalRequestIds);

    void deleteByRentalRequestId(Long rentalRequestId);
}
