package com.kovil.common.handler;

import com.kovil.common.dto.response.ApiResponse;
import com.kovil.common.dto.response.ErrorResponse;
import com.kovil.common.exception.CustomException;
import com.kovil.common.exception.ErrorCode;
import com.kovil.common.exception.ErrorCodeResolver;
import com.kovil.common.exception.ErrorMessageResolver;
import com.kovil.common.ratelimit.RateLimitException;
import com.kovil.domain.verification.service.VerificationSubmitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PessimisticLockException;
import org.springframework.dao.PessimisticLockingFailureException;

import java.util.List;

import static com.kovil.common.exception.CommonErrorCode.CONFLICT;
import static com.kovil.common.exception.CommonErrorCode.INTERNAL_SERVER_ERROR;
import static com.kovil.common.exception.CommonErrorCode.INVALID_REQUEST;
import static com.kovil.common.exception.CommonErrorCode.NOT_FOUND;
import static com.kovil.common.security.exception.SecurityErrorCode.FORBIDDEN_ACCESS;

@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final ErrorCodeResolver errorCodeResolver;
    private final ErrorMessageResolver errorMessageResolver;

    @ExceptionHandler(CustomException.class)
    public ResponseEntity<ApiResponse<Void>> handleCustom(CustomException e) {
        ErrorCode code = e.getErrorCode();
        log.warn("CustomException: {} - {}", code.getCode(), e.getMessage());
        return ResponseEntity
                .status(code.getHttpStatus())
                .body(ApiResponse.failure(code, errorMessageResolver.resolve(code)));
    }

    // 리미터는 인터셉터(preHandle)에서 던진다 — 핸들러 호출 전이지만 예외 리졸버는 그대로 탄다.
    @ExceptionHandler(RateLimitException.class)
    public ResponseEntity<ApiResponse<Void>> handleRateLimit(RateLimitException e) {
        ErrorCode code = e.getErrorCode();
        log.warn("RateLimited: retryAfter={}s", e.getRetryAfterSeconds());
        return ResponseEntity
                .status(code.getHttpStatus())
                .header("Retry-After", String.valueOf(e.getRetryAfterSeconds()))
                .body(ApiResponse.failure(code, errorMessageResolver.resolve(code)));
    }

    // FR-204 — 무엇이 빠졌는지 알려주지 않으면 사용자가 제출 화면을 처음부터 다시 훑어야 한다.
    // validationErrors 자리에 누락 코드를 실어 보낸다.
    @ExceptionHandler(VerificationSubmitService.MissingDocumentsException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingDocuments(
            VerificationSubmitService.MissingDocumentsException e) {
        ErrorCode code = e.getErrorCode();
        List<ErrorResponse> missing = e.getMissingDocumentCodes().stream()
                .map(documentCode -> new ErrorResponse(documentCode, code.getDefaultMessage()))
                .toList();

        log.warn("Verification documents missing: {}", e.getMissingDocumentCodes());
        return ResponseEntity
                .status(code.getHttpStatus())
                .body(ApiResponse.failure(code, errorMessageResolver.resolve(code), missing));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        List<ErrorResponse> errors = e.getBindingResult().getFieldErrors().stream()
                .map(this::toErrorResponse)
                .toList();

        log.warn("Validation failed: {}", errors);
        return ResponseEntity
                .status(INVALID_REQUEST.getHttpStatus())
                .body(ApiResponse.failure(INVALID_REQUEST, errorMessageResolver.resolve(INVALID_REQUEST), errors));
    }

    // @ModelAttribute·폼 바인딩 검증 실패는 MethodArgumentNotValid가 아니라 이쪽으로 온다.
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiResponse<Void>> handleBind(BindException e) {
        List<ErrorResponse> errors = e.getBindingResult().getFieldErrors().stream()
                .map(this::toErrorResponse)
                .toList();

        log.warn("Bind validation failed: {}", errors);
        return ResponseEntity
                .status(INVALID_REQUEST.getHttpStatus())
                .body(ApiResponse.failure(INVALID_REQUEST, errorMessageResolver.resolve(INVALID_REQUEST), errors));
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MissingRequestHeaderException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleMalformedRequest(Exception e) {
        log.warn("Malformed request: {}", e.getMessage());
        return ResponseEntity
                .status(INVALID_REQUEST.getHttpStatus())
                .body(ApiResponse.failure(INVALID_REQUEST, errorMessageResolver.resolve(INVALID_REQUEST)));
    }

    @ExceptionHandler({NoResourceFoundException.class, HttpRequestMethodNotSupportedException.class})
    public ResponseEntity<ApiResponse<Void>> handleNoResource(Exception e) {
        return ResponseEntity
                .status(NOT_FOUND.getHttpStatus())
                .body(ApiResponse.failure(NOT_FOUND, errorMessageResolver.resolve(NOT_FOUND)));
    }

    // @PreAuthorize 거부는 필터가 아니라 메서드 호출 중에 터진다. 이 핸들러가 없으면
    // 아래 catch-all에 먹혀 403이어야 할 응답이 500으로 나간다.
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException e) {
        log.warn("Access denied: {}", e.getMessage());
        return ResponseEntity
                .status(FORBIDDEN_ACCESS.getHttpStatus())
                .body(ApiResponse.failure(FORBIDDEN_ACCESS, errorMessageResolver.resolve(FORBIDDEN_ACCESS)));
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleOptimisticLock(ObjectOptimisticLockingFailureException e) {
        log.warn("Optimistic lock conflict: {}", e.getMessage());
        return ResponseEntity
                .status(CONFLICT.getHttpStatus())
                .body(ApiResponse.failure(CONFLICT, errorMessageResolver.resolve(CONFLICT)));
    }

    /*
     * ★ 같은 충돌이 **Spring 예외로 바뀌지 않은 채** 올라오는 길이 있다 — 서비스가 EntityManager.flush() 를 직접 부르면
     *   변환 층(리포지토리 프록시)을 거치지 않아 JPA 표준 예외가 그대로 온다. 이걸 놓치면 같은 충돌이 경로에 따라
     *   409 와 500 으로 갈린다. 교착·잠금 대기 초과도 다시 하면 되는 충돌이라 500 이 아니라 409 다.
     */
    @ExceptionHandler({OptimisticLockException.class,
            PessimisticLockException.class,
            LockTimeoutException.class,
            PessimisticLockingFailureException.class})
    public ResponseEntity<ApiResponse<Void>> handleLockConflict(Exception e) {
        log.warn("Lock conflict: {}", e.getMessage());
        return ResponseEntity
                .status(CONFLICT.getHttpStatus())
                .body(ApiResponse.failure(CONFLICT, errorMessageResolver.resolve(CONFLICT)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
        log.error("Unexpected exception", e);
        return ResponseEntity
                .status(INTERNAL_SERVER_ERROR.getHttpStatus())
                .body(ApiResponse.failure(INTERNAL_SERVER_ERROR, errorMessageResolver.resolve(INTERNAL_SERVER_ERROR)));
    }

    private ErrorResponse toErrorResponse(FieldError fieldError) {
        ErrorCode resolved = errorCodeResolver.resolve(fieldError.getDefaultMessage());
        return new ErrorResponse(resolved.getCode(), errorMessageResolver.resolve(resolved));
    }
}
