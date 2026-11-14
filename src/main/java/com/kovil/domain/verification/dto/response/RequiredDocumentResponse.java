package com.kovil.domain.verification.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = """
        제출해야 할 문서 한 건. **이 목록이 곧 제출 화면이다** — 순서대로 그리면 된다.

        `required=false`는 없어도 제출이 되지만 있으면 심사가 빨라진다.
        """)
public record RequiredDocumentResponse(

        @Schema(description = "제출 시 문서를 지목할 코드", example = "PASSPORT")
        String documentCode,

        @Schema(description = "요청 언어로 해석된 문서명", example = "여권")
        String name,

        @Schema(description = "촬영 가이드. **반려율을 좌우하므로 반드시 노출할 것.**",
                example = "사진면 전체가 잘리지 않게, 빛 반사 없이 촬영해 주세요.")
        String guideNote,

        @Schema(description = "true면 없을 때 제출이 거부된다", example = "true")
        boolean required,

        @Schema(description = "허용 장수 상한(여권 앞·뒤 등)", example = "2")
        int maxFiles,

        @Schema(description = "신분증류 여부. true면 취급 주의 문구를 띄우면 좋다.", example = "true")
        boolean sensitive
) {
}
