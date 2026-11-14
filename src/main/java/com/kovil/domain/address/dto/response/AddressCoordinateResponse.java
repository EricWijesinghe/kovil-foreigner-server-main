package com.kovil.domain.address.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = """
        고른 주소 후보의 좌표. **못 찾으면 둘 다 null** — 오류가 아니다(앱은 그 후보를 고르지 않고 안내한다).
        """)
public record AddressCoordinateResponse(

        @Schema(description = "경도(벤더 원문 문자열 — 검색 결과의 longitude 와 같은 모양)", example = "127.1054328",
                nullable = true) String longitude,

        @Schema(description = "위도(벤더 원문 문자열)", example = "37.3595963", nullable = true) String latitude
) {

    public static AddressCoordinateResponse found(String longitude, String latitude) {
        return new AddressCoordinateResponse(longitude, latitude);
    }

    public static AddressCoordinateResponse notFound() {
        return new AddressCoordinateResponse(null, null);
    }
}
