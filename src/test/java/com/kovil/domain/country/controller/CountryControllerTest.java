package com.kovil.domain.country.controller;

import com.kovil.common.security.filter.JwtAuthenticationFilter;
import com.kovil.domain.country.entity.Country;
import com.kovil.domain.country.entity.CountryTranslation;
import com.kovil.domain.country.repository.CountryRepository;
import com.kovil.domain.country.repository.CountryTranslationRepository;
import com.kovil.domain.country.service.CountryQueryService;
import com.kovil.common.exception.ErrorCodeResolver;
import com.kovil.common.exception.ErrorMessageResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 서비스를 목킹하지 않고 실제로 태운다 — 확인하려는 것이 "Accept-Language가 LocaleContextHolder까지
// 실제로 도달하는가"이기 때문이다. 서비스를 목으로 바꾸면 그 고리가 검증에서 빠진다.
@WebMvcTest(
        controllers = CountryController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE, classes = JwtAuthenticationFilter.class)
)
@AutoConfigureMockMvc(addFilters = false)
@Import({CountryController.class, CountryQueryService.class,
        ErrorCodeResolver.class,
        ErrorMessageResolver.class})
class CountryControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    CountryRepository countryRepository;

    @MockitoBean
    CountryTranslationRepository countryTranslationRepository;

    private static Country country(long id, String code, String nameEn) {
        Country c = Country.builder().code(code).nameEn(nameEn).build();
        try {
            var field = Country.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(c, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return c;
    }

    private void givenVietnamWithKoreanName() {
        given(countryRepository.findByIsActiveTrueOrderBySortOrderAscNameEnAsc())
                .willReturn(List.of(country(1L, "VN", "Vietnam")));
        given(countryTranslationRepository.findByLanguageCode("ko"))
                .willReturn(List.of(CountryTranslation.builder()
                        .countryId(1L).languageCode("ko").name("베트남").build()));
        given(countryTranslationRepository.findByLanguageCode("en")).willReturn(List.of());
        given(countryTranslationRepository.findByLanguageCode("vi")).willReturn(List.of());
    }

    @Test
    @DisplayName("Accept-Language가 응답 국가명을 결정한다")
    void locale_drives_country_name() throws Exception {
        givenVietnamWithKoreanName();

        mockMvc.perform(get("/api/v1/countries").header("Accept-Language", "ko"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].code").value("VN"))
                .andExpect(jsonPath("$.data[0].name").value("베트남"));

        mockMvc.perform(get("/api/v1/countries").header("Accept-Language", "en"))
                .andExpect(jsonPath("$.data[0].name").value("Vietnam"));
    }

    @Test
    @DisplayName("번역이 없는 언어로 요청해도 영문명으로 응답한다")
    void untranslated_language_falls_back() throws Exception {
        givenVietnamWithKoreanName();

        mockMvc.perform(get("/api/v1/countries").header("Accept-Language", "vi"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("Vietnam"));
    }

    @Test
    @DisplayName("활성 국가가 없으면 빈 배열이다 — null이 아니다")
    void empty_list_is_array() throws Exception {
        given(countryRepository.findByIsActiveTrueOrderBySortOrderAscNameEnAsc()).willReturn(List.of());
        given(countryTranslationRepository.findByLanguageCode("en")).willReturn(List.of());

        mockMvc.perform(get("/api/v1/countries").header("Accept-Language", "en"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }
}
