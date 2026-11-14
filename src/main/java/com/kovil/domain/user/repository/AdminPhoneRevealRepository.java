package com.kovil.domain.user.repository;

import com.kovil.domain.user.entity.AdminPhoneReveal;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminPhoneRevealRepository extends JpaRepository<AdminPhoneReveal, Long> {

    long countByTargetUserId(Long targetUserId);
}
