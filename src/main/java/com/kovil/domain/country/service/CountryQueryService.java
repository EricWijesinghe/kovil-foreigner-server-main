package com.kovil.domain.country.service;

import com.kovil.domain.country.dto.response.CountryResponse;
import com.kovil.domain.country.entity.CountryTranslation;
import com.kovil.domain.country.repository.CountryRepository;
import com.kovil.domain.country.repository.CountryTranslationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CountryQueryService {

    private final CountryRepository countryRepository;
    private final CountryTranslationRepository countryTranslationRepository;

    public List<CountryResponse> findActiveCountries() {
        return findActiveCountries(LocaleContextHolder.getLocale().getLanguage());
    }

    // 번역을 국가별로 조회하면 N+1이 된다. 언어당 한 번만 읽어 메모리에서 합친다 —
    // 마스터 데이터라 전량이 수백 행 수준이다.
    public List<CountryResponse> findActiveCountries(String languageCode) {
        Map<Long, String> namesByCountryId = countryTranslationRepository.findByLanguageCode(languageCode)
                .stream()
                .collect(Collectors.toMap(
                        CountryTranslation::getCountryId,
                        CountryTranslation::getName,
                        (first, second) -> first));

        return countryRepository.findByIsActiveTrueOrderBySortOrderAscNameEnAsc()
                .stream()
                .map(country -> CountryResponse.of(country, namesByCountryId.get(country.getId())))
                .toList();
    }
}
