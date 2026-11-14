package com.kovil.common.dto.request;

import com.kovil.common.validator.annotation.NotNullWithErrorCode;
import com.kovil.common.validator.annotation.SizeWithErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

// 평면 축(국가·체류 신분·문서 유형·게시판) 공용 — 축별 DTO 7개를 만들면 springdoc이
// 홑이름을 하나로 붕괴시키던 실사고(§12 관례)를 다시 낳는다. 그래서 공용 2개에 이름을 못 박는다.
@Schema(name = "MasterOrderRequest", description = """
        축 전체의 순서를 한 번에 재부여한다. `ids`는 그 축 **전 행(비활성 포함)의 완전한 순열**이어야
        하며, 누락·여분·중복이 있으면 `MASTER_ORDER_SCOPE_MISMATCH`(409)로 전체 거부된다 —
        다른 관리자가 방금 행을 추가했다는 뜻이므로 목록을 다시 불러 다시 시도한다.
        서버는 배열 순서대로 sortOrder를 1..N으로 다시 매긴다.
        """)
public record MasterOrderRequest(

        @Schema(description = "원하는 순서의 id 배열 — 축 전 행의 완전한 순열",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNullWithErrorCode("INVALID_REQUEST")
        @SizeWithErrorCode(value = "INVALID_REQUEST", min = 1, max = Integer.MAX_VALUE)
        List<Long> ids
) {
}
