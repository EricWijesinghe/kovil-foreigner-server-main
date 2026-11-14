package com.kovil.domain.university.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

@Schema(description = "대학교 캠퍼스 한 점(지도 레이어)")
public record UniversityResponse(
        @Schema(description = "교육부 학교코드(7자리, 캠퍼스마다 다르다) — 안정 식별자", example = "0000149")
        String code,
        @Schema(description = "한국어 요청이면 국문, 그 외 언어는 영문")
        String name,
        @Schema(description = "알약용 짧은 이름. 없으면 null — 앱이 name 을 쓴다")
        String shortName,
        @Schema(description = "캠퍼스 이름(원천 본분교 값 — 제2캠퍼스 등). 본교면 null")
        String campusName,
        @Schema(description = "캠퍼스 대표 좌표(교육부 주소기반 좌표)")
        BigDecimal lat,
        BigDecimal lng,
        @Schema(description = "시군구 법정동코드. 서비스 지역 밖이면 null")
        String regionCode
) {
}