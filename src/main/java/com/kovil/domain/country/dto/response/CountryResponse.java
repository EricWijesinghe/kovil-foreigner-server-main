package com.kovil.domain.country.dto.response;

import com.kovil.domain.country.entity.Country;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "국가. 이름은 Accept-Language로 해석되며, 번역이 없으면 영문명으로 폴백한다.")
public record CountryResponse(

        @Schema(description = "ISO 3166-1 alpha-2", example = "VN")
        String code,

        @Schema(description = "요청 언어로 해석된 국가명", example = "베트남")
        String name,

        // 200여 개 국기 이미지를 호스팅하지 않기 위한 선택이다.
        @Schema(description = "국기 이모지. 없을 수 있다.", example = "\uD83C\uDDFB\uD83C\uDDF3")
        String flagEmoji
) {
    public static CountryResponse of(Country country, String translatedName) {
        return new CountryResponse(
                country.getCode(),
                translatedName != null ? translatedName : country.getNameEn(),
                country.getFlagEmoji());
    }
}
