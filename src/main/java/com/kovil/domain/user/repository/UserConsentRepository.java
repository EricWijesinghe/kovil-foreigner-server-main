package com.kovil.domain.user.repository;

import com.kovil.domain.user.entity.UserConsent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserConsentRepository extends JpaRepository<UserConsent, Long> {

    // 최신이 앞에 온다 — 타입별 첫 행이 현재 상태다.
    List<UserConsent> findByUserIdOrderByIdDesc(Long userId);
}
