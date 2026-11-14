package com.kovil.domain.listing.service;

import com.kovil.domain.listing.exception.ListingException;
import com.kovil.domain.region.entity.Region;
import com.kovil.domain.region.repository.RegionRepository;
import com.kovil.domain.region.service.AddressRegionResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;

/*
 * 매물 지역 — **밖으로는 법정동코드, 안에서는 id.**
 * 숫자 id 로 받던 시절에는 클라이언트가 넣을 값을 얻을 수 없었고(공개 지역 API 는 code 만 준다),
 * 검증이 없어 아무 숫자나 넣으면 FK 위반 500, 시도 id 를 넣으면 '시군구 묶음' 지도에
 * '서울특별시'가 한 점으로 찍혔다(적대적 리뷰 2차 2026-10-06).
 * 규칙은 선례 RentalRequestSelectionResolver 와 같다.
 */
class ListingRegionResolverTest {

    private static final Region SEOUL = Region.builder().id(1L).code("11").nameKo("서울특별시")
            .nameEn("Seoul").isActive(true).build();
    private static final Region GWANAK = Region.builder().id(20L).parentId(1L).code("11620")
            .nameKo("관악구").nameEn("Gwanak-gu").isActive(true).build();

    private static final String JIBUN = "서울특별시 관악구 봉천동 1";
    private static final String ROAD = "서울특별시 관악구 관악로 1";

    private RegionRepository regionRepository;
    private AddressRegionResolver addressRegionResolver;
    private ListingRegionResolver resolver;

    @BeforeEach
    void setUp() {
        regionRepository = mock(RegionRepository.class);
        addressRegionResolver = mock(AddressRegionResolver.class);
        resolver = new ListingRegionResolver(regionRepository, addressRegionResolver);
    }

    /*
     * ★ 매물의 지역 = 주소의 시군구(PO 확정 2026-10-23). 앱이 보낸 코드가 이기면 [바꾸기] 하나로 매물이 주소와 다른 지역 목록 ·
     *   관심 지역 알림에 뜬다. 주소로 정해지면 보낸 코드는 검사도 하지 않는다.
     */
    @Test
    @DisplayName("저장 — 주소로 정해지면 보낸 코드는 보지 않고 주소의 시군구다")
    void save_takes_the_address_district_over_the_sent_code() {
        given(addressRegionResolver.regionOfAddress("봉천동", JIBUN, ROAD)).willReturn(GWANAK);

        assertThat(resolver.resolveIdForSave("26110", "봉천동", JIBUN, ROAD, null)).isEqualTo(20L);
        assertThat(resolver.resolveIdForSave(null, "봉천동", JIBUN, ROAD, 99L)).as("수정 · 코드 없음").isEqualTo(20L);
        verify(regionRepository, never()).findByCodeInAndIsActiveTrue(any());
    }

    // 닫힌 시군구 주소는 그 지역으로 남기고 게시만 막는다 — 비워 두면 지역이 다시 열려도 다시 저장해야 게시된다.
    @Test
    @DisplayName("저장 — 주소의 시군구가 닫혀 있어도 그 지역으로 저장한다")
    void save_keeps_a_closed_address_district() {
        Region closed = Region.builder().id(30L).parentId(1L).code("11650")
                .nameKo("서초구").nameEn("Seocho-gu").isActive(false).build();
        given(addressRegionResolver.regionOfAddress(null, JIBUN, null)).willReturn(closed);

        assertThat(resolver.resolveIdForSave(null, null, JIBUN, null, null)).isEqualTo(30L);
    }

    @Test
    @DisplayName("저장 — 주소로 못 가르면 보낸 코드를 쓰고, 열린 시군구만 받는다")
    void save_falls_back_to_the_sent_code_only_when_the_address_is_undecided() {
        given(regionRepository.findByCodeInAndIsActiveTrue(List.of("11620"))).willReturn(List.of(GWANAK));
        given(regionRepository.findByIdInAndIsActiveTrue(List.of(1L))).willReturn(List.of(SEOUL));
        given(regionRepository.findByCodeInAndIsActiveTrue(List.of("99999"))).willReturn(List.of());

        assertThat(resolver.resolveIdForSave("11620", null, JIBUN, ROAD, null)).isEqualTo(20L);
        assertThat(resolver.resolveIdForSave(null, null, JIBUN, ROAD, null)).as("코드도 없으면 지역 없음").isNull();
        assertThatThrownBy(() -> resolver.resolveIdForSave("99999", null, JIBUN, ROAD, null))
                .isInstanceOf(ListingException.class)
                .satisfies(e -> assertThat(((ListingException) e).getErrorCode().getCode())
                        .isEqualTo("LISTING_REGION_INVALID"));
    }

    // 주소로 못 가르는 매물의 수정은 지금 규칙 그대로 — 같은 코드를 되보내면 그 지역이 닫혔어도 둔다.
    @Test
    @DisplayName("저장 — 주소로 못 가르는 매물의 수정에서 지금 지역을 되보내면 닫혔어도 그대로 둔다")
    void save_keeps_the_current_region_when_undecided_and_unchanged() {
        given(regionRepository.findByIdIn(List.of(20L))).willReturn(List.of(GWANAK));

        assertThat(resolver.resolveIdForSave("11620", null, JIBUN, null, 20L)).isEqualTo(20L);
        verify(regionRepository, never()).findByCodeInAndIsActiveTrue(any());
    }

