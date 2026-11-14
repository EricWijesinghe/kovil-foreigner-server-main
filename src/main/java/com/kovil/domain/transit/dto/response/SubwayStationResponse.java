package com.kovil.domain.transit.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;

@Schema(description = "지하철역 한 점(지도 레이어) — 환승역은 역 하나에 노선 여럿")
public record SubwayStationResponse(
        @Schema(description = "역 코드(ST0001 …) — 안정 식별자", example = "ST0065")
        String code,
        @Schema(description = "한국어 요청이면 국문, 그 외 언어는 영문")
        String name,
        @Schema(description = "역 대표 좌표(묶은 노선별 원천 좌표의 평균)")
        BigDecimal lat,
        BigDecimal lng,
        @Schema(description = "역이 지나는 노선 — 마커의 색 점 순서")
        List<Line> lines
) {

    @Schema(name = "SubwayStationLine", description = "역이 지나는 노선 한 줄")
    public record Line(
            @Schema(description = "노선 코드(SEL-2 · SHINBUNDANG …)", example = "SEL-2")
            String code,
            @Schema(description = "한국어 요청이면 국문, 그 외 언어는 영문", example = "2호선")
            String name,
            @Schema(description = "노선색 #RRGGBB", example = "#00A84D")
            String color
    ) {
    }
}