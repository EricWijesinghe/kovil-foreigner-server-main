package com.kovil.domain.search.dto.response;

import com.kovil.domain.search.enums.PlaceType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;

@Schema(description = "장소 통합 검색 결과 — 지역 · 대학교 · 지하철역을 한 목록에(완전 일치 → 앞부분 일치 → 포함, 같으면 지역 → 대학교 → 역)")
public record PlaceSearchResponse(List<Item> items) {

    @Schema(name = "PlaceSearchItem", description = "검색 결과 한 줄")
    public record Item(
            @Schema(description = "REGION(열린 시군구) · UNIVERSITY(캠퍼스) · STATION(지하철역)", example = "UNIVERSITY")
            PlaceType type,
            @Schema(description = "지역 = 시군구 법정동코드 · 대학교 = 교육부 학교코드(7자리) · 역 = 역 코드(ST0001 …)", example = "0000149")
            String code,
            @Schema(description = "한국어 요청이면 국문, 그 외 언어는 영문", example = "연세대학교")
            String name,
            @Schema(description = "지역 = 시도 이름 · 대학교 = 캠퍼스 · 시군구 · 역 = 노선 이름들. 붙일 것이 없으면 null", example = "서대문구")
            String subtitle,
            @Schema(description = "지도를 옮길 자리. 지역은 그 구 공개 매물의 흐린 좌표 평균(지도 묶음과 같은 규칙) — 공개 매물이 없으면 null(앱은 regionCode 로 목록만 연다)")
            BigDecimal lat,
            BigDecimal lng,
            @Schema(description = "열린 시군구 코드 — 목록 필터(`regionCode`)에 그대로 쓴다. 서비스 지역 밖이면 null", example = "11410")
            String regionCode
    ) {
    }
}