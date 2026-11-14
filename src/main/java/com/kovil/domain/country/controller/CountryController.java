package com.kovil.domain.country.controller;

import com.kovil.common.dto.response.ApiResponse;
import com.kovil.domain.country.dto.response.CountryResponse;
import com.kovil.domain.country.service.CountryQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Country", description = "국가 목록 (공개)")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/countries")
public class CountryController {

    private final CountryQueryService countryQueryService;

    @Operation(summary = "가입·내 정보의 국적 선택에 쓴다. **비로그인 공개**.", description = """
            활성 국가 목록.

            이름은 `Accept-Language`로 해석된다. 번역이 없는 언어면 영문명으로 폴백하므로\n            **빈 이름이 오는 경우는 없다**. 정렬은 주요 출신국이 앞에 오도록 서버가 큐레이션한다.
            """)
    @GetMapping
    public ApiResponse<List<CountryResponse>> getCountries() {
        return ApiResponse.success(countryQueryService.findActiveCountries());
    }
}
