package com.kovil.domain.appversion.controller;

import com.kovil.common.security.filter.JwtAuthenticationFilter;
import com.kovil.domain.appversion.service.AppVersionService;
import com.kovil.common.exception.ErrorCodeResolver;
import com.kovil.common.exception.ErrorMessageResolver;
import com.kovil.domain.appversion.dto.response.AppVersionCheckResponse;
import com.kovil.domain.appversion.enums.UpdateVerdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 파라미터 → 판정 배선. 이 엔드포인트는 스토어에 나간 모든 바이너리가 로그인 전에 부른다 —
 * 인증 헤더 없이 200이어야 하고, 이상 입력에도 400 대신 OK 봉투가 나가야 한다.
 */
@WebMvcTest(
        controllers = AppVersionController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE, classes = JwtAuthenticationFilter.class)
)
@AutoConfigureMockMvc(addFilters = false)
@Import({AppVersionController.class,
        ErrorCodeResolver.class,
        ErrorMessageResolver.class})
class AppVersionControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    AppVersionService appVersionService;

    @Test
    @DisplayName("인증 없이 200 — 판정·최신버전·스토어 URL이 봉투로 나간다")
    void public_check_returns_verdict() throws Exception {
        given(appVersionService.check(null, "ios", "1.0.0")).willReturn(
                new AppVersionCheckResponse(
                        UpdateVerdict.FORCE,
                        "2.0.0", "https://apps.apple.com/app/id123"));

        mockMvc.perform(get("/api/v1/app-version/check")
                        .queryParam("platform", "ios")
                        .queryParam("version", "1.0.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verdict").value("FORCE"))
                .andExpect(jsonPath("$.data.latestVersion").value("2.0.0"))
                .andExpect(jsonPath("$.data.storeUrl").value("https://apps.apple.com/app/id123"));
    }

    @Test
    @DisplayName("파라미터가 아예 없어도 400이 아니다 — 구버전 바이너리가 무엇을 보내든 답을 받는다")
    void missing_params_do_not_400() throws Exception {
        given(appVersionService.check(null, null, null)).willReturn(
                new AppVersionCheckResponse(
                        UpdateVerdict.OK, null, null));

        mockMvc.perform(get("/api/v1/app-version/check"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verdict").value("OK"));
    }

    // 파트너스 앱은 app=PARTNER 를 보낸다. 문자열 그대로 판정에 넘긴다 — enum 으로 받으면 모르는 값이 400 이 된다.
    @Test
    @DisplayName("app 은 그대로 판정에 넘어가고, 모르는 app 이어도 400 이 아니다")
    void app_param_is_passed_through_without_400() throws Exception {
        given(appVersionService.check("NOPE", "ios", "1.0.0")).willReturn(
                new AppVersionCheckResponse(
                        UpdateVerdict.OK, null, null));

        mockMvc.perform(get("/api/v1/app-version/check")
                        .queryParam("app", "NOPE")
                        .queryParam("platform", "ios")
                        .queryParam("version", "1.0.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verdict").value("OK"));
    }
}
