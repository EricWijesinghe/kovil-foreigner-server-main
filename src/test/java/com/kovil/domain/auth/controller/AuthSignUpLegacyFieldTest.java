package com.kovil.domain.auth.controller;

import com.kovil.common.exception.ErrorCodeResolver;
import com.kovil.common.exception.ErrorMessageResolver;
import com.kovil.common.handler.GlobalExceptionHandler;
import com.kovil.common.ratelimit.ClientIpResolver;
import com.kovil.common.security.filter.JwtAuthenticationFilter;
import com.kovil.domain.auth.dto.request.LocalSignUpRequest;
import com.kovil.domain.auth.dto.response.SignInResponse;
import com.kovil.domain.auth.service.AccountLookupService;
import com.kovil.domain.auth.service.AuthService;
import com.kovil.domain.auth.service.LocalAuthService;
import com.kovil.domain.auth.service.PasswordResetService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * 유학생 가입 본문의 옛 requestedRole 은 **무시**한다(400 이 아니다) — 옛 직원 가입 화면이 FE 갱신 전까지 보낼 수 있다.
 *
 * ★ 이 동작은 코드 한 줄 없이 Jackson 의 기본값(모르는 필드 무시)에만 기대고 있다. 누가
 *   spring.jackson.deserialization.fail-on-unknown-properties 를 켜거나 Jackson 기본값 묶음을 바꾸면 옛 가입 요청이
 *   전부 400 이 되는데, 서비스 테스트는 DTO 를 직접 만들어 이걸 못 본다. 그래서 부트가 구성한 매퍼로 실제 본문을 태운다.
 * ★ 값이 무엇이든 역할은 서버가 USER 로 정한다 — 서비스는 역할을 받지 않는다(LocalAuthService.signUp).
 */
@WebMvcTest(
        controllers = AuthController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE, classes = JwtAuthenticationFilter.class)
)
@AutoConfigureMockMvc(addFilters = false)
@Import({AuthController.class, GlobalExceptionHandler.class, ErrorCodeResolver.class, ErrorMessageResolver.class})
class AuthSignUpLegacyFieldTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean AuthService authService;
    @MockitoBean PasswordResetService passwordResetService;
    @MockitoBean AccountLookupService accountLookupService;
    @MockitoBean LocalAuthService localAuthService;
    @MockitoBean ClientIpResolver clientIpResolver;

    @Test
    @DisplayName("옛 requestedRole 을 실은 가입은 400 이 아니라 그대로 가입된다 — 필드는 버려진다")
    void legacy_requested_role_is_ignored() throws Exception {
        given(localAuthService.signUp(any())).willReturn(
                new SignInResponse("at", "rt", 1800, "u-1", "USER", List.of(), "ko"));

        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"verificationToken":"vt-1","email":"someone@example.test",
                                 "password":"passw0rd!","name":"홍길동","preferredLanguage":"ko",
                                 "requestedRole":"MANAGER"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.role").value("USER"));

        ArgumentCaptor<LocalSignUpRequest> request = ArgumentCaptor.forClass(LocalSignUpRequest.class);
        then(localAuthService).should().signUp(request.capture());
        assertThat(request.getValue().email()).isEqualTo("someone@example.test");
        assertThat(request.getValue().verificationToken()).isEqualTo("vt-1");
    }
}
