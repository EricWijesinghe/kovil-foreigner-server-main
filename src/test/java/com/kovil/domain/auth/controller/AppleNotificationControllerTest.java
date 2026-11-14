package com.kovil.domain.auth.controller;

import com.kovil.common.exception.ErrorCodeResolver;
import com.kovil.common.exception.ErrorMessageResolver;
import com.kovil.common.handler.GlobalExceptionHandler;
import com.kovil.common.security.filter.JwtAuthenticationFilter;
import com.kovil.domain.auth.exception.AuthErrorCode;
import com.kovil.domain.auth.exception.OAuthException;
import com.kovil.domain.auth.service.AppleAccountEventService;
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

import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Apple 은 200 이 아니면 다시 보낸다. 처리한 것은 200, 서명이 틀린 것은 400 으로 갈라야 한다.
@WebMvcTest(
        controllers = AppleNotificationController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE, classes = JwtAuthenticationFilter.class)
)
@AutoConfigureMockMvc(addFilters = false)
@Import({AppleNotificationController.class, GlobalExceptionHandler.class,
        ErrorCodeResolver.class, ErrorMessageResolver.class})
class AppleNotificationControllerTest {

    private static final String URL = "/oauth/apple/notifications";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    AppleAccountEventService appleAccountEventService;

    @Test
    @DisplayName("payload 를 서비스로 넘기고 200 이다")
    void handled_event_is_200() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"payload\":\"a.b.c\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        then(appleAccountEventService).should().handle("a.b.c");
    }

    @Test
    @DisplayName("서명이 틀리면 400 OAUTH_TOKEN_INVALID 다")
    void forged_event_is_400() throws Exception {
        willThrow(new OAuthException(AuthErrorCode.OAUTH_TOKEN_INVALID))
                .given(appleAccountEventService).handle("forged");

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"payload\":\"forged\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("OAUTH_TOKEN_INVALID"));
    }

    @Test
    @DisplayName("payload 가 없으면 400 이고 서비스를 부르지 않는다")
    void missing_payload_is_400() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors[0].errorCode").value("OAUTH_TOKEN_INVALID"));

        then(appleAccountEventService).shouldHaveNoInteractions();
    }
}
