package com.kovil.domain.appversion.service;

import com.kovil.domain.appversion.dto.response.AppVersionCheckResponse;
import com.kovil.domain.appversion.entity.AppUpdatePolicy;
import com.kovil.domain.appversion.enums.AppKind;
import com.kovil.domain.appversion.enums.UpdateVerdict;
import com.kovil.domain.appversion.repository.AppUpdatePolicyRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.kovil.domain.appversion.enums.UpdateVerdict.FORCE;
import static com.kovil.domain.appversion.enums.UpdateVerdict.OK;
import static com.kovil.domain.appversion.enums.UpdateVerdict.RECOMMEND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * 버전 게이트 판정 격자.
 *
 * <p>★ 이 판정의 실패 방향은 전부 <b>통과(OK)</b>로 고정한다. 잘못된 FORCE는 스토어에 나간
 * 모든 바이너리를 잠그고, 유저가 앱을 못 여는 동안엔 무엇도 되돌릴 수 없다 —
 * 모르는 플랫폼·못 읽는 버전·설정 오타·스토어 URL 미설정이 그 케이스다.
 */
class AppVersionServiceTest {

    private static final String STORE = "https://play.google.com/store/apps/details?id=com.kovil";

    // 기본은 유학생 앱 행이다 — 이미 나간 유학생 바이너리(app 을 안 보낸다)가 보는 정책.
    static AppUpdatePolicy policy(String platform, String min, String latest, String storeUrl) {
        return policy(AppKind.STUDENT, platform, min, latest, storeUrl);
    }

    static AppUpdatePolicy policy(AppKind app, String platform, String min, String latest, String storeUrl) {
        return AppUpdatePolicy.builder()
                .app(app).platform(platform).minSupportedVersion(min).latestVersion(latest).storeUrl(storeUrl)
                .build();
    }

    private static AppVersionService service(String min, String latest, String storeUrl) {
        AppUpdatePolicyRepository repository = mock(AppUpdatePolicyRepository.class);
        given(repository.findAll()).willReturn(List.of(
                policy("IOS", min, latest, storeUrl), policy("ANDROID", min, latest, storeUrl)));
        return new AppVersionService(repository);
    }

    private static UpdateVerdict verdict(AppVersionService service, String version) {
        return service.check(null, "ios", version).verdict();
    }

    @Nested
    @DisplayName("판정 격자 — 경계는 '미만'이다")
    class Lattice {

        @Test
        @DisplayName("최소지원 미만이면 FORCE, 최소지원과 같으면 아니다")
        void force_boundary() {
            AppVersionService service = service("1.2.0", "1.3.0", STORE);
            assertThat(verdict(service, "1.1.9")).isEqualTo(FORCE);
            assertThat(verdict(service, "1.2.0")).isEqualTo(RECOMMEND); // 지원 범위, 최신 아님
        }

        @Test
        @DisplayName("지원 범위지만 최신이 아니면 RECOMMEND, 최신과 같거나 높으면 OK")
        void recommend_boundary() {
            AppVersionService service = service("1.0.0", "1.3.0", STORE);
            assertThat(verdict(service, "1.2.9")).isEqualTo(RECOMMEND);
            assertThat(verdict(service, "1.3.0")).isEqualTo(OK);
            assertThat(verdict(service, "9.9.9")).isEqualTo(OK); // 개발 빌드가 정책보다 앞설 수 있다
        }

        @Test
        @DisplayName("자리 수가 달라도 숫자로 비교한다 — 1.10.0은 1.9.0보다 높다")
        void numeric_not_lexicographic() {
            AppVersionService service = service("1.9.0", "1.10.0", STORE);
            assertThat(verdict(service, "1.10.0")).isEqualTo(OK);
            assertThat(verdict(service, "1.9.0")).isEqualTo(RECOMMEND);
        }

        @Test
        @DisplayName("플랫폼별 정책은 독립이다 — 심사 통과 시점이 다르면 버전도 다르다")
        void per_platform() {
            AppUpdatePolicyRepository repository = mock(AppUpdatePolicyRepository.class);
            given(repository.findAll()).willReturn(List.of(
                    policy("IOS", "2.0.0", "2.0.0", STORE),
                    policy("ANDROID", "1.0.0", "1.0.0", STORE)));
            AppVersionService service = new AppVersionService(repository);
            assertThat(service.check(null, "ios", "1.5.0").verdict()).isEqualTo(FORCE);
            assertThat(service.check(null, "android", "1.5.0").verdict()).isEqualTo(OK);
        }
    }

    @Nested
    @DisplayName("실패 방향 고정 — 전부 통과")
    class FailOpen {

