package com.kovil.domain.auth.controller;

import com.kovil.common.exception.ErrorCodeResolver;
import com.kovil.common.exception.ErrorMessageResolver;
import com.kovil.common.handler.GlobalExceptionHandler;
import com.kovil.common.security.filter.JwtAuthenticationFilter;
import com.kovil.domain.auth.dto.response.OAuthLoginResponse;
import com.kovil.domain.auth.service.AppleNativeAuthService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = AppleNativeAuthController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE, classes = JwtAuthenticationFilter.class)
)
@AutoConfigureMockMvc(addFilters = false)
@Import({AppleNativeAuthController.class, GlobalExceptionHandler.class,
        ErrorCodeResolver.class, ErrorMessageResolver.class})
class AppleNativeAuthControllerTest {

    private static final String URL = "/api/v1/auth/oauth/apple/native";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    AppleNativeAuthService appleNativeAuthService;

    @Test
    @DisplayName("nonce 를 data.nonce 로 준다")
    void issues_nonce() throws Exception {
        given(appleNativeAuthService.issueNonce()).willReturn("fresh-nonce");

        mockMvc.perform(get("/api/v1/auth/oauth/apple/nonce"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nonce").value("fresh-nonce"));
    }

    // 이메일은 서버가 검증한 identityToken 에서만 읽는다. 본문에 실려 와도 서비스로 넘기지 않는다.
    @Test
    @DisplayName("Apple 결과 · 이름을 서비스로 넘기고 본문의 email 은 쓰지 않는다")
    void passes_credential_and_name() throws Exception {
        given(appleNativeAuthService.login("id-token", "auth-code", "nonce-1", "Van A", "NGUYEN"))
                .willReturn(OAuthLoginResponse.phoneVerificationRequired("temp-1"));

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("""
                        {"identityToken":"id-token","authorizationCode":"auth-code","nonce":"nonce-1",
                         "fullName":{"givenName":"Van A","familyName":"NGUYEN"},"email":"attacker@example.test"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.phoneVerificationRequired").value(true))
                .andExpect(jsonPath("$.data.oauthToken").value("temp-1"));
    }

    @Test
    @DisplayName("fullName 이 없으면 이름 없이 넘긴다")
    void missing_full_name_passes_nulls() throws Exception {
        given(appleNativeAuthService.login("id-token", "auth-code", "nonce-1", null, null))
                .willReturn(OAuthLoginResponse.phoneVerificationRequired("temp-1"));

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("""
                        {"identityToken":"id-token","authorizationCode":"auth-code","nonce":"nonce-1","fullName":null}
                        """))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("nonce 가 없으면 OAUTH_STATE_INVALID 로 앞단에서 끊는다")
    void missing_nonce_is_rejected_before_service() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("""
                        {"identityToken":"id-token","authorizationCode":"auth-code"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors[0].errorCode").value("OAUTH_STATE_INVALID"));

        then(appleNativeAuthService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("identityToken 이 없거나 4096자를 넘으면 OAUTH_TOKEN_INVALID 다")
    void oversized_token_is_rejected_before_service() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("""
                        {"authorizationCode":"auth-code","nonce":"nonce-1"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors[0].errorCode").value("OAUTH_TOKEN_INVALID"));
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(
                        "{\"identityToken\":\"" + "a".repeat(4097)
                                + "\",\"authorizationCode\":\"auth-code\",\"nonce\":\"nonce-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors[0].errorCode").value("OAUTH_TOKEN_INVALID"));

        then(appleNativeAuthService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("이름 칸이 50자를 넘으면 INVALID_REQUEST 다")
    void oversized_name_is_rejected() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(
                        "{\"identityToken\":\"id-token\",\"authorizationCode\":\"auth-code\",\"nonce\":\"nonce-1\","
                                + "\"fullName\":{\"givenName\":\"" + "a".repeat(51) + "\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors[0].errorCode").value("INVALID_REQUEST"));

        then(appleNativeAuthService).shouldHaveNoInteractions();
    }
}
