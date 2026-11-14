package com.kovil.domain.appversion.service;

import com.kovil.domain.appversion.dto.response.AppVersionCheckResponse;
import com.kovil.domain.appversion.entity.AppUpdatePolicy;
import com.kovil.domain.appversion.enums.AppKind;
import com.kovil.domain.appversion.enums.UpdateVerdict;
import com.kovil.domain.appversion.repository.AppUpdatePolicyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


@Slf4j
@Service
@RequiredArgsConstructor
public class AppVersionService {

    private static final long CACHE_TTL_NANOS = 60L * 1_000_000_000L;

    private final AppUpdatePolicyRepository appUpdatePolicyRepository;

    private record Key(AppKind app, String platform) {}
    private record Policy(String minSupportedVersion, String latestVersion, String storeUrl) {}
    private record Cached(Map<Key, Policy> byKey, long expiresAtNanos) {}
    private volatile Cached cached;

    public AppVersionCheckResponse check(String app, String platform, String rawVersion) {
        Policy policy = policyOf(app, platform);
        if (policy == null) {
            return new AppVersionCheckResponse(UpdateVerdict.OK, null, null);
        }

        String storeUrl = policy.storeUrl() == null || policy.storeUrl().isBlank()
                ? null : policy.storeUrl().trim();

        long[] current = parse(rawVersion);
        long[] min = parse(policy.minSupportedVersion());
        long[] latest = parse(policy.latestVersion());
        if (current == null || min == null || latest == null) {
            log.warn("버전 판정 불가 → OK 통과: platform={} version={} min={} latest={}",
                    platform, rawVersion, policy.minSupportedVersion(), policy.latestVersion());
            return new AppVersionCheckResponse(UpdateVerdict.OK, policy.latestVersion(), storeUrl);
        }

        if (compare(min, latest) > 0) {
            log.error("min({}) > latest({}) — 오설정이므로 발동을 억제한다: platform={}",
                    policy.minSupportedVersion(), policy.latestVersion(), platform);
            return new AppVersionCheckResponse(UpdateVerdict.OK, policy.latestVersion(), storeUrl);
        }

        UpdateVerdict verdict = UpdateVerdict.OK;
        if (compare(current, min) < 0) {
            verdict = UpdateVerdict.FORCE;
        } else if (compare(current, latest) < 0) {
            verdict = UpdateVerdict.RECOMMEND;
        }

        if (verdict != UpdateVerdict.OK && storeUrl == null) {
            log.error("스토어 URL 없이 {} 정책이 걸려 있다 — 발동을 억제한다: platform={}", verdict, platform);
            verdict = UpdateVerdict.OK;
        }
        return new AppVersionCheckResponse(verdict, policy.latestVersion(), storeUrl);
    }

    public void evictCache() {
        cached = null;
    }

    private Policy policyOf(String app, String platform) {
        AppKind kind = appOf(app);
        if (kind == null || platform == null) return null;

        String key = platform.trim().toUpperCase();
        if (!key.equals("IOS") && !key.equals("ANDROID")) return null;
        return policies().get(new Key(kind, key));
    }

    private AppKind appOf(String app) {
        if (!StringUtils.hasText(app)) return AppKind.STUDENT;
        try {
            return AppKind.valueOf(app.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Map<Key, Policy> policies() {
        Cached snapshot = cached;
        if (snapshot != null && System.nanoTime() < snapshot.expiresAtNanos()) {
            return snapshot.byKey();
        }
        try {
            List<AppUpdatePolicy> rows = appUpdatePolicyRepository.findAll();
            Map<Key, Policy> byKey = rows.stream().collect(Collectors.toMap(
                    row -> new Key(row.getApp(), row.getPlatform()),
                    row -> new Policy(row.getMinSupportedVersion(), row.getLatestVersion(), row.getStoreUrl()),
                    (a, b) -> a
            ));
            cached = new Cached(byKey, System.nanoTime() + CACHE_TTL_NANOS);
            return byKey;
        } catch (RuntimeException e) {
            log.error("버전 정책 조회 실패 → 전부 OK 통과", e);
            return Map.of();
        }
    }

    static long[] parse(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || !Character.isDigit(trimmed.charAt(0))) return null;
        int end = 0;
        while (end < trimmed.length() && (Character.isDigit(trimmed.charAt(end)) || trimmed.charAt(end) == '.')) {
            end++;
        }
        String core = trimmed.substring(0, end);
        if (core.endsWith(".") || core.contains("..")) return null;
        String[] parts = core.split("\\.");
        long[] numbers = new long[3];
        for (int i = 0; i < 3; i++) {
            if (i >= parts.length) continue;
            try {
                numbers[i] = Long.parseLong(parts[i]);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return numbers;
    }

    static int compare(long[] a, long[] b) {
        for (int i = 0; i < 3; i++) {
            int result = Long.compare(a[i], b[i]);
            if (result != 0) return result;
        }
        return 0;
    }
}