        @Test
        @DisplayName("모르는 플랫폼·누락은 OK다 — web에는 스토어가 없다")
        void unknown_platform_is_ok() {
            AppVersionService service = service("9.0.0", "9.0.0", STORE);
            assertThat(service.check(null, "web", "0.1.0").verdict()).isEqualTo(OK);
            assertThat(service.check(null, null, "0.1.0").verdict()).isEqualTo(OK);
            assertThat(service.check(null, "windows", "0.1.0").verdict()).isEqualTo(OK);
        }

        @Test
        @DisplayName("못 읽는 클라이언트 버전은 OK다 — 이미 나간 바이너리를 400으로 막을 수 없다")
        void unparseable_client_version_is_ok() {
            AppVersionService service = service("9.0.0", "9.0.0", STORE);
            assertThat(verdict(service, null)).isEqualTo(OK);
            assertThat(verdict(service, "")).isEqualTo(OK);
            assertThat(verdict(service, "beta")).isEqualTo(OK);
        }

        @Test
        @DisplayName("설정 오타(min/latest)도 잠그지 않는다 — OK로 통과시키고 로그만 남긴다")
        void config_typo_is_ok() {
            assertThat(verdict(service("oops", "1.0.0", STORE), "0.0.1")).isEqualTo(OK);
            assertThat(verdict(service("9.0.0", "oops", STORE), "0.0.1")).isEqualTo(OK);
        }

        @Test
        @DisplayName("min > latest 오설정은 발동하지 않는다 — min만 올리면 최신 유저까지 스토어 왕복 락아웃이 된다")
        void min_above_latest_suppresses_enforcement() {
            // 문서화된 사고 경로: 강제를 켜려고 min만 올리고 latest(권장용이라 방치되기 쉬움)를 안 올림.
            AppVersionService service = service("1.1.0", "1.0.0", STORE);
            assertThat(verdict(service, "1.0.0")).isEqualTo(OK); // 최신(=스토어에 있는) 바이너리
            assertThat(verdict(service, "0.9.0")).isEqualTo(OK); // 구버전도 — 오설정은 통째로 억제
            // min == latest 는 오설정이 아니다 — 강제 업데이트를 켜는 표준 상태.
            assertThat(verdict(service("1.1.0", "1.1.0", STORE), "1.0.0")).isEqualTo(FORCE);
        }

        @Test
        @DisplayName("점으로 시작하거나 세그먼트가 빈 버전은 판정 불가 = OK — 0으로 채우면 FORCE로 샌다")
        void dot_led_or_empty_segment_version_is_ok() {
            AppVersionService service = service("1.0.0", "1.0.0", STORE);
            // 관대한 파싱이 "."을 0.0.0으로 읽으면 이상 입력이 유일하게 금지된 방향(fail-closed)으로 넘어진다.
            assertThat(verdict(service, ".")).isEqualTo(OK);
            assertThat(verdict(service, "..")).isEqualTo(OK);
            assertThat(verdict(service, ".1.0")).isEqualTo(OK);
            assertThat(verdict(service, "1..2")).isEqualTo(OK);
            assertThat(verdict(service, "1.2.")).isEqualTo(OK);
            assertThat(verdict(service, "99999999999999999999.0.0")).isEqualTo(OK); // 오버플로도 판정 불가
        }

        @Test
        @DisplayName("DB 조회가 실패하면 전부 OK다 — DB 장애가 전 유저 강제 화면이 되면 안 된다")
        void repository_failure_is_ok() {
            AppUpdatePolicyRepository repository = mock(AppUpdatePolicyRepository.class);
            given(repository.findAll()).willThrow(new RuntimeException("connection refused"));
            AppVersionService service = new AppVersionService(repository);

            assertThat(service.check(null, "ios", "0.0.1").verdict()).isEqualTo(OK);
        }

        @Test
        @DisplayName("정책 캐시는 evictCache로 즉시 갈린다 — 관리자 저장이 다음 판정부터 보인다")
        void evict_cache_reloads_policy() {
            AppUpdatePolicyRepository repository = mock(AppUpdatePolicyRepository.class);
            given(repository.findAll()).willReturn(
                    List.of(policy("IOS", "1.0.0", "1.0.0", STORE)),
                    List.of(policy("IOS", "2.0.0", "2.0.0", STORE)));
            AppVersionService service = new AppVersionService(repository);

            assertThat(service.check(null, "ios", "1.0.0").verdict()).isEqualTo(OK);   // 첫 로드 + 캐시
            assertThat(service.check(null, "ios", "1.0.0").verdict()).isEqualTo(OK);   // 캐시 재사용(두 번째 스텁 미소비)
            service.evictCache();
            assertThat(service.check(null, "ios", "1.0.0").verdict()).isEqualTo(FORCE); // 새 정책
        }

