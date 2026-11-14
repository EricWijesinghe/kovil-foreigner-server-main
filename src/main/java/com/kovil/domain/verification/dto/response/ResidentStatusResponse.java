package com.kovil.domain.verification.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = """
        체류 신분. **신분이 늘어도 앱 배포가 필요 없다** — 서버가 주는 목록을 그대로 그리면 된다.
        `iconKey`로 아이콘을 고르고, 모르는 키가 오면 기본 아이콘으로 떨어뜨린다.
        """)
public record ResidentStatusResponse(

        @Schema(description = "요건 조회·제출에 보낼 코드", example = "STUDENT")
        String code,

        @Schema(description = "요청 언어로 해석된 표시명", example = "유학생")
        String name,

        @Schema(description = "선택 화면 보조 설명", example = "대학·어학당 재학 중인 분")
        String description,

        @Schema(description = "FE 아이콘 키. 모르는 값이면 기본 아이콘으로.", example = "graduation-cap")
        String iconKey
) {
}
