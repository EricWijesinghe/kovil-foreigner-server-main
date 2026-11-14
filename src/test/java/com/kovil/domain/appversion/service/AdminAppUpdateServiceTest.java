package com.kovil.domain.appversion.service;

import com.kovil.domain.appversion.dto.request.AdminAppUpdatePolicyRequest;
import com.kovil.domain.appversion.dto.response.AdminAppUpdatePolicyResponse;
import com.kovil.domain.appversion.entity.AppUpdatePolicy;
import com.kovil.domain.appversion.enums.AppKind;
import com.kovil.domain.appversion.exception.AppVersionException;
import com.kovil.domain.appversion.repository.AppUpdatePolicyRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 정책 편집의 쓰기 시점 검증 격자.
 *
 * <p>★ 판정(check)은 fail-open이라 무엇이든 통과시킨다 — 잘못된 값을 막을 수 있는 유일한
 * 지점은 **사람이 보고 있는 저장 순간**뿐이다. min > latest(최신 유저까지 락아웃),
 * 스킴 없는 URL(강제 화면의 죽은 버튼)이 대표 사고이고, 여기서 400으로 끊는다.
 */
@ExtendWith(MockitoExtension.class)
class AdminAppUpdateServiceTest {

    @Mock AppUpdatePolicyRepository repository;
    @Mock AppVersionService appVersionService;
    @InjectMocks AdminAppUpdateService service;

    private AppUpdatePolicy ios() {
        AppUpdatePolicy policy = AppVersionServiceTest.policy("IOS", "1.0.0", "1.0.0", "");
        given(repository.findByAppAndPlatform(AppKind.STUDENT, "IOS")).willReturn(Optional.of(policy));
        return policy;
    }

    private static AdminAppUpdatePolicyRequest req(String min, String latest, String url) {
        return new AdminAppUpdatePolicyRequest(min, latest, url);
    }

    private void expectError(AdminAppUpdatePolicyRequest request, String code) {
        assertThatThrownBy(() -> service.update(AppKind.STUDENT, "ios", request))
                .isInstanceOf(AppVersionException.class)
                .satisfies(e -> assertThat(((AppVersionException) e).getErrorCode().getCode()).isEqualTo(code));
    }

    @Test
    @DisplayName("min > latest는 저장을 거절한다 — 통과시키면 최신 유저까지 스토어 왕복 락아웃")
    void min_above_latest_is_rejected() {
        ios();
        expectError(req("1.2.0", "1.1.0", "https://apps.apple.com/app/id1"), "APP_UPDATE_MIN_ABOVE_LATEST");
    }

    @Test
    @DisplayName("1.2.3 형식이 아닌 버전은 저장을 거절한다 — 판정 쪽은 이런 값을 조용히 무시(OK)할 뿐이다")
    void unparseable_version_is_rejected() {
        ios();
        expectError(req("v1.2", "1.2.0", ""), "APP_UPDATE_VERSION_INVALID");
        expectError(req("1.2.0", ".", ""), "APP_UPDATE_VERSION_INVALID");
        expectError(req(null, "1.2.0", ""), "APP_UPDATE_VERSION_INVALID");
    }

    @Test
    @DisplayName("스토어 URL은 https:// 시작 아니면 거절 — 오타 URL은 강제 화면의 유일한 버튼을 죽인다")
    void store_url_scheme_is_enforced() {
        ios();
        expectError(req("1.0.0", "1.0.0", "apps.apple.com/app/id1"), "APP_UPDATE_STORE_URL_INVALID");
        expectError(req("1.0.0", "1.0.0", "http://apps.apple.com/app/id1"), "APP_UPDATE_STORE_URL_INVALID");
    }

    @Test
    @DisplayName("빈 URL은 허용이다 — '아직 발동 안 함' 상태 자체가 유효한 정책이다")
    void blank_store_url_is_allowed() {
        AppUpdatePolicy policy = ios();

        AdminAppUpdatePolicyResponse response = service.update(AppKind.STUDENT, "ios", req("1.0.0", "1.2.0", "  "));

        assertThat(response.storeUrl()).isEmpty();
        assertThat(policy.getLatestVersion()).isEqualTo("1.2.0");
    }

