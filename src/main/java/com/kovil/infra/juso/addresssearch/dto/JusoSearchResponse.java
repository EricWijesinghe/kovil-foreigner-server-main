package com.kovil.infra.juso.addresssearch.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

// 오류도 HTTP 200 으로 온다 — 성패는 common.errorCode 로만 갈린다("0" 이 정상). 숫자도 문자열로 온다.
@JsonIgnoreProperties(ignoreUnknown = true)
public record JusoSearchResponse(Results results) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Results(Common common, List<Juso> juso) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Common(String totalCount, String errorCode, String errorMessage) {
    }

    // admCd = 법정동 코드 10자리(앞 5자리가 시군구) · emdNm = 법정 읍면동 이름 — 지역 판정의 첫 축과 dongName 이 된다.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Juso(String roadAddr, String jibunAddr, String engAddr, String zipNo,
                       String admCd, String siNm, String sggNm, String emdNm) {
    }
}
