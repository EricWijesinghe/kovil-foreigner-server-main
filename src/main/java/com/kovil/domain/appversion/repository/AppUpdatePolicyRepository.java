package com.kovil.domain.appversion.repository;

import com.kovil.domain.appversion.entity.AppUpdatePolicy;
import com.kovil.domain.appversion.enums.AppKind;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AppUpdatePolicyRepository extends JpaRepository<AppUpdatePolicy, Long> {

    Optional<AppUpdatePolicy> findByAppAndPlatform(AppKind app, String platform);

    List<AppUpdatePolicy> findByAppOrderByPlatformAsc(AppKind app);

}
