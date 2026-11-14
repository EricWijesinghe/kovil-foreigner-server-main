package com.kovil.domain.file.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "단기 열람 URL. **캐시하거나 공유하면 안 된다** — 만료되며, 그게 보호 장치다.")
public record FileReadUrlResponse(

        @Schema(description = "이 URL로 바로 GET하면 파일이 내려온다")
        String url,

        @Schema(description = "유효 시간(초). 지나면 다시 요청한다.", example = "300")
        long expiresInSeconds
) {
}
