package com.kovil.domain.appversion.service;

import com.kovil.common.tx.AfterCommitRunner;
import com.kovil.domain.appversion.dto.request.AdminAppUpdatePolicyRequest;
import com.kovil.domain.appversion.dto.response.AdminAppUpdatePolicyResponse;
import com.kovil.domain.appversion.entity.AppUpdatePolicy;
import com.kovil.domain.appversion.enums.AppKind;
import com.kovil.domain.appversion.exception.AppVersionException;
import com.kovil.domain.appversion.repository.AppUpdatePolicyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

import static com.kovil.domain.appversion.exception.AppVersionErrorCode.APP_UPDATE_MIN_ABOVE_LATEST;
import static com.kovil.domain.appversion.exception.AppVersionErrorCode.APP_UPDATE_PLATFORM_INVALID;
import static com.kovil.domain.appversion.exception.AppVersionErrorCode.APP_UPDATE_STORE_URL_INVALID;
import static com.kovil.domain.appversion.exception.AppVersionErrorCode.APP_UPDATE_VERSION_INVALID;

@Service
@RequiredArgsConstructor
public class AdminAppUpdateService {

    private final AppUpdatePolicyRepository appUpdatePolicyRepository;
    private final AppVersionService appVersionService;

    public List<AdminAppUpdatePolicyResponse> findAll(AppKind app) {
        return appUpdatePolicyRepository.findByAppOrderByPlatformAsc(app).stream()
                .sorted(Comparator.comparing(AppUpdatePolicy::getPlatform))
                .map(AdminAppUpdatePolicyResponse::from)
                .toList();
    }

    @Transactional
    public AdminAppUpdatePolicyResponse update(AppKind app, String platform, AdminAppUpdatePolicyRequest request) {
        AppUpdatePolicy policy = appUpdatePolicyRepository
                .findByAppAndPlatform(app,
                        platform == null ? "" : platform.trim().toUpperCase())
                .orElseThrow(() -> new AppVersionException(APP_UPDATE_PLATFORM_INVALID));

        long[] min = AppVersionService.parse(request.minSupportedVersion());
        long[] latest = AppVersionService.parse(request.latestVersion());
        if (min == null || latest == null) {
            throw new AppVersionException(APP_UPDATE_VERSION_INVALID);
        }
        if (AppVersionService.compare(min, latest) > 0) {
            throw new AppVersionException(APP_UPDATE_MIN_ABOVE_LATEST);
        }

        String storeUrl = request.storeUrl() == null ? "" : request.storeUrl().trim();
        if (!storeUrl.isEmpty() && !storeUrl.startsWith("https://")) {
            throw new AppVersionException(APP_UPDATE_STORE_URL_INVALID);
        }

        policy.update(request.minSupportedVersion().trim(), request.latestVersion().trim(), storeUrl);
        AfterCommitRunner.run("app-version-cache", appVersionService::evictCache);
        return AdminAppUpdatePolicyResponse.from(policy);
    }
}