        @Test
        @DisplayName("스토어 URL 없이는 어떤 판정도 발동하지 않는다 — 보낼 곳 없는 강제 화면은 락아웃이다")
        void no_store_url_suppresses_enforcement() {
            assertThat(verdict(service("9.0.0", "9.0.0", ""), "1.0.0")).isEqualTo(OK);
            assertThat(verdict(service("9.0.0", "9.0.0", null), "1.0.0")).isEqualTo(OK);
            assertThat(service("9.0.0", "9.0.0", " ").check(null, "ios", "1.0.0").storeUrl()).isNull();
        }
    }

    @Nested
    @DisplayName("버전 문자열 해석")
    class Parsing {

        @Test
        @DisplayName("빠진 자리는 0이다 — '1.2'는 '1.2.0'과 같다")
        void missing_segments_are_zero() {
            AppVersionService service = service("1.2.0", "1.2.0", STORE);
            assertThat(verdict(service, "1.2")).isEqualTo(OK);
        }

        @Test
        @DisplayName("프리릴리스 꼬리는 무시한다 — '1.2.3-beta.1'은 1.2.3이다")
        void prerelease_suffix_is_ignored() {
            AppVersionService service = service("1.2.3", "1.2.3", STORE);
            assertThat(verdict(service, "1.2.3-beta.1")).isEqualTo(OK);
            assertThat(verdict(service, "1.2.2-rc1")).isEqualTo(FORCE);
        }
    }

    @Test
    @DisplayName("응답에는 항상 최신버전이 실린다 — RECOMMEND '한 번만 안내' 키로 쓴다")
    void latest_version_always_present_for_known_platform() {
        AppVersionCheckResponse response = service("1.0.0", "1.3.0", STORE).check(null, "ios", "1.0.0");
        assertThat(response.latestVersion()).isEqualTo("1.3.0");
        assertThat(response.storeUrl()).isEqualTo(STORE);
    }

    /*
     * ★★ 유학생 앱과 파트너스 앱은 릴리스가 다르다 — 한쪽 정책이 다른 쪽 판정에 새면, 한쪽을 강제 업데이트하는 순간
     *    다른 앱까지 잠긴다. app 을 안 보내는 것은 이미 나간 유학생 바이너리뿐이라 그때는 유학생 정책이다.
     *    모르는 app 은 400 이 아니라 OK 다(fail-open) — 유학생 정책으로 떨어뜨리지도 않는다.
     */
    @Nested
    @DisplayName("앱 구분")
    class AppSplit {

        private AppVersionService split() {
            AppUpdatePolicyRepository repository = mock(AppUpdatePolicyRepository.class);
            given(repository.findAll()).willReturn(List.of(
                    policy(AppKind.STUDENT, "IOS", "2.0.0", "2.0.0", STORE),
                    policy(AppKind.PARTNER, "IOS", "1.0.0", "3.0.0", STORE)));
            return new AppVersionService(repository);
        }

        @Test
        @DisplayName("파트너스 앱은 파트너스 정책, 유학생 앱은 유학생 정책으로 판정한다 — 서로 새지 않는다")
        void each_app_uses_its_own_policy() {
            AppVersionService service = split();

            assertThat(service.check("STUDENT", "ios", "1.5.0").verdict()).isEqualTo(FORCE);
            assertThat(service.check("PARTNER", "ios", "1.5.0").verdict()).isEqualTo(RECOMMEND);
            assertThat(service.check("PARTNER", "ios", "1.5.0").latestVersion()).isEqualTo("3.0.0");
        }

        @Test
        @DisplayName("app 이 없거나 비어 있으면 유학생 앱이다 — 이미 나간 유학생 바이너리")
        void missing_or_blank_app_is_student() {
            AppVersionService service = split();

            assertThat(service.check(null, "ios", "1.5.0").verdict()).isEqualTo(FORCE);
            assertThat(service.check("", "ios", "1.5.0").verdict()).isEqualTo(FORCE);
            assertThat(service.check("  ", "ios", "1.5.0").verdict()).isEqualTo(FORCE);
        }

        @Test
        @DisplayName("app 은 대소문자를 가리지 않는다")
        void app_is_case_insensitive() {
            assertThat(split().check(" partner ", "ios", "1.5.0").verdict()).isEqualTo(RECOMMEND);
        }

        @Test
        @DisplayName("모르는 app 은 OK 다 — 유학생 정책으로 떨어뜨려 강제하지도 않는다")
        void unknown_app_is_ok_not_student() {
            AppVersionCheckResponse response = split().check("ADMIN", "ios", "0.0.1");

            assertThat(response.verdict()).isEqualTo(OK);
            assertThat(response.latestVersion()).isNull();
        }

        @Test
        @DisplayName("파트너스 행이 없는 플랫폼은 유학생 행을 빌려 쓰지 않고 OK 다")
        void partner_does_not_borrow_student_row() {
            assertThat(split().check("PARTNER", "android", "0.0.1").verdict()).isEqualTo(OK);
        }
    }
}
