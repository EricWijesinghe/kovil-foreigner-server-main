package com.kovil.common.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "검증 실패 항목. 필드명을 주지 않는 이유: FE가 필드가 아니라 코드로 분기하기 때문이다.")
public record ErrorResponse(
        @Schema(description = "이 항목이 왜 실패했는지의 코드", example = "PASSWORD_INVALID_FORMAT")
        String errorCode,

        @Schema(description = "사용자 노출 문구", example = "비밀번호는 8~64자이며 영문과 숫자를 포함해야 합니다.")
        String message
) {}
