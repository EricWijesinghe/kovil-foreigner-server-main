package com.kovil.domain.auth.repository;

import com.kovil.domain.auth.entity.PhoneVerification;
import com.kovil.domain.auth.enums.VerificationPurpose;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PhoneVerificationRepository extends JpaRepository<PhoneVerification, Long> {

    Optional<PhoneVerification> findFirstByPhoneHashAndPurposeAndConsumedAtIsNullOrderByIdDesc(
            String phoneHash, VerificationPurpose purpose);
}
