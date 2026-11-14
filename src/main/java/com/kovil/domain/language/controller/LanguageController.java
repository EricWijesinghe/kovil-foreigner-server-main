package com.kovil.domain.language.controller;

import com.kovil.common.dto.response.ApiResponse;
import com.kovil.domain.language.dto.response.LanguageResponse;
import com.kovil.domain.language.service.LanguageQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Language", description = "지원 언어 목록 (공개)")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/languages")
public class LanguageController {

    private final LanguageQueryService languageQueryService;

    @Operation(summary = "앱 최초 진입의 언어 선택 화면에 쓴다. **비로그인 공개**.", description = """
            활성 언어 목록.

            선택 목록에는 `nativeName`을 보여준다 — 언어 선택 화면은 항상 그 언어로 표기해야 한다.\n            정렬은 서버가 정한 노출 순서이며 FE는 그대로 그리면 된다.
            """)
    @GetMapping
    public ApiResponse<List<LanguageResponse>> getLanguages() {
        return ApiResponse.success(languageQueryService.findActiveLanguages());
    }
}
