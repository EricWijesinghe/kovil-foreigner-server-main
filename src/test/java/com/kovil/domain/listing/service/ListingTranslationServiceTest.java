package com.kovil.domain.listing.service;

import com.kovil.domain.language.entity.Language;
import com.kovil.domain.language.repository.LanguageRepository;
import com.kovil.domain.listing.dto.response.ListingTranslationResponse;
import com.kovil.domain.listing.entity.Listing;
import com.kovil.domain.listing.entity.ListingDescriptionTranslation;
import com.kovil.domain.listing.enums.*;
import com.kovil.domain.listing.exception.ListingException;
import com.kovil.domain.listing.repository.ListingDescriptionTranslationRepository;
import com.kovil.domain.listing.repository.ListingRepository;
import com.kovil.infra.translation.TranslationClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/*
 * 상세설명 번역. 판별 축은 셋이다 — **캐시 히트 요건**(매물·언어·원문 버전),
 * **실패했을 때 무엇이 나가는가**(원문이고 200이다), **실패를 저장하지 않는가**.
 */
class ListingTranslationServiceTest {

    private static final String KOREAN = "역세권 원룸입니다. 보증금 300만원.";

    private ListingRepository listingRepository;
    private ListingDescriptionTranslationRepository translationRepository;
    private LanguageRepository languageRepository;
    private TranslationClient translationClient;
    private ListingTranslationService service;

    @BeforeEach
    void setUp() {
        listingRepository = mock(ListingRepository.class);
        translationRepository = mock(ListingDescriptionTranslationRepository.class);
        languageRepository = mock(LanguageRepository.class);
        translationClient = mock(TranslationClient.class);
        service = new ListingTranslationService(
                listingRepository, translationRepository, languageRepository, translationClient);

        lenient().when(languageRepository.findByCodeAndActiveTrue(anyString()))
                .thenReturn(Optional.of(Language.builder().code("en").nativeName("English")
                        .englishName("English").build()));
        lenient().when(translationClient.engineName()).thenReturn("fake");
    }

    @Test
    @DisplayName("캐시에 있으면 벤더를 부르지 않는다")
    void cache_hit_does_not_call_vendor() {
        givenListing(listing(KOREAN, 3));
        givenCached(1L, "en", 3, "A studio near the station.");

        ListingTranslationResponse out = service.translate("l-1", "en");

        assertThat(out.cached()).isTrue();
        assertThat(out.translated()).isTrue();
        assertThat(out.content()).isEqualTo("A studio near the station.");
        verify(translationClient, never()).translate(any(), any());
        verify(translationRepository, never()).saveAndFlush(any());
    }

    /*
     * ★★ 버전이 캐시 키에서 빠지면 중개사가 설명을 고쳐도 옛 번역이 계속 나간다 —
     *    유학생이 **이미 사라진 조건**을 읽고 찾아온다. 조회에 버전이 실리는지 직접 본다.
     */
    @Test
    @DisplayName("원문 버전이 오르면 옛 번역은 히트하지 않고 다시 번역한다")
    void version_bump_invalidates_cache() {
        givenListing(listing(KOREAN, 4));
        given(translationRepository.findByListingIdAndLanguageCodeAndSourceVersion(1L, "en", 4))
                .willReturn(Optional.empty());
        given(translationClient.translate(KOREAN, "en")).willReturn("A studio near the station.");
        givenSaveEchoes();

        ListingTranslationResponse out = service.translate("l-1", "en");

        assertThat(out.cached()).isFalse();
        assertThat(out.translated()).isTrue();
        verify(translationRepository).findByListingIdAndLanguageCodeAndSourceVersion(1L, "en", 4);
    }

