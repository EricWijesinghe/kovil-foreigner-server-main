package com.kovil.domain.country.service;

import com.kovil.domain.country.dto.response.CountryResponse;
import com.kovil.domain.country.entity.Country;
import com.kovil.domain.country.entity.CountryTranslation;
import com.kovil.domain.country.repository.CountryRepository;
import com.kovil.domain.country.repository.CountryTranslationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.assertj.core.groups.Tuple;
import org.mockito.BDDMockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class CountryQueryServiceTest {

    @Mock
    private CountryRepository countryRepository;

    @Mock
    private CountryTranslationRepository countryTranslationRepository;

    @InjectMocks
    private CountryQueryService countryQueryService;

    private static Country country(long id, String code, String nameEn) {
        Country c = Country.builder().code(code).nameEn(nameEn).flagEmoji("\uD83C\uDDFB\uD83C\uDDF3").build();
        try {
            var field = Country.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(c, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return c;
    }

    private static CountryTranslation translation(long countryId, String lang, String name) {
        return CountryTranslation.builder().countryId(countryId).languageCode(lang).name(name).build();
    }

    // FR-807 — 번역 행이 없으면 countries.name_en으로 폴백한다. 전량 번역 의무가 없는 설계라
    // 이 폴백이 깨지면 국가 목록에 빈 이름이 섞인다.
    @Test
    @DisplayName("번역이 있으면 번역명을, 없으면 영문명을 준다")
    void falls_back_to_english_name() {
        given(countryRepository.findByIsActiveTrueOrderBySortOrderAscNameEnAsc())
                .willReturn(List.of(country(1L, "VN", "Vietnam"), country(2L, "MN", "Mongolia")));
        given(countryTranslationRepository.findByLanguageCode("ko"))
                .willReturn(List.of(translation(1L, "ko", "베트남")));

        List<CountryResponse> result = countryQueryService.findActiveCountries("ko");

        assertThat(result).extracting(CountryResponse::code, CountryResponse::name)
                .containsExactly(
                        Tuple.tuple("VN", "베트남"),
                        Tuple.tuple("MN", "Mongolia"));
    }

    @Test
    @DisplayName("해당 언어의 번역이 하나도 없으면 전부 영문명으로 나간다")
    void no_translation_for_language() {
        given(countryRepository.findByIsActiveTrueOrderBySortOrderAscNameEnAsc())
                .willReturn(List.of(country(1L, "VN", "Vietnam")));
        given(countryTranslationRepository.findByLanguageCode("mn")).willReturn(List.of());

        assertThat(countryQueryService.findActiveCountries("mn"))
                .extracting(CountryResponse::name).containsExactly("Vietnam");
    }

    // 번역을 국가별로 읽으면 200여 번의 추가 조회가 된다. 언어당 한 번만 읽는 것이 계약이다.
    @Test
    @DisplayName("번역 조회는 국가 수와 무관하게 한 번만 한다")
    void translations_are_read_once() {
        given(countryRepository.findByIsActiveTrueOrderBySortOrderAscNameEnAsc())
                .willReturn(List.of(country(1L, "VN", "Vietnam"), country(2L, "MN", "Mongolia"),
                        country(3L, "UZ", "Uzbekistan")));
        given(countryTranslationRepository.findByLanguageCode("ko")).willReturn(List.of());

        countryQueryService.findActiveCountries("ko");

        BDDMockito.then(countryTranslationRepository).should().findByLanguageCode("ko");
        BDDMockito.then(countryTranslationRepository).shouldHaveNoMoreInteractions();
    }

    @Test
    @DisplayName("국기 이모지는 그대로 실린다 — 없으면 null")
    void flag_emoji_passthrough() {
        given(countryRepository.findByIsActiveTrueOrderBySortOrderAscNameEnAsc())
                .willReturn(List.of(country(1L, "VN", "Vietnam")));
        given(countryTranslationRepository.findByLanguageCode("en")).willReturn(List.of());

        assertThat(countryQueryService.findActiveCountries("en").get(0).flagEmoji()).isNotBlank();
    }
}