    @Test
    @DisplayName("저장이 성공하면 판정 캐시를 비운다 — 다음 앱 실행이 새 정책을 본다")
    void save_evicts_verdict_cache() {
        ios();

        service.update(AppKind.STUDENT, "ios", req("1.1.0", "1.2.0", "https://apps.apple.com/app/id1"));

        verify(appVersionService).evictCache();
    }

    /*
     * ★ 캐시는 커밋 **뒤에** 비운다 — 저장 트랜잭션 안에서 비우면, 비운 순간부터 커밋 전까지 들어온 앱 실행이
     *   옛 정책을 다시 읽어 60초 동안 들고 있는다(강제 업데이트 기준이 늦게 적용된다).
     */
    @Test
    @DisplayName("트랜잭션 안에서는 커밋 전까지 캐시를 비우지 않고, 커밋 뒤에 비운다")
    void evicts_only_after_commit() {
        ios();
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.update(AppKind.STUDENT, "ios", req("1.1.0", "1.2.0", "https://apps.apple.com/app/id1"));

            verify(appVersionService, never()).evictCache();

            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            verify(appVersionService).evictCache();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("모르는 플랫폼은 404다 — 행 추가가 아니라 고정 두 행의 수정만 있다")
    void unknown_platform_is_404() {
        given(repository.findByAppAndPlatform(AppKind.STUDENT, "WINDOWS")).willReturn(Optional.empty());
        expectError_platform();
    }

    private void expectError_platform() {
        assertThatThrownBy(() -> service.update(AppKind.STUDENT, "windows", req("1.0.0", "1.0.0", "")))
                .isInstanceOf(AppVersionException.class)
                .satisfies(e -> assertThat(((AppVersionException) e).getErrorCode().getCode())
                        .isEqualTo("APP_UPDATE_PLATFORM_INVALID"));
    }

    // ★ 경로의 앱으로만 행을 찾는다 — 플랫폼만으로 찾으면 파트너스 IOS 를 고쳤는데 유학생 IOS 가 바뀐다.
    @Test
    @DisplayName("파트너스 앱 정책 수정은 파트너스 행만 고친다")
    void partner_update_touches_only_partner_row() {
        AppUpdatePolicy partner = AppVersionServiceTest.policy(AppKind.PARTNER, "IOS", "1.0.0", "1.0.0", "");
        given(repository.findByAppAndPlatform(AppKind.PARTNER, "IOS")).willReturn(Optional.of(partner));

        AdminAppUpdatePolicyResponse response = service.update(AppKind.PARTNER, "ios", req("1.0.0", "1.4.0", ""));

        assertThat(response.app()).isEqualTo(AppKind.PARTNER);
        assertThat(partner.getLatestVersion()).isEqualTo("1.4.0");
        verify(repository, never()).findByAppAndPlatform(eq(AppKind.STUDENT), any());
    }

    @Test
    @DisplayName("앱별 목록은 그 앱의 행만 준다")
    void find_all_is_per_app() {
        given(repository.findByAppOrderByPlatformAsc(AppKind.PARTNER)).willReturn(List.of(
                AppVersionServiceTest.policy(AppKind.PARTNER, "ANDROID", "1.0.0", "1.0.0", ""),
                AppVersionServiceTest.policy(AppKind.PARTNER, "IOS", "1.0.0", "1.0.0", "")));

        List<AdminAppUpdatePolicyResponse> rows = service.findAll(AppKind.PARTNER);

        assertThat(rows).extracting(AdminAppUpdatePolicyResponse::app).containsOnly(AppKind.PARTNER);
        assertThat(rows).extracting(AdminAppUpdatePolicyResponse::platform).containsExactly("ANDROID", "IOS");
    }
}
