package com.kovil.domain.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

public record AppleNonceResponse(

        @Schema(description = "`signInAsync({ nonce })` 에 그대로 넘기고, `POST /apple/native` 에도 같은 값을 싣는다. 10분 유효 · 한 번만 쓴다.",
                example = "Yp3t1oO2c4m8bQkXvL0aZr6eWn9sHgDfJuKiTyM5qE7")
        String nonce
) {
}
