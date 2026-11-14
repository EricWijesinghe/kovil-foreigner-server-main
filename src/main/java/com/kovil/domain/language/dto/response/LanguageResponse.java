package com.kovil.domain.language.dto.response;

import com.kovil.domain.language.entity.Language;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "지원 언어. 언어 선택 화면은 항상 그 언어로 표기해야 하므로 nativeName을 쓴다.")
public record LanguageResponse(

        @Schema(description = "BCP47 서브셋 코드. Accept-Language·preferredLanguage에 이 값을 쓴다.",
                example = "ko")
        String code,

        @Schema(description = "그 언어로 쓴 이름. **선택 목록에는 이 값을 보여준다.**", example = "한국어")
        String nativeName,

        @Schema(description = "영문 이름. 관리자 화면용.", example = "Korean")
        String englishName,

        @Schema(description = "번역 누락 시 폴백 언어인지", example = "false")
        boolean isDefault
) {
    public static LanguageResponse from(Language language) {
        return new LanguageResponse(
                language.getCode(),
                language.getNativeName(),
                language.getEnglishName(),
                Boolean.TRUE.equals(language.getIsDefault()));
    }
}
