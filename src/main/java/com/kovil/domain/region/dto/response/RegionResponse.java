package com.kovil.domain.region.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = """
        행정구역 2단 트리(시도 → 시군구). 깊이는 2로 고정이다.

        **서비스 미개시 지역은 목록에 아예 없다** — 비활성 시도의 하위 시군구도 통째로 빠진다.
        FE는 받은 트리를 그대로 그리면 되고 별도 필터가 필요 없다.
        """)
public record RegionResponse(

        @Schema(description = "법정동코드(시도 2자리 / 시군구 5자리). 선택 결과로 이 값을 보낸다.",
                example = "11")
        String code,

        @Schema(description = "한국어 요청이면 국문, 그 외 언어는 전부 영문", example = "서울특별시")
        String name,

        @Schema(description = "하위 시군구. 시군구 노드에서는 항상 빈 배열이다(null 아님).")
        List<RegionResponse> children
) {
}