    @Test
    @DisplayName("새로 번역하면 매물·언어·원문 버전·엔진과 함께 저장한다")
    void saves_with_source_version_and_engine() {
        givenListing(listing(KOREAN, 7));
        givenCacheMiss();
        given(translationClient.translate(KOREAN, "en")).willReturn("A studio near the station.");
        givenSaveEchoes();

        service.translate("l-1", "en");

        var captor = ArgumentCaptor.forClass(ListingDescriptionTranslation.class);
        verify(translationRepository).saveAndFlush(captor.capture());
        ListingDescriptionTranslation saved = captor.getValue();
        assertThat(saved.getListingId()).isEqualTo(1L);
        assertThat(saved.getLanguageCode()).isEqualTo("en");
        assertThat(saved.getSourceVersion()).isEqualTo(7);
        assertThat(saved.getEngine()).isEqualTo("fake");
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    /*
     * ★★ 번역은 곁다리다. 벤더가 죽었다고 상세설명이 통째로 안 보이면 안 된다 —
     *    원문이 200 으로 나가고, 앱은 translated=false 로 "번역됨" 표시를 끈다.
     */
    @Test
    @DisplayName("벤더가 실패하면 한국어 원문이 그대로 나간다")
    void vendor_failure_falls_back_to_korean() {
        givenListing(listing(KOREAN, 1));
        givenCacheMiss();
        given(translationClient.translate(KOREAN, "en")).willReturn(null);

        ListingTranslationResponse out = service.translate("l-1", "en");

        assertThat(out.content()).isEqualTo(KOREAN);
        assertThat(out.translated()).isFalse();
        assertThat(out.engine()).isNull();
    }

    /*
     * ★★ 실패를 캐시에 넣으면 그 실패가 **버전이 바뀔 때까지 히트**한다 —
     *    벤더가 1분 죽은 대가로 그 매물은 영원히 한국어만 보여 준다.
     */
    @Test
    @DisplayName("실패는 저장하지 않는다 — 다음 요청이 다시 시도한다")
    void failure_is_not_cached() {
        givenListing(listing(KOREAN, 1));
        givenCacheMiss();
        given(translationClient.translate(KOREAN, "en")).willReturn("   ");

        service.translate("l-1", "en");

        verify(translationRepository, never()).saveAndFlush(any());
    }

    /*
     * ★★ 캐시 키에 **엔진이 없다.** 그래서 페이크로 돌던 동안 쌓인 `[FAKE en] …` 행이
     *    유료 키를 넣은 뒤에도 버전이 바뀔 때까지 계속 히트한다 —
     *    유학생이 접두사 붙은 한국어를 번역문으로 읽는다(적대적 리뷰 2026-10-05).
     */
    @Test
    @DisplayName("페이크가 만든 캐시 행은 실엔진으로 바꾸면 다시 번역한다")
    void fake_rows_are_not_reused_under_a_real_engine() {
        given(translationClient.engineName()).willReturn("gemini");
        givenListing(listing(KOREAN, 3));
        givenCached(1L, "en", 3, "[FAKE en] " + KOREAN);
        given(translationClient.translate(KOREAN, "en")).willReturn("A studio.");
        givenSaveEchoes();

        ListingTranslationResponse out = service.translate("l-1", "en");

        assertThat(out.cached()).isFalse();
        assertThat(out.content()).isEqualTo("A studio.");
        verify(translationClient).translate(KOREAN, "en");
        /*
         * ★★ **비켜 가는 것으로는 부족하다.** 유니크 키가 (매물·언어·버전)이라 그 자리를 비우지
         *    않으면 새 번역을 저장할 수 없고, save 의 복구 경로가 낡은 페이크 행을 다시 집어
         *    돌려준다 — 과금만 매번 일어나고 결과는 버려진다(적대적 리뷰 2차 2026-10-06).
         */
        verify(translationRepository).deleteOne(1L, "en", 3);
    }

    // 페이크 모드에서는 페이크 캐시를 그대로 쓴다 — 개발 중에 캐시 경로를 못 밟으면 그것도 결함이다.
    @Test
    @DisplayName("페이크 모드에서는 페이크 캐시 행을 그대로 쓴다")
    void fake_rows_still_hit_while_provider_is_fake() {
        givenListing(listing(KOREAN, 3));
        givenCached(1L, "en", 3, "[FAKE en] " + KOREAN);

        ListingTranslationResponse out = service.translate("l-1", "en");

        assertThat(out.cached()).isTrue();
        verify(translationClient, never()).translate(any(), any());
    }

    @Test
    @DisplayName("ko 를 요청하면 벤더를 부르지 않고 원문을 준다")
    void korean_short_circuits() {
        givenListing(listing(KOREAN, 1));
        given(languageRepository.findByCodeAndActiveTrue("ko")).willReturn(
                Optional.of(Language.builder().code("ko").nativeName("한국어")
                        .englishName("Korean").build()));

        ListingTranslationResponse out = service.translate("l-1", "ko");

        assertThat(out.content()).isEqualTo(KOREAN);
        assertThat(out.translated()).isFalse();
        verify(translationClient, never()).translate(any(), any());
    }

    @Test
    @DisplayName("설명이 비어 있으면 벤더를 부르지 않는다")
    void blank_description_short_circuits() {
        givenListing(listing("   ", 1));

        ListingTranslationResponse out = service.translate("l-1", "en");

        assertThat(out.translated()).isFalse();
        verify(translationClient, never()).translate(any(), any());
    }

    // 꺼진 언어로 부르면 그 코드의 번역이 쌓인다 — 켜 줄 생각이 없는 언어에 돈을 낸다.
    @Test
    @DisplayName("languages 에 없거나 꺼진 언어는 400")
    void unknown_language_is_rejected() {
        given(languageRepository.findByCodeAndActiveTrue("xx")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.translate("l-1", "xx"))
                .isInstanceOf(ListingException.class)
                .satisfies(e -> assertThat(((ListingException) e).getErrorCode().getCode())
                        .isEqualTo("LANGUAGE_NOT_SUPPORTED"));
        verify(translationClient, never()).translate(any(), any());
    }

    /*
     * ★ 내려간 매물까지 번역해 주면 상세를 막아 둔 의미가 없다 —
     *   URL 만 알면 비공개 설명을 읽는 경로가 된다.
     */
    @Test
    @DisplayName("공개 중이 아닌 매물은 번역하지 않는다")
    void refuses_when_listing_is_not_public() {
        given(listingRepository.findPublicByUuid(eq("l-1"), any())).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.translate("l-1", "en"))
                .isInstanceOf(ListingException.class)
                .satisfies(e -> assertThat(((ListingException) e).getErrorCode().getCode())
                        .isEqualTo("LISTING_NOT_FOUND"));
    }

