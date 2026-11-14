package com.kovil.infra.geocoding;

// 벤더 응답의 addressElements를 여기서 미리 캐낸다 — 시도·시군구·우편번호를
// 클라이언트마다 다시 파싱하게 두면 파싱 규칙이 화면 수만큼 복제된다.
public record GeocodedAddress(
        String roadAddress,
        String jibunAddress,
        String englishAddress,
        String postalCode,
        String sido,
        String sigugun,
        String longitude,
        String latitude,
        // 법정동 코드(카카오 b_code 10자리). 주지 않는 벤더는 null — 시군구 판정의 첫 축.
        String legalDongCode,
        // 법정동(동 · 읍 · 면) 이름. 못 받으면 null — 지번에서 떼어 지어내지 않음.
        String dongName
) {
}
