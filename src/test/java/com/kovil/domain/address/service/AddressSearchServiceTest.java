package com.kovil.domain.address.service;

import com.kovil.domain.address.dto.response.AddressSearchResponse;
import com.kovil.domain.region.service.AddressRegionResolver;
import com.kovil.infra.geocoding.GeocodedAddress;
import com.kovil.infra.geocoding.GeocodedPage;
import com.kovil.infra.addresssearch.AddressSearchClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

// 빈 검색어는 벤더를 부르지 않는다(과금) · 페이지는 0기반 ↔ 벤더 1기반 · 결과마다 지역 판정 · 지역은 벤더 뒤에 읽는다.
@ExtendWith(MockitoExtension.class)
class AddressSearchServiceTest {

    @Mock AddressSearchClient addressSearchClient;
    @Mock AddressRegionResolver regionResolver;

    private AddressSearchService service() {
        return new AddressSearchService(addressSearchClient, regionResolver);
    }

    private static GeocodedAddress naju() {
        return new GeocodedAddress("전라남도 나주시 빛가람로 685", "전라남도 나주시 빛가람동 235-1", "eng",
                "58217", "전라남도", "나주시", "126.7891", "35.0210", "4617010300", "빛가람동");
    }

    @Test
    @DisplayName("빈 · 공백 검색어는 벤더도 지역도 읽지 않고 빈 목록이다")
    void blank_query_skips_vendor() {
        AddressSearchResponse response = service().search("   ", 0, 10);

        assertThat(response.totalCount()).isZero();
        assertThat(response.items()).isEmpty();
        verifyNoInteractions(addressSearchClient, regionResolver);
    }

    // 비로그인 공개 경로라 아주 긴 검색어를 그대로 벤더에 넘기지 않는다 — 글자 단위(코드 포인트)로 자르고 끝 공백은 뗀다.
    @Test
    @DisplayName("80자를 넘는 검색어는 80자까지만 벤더에 넘긴다")
    void long_query_is_clipped() {
        String head = "가".repeat(79) + " ";
        given(addressSearchClient.search("가".repeat(79), 1, 10)).willReturn(new GeocodedPage(0, 1, List.of()));

        service().search(head + "나".repeat(500), 0, 10);

        verify(addressSearchClient).search("가".repeat(79), 1, 10);
        String exact = "다".repeat(80);
        given(addressSearchClient.search(exact, 1, 10)).willReturn(new GeocodedPage(0, 1, List.of()));
        service().search(exact, 0, 10);
        verify(addressSearchClient).search(exact, 1, 10);
    }

    // 쪽 + 1 이 넘치면 음수 쪽이 벤더로 가고, 벤더 오류가 키 만료와 같은 error 경보로 남는다 — 상한 밖은 부르지 않는다
    @Test
    @DisplayName("쪽 번호가 상한(1000) 이상이면 벤더를 부르지 않고 빈 목록")
    void page_beyond_cap_skips_vendor() {
        AddressSearchResponse response = service().search("관악로", Integer.MAX_VALUE, 10);

        assertThat(response.items()).isEmpty();
        assertThat(service().search("관악로", AddressSearchService.MAX_PAGE, 10).items()).isEmpty();
        verifyNoInteractions(addressSearchClient, regionResolver);
    }

    @Test
    @DisplayName("0기반 페이지가 벤더 1기반으로 변환되고 응답은 0기반을 유지한다")
    void page_base_conversion() {
        given(addressSearchClient.search("빛가람로", 3, 10)).willReturn(new GeocodedPage(31, 3, List.of(naju())));
        given(regionResolver.snapshot()).willReturn(mock(AddressRegionResolver.Snapshot.class, invocation ->
                new AddressRegionResolver.Resolved(null, null)));

        AddressSearchResponse response = service().search("빛가람로", 2, 10);

        assertThat(response.page()).isEqualTo(2);
        assertThat(response.totalCount()).isEqualTo(31);
        assertThat(response.items().get(0).postalCode()).isEqualTo("58217");
    }

    @Test
    @DisplayName("결과마다 시군구 코드 · 열림 · 법정동 이름을 싣는다 — 판정에는 벤더가 준 축을 다 넘긴다")
    void items_carry_the_region() {
        given(addressSearchClient.search("빛가람로", 1, 10)).willReturn(new GeocodedPage(1, 1, List.of(naju())));
        AddressRegionResolver.Snapshot snapshot = mock(AddressRegionResolver.Snapshot.class);
        given(regionResolver.snapshot()).willReturn(snapshot);
        given(snapshot.resolve("4617010300", "전라남도", "나주시", "빛가람동",
                "전라남도 나주시 빛가람동 235-1", "전라남도 나주시 빛가람로 685"))
                .willReturn(new AddressRegionResolver.Resolved("12170", false));

        AddressSearchResponse.Item item = service().search("빛가람로", 0, 10).items().get(0);

        assertThat(item.regionCode()).isEqualTo("12170");
        assertThat(item.regionActive()).isFalse();
        assertThat(item.dongName()).isEqualTo("빛가람동");
    }

    // 이 경로는 OSIV 아래다 — 지역을 먼저 읽으면 벤더를 기다리는 동안 DB 커넥션을 쥔다.
    @Test
    @DisplayName("지역은 벤더 호출 뒤에 읽고, 결과가 없으면 읽지 않는다")
    void regions_are_read_after_the_vendor() {
        given(addressSearchClient.search("빛가람로", 1, 10)).willReturn(new GeocodedPage(1, 1, List.of(naju())));
        given(regionResolver.snapshot()).willReturn(mock(AddressRegionResolver.Snapshot.class, invocation ->
                new AddressRegionResolver.Resolved(null, null)));

        service().search("빛가람로", 0, 10);

        InOrder order = inOrder(addressSearchClient, regionResolver);
        order.verify(addressSearchClient).search(any(), ArgumentMatchers.anyInt(), ArgumentMatchers.anyInt());
        order.verify(regionResolver).snapshot();

        given(addressSearchClient.search("없는주소", 1, 10)).willReturn(new GeocodedPage(0, 1, List.of()));
        Mockito.clearInvocations(regionResolver);
        service().search("없는주소", 0, 10);
        verify(regionResolver, never()).snapshot();
    }
}