    @Test
    @DisplayName("주소로 정해지는가 — 앱이 지역 칸을 잠그는 기준")
    void decided_by_address() {
        given(addressRegionResolver.regionOfAddress("봉천동", JIBUN, ROAD)).willReturn(GWANAK);

        assertThat(resolver.decidedByAddress("봉천동", JIBUN, ROAD)).isTrue();
        assertThat(resolver.decidedByAddress(null, "어딘가 1", null)).isFalse();
    }

    @Test
    @DisplayName("활성 시군구 코드는 id 로 풀린다")
    void resolves_an_active_district() {
        given(regionRepository.findByCodeInAndIsActiveTrue(List.of("11620"))).willReturn(List.of(GWANAK));
        given(regionRepository.findByIdInAndIsActiveTrue(List.of(1L))).willReturn(List.of(SEOUL));

        assertThat(resolver.resolveIdOrNull("11620")).isEqualTo(20L);
    }

    // 초안은 지역 없이 저장할 수 있다 — 게시 관문이 따로 요구한다.
    @Test
    @DisplayName("코드가 비면 null — 초안은 지역 없이 저장된다")
    void blank_code_is_null() {
        assertThat(resolver.resolveIdOrNull(null)).isNull();
        assertThat(resolver.resolveIdOrNull("  ")).isNull();
    }

    /*
     * ★ 시도를 받으면 '시군구 묶음' 지도에 시도가 한 점으로 찍힌다 — 묶음 단위가 깨진다.
     */
    @Test
    @DisplayName("시도 코드는 거절한다 — 시군구여야 한다")
    void rejects_a_province() {
        given(regionRepository.findByCodeInAndIsActiveTrue(List.of("11"))).willReturn(List.of(SEOUL));

        assertThatThrownBy(() -> resolver.resolveIdOrNull("11"))
                .isInstanceOf(ListingException.class)
                .satisfies(e -> assertThat(((ListingException) e).getErrorCode().getCode())
                        .isEqualTo("LISTING_REGION_INVALID"));
    }

    @Test
    @DisplayName("없거나 닫힌 코드는 거절한다 — FK 위반 500 이 아니라 400 이다")
    void rejects_an_unknown_or_closed_code() {
        given(regionRepository.findByCodeInAndIsActiveTrue(List.of("99999"))).willReturn(List.of());

        assertThatThrownBy(() -> resolver.resolveIdOrNull("99999"))
                .isInstanceOf(ListingException.class)
                .satisfies(e -> assertThat(((ListingException) e).getErrorCode().getCode())
                        .isEqualTo("LISTING_REGION_INVALID"));
    }

    /*
     * ★ 시도만 닫으면(자식 연쇄 비활성화 없음) 공개 트리에서는 통째로 사라지는데,
     *   코드를 직접 보내면 행 단위 검사만 통과해 닫힌 지역에 매물이 쌓인다 — 선례가 같은 이유로 막는다.
     */
    @Test
    @DisplayName("부모 시도가 닫혔으면 그 시군구도 거절한다")
    void rejects_a_district_under_a_closed_province() {
        given(regionRepository.findByCodeInAndIsActiveTrue(List.of("11620"))).willReturn(List.of(GWANAK));
        given(regionRepository.findByIdInAndIsActiveTrue(List.of(1L))).willReturn(List.of());

        assertThatThrownBy(() -> resolver.resolveIdOrNull("11620"))
                .isInstanceOf(ListingException.class);
    }

    @Test
    @DisplayName("게시 가능 판정 — 활성 시군구 + 활성 부모일 때만 true")
    void publishable_only_for_an_active_district() {
        given(regionRepository.findByIdInAndIsActiveTrue(List.of(20L))).willReturn(List.of(GWANAK));
        given(regionRepository.findByIdInAndIsActiveTrue(List.of(1L))).willReturn(List.of(SEOUL));
        assertThat(resolver.isPublishable(20L)).isTrue();

        assertThat(resolver.isPublishable(null)).as("지역 없음").isFalse();

        given(regionRepository.findByIdInAndIsActiveTrue(List.of(1L))).willReturn(List.of(SEOUL));
        assertThat(resolver.isPublishable(1L)).as("시도").isFalse();
    }

    // ★ 저장 뒤 그 지역이 닫혔으면 게시도 닫혀야 한다 — "저장할 때 활성이었다"로는 부족하다.
    @Test
    @DisplayName("저장 뒤 닫힌 지역은 게시할 수 없다")
    void a_district_closed_after_saving_is_not_publishable() {
        given(regionRepository.findByIdInAndIsActiveTrue(List.of(20L))).willReturn(List.of());

        assertThat(resolver.isPublishable(20L)).isFalse();
    }

