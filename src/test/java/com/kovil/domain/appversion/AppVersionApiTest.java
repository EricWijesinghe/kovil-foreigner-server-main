package com.kovil.domain.appversion;

import com.kovil.domain.appversion.entity.AppUpdatePolicy;
import com.kovil.domain.appversion.enums.AppKind;
import com.kovil.domain.appversion.repository.AppUpdatePolicyRepository;
import com.kovil.domain.appversion.service.AppVersionService;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.user.repository.UserRepository;
import com.kovil.infra.jwt.JwtTokenProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * 앱 버전 정책을 앱(유학생 · 파트너스)별로 나눈 것을 **진짜 보안 필터 · 진짜 DB · 진짜 캐시**로 태운다.
 * 판정은 비로그인 공개이고 앱별로 갈려야 하며, 관리자 수정은 경로의 앱 행만 고쳐야 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AppVersionApiTest {

    private static final String STORE = "https://apps.apple.com/app/id0000000000";

    @Autowired MockMvc mockMvc;
    @Autowired AppUpdatePolicyRepository repository;
    @Autowired AppVersionService appVersionService;
    @Autowired UserRepository userRepository;
    @Autowired JwtTokenProvider jwtTokenProvider;

    private String adminBearer;

    // 판정 캐시는 싱글턴이라 테스트 사이에 남는다 — 앞뒤로 비운다.
    @BeforeEach
    void setUp() {
        appVersionService.evictCache();
        repository.save(policy(AppKind.STUDENT, "IOS", "2.0.0", "2.0.0"));
        repository.save(policy(AppKind.PARTNER, "IOS", "1.0.0", "3.0.0"));
        User admin = userRepository.save(User.builder()
                .uuid("app-version-admin").status(UserStatus.ACTIVE).role(UserRole.ADMIN)
                .preferredLanguage("ko").build());
        adminBearer = "Bearer " + jwtTokenProvider.issueAccessToken(admin.getUuid(), UserRole.ADMIN);
    }

    @AfterEach
    void evict() {
        appVersionService.evictCache();
    }

    @Test
    @DisplayName("판정은 비로그인으로 열리고 app 이 없으면 유학생 정책, app=PARTNER 면 파트너스 정책이다")
    void public_check_is_split_by_app() throws Exception {
        mockMvc.perform(get("/api/v1/app-version/check").param("platform", "ios").param("version", "1.5.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verdict").value("FORCE"));

        mockMvc.perform(get("/api/v1/app-version/check")
                        .param("app", "PARTNER").param("platform", "ios").param("version", "1.5.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verdict").value("RECOMMEND"))
                .andExpect(jsonPath("$.data.latestVersion").value("3.0.0"));
    }

    @Test
    @DisplayName("관리자 목록은 경로의 앱 행만 준다")
    void admin_list_is_per_app() throws Exception {
        mockMvc.perform(get("/api/v1/admin/app-update-policies/PARTNER").header("Authorization", adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].app").value("PARTNER"))
                .andExpect(jsonPath("$.data[0].latestVersion").value("3.0.0"));
    }

    // ★ 플랫폼만으로 찾던 시절의 사고 — 파트너스 IOS 를 고쳤는데 유학생 IOS 가 바뀐다. 경로의 앱으로만 찾는다.
    @Test
    @DisplayName("관리자 수정은 경로의 앱 행만 고치고, 다른 앱의 같은 플랫폼 행은 그대로다")
    void admin_update_touches_only_that_app() throws Exception {
        mockMvc.perform(put("/api/v1/admin/app-update-policies/PARTNER/ios").header("Authorization", adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"minSupportedVersion\":\"1.1.0\",\"latestVersion\":\"3.1.0\",\"storeUrl\":\"" + STORE + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.app").value("PARTNER"));

        assertThat(repository.findByAppAndPlatform(AppKind.PARTNER, "IOS").orElseThrow().getLatestVersion())
                .isEqualTo("3.1.0");
        assertThat(repository.findByAppAndPlatform(AppKind.STUDENT, "IOS").orElseThrow().getLatestVersion())
                .isEqualTo("2.0.0");
    }

    @Test
    @DisplayName("관리자 API 의 모르는 앱은 400 이고, 앱 없는 옛 경로는 더 이상 없다")
    void admin_requires_known_app() throws Exception {
        mockMvc.perform(get("/api/v1/admin/app-update-policies/NOPE").header("Authorization", adminBearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
        mockMvc.perform(get("/api/v1/admin/app-update-policies").header("Authorization", adminBearer))
                .andExpect(status().isNotFound());
    }

    private AppUpdatePolicy policy(AppKind app, String platform, String min, String latest) {
        return AppUpdatePolicy.builder().app(app).platform(platform)
                .minSupportedVersion(min).latestVersion(latest).storeUrl(STORE).build();
    }
}
