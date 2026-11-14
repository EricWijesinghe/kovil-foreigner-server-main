package com.kovil.domain.university.service;

import com.kovil.common.exception.CustomException;
import com.kovil.domain.region.entity.Region;
import com.kovil.domain.region.repository.RegionRepository;
import com.kovil.domain.university.dto.response.UniversityResponse;
import com.kovil.domain.university.entity.University;
import com.kovil.domain.university.repository.UniversityRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/*
 * 대학교 지도 레이어의 조립부.
 * ★ 지역 코드는 **열린 지역만** 내보낸다 — 닫힌 지역 코드를 내보내면 앱이 그 코드로 목록 필터를 걸어 400 을 받는다.
 * ★ 영역 숫자는 자릿수부터 본다 — 지수 표기(1e-999999999)는 범위 비교에서 서버를 멈추게 한다.
 */
class UniversityQueryServiceTest {

    private static final BigDecimal SW_LAT = new BigDecimal("37.50");
    private static final BigDecimal SW_LNG = new BigDecimal("126.90");
    private static final BigDecimal NE_LAT = new BigDecimal("37.60");
    private static final BigDecimal NE_LNG = new BigDecimal("127.00");

    private UniversityRepository universityRepository;
    private RegionRepository regionRepository;
    private UniversityQueryService service;

    @BeforeEach
    void setUp() {
        universityRepository = mock(UniversityRepository.class);
        regionRepository = mock(RegionRepository.class);
        service = new UniversityQueryService(universityRepository, regionRepository);
    }

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    private static University university(String code, Long regionId, String campusKo, String campusEn) {
        return University.builder().id(Long.valueOf(code)).code(code)
                .nameKo("한국대학교 " + code).nameEn("Hanguk University " + code)
                .shortNameKo("한국대").shortNameEn("Hanguk U")
                .campusNameKo(campusKo).campusNameEn(campusEn)
                .addressRoad("서울특별시 서대문구 테스트로 1")
                .lat(new BigDecimal("37.5600000")).lng(new BigDecimal("126.9400000"))
                .regionId(regionId).build();
    }

    private static Region region(Long id, Long parentId, String code) {
        return Region.builder().id(id).parentId(parentId).code(code).nameKo("구").nameEn("gu").build();
    }

    @Test
    @DisplayName("한국어 요청이면 국문 이름 · 짧은 이름 · 캠퍼스, 그 밖이면 영문")
    void language_rule() {
        given(universityRepository.findActiveWithin(any(), any(), any(), any(), any(), any(), any(), any()))
                .willReturn(List.of(university("0000001", null, "제2캠퍼스", "Campus 2")));

        LocaleContextHolder.setLocale(Locale.KOREAN);
        UniversityResponse ko = service.findWithin(SW_LAT, SW_LNG, NE_LAT, NE_LNG).get(0);
        LocaleContextHolder.setLocale(Locale.forLanguageTag("vi"));
        UniversityResponse vi = service.findWithin(SW_LAT, SW_LNG, NE_LAT, NE_LNG).get(0);

        assertThat(ko.name()).isEqualTo("한국대학교 0000001");
        assertThat(ko.shortName()).isEqualTo("한국대");
        assertThat(ko.campusName()).isEqualTo("제2캠퍼스");
        assertThat(vi.name()).isEqualTo("Hanguk University 0000001");
        assertThat(vi.shortName()).isEqualTo("Hanguk U");
        assertThat(vi.campusName()).isEqualTo("Campus 2");
        assertThat(vi.code()).isEqualTo("0000001");
        assertThat(vi.lat()).isEqualByComparingTo("37.56");
        assertThat(vi.lng()).isEqualByComparingTo("126.94");

        // 브라우저 · OS 언어 그대로 보내는 클라이언트 — ko-KR 도 국문이다(언어 부분만 본다).
        LocaleContextHolder.setLocale(Locale.KOREA);
        UniversityResponse koKr = service.findWithin(SW_LAT, SW_LNG, NE_LAT, NE_LNG).get(0);
        assertThat(koKr.name()).isEqualTo("한국대학교 0000001");
        assertThat(koKr.shortName()).isEqualTo("한국대");
        assertThat(koKr.campusName()).isEqualTo("제2캠퍼스");
    }

    /*
     * ★ '열린 지역' = 구가 열려 있고 그 시도도 열려 있음(지역 해석 · 지역 트리와 같은 기준).
     *   구 38 = 열린 시도 1 아래 · 구 39 = 닫힌 구 · 구 40 = 닫힌 시도 2 아래 열린 구.
     */
    @Test
    @DisplayName("지역 코드는 열린 지역만 — 닫힌 구 · 닫힌 시도 아래 구 · 지역 없음은 null, 조회는 구 한 번 · 시도 한 번")
    void region_code_only_for_open_regions() {
        given(universityRepository.findActiveWithin(any(), any(), any(), any(), any(), any(), any(), any())).willReturn(List.of(
                university("0000001", 38L, null, null), university("0000002", 39L, null, null),
                university("0000003", null, null, null), university("0000004", 38L, null, null),
                university("0000005", 40L, null, null)));
        given(regionRepository.findByIdInAndIsActiveTrue(List.of(38L, 39L, 40L)))
                .willReturn(List.of(region(38L, 1L, "11410"), region(40L, 2L, "26110")));
        given(regionRepository.findByIdInAndIsActiveTrue(List.of(1L, 2L)))
                .willReturn(List.of(Region.builder().id(1L).code("11").nameKo("서울").nameEn("Seoul").build()));

        List<UniversityResponse> found = service.findWithin(SW_LAT, SW_LNG, NE_LAT, NE_LNG);

        assertThat(found).extracting(UniversityResponse::regionCode).containsExactly("11410", null, null, "11410", null);
        verify(regionRepository, times(2)).findByIdInAndIsActiveTrue(any());
        verify(regionRepository, never()).findByIdIn(any());
    }

