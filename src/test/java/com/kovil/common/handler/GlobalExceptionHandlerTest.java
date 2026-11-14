package com.kovil.common.handler;

import com.kovil.common.dto.response.ApiResponse;
import com.kovil.common.exception.CommonErrorCode;
import com.kovil.common.exception.CustomException;
import com.kovil.common.exception.ErrorCodeResolver;
import com.kovil.common.exception.ErrorMessageResolver;
import com.kovil.common.security.filter.JwtAuthenticationFilter;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PessimisticLockException;
import jakarta.validation.Valid;
import org.springframework.dao.CannotAcquireLockException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = GlobalExceptionHandlerTest.TestController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE, classes = JwtAuthenticationFilter.class)
)
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandlerTest.TestController.class, GlobalExceptionHandler.class,
        ErrorCodeResolver.class, ErrorMessageResolver.class})
class GlobalExceptionHandlerTest {

    @Autowired
    MockMvc mockMvc;

    @TestComponent
    @RestController
    static class TestController {

        @GetMapping("/test/conflict")
        ApiResponse<Void> conflict() {
            throw new CustomException(CommonErrorCode.CONFLICT);
        }

        @GetMapping("/test/not-found")
        ApiResponse<Void> notFound() {
            throw new CustomException(CommonErrorCode.NOT_FOUND);
        }

        @GetMapping("/test/jpa-optimistic")
        ApiResponse<Void> jpaOptimistic() {
            throw new OptimisticLockException("stale");
        }

        @GetMapping("/test/jpa-pessimistic")
        ApiResponse<Void> jpaPessimistic() {
            throw new PessimisticLockException("deadlock");
        }

        @GetMapping("/test/spring-pessimistic")
        ApiResponse<Void> springPessimistic() {
            throw new CannotAcquireLockException("deadlock");
        }

        @GetMapping("/test/boom")
        ApiResponse<Void> boom() {
            throw new IllegalStateException("internal detail that must never reach the client");
        }

        @GetMapping("/test/ok")
        ApiResponse<String> ok() {
            return ApiResponse.success("hello");
        }

        @PostMapping("/test/validate")
        ApiResponse<Void> validate(@Valid @RequestBody Payload payload) {
            return ApiResponse.ok();
        }

        record Payload(@NotBlank(message = "INVALID_REQUEST") String name) {
        }
    }

    @Nested
    @DisplayName("응답 봉투")
    class Envelope {

        @Test
        @DisplayName("성공 응답은 success=true와 data만 담고 에러 필드는 아예 나가지 않는다")
        void success_envelope() throws Exception {
            mockMvc.perform(get("/test/ok"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data").value("hello"))
                    .andExpect(jsonPath("$.errorCode").doesNotExist())
                    .andExpect(jsonPath("$.message").doesNotExist());
        }

        @Test
        @DisplayName("실패 응답은 errorCode와 코드가 소유한 메시지를 함께 담는다")
        void error_envelope() throws Exception {
            mockMvc.perform(get("/test/not-found").header("Accept-Language", "ko"))
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"))
                    .andExpect(jsonPath("$.message").value("요청하신 정보를 찾을 수 없어요."))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }
    }

    @Nested
    @DisplayName("에러코드 → HTTP 상태 매핑")
    class StatusMapping {

        @Test
        @DisplayName("NOT_FOUND는 404로 나간다")
        void not_found_is_404() throws Exception {
            mockMvc.perform(get("/test/not-found")).andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("CONFLICT는 409로 나간다")
        void conflict_is_409() throws Exception {
            mockMvc.perform(get("/test/conflict")).andExpect(status().isConflict());
        }

        /*
         * ★ 서비스가 EntityManager.flush() 를 직접 부르면 충돌이 Spring 예외로 바뀌지 않고 JPA 표준 예외로 온다.
         *   잡지 않으면 같은 동시 저장이 경로에 따라 409 와 500 으로 갈린다(적대적 검증 2026-10-06).
         */
        @Test
        @DisplayName("변환되지 않은 JPA 낙관·비관 잠금 예외와 Spring 비관 잠금 예외도 409 로 나간다")
        void lock_conflicts_are_409() throws Exception {
            for (String path : new String[]{"/test/jpa-optimistic", "/test/jpa-pessimistic", "/test/spring-pessimistic"}) {
                mockMvc.perform(get(path)).andExpect(status().isConflict())
                        .andExpect(jsonPath("$.errorCode").value("CONFLICT"));
            }
        }

        @Test
        @DisplayName("처리하지 못한 예외는 500이 되고 내부 메시지는 노출되지 않는다")
        void unhandled_is_500_without_internal_detail() throws Exception {
            mockMvc.perform(get("/test/boom").header("Accept-Language", "ko"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.errorCode").value("INTERNAL_SERVER_ERROR"))
                    .andExpect(jsonPath("$.message").value("서버에 오류가 생겼어요."));
        }
    }

    @Nested
    @DisplayName("밸리데이션 실패")
    class Validation {

        @Test
        @DisplayName("제약 메시지에 적은 에러코드가 코드+메시지로 변환되어 나간다")
        void validation_failure_resolves_error_code() throws Exception {
            mockMvc.perform(post("/test/validate")
                            .header("Accept-Language", "ko")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.validationErrors[0].errorCode").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.validationErrors[0].message").value("올바르지 않은 요청이에요."));
        }

        @Test
        @DisplayName("본문이 깨진 요청은 400 INVALID_REQUEST로 나간다")
        void malformed_body_is_400() throws Exception {
            mockMvc.perform(post("/test/validate")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{not json"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
        }
    }
}
