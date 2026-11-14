package com.kovil.domain.address.dto.response;

import com.kovil.domain.region.service.AddressRegionResolver;
import com.kovil.infra.geocoding.GeocodedAddress;
import com.kovil.infra.geocoding.GeocodedPage;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = """
        주소 검색 결과 한 페이지. 후보 목록 UI에 그대로 대응된다.

        검색은 도로명 · 지번 일부가 기본이다. 행정안전부 검색(juso)이면 건물명 · 동 이름으로도 찾고, 도로명 끝에
        참고항목 `(법정동, 단지명)` 이 붙으며 좌표는 비어 온다. 상호명 검색은 아니다.
        page는 0부터(우리 관례). 벤더 1기반 페이지는 서버가 변환한다.
        """)
public record AddressSearchResponse(

        @Schema(description = "전체 후보 수", example = "3") long totalCount,

        @Schema(description = "현재 페이지(0부터)", example = "0") int page,

        @Schema(description = "후보 목록") List<Item> items
) {

    // springdoc은 중첩 record의 홑이름으로 스키마를 만든다 — 도메인 접두로 못 박는다.
    @Schema(name = "AddressSearchItem", description = "주소 후보 1건")
    public record Item(

            @Schema(description = "도로명 주소 — 계약의 road_address에 들어갈 값",
                    example = "전라남도 나주시 빛가람로 685") String roadAddress,

            @Schema(description = "지번 주소", example = "전라남도 나주시 빛가람동 235-1") String jibunAddress,

            @Schema(description = "영문 주소 — 외국인 안내에 쓸 수 있다",
                    example = "685, Bitgaram-ro, Naju-si, Jeollanam-do, Republic of Korea")
            String englishAddress,

            @Schema(description = "우편번호. 벤더가 못 캐면 null", example = "58217") String postalCode,

            @Schema(description = "시도 명칭(벤더 표기 — 카카오는 \"전남\"처럼 줄인다). 지역 판정은 `regionCode` 를 쓴다",
                    example = "전라남도") String sido,

            @Schema(description = "시군구 명칭(벤더 표기). 닫힌 지역 안내 문구에 쓴다", example = "나주시") String sigugun,

            @Schema(description = """
                    경도(벤더 원문 문자열). **행정안전부 검색(juso)이면 null** — 고른 뒤 `GET /api/v1/partner/address/coordinate` 로 받는다
                    """, example = "126.7891", nullable = true) String longitude,

            @Schema(description = "위도(벤더 원문 문자열). 행정안전부 검색이면 null — 경도와 같다", example = "35.0210",
                    nullable = true) String latitude,

            @Schema(description = """
                    그 주소의 **시군구** 코드(`GET /api/v1/regions` 의 시군구 `code`) — 매물 저장 본문 `regionCode` 에 그대로 쓴다.
                    **닫힌 시군구여도 준다**(`regionActive` 로 가른다). 못 가르면 null — 그때는 사용자가 직접 고른다.
                    """, example = "12170", nullable = true) String regionCode,

            @Schema(description = """
                    그 `regionCode` 로 지금 매물을 게시할 수 있는가 — 게시 관문과 같은 판정(시군구 열림 · 부모 시도 열림).
                    `regionCode` 가 null 이면 null.
                    """, example = "true", nullable = true) Boolean regionActive,

            @Schema(description = "법정동(동 · 읍 · 면) 이름 — 매물 저장 본문 `dongName` 에 그대로 쓴다. 벤더가 안 주면 null",
                    example = "빛가람동", nullable = true) String dongName
    ) {

        static Item from(GeocodedAddress address, AddressRegionResolver.Snapshot regions) {
            AddressRegionResolver.Resolved region = regions.resolve(address.legalDongCode(), address.sido(),
                    address.sigugun(), address.dongName(), address.jibunAddress(), address.roadAddress());
            return new Item(address.roadAddress(), address.jibunAddress(), address.englishAddress(),
                    address.postalCode(), address.sido(), address.sigugun(),
                    address.longitude(), address.latitude(),
                    region.regionCode(), region.regionActive(), address.dongName());
        }
    }

    public static AddressSearchResponse of(GeocodedPage page, int zeroBasedPage, AddressRegionResolver.Snapshot regions) {
        return new AddressSearchResponse(page.totalCount(), zeroBasedPage,
                page.addresses().stream().map(address -> Item.from(address, regions)).toList());
    }
}
