package com.kovil.domain.listing.service;

import com.kovil.common.exception.CustomException;
import com.kovil.domain.listing.dto.response.ListingNearbyResponse;
import com.kovil.domain.listing.entity.Listing;
import com.kovil.domain.listing.enums.ListingStatus;
import com.kovil.domain.listing.enums.NearbyCategory;
import com.kovil.domain.listing.repository.ListingRepository;
import com.kovil.infra.nearby.NearbyPlaceClient;
import com.kovil.infra.nearby.NearbySearchResult;
import com.kovil.infra.nearby.NearbyVendorException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static com.kovil.domain.listing.exception.ListingErrorCode.LISTING_NOT_FOUND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/*
 * 주변시설 조립 격자.
 * ★ 중심은 흐린 좌표여야 한다 — 실좌표가 벤더로 나가면 시설 거리 여러 개로 집 위치가 역산된다.
 *   그래서 Listing 은 목으로 두고 **흐린 좌표만** 답하게 한다(실좌표 getter 를 부르면 null 이 간다).
 * ★ 실패는 200 + UNAVAILABLE 이고 캐시에 남지 않아야 한다 — 남으면 장애가 TTL 동안 굳는다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ListingNearbyServiceTest {

    private static final String UUID = "nb-listing";
    private static final long LISTING_ID = 77L;
    private static final BigDecimal DISPLAY_LAT = new BigDecimal("37.5552000");
    private static final BigDecimal DISPLAY_LNG = new BigDecimal("126.9368000");
    private static final String KEY = "listing:nearby:77:37.5552000:126.9368000";
    private static final Duration WEEK = Duration.ofDays(7);

    @Mock ListingRepository listingRepository;
    @Mock NearbyPlaceClient client;
    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> ops;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private Listing listing;

    @BeforeEach
    void setUp() {
        listing = mock(Listing.class);
        given(listing.getId()).willReturn(LISTING_ID);
        given(listing.getDisplayLat()).willReturn(DISPLAY_LAT);
        given(listing.getDisplayLng()).willReturn(DISPLAY_LNG);
        given(listingRepository.findPublicByUuid(eq(UUID), any())).willReturn(Optional.of(listing));
        given(redisTemplate.opsForValue()).willReturn(ops);
        given(client.search(anyString(), any(), any(), anyInt(), anyInt()))
                .willAnswer(inv -> result(inv.getArgument(0)));
    }

    private ListingNearbyService service(Duration cacheTtl) {
        return new ListingNearbyService(listingRepository, client,
                new ListingNearbyProperties(500, 3, cacheTtl), redisTemplate, jsonMapper);
    }

    // 카테고리마다 다른 수 · 이름이라 순서가 섞이면 드러난다.
    private static NearbySearchResult result(String code) {
        int n = NearbyCategory.valueOf(code).ordinal() + 1;
        return new NearbySearchResult(n * 10L, List.of(
                new NearbySearchResult.Place(code + "-1", new BigDecimal("37.556"), new BigDecimal("126.937")),
                new NearbySearchResult.Place(code + "-2", new BigDecimal("37.557"), new BigDecimal("126.938"))));
    }

    @Nested
    @DisplayName("조립")
    class Assemble {

        @Test
        @DisplayName("공개 매물이 아니면 404 이고 벤더를 부르지 않는다")
        void not_public_is_404() {
            given(listingRepository.findPublicByUuid(eq("gone"), any())).willReturn(Optional.empty());

            assertThatThrownBy(() -> service(Duration.ZERO).nearby("gone"))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(LISTING_NOT_FOUND);
            verifyNoInteractions(client);
        }

        // 상태 목록을 이 서비스가 따로 들고 있다(ListingStatus 의 공개 판정을 쓰지 않는다) — 다른 스텁은 any() 라 가려진다.
        @Test
        @DisplayName("공개로 치는 상태는 판매 중 · 예약 중 둘뿐이다 — 거래 완료 · 숨김 · 정지가 섞이면 비공개 매물 주변이 열린다")
        void public_statuses_are_active_and_reserved_only() {
            service(Duration.ZERO).nearby(UUID);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Collection<ListingStatus>> statuses = ArgumentCaptor.forClass(Collection.class);
            verify(listingRepository).findPublicByUuid(eq(UUID), statuses.capture());
            assertThat(statuses.getValue()).containsExactlyInAnyOrder(ListingStatus.ACTIVE, ListingStatus.RESERVED);
        }

        @Test
        @DisplayName("카테고리 8개가 선언 순서대로 · 수와 가까운 곳이 그대로 온다")
        void eight_categories_in_order() {
            ListingNearbyResponse response = service(Duration.ZERO).nearby(UUID);

            assertThat(response.status()).isEqualTo(ListingNearbyResponse.Status.OK);
            assertThat(response.radius()).isEqualTo(500);
            assertThat(response.categories()).extracting(ListingNearbyResponse.Category::code)
                    .containsExactly(NearbyCategory.values());
            assertThat(response.categories()).extracting(ListingNearbyResponse.Category::count)
                    .containsExactly(10L, 20L, 30L, 40L, 50L, 60L, 70L, 80L);
            ListingNearbyResponse.Category sw8 = response.categories().get(7);
            assertThat(sw8.nearest()).extracting(ListingNearbyResponse.Place::name)
                    .containsExactly("SW8-1", "SW8-2");
            assertThat(sw8.nearest().get(0).lat()).isEqualByComparingTo("37.556");
            assertThat(sw8.nearest().get(0).lng()).isEqualByComparingTo("126.937");
        }

        @Test
        @DisplayName("벤더에는 흐린 좌표 · 설정 반경 500 · 3곳을 넘긴다 — 카테고리 순서대로 한 번씩")
        void asks_vendor_with_display_coordinates() {
            service(Duration.ZERO).nearby(UUID);

            InOrder order = inOrder(client);
            for (NearbyCategory category : NearbyCategory.values()) {
                order.verify(client).search(category.name(), DISPLAY_LAT, DISPLAY_LNG, 500, 3);
            }
            verify(client, times(8)).search(anyString(), any(), any(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("응답 장소에는 이름 · 좌표뿐이다 — 시간 · 거리 칸이 없다")
        void place_has_no_distance_or_time() {
            assertThat(Arrays.stream(ListingNearbyResponse.Place.class.getRecordComponents())
                    .map(c -> c.getName()))
                    .containsExactly("name", "lat", "lng");
        }
    }

    @Nested
    @DisplayName("실패")
    class Failure {

        @Test
        @DisplayName("중간 카테고리에서 벤더가 실패해도 200 모양 — UNAVAILABLE · 빈 목록 · 반경은 그대로")
        void vendor_failure_mid_way_is_unavailable() {
            given(client.search(eq("BK9"), any(), any(), anyInt(), anyInt()))
                    .willThrow(new NearbyVendorException("kakao local 429"));

            ListingNearbyResponse response = service(Duration.ZERO).nearby(UUID);

            assertThat(response.status()).isEqualTo(ListingNearbyResponse.Status.UNAVAILABLE);
            assertThat(response.categories()).isEmpty();
            assertThat(response.radius()).isEqualTo(500);
            // 실패한 뒤에는 더 부르지 않는다(나머지 다섯 카테고리)
            verify(client, never()).search(eq("SW8"), any(), any(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("타임아웃 같은 다른 런타임 예외도 UNAVAILABLE 이다 — 상세 옆 칸이 500 을 내면 안 된다")
        void any_runtime_exception_is_unavailable() {
            given(client.search(eq("CS2"), any(), any(), anyInt(), anyInt()))
                    .willThrow(new IllegalStateException("read timed out"));

            assertThat(service(Duration.ZERO).nearby(UUID).status())
                    .isEqualTo(ListingNearbyResponse.Status.UNAVAILABLE);
        }

        @Test
        @DisplayName("실패한 결과는 캐시가 켜져 있어도 저장하지 않는다 — 장애가 TTL 동안 굳는다")
        void failure_is_not_cached() {
            given(client.search(eq("CS2"), any(), any(), anyInt(), anyInt()))
                    .willThrow(new NearbyVendorException("kakao local 500"));

            service(WEEK).nearby(UUID);

            verify(ops, never()).set(anyString(), anyString(), any(Duration.class));
        }
    }

    @Nested
    @DisplayName("캐시")
    class Cache {

        @Test
        @DisplayName("TTL 0(기본)이면 Redis 를 건드리지 않는다")
        void off_never_touches_redis() {
            service(Duration.ZERO).nearby(UUID);

            verifyNoInteractions(redisTemplate);
        }

        @Test
        @DisplayName("없으면 벤더를 부르고 (매물 id · 흐린 좌표) 키에 TTL 로 저장한다 — 저장한 값은 그대로 되읽힌다")
        void miss_stores_with_ttl() {
            ListingNearbyResponse response = service(WEEK).nearby(UUID);

            ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
            verify(ops).set(eq(KEY), json.capture(), eq(WEEK));
            assertThat(jsonMapper.readValue(json.getValue(), ListingNearbyResponse.class)).isEqualTo(response);
        }

        @Test
        @DisplayName("있으면 그 값을 주고 벤더를 부르지 않는다")
        void hit_skips_vendor() {
            ListingNearbyResponse cached = new ListingNearbyResponse(ListingNearbyResponse.Status.OK, 500,
                    List.of(new ListingNearbyResponse.Category(NearbyCategory.CS2, 1,
                            List.of(new ListingNearbyResponse.Place("캐시된 곳",
                                    new BigDecimal("37.5"), new BigDecimal("126.9"))))));
            given(ops.get(KEY)).willReturn(jsonMapper.writeValueAsString(cached));

            assertThat(service(WEEK).nearby(UUID)).isEqualTo(cached);
            verifyNoInteractions(client);
        }

        @Test
        @DisplayName("Redis 가 죽어 있으면 캐시 없이 벤더로 간다(fail-open)")
        void redis_down_falls_back() {
            given(ops.get(KEY)).willThrow(new RedisConnectionFailureException("down"));
            Mockito.doThrow(new RedisConnectionFailureException("down"))
                    .when(ops).set(anyString(), anyString(), any(Duration.class));

            ListingNearbyResponse response = service(WEEK).nearby(UUID);

            assertThat(response.status()).isEqualTo(ListingNearbyResponse.Status.OK);
            assertThat(response.categories()).hasSize(8);
        }

        @Test
        @DisplayName("옛 모양 · 깨진 값이 들어 있으면 버리고 벤더로 간다")
        void corrupt_value_falls_back() {
            given(ops.get(KEY)).willReturn("not-json");

            assertThat(service(WEEK).nearby(UUID).categories()).hasSize(8);
            verify(client, times(8)).search(anyString(), any(), any(), anyInt(), anyInt());
        }
    }
}