    /*
     * ★ 시도만 닫으면 그 아래 시군구 행은 활성으로 남는다. 부모까지 보지 않으면 지역 닫힘으로 내려간 매물을
     *   중개사가 곧바로 다시 게시해 **닫힌 시도 아래 광고**가 생긴다.
     */
    @Test
    @DisplayName("부모 시도가 닫혔으면 그 아래 시군구는 게시할 수 없다")
    void a_district_under_a_closed_province_is_not_publishable() {
        given(regionRepository.findByIdInAndIsActiveTrue(List.of(20L))).willReturn(List.of(GWANAK));
        given(regionRepository.findByIdInAndIsActiveTrue(List.of(1L))).willReturn(List.of());

        assertThat(resolver.isPublishable(20L)).isFalse();
    }

    // 공백이 섞여 와도 같은 코드다 — 다듬지 않으면 등록·수정·검색이 400 이 된다.
    @Test
    @DisplayName("앞뒤 공백은 다듬어서 찾는다")
    void trims_the_code() {
        given(regionRepository.findByCodeInAndIsActiveTrue(List.of("11620"))).willReturn(List.of(GWANAK));
        given(regionRepository.findByIdInAndIsActiveTrue(List.of(1L))).willReturn(List.of(SEOUL));

        assertThat(resolver.resolveIdOrNull(" 11620 ")).isEqualTo(20L);
    }

    /*
     * ★★ 편집 화면은 받은 regionCode 를 그대로 되보낸다. 그 지역이 닫혔다고 저장까지 막으면 가격 하나 고치려 해도
     *    지역을 지워야 하고, 지역이 다시 열리면 새로 골라야 한다. 지역을 **바꾸지 않았으면** 그대로 둔다 —
     *    닫힌 지역으로 게시하는 것은 게시 관문이 따로 막는다.
     */
    @Test
    @DisplayName("수정에서 지금 지역을 그대로 되보내면 그 지역이 닫혔어도 그대로 둔다")
    void unchanged_region_survives_closure_on_update() {
        given(regionRepository.findByIdIn(List.of(20L))).willReturn(List.of(GWANAK));

        assertThat(resolver.resolveIdForUpdate("11620", 20L)).isEqualTo(20L);
        assertThat(resolver.resolveIdForUpdate(" 11620 ", 20L)).isEqualTo(20L);
        verify(regionRepository, never()).findByCodeInAndIsActiveTrue(any());
    }

    // 다른 지역으로 바꿀 때는 새로 고르는 것이라 활성 검사를 그대로 받는다.
    @Test
    @DisplayName("수정에서 지역을 바꾸면 새 지역은 검사를 받는다")
    void changed_region_is_validated_on_update() {
        given(regionRepository.findByIdIn(List.of(20L))).willReturn(List.of(GWANAK));
        given(regionRepository.findByCodeInAndIsActiveTrue(List.of("26110"))).willReturn(List.of());

        assertThatThrownBy(() -> resolver.resolveIdForUpdate("26110", 20L))
                .isInstanceOf(ListingException.class);
        assertThat(resolver.resolveIdForUpdate(null, 20L)).as("지역을 지우면 지역 없음").isNull();
    }

    // 편집 화면이 받은 값을 그대로 되보낼 수 있어야 한다.
    @Test
    @DisplayName("응답용으로 id 를 code 로 되돌린다")
    void code_of_returns_the_code() {
        given(regionRepository.findByIdIn(List.of(20L))).willReturn(List.of(GWANAK));

        assertThat(resolver.codeOf(20L)).isEqualTo("11620");
        assertThat(resolver.codeOf(null)).isNull();
    }

    // 목록 한 페이지의 지역을 한 번에 — 같은 지역 매물이 여러 건이어도 한 번만 묻고, 지역 없는 초안(null)은 빼고 묻는다.
    // 닫힌 지역도 코드는 돌려준다 — 목록이 "닫힌 지역"을 그리려면 코드가 있어야 한다.
    @Test
    @DisplayName("목록용 일괄 조회 — null 은 빼고 중복은 한 번만, 닫힌 지역도 코드를 준다")
    void codes_of_batches_and_skips_nulls() {
        Region closed = Region.builder().id(30L).parentId(1L).code("11650")
                .nameKo("서초구").nameEn("Seocho-gu").isActive(false).build();
        given(regionRepository.findByIdIn(List.of(20L, 30L))).willReturn(List.of(GWANAK, closed));

        List<Long> ids = new ArrayList<>(Arrays.asList(20L, null, 30L, 20L));
        assertThat(resolver.codesOf(ids)).containsExactlyInAnyOrderEntriesOf(
                Map.of(20L, "11620", 30L, "11650"));
        verify(regionRepository).findByIdIn(List.of(20L, 30L));
    }

    @Test
    @DisplayName("지역이 하나도 없으면 묻지 않고 빈 표를 준다")
    void codes_of_without_regions_does_not_query() {
        List<Long> ids = new ArrayList<>();
        ids.add(null);
        assertThat(resolver.codesOf(ids)).isEmpty();
        assertThat(resolver.codesOf(List.of())).isEmpty();
        verify(regionRepository, never()).findByIdIn(any());
    }
}
