package com.kovil.domain.master;

import com.kovil.common.config.JpaAuditingConfig;
import com.kovil.domain.country.entity.Country;
import com.kovil.domain.country.entity.CountryTranslation;
import com.kovil.domain.country.repository.CountryRepository;
import com.kovil.domain.country.repository.CountryTranslationRepository;
import com.kovil.domain.language.entity.Language;
import com.kovil.domain.language.repository.LanguageRepository;
import com.kovil.domain.region.entity.Region;
import com.kovil.domain.region.repository.RegionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

// 서비스 목킹으로는 active 필터·정렬이 검증되지 않는다. 파생 쿼리 이름이 한 글자 틀리면
// 비활성 행이 목록에 섞여 나가는데, 그건 실제 DB에 붙여야만 드러난다.
@DataJpaTest
@Import(JpaAuditingConfig.class)
class MasterDataRepositoryTest {

    @Autowired
    LanguageRepository languageRepository;

    @Autowired
    CountryRepository countryRepository;

    @Autowired
    CountryTranslationRepository countryTranslationRepository;

    @Autowired
    RegionRepository regionRepository;

    @Nested
    @DisplayName("languages")
    class Languages {

        @Test
        @DisplayName("활성 언어만 sortOrder 순으로 나온다")
        void active_only_ordered() {
            languageRepository.save(Language.builder()
                    .code("en").nativeName("English").englishName("English")
                    .isDefault(true).active(true).sortOrder(1).build());
            languageRepository.save(Language.builder()
                    .code("ko").nativeName("한국어").englishName("Korean")
                    .active(true).sortOrder(2).build());
            languageRepository.save(Language.builder()
                    .code("vi").nativeName("Tiếng Việt").englishName("Vietnamese")
                    .active(false).sortOrder(3).build());

            assertThat(languageRepository.findByActiveTrueOrderBySortOrderAscCodeAsc())
                    .extracting(Language::getCode).containsExactly("en", "ko");
        }

        @Test
        @DisplayName("비활성 언어는 코드로 찾아도 나오지 않는다")
        void inactive_not_found_by_code() {
            languageRepository.save(Language.builder()
                    .code("vi").nativeName("Tiếng Việt").englishName("Vietnamese")
                    .active(false).sortOrder(1).build());

            assertThat(languageRepository.findByCodeAndActiveTrue("vi")).isEmpty();
        }
    }

    @Nested
    @DisplayName("countries")
    class Countries {

        @Test
        @DisplayName("활성 국가만 sortOrder → 영문명 순으로 나온다")
        void active_only_ordered() {
            countryRepository.save(Country.builder().code("MN").nameEn("Mongolia").sortOrder(9).build());
            countryRepository.save(Country.builder().code("UZ").nameEn("Uzbekistan").sortOrder(9).build());
            countryRepository.save(Country.builder().code("VN").nameEn("Vietnam").sortOrder(1).build());
            countryRepository.save(Country.builder().code("XX").nameEn("Hidden").isActive(false).sortOrder(0).build());

            assertThat(countryRepository.findByIsActiveTrueOrderBySortOrderAscNameEnAsc())
                    .extracting(Country::getCode).containsExactly("VN", "MN", "UZ");
        }

        @Test
        @DisplayName("번역은 언어별로만 걸러 온다")
        void translations_by_language() {
            Country vn = countryRepository.save(Country.builder().code("VN").nameEn("Vietnam").build());
            countryTranslationRepository.save(CountryTranslation.builder()
                    .countryId(vn.getId()).languageCode("ko").name("베트남").build());
            countryTranslationRepository.save(CountryTranslation.builder()
                    .countryId(vn.getId()).languageCode("ja").name("ベトナム").build());

            assertThat(countryTranslationRepository.findByLanguageCode("ko"))
                    .extracting(CountryTranslation::getName).containsExactly("베트남");
            assertThat(countryTranslationRepository.findByLanguageCode("mn")).isEmpty();
        }
    }

    @Nested
    @DisplayName("regions")
    class Regions {

        @Test
        @DisplayName("활성 지역만 나오고 시도·시군구가 한 번에 온다")
        void active_only_flat() {
            Region seoul = regionRepository.save(Region.builder()
                    .code("11").nameKo("서울특별시").nameEn("Seoul").sortOrder(1).build());
            regionRepository.save(Region.builder().parentId(seoul.getId())
                    .code("11110").nameKo("종로구").nameEn("Jongno-gu").sortOrder(1).build());
            regionRepository.save(Region.builder()
                    .code("26").nameKo("부산광역시").nameEn("Busan").isActive(false).sortOrder(2).build());

            assertThat(regionRepository.findByIsActiveTrueOrderBySortOrderAscCodeAsc())
                    .extracting(Region::getCode).containsExactly("11", "11110");
        }
    }
}
