package com.kovil.common.dto.request;

import com.kovil.common.validator.annotation.NotNullWithErrorCode;
import com.kovil.common.validator.annotation.SizeWithErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

// 트리 축(지역·요청 옵션) 공용. 스코프는 "같은 부모의 형제들"이다 —
// 유저 화면이 parentId로 그룹핑해 버킷 내 상대 순서만 쓰므로, 부모별 1..N 재부여는 안전하다.
@Schema(name = "MasterTreeOrderRequest", description = """
        같은 부모 아래 형제들의 순서를 한 번에 재부여한다. `parentId`가 null이면 최상위 형제들이고,
        값이 있으면 **최상위 행이어야 한다**(깊이 2 고정 — 리프를 부모로 주면 거부된다).
        `ids`는 그 부모의 자식 **전 행(비활성 포함)의 완전한 순열**이어야 한다.
        """)
public record MasterTreeOrderRequest(

        @Schema(description = "부모 id. null = 최상위 형제들") Long parentId,

        @Schema(description = "원하는 순서의 id 배열 — 그 스코프 전 행의 완전한 순열",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNullWithErrorCode("INVALID_REQUEST")
        @SizeWithErrorCode(value = "INVALID_REQUEST", min = 1, max = Integer.MAX_VALUE)
        List<Long> ids
) {
}
