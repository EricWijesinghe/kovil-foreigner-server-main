package com.kovil.common.validator;

import com.kovil.common.dto.response.ApiResponse;
import com.kovil.common.exception.ErrorCodeResolver;
import com.kovil.common.exception.ErrorMessageResolver;
import com.kovil.common.handler.GlobalExceptionHandler;
import com.kovil.common.security.filter.JwtAuthenticationFilter;
import com.kovil.common.validator.annotation.NotBlankWithErrorCode;
import com.kovil.common.validator.annotation.RangeWithErrorCode;
import jakarta.validation.Valid;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = WithErrorCodeValidationFlowTest.TestController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE, classes = JwtAuthenticationFilter.class)
)
@AutoConfigureMockMvc(addFilters = false)
@Import({WithErrorCodeValidationFlowTest.TestController.class, GlobalExceptionHandler.class, ErrorCodeResolver.class, ErrorMessageResolver.class})
class WithErrorCodeValidationFlowTest {

    @Autowired
    MockMvc mockMvc;

    @RestController
    static class TestController {

        @PostMapping("/test/with-error-code")
        ApiResponse<Void> validate(@Valid @RequestBody Payload payload) {
            return ApiResponse.ok();
        }

        record Payload(
                @NotBlankWithErrorCode("INVALID_REQUEST") String name,
                @RangeWithErrorCode(value = "NOT_FOUND", min = 1, max = 31) Integer paymentDay
        ) {
        }
    }

    @Test
    @DisplayName("커스텀 애노테이션에 적은 에러코드 이름이 코드+메시지로 변환되어 응답에 실린다")
    void error_code_name_is_resolved_into_response() throws Exception {
        mockMvc.perform(post("/test/with-error-code")
                        .header("Accept-Language", "ko")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  \",\"paymentDay\":31}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.validationErrors.length()").value(1))
                .andExpect(jsonPath("$.validationErrors[0].errorCode").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.validationErrors[0].message").value("올바르지 않은 요청이에요."));
    }

    @Test
    @DisplayName("필드마다 자기 에러코드를 갖고, 여러 필드가 깨지면 모두 실린다")
    void every_broken_field_carries_its_own_code() throws Exception {
        mockMvc.perform(post("/test/with-error-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":null,\"paymentDay\":32}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.length()").value(2))
                .andExpect(jsonPath("$.validationErrors[?(@.errorCode=='INVALID_REQUEST')]").exists())
                .andExpect(jsonPath("$.validationErrors[?(@.errorCode=='NOT_FOUND')]").exists());
    }

    @Test
    @DisplayName("모든 제약을 만족하면 통과한다")
    void valid_payload_passes() throws Exception {
        mockMvc.perform(post("/test/with-error-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"hyo\",\"paymentDay\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }
}
