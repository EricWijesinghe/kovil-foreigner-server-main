package com.kovil.domain.auth.controller;

import com.kovil.common.exception.ErrorCodeResolver;
import com.kovil.common.exception.ErrorMessageResolver;
import com.kovil.common.handler.GlobalExceptionHandler;
import com.kovil.common.security.filter.JwtAuthenticationFilter;
import com.kovil.domain.auth.dto.response.PhoneSendResponse;
import com.kovil.domain.auth.dto.response.PhoneVerifyResponse;
import com.kovil.domain.auth.enums.VerificationPurpose;
import com.kovil.domain.auth.exception.PhoneVerificationException;
import com.kovil.domain.auth.service.PhoneVerificationService;
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
import org.hamcrest.Matchers;
import org.mockito.BDDMockito;

import static com.kovil.domain.auth.exception.AuthErrorCode.PHONE_NOT_KR;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = PhoneVerificationController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE, classes = JwtAuthenticationFilter.class)
)
@AutoConfigureMockMvc(addFilters = false)
@Import({PhoneVerificationController.class, GlobalExceptionHandler.class,
        ErrorCodeResolver.class, ErrorMessageResolver.class})
class PhoneVerificationControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    PhoneVerificationService phoneVerificationService;

    // 인증번호가 응답으로 새면 인증이 통째로 무의미해진다. 응답 본문 전체를 검사한다.
    @Test
    @DisplayName("발송 응답 어디에도 인증번호가 없다 — 만료 초만 나간다")
    void send_response_never_carries_code() throws Exception {
        given(phoneVerificationService.send(anyString(), any(VerificationPurpose.class)))
                .willReturn(new PhoneSendResponse(300, 60));

        mockMvc.perform(post("/api/v1/auth/phone/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"010-1234-5678\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expiresInSeconds").value(300))
                .andExpect(content().string(Matchers.not(
                        Matchers.matchesPattern(".*\\d{6}.*"))));
    }

    @Test
    @DisplayName("확인 성공 응답은 토큰과 만료 초만 담는다")
    void verify_returns_token() throws Exception {
        given(phoneVerificationService.verify(anyString(), anyString(), any(VerificationPurpose.class)))
                .willReturn(new PhoneVerifyResponse("tok-abc", 600));

        mockMvc.perform(post("/api/v1/auth/phone/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"01012345678\",\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verificationToken").value("tok-abc"))
                .andExpect(jsonPath("$.data.expiresInSeconds").value(600));
    }

    @Test
    @DisplayName("6자리가 아닌 코드는 컨트롤러 앞단에서 걸린다 — 서비스까지 가지 않는다")
    void malformed_code_is_rejected_before_service() throws Exception {
        mockMvc.perform(post("/api/v1/auth/phone/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"01012345678\",\"code\":\"12\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors[0].errorCode").value("VERIFICATION_CODE_INVALID"));

        BDDMockito.then(phoneVerificationService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("한국 번호가 아니면 400 PHONE_NOT_KR이 사용자 언어로 나간다")
    void non_kr_phone_is_400() throws Exception {
        willThrow(new PhoneVerificationException(PHONE_NOT_KR))
                .given(phoneVerificationService).send(anyString(), any(VerificationPurpose.class));

        mockMvc.perform(post("/api/v1/auth/phone/send")
                        .header("Accept-Language", "en")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"+8613800138000\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("PHONE_NOT_KR"))
                .andExpect(jsonPath("$.message").value("Only Korean mobile numbers are supported."));
    }

    @Test
    @DisplayName("번호가 비면 400 PHONE_REQUIRED로 걸린다")
    void blank_phone_is_400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/phone/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors[0].errorCode").value("PHONE_REQUIRED"));
    }
}
