package com.kovil.common.dto.response;

import com.kovil.common.exception.ErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "모든 응답이 따르는 공통 봉투. null 필드는 직렬화에서 제외된다.")
public record ApiResponse<T>(

        @Schema(description = "성공 여부. FE의 1차 분기 축이다.", example = "true")
        boolean success,

        @Schema(description = "성공 시 payload. 실패 응답에는 없다.")
        T data,

        @Schema(description = "사용자에게 그대로 보여줘도 되는 문구. Accept-Language로 해석된다.",
                example = "요청하신 정보를 찾을 수 없습니다.")
        String message,

        @Schema(description = "실패 시 분기 축. 문구가 아니라 이 값으로 화면을 나눈다.",
                example = "NOT_FOUND")
        String errorCode,

        @Schema(description = "입력 검증 실패 시 필드별 사유. 항목은 필드명이 아니라 errorCode다.")
        List<ErrorResponse> validationErrors
) {
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, data, null, null, null);
    }

    public static <T> ApiResponse<T> success(T data, String message) {
        return new ApiResponse<>(true, data, message, null, null);
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(true, null, null, null, null);
    }

    public static <T> ApiResponse<T> failure(ErrorCode errorCode, String message) {
        return new ApiResponse<>(false, null, message, errorCode.getCode(), null);
    }

    public static <T> ApiResponse<T> failure(ErrorCode errorCode, String message,
                                             List<ErrorResponse> validationErrors) {
        return new ApiResponse<>(false, null, message, errorCode.getCode(), validationErrors);
    }
}