    // 열린 구가 없으면 시도를 묻지 않는다. 루트(시도) 행이 구 자리에 와도 시도 조회로 넘어가지 않는다.
    @Test
    @DisplayName("열린 구가 하나도 없으면 시도를 조회하지 않는다 — 지역 코드는 null, 조회는 구 한 번")
    void no_parent_lookup_without_open_districts() {
        given(universityRepository.findActiveWithin(any(), any(), any(), any(), any(), any(), any(), any()))
                .willReturn(List.of(university("0000002", 39L, null, null), university("0000003", 1L, null, null)));
        given(regionRepository.findByIdInAndIsActiveTrue(List.of(39L, 1L)))
                .willReturn(List.of(Region.builder().id(1L).code("11").nameKo("서울").nameEn("Seoul").build()));

        List<UniversityResponse> found = service.findWithin(SW_LAT, SW_LNG, NE_LAT, NE_LNG);

        assertThat(found).extracting(UniversityResponse::regionCode).containsExactly(null, null);
        verify(regionRepository, times(1)).findByIdInAndIsActiveTrue(any());
    }

    @Test
    @DisplayName("지역이 붙은 학교가 없으면 지역을 조회하지 않는다")
    void no_region_lookup_without_regions() {
        given(universityRepository.findActiveWithin(any(), any(), any(), any(), any(), any(), any(), any()))
                .willReturn(List.of(university("0000003", null, null, null)));

        service.findWithin(SW_LAT, SW_LNG, NE_LAT, NE_LNG);

        verifyNoInteractions(regionRepository);
    }

    @Test
    @DisplayName("영역 네 값을 그대로 넘기고, 한 번에 200개까지만 읽는다")
    void passes_box_and_caps_at_200() {
        given(universityRepository.findActiveWithin(any(), any(), any(), any(), any(), any(), any(), any())).willReturn(List.of());

        service.findWithin(SW_LAT, SW_LNG, NE_LAT, NE_LNG);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(universityRepository).findActiveWithin(eq(SW_LAT), eq(SW_LNG), eq(NE_LAT), eq(NE_LNG),
                eq(new BigDecimal("37.55")), eq(new BigDecimal("126.95")), any(), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isZero();
        assertThat(pageable.getValue().getPageSize()).isEqualTo(200);
    }

    // 지도 SDK 는 JS double 을 그대로 넘긴다(위도 소수 15자리까지 나온다). 극점 · 날짜변경선도 받는다 — 막는 것은 그 바깥만.
    @Test
    @DisplayName("자릿수 15 · 위도 ±90 · 경도 ±180 은 받는다")
    void accepts_widest_sane_box() {
        given(universityRepository.findActiveWithin(any(), any(), any(), any(), any(), any(), any(), any())).willReturn(List.of());
        BigDecimal lat15 = new BigDecimal("37.566535123456789");
        BigDecimal lng15 = new BigDecimal("126.977969199999999");

        service.findWithin(lat15, lng15, new BigDecimal("37.566535123456790"), new BigDecimal("126.977969200000000"));
        service.findWithin(new BigDecimal("-90"), new BigDecimal("-180"), new BigDecimal("90"), new BigDecimal("180"));

        verify(universityRepository).findActiveWithin(eq(lat15), eq(lng15), any(), any(), any(), any(), any(), any());
        verify(universityRepository, times(2)).findActiveWithin(any(), any(), any(), any(), any(), any(), any(), any());
    }

    // 네 칸마다 두 규칙(자릿수 · 범위)을 따로 찌른다 — 한 칸의 검사를 빼먹거나 위도 칸에 경도 한도를 쓰면 그 줄이 통과한다.
    @Test
    @DisplayName("지수 표기 · 자릿수 16 · 범위 밖 영역은 400 INVALID_REQUEST — 조회하지 않는다")
    void rejects_insane_box() {
        BigDecimal[][] bad = {
                {new BigDecimal("1e-999999999"), SW_LNG, NE_LAT, NE_LNG},
                {new BigDecimal("37.5000000000000001"), SW_LNG, NE_LAT, NE_LNG},
                {new BigDecimal("-90.5"), SW_LNG, NE_LAT, NE_LNG},
                {SW_LAT, SW_LNG, new BigDecimal("37.6000000000000001"), NE_LNG},
                {SW_LAT, SW_LNG, new BigDecimal("90.1"), NE_LNG},
                {SW_LAT, SW_LNG, new BigDecimal("-90.0000001"), NE_LNG},
                {SW_LAT, new BigDecimal("126.9000000000000001"), NE_LAT, NE_LNG},
                {SW_LAT, new BigDecimal("-180.5"), NE_LAT, NE_LNG},
                {SW_LAT, new BigDecimal("180.0000001"), NE_LAT, NE_LNG},
                {SW_LAT, SW_LNG, NE_LAT, new BigDecimal("127.0000000000000001")},
                {SW_LAT, SW_LNG, NE_LAT, new BigDecimal("1E+3")}};
        for (BigDecimal[] box : bad) {
            assertThatThrownBy(() -> service.findWithin(box[0], box[1], box[2], box[3]))
                    .as("%s", (Object) box)
                    .isInstanceOf(CustomException.class)
                    .satisfies(e -> assertThat(((CustomException) e).getErrorCode().getCode()).isEqualTo("INVALID_REQUEST"));
        }
        verifyNoInteractions(universityRepository);
    }
}