    /*
     * ★★ 같은 매물을 두 사람이 같은 순간에 열면 둘 다 캐시 미스로 번역하고 둘 다 저장한다.
     *    유니크 키가 뒤를 막는데 그 예외를 흘리면 **번역을 받아 놓고 500** 이 된다.
     */
    /*
     * ★★ 유니크 충돌만 DataIntegrityViolationException 이 아니다 — FK 위반(조회 뒤 매물이 지워졌다)·
     *    데이터 잘림도 같은 예외다. 그때는 재조회가 비는데, 전에는 **원래 예외를 다시 던져 500** 이
     *    나갔다. 이 엔드포인트의 계약은 **"실패해도 200 + 원문"** 이라 500 은 계약 위반이다
     *    (적대적 리뷰 2026-10-05).
     */
    @Test
    @DisplayName("캐시 저장이 실패하고 이긴 행도 없으면 — 500 이 아니라 번역문을 그대로 준다")
    void save_failure_without_a_winner_still_returns_200() {
        givenListing(listing(KOREAN, 2));
        given(translationRepository.findByListingIdAndLanguageCodeAndSourceVersion(1L, "en", 2))
                .willReturn(Optional.empty());
        given(translationClient.translate(KOREAN, "en")).willReturn("A studio.");
        given(translationRepository.saveAndFlush(any()))
                .willThrow(new DataIntegrityViolationException("fk_ldt_listing"));

        ListingTranslationResponse out = service.translate("l-1", "en");

        assertThat(out.content()).isEqualTo("A studio.");
        assertThat(out.translated()).isTrue();
        assertThat(out.cached()).isFalse();
    }

    @Test
    @DisplayName("동시 저장에서 진 쪽은 이긴 쪽의 번역을 돌려준다")
    void concurrent_save_returns_the_winner_row() {
        givenListing(listing(KOREAN, 2));
        given(translationRepository.findByListingIdAndLanguageCodeAndSourceVersion(1L, "en", 2))
                .willReturn(Optional.empty())
                .willReturn(Optional.of(row(1L, "en", 2, "winner text")));
        given(translationClient.translate(KOREAN, "en")).willReturn("loser text");
        given(translationRepository.saveAndFlush(any()))
                .willThrow(new DataIntegrityViolationException("uq_ldt"));

        ListingTranslationResponse out = service.translate("l-1", "en");

        assertThat(out.content()).isEqualTo("winner text");
        assertThat(out.translated()).isTrue();
    }

    // ───────────────────────── 픽스처

    private void givenListing(Listing listing) {
        given(listingRepository.findPublicByUuid(eq("l-1"), any())).willReturn(Optional.of(listing));
    }

    private void givenCacheMiss() {
        given(translationRepository.findByListingIdAndLanguageCodeAndSourceVersion(
                anyLong(), anyString(), anyInt())).willReturn(Optional.empty());
    }

    private void givenCached(Long listingId, String code, int version, String content) {
        given(translationRepository.findByListingIdAndLanguageCodeAndSourceVersion(
                listingId, code, version)).willReturn(Optional.of(row(listingId, code, version, content)));
    }

    private void givenSaveEchoes() {
        given(translationRepository.saveAndFlush(any()))
                .willAnswer(i -> i.getArgument(0));
    }

    private ListingDescriptionTranslation row(Long listingId, String code, int version, String content) {
        return ListingDescriptionTranslation.builder()
                .id(100L).listingId(listingId).languageCode(code).sourceVersion(version)
                .content(content).engine("fake").createdAt(LocalDateTime.now()).build();
    }

    private Listing listing(String description, int descriptionVersion) {
        return Listing.builder()
                .id(1L).uuid("l-1").listingNo("LS-261005-ABC123")
                .officeId(1L).createdByUserId(10L)
                .category(ListingCategory.ONE_TWO_ROOM).roomType(RoomType.ONE_ROOM)
                .title("테스트").sellingType(SellingType.MONTHLY_RENT)
                .deposit(3_000_000L).monthlyRent(300_000L)
                .exclusiveArea(new BigDecimal("26.44")).floorType(FloorType.GROUND)
                .residentRegistration(AvailabilityAnswer.POSSIBLE)
                .depositInsurance(AvailabilityAnswer.ASK)
                .realLat(new BigDecimal("37.4862378")).realLng(new BigDecimal("126.9331220"))
                .displayLat(new BigDecimal("37.4862552")).displayLng(new BigDecimal("126.9332423"))
                .addressJibun("서울특별시 관악구 신림동")
                .description(description).descriptionVersion(descriptionVersion)
                .status(ListingStatus.ACTIVE).build();
    }
}
