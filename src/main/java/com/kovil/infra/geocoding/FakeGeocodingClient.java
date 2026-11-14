package com.kovil.infra.geocoding;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@ConditionalOnProperty(name = "geocoding.provider", havingValue = "fake", matchIfMissing = true)
public class FakeGeocodingClient implements GeocodingClient {

    private static final List<GeocodedAddress> SAMPLE = List.of(
            new GeocodedAddress(
                    "전라남도 나주시 빛가람로 685", "전라남도 나주시 빛가람동 235-1",
                    "685, Bitgaram-ro, Naju-si, Jeollanam-do, Republic of Korea",
                    "58217", "전라남도", "나주시", "126.7891", "35.0210", null, "빛가람동"),
            new GeocodedAddress(
                    "서울특별시 광진구 아차산로 200", "서울특별시 광진구 자양동 227-7",
                    "200, Achasan-ro, Gwangjin-gu, Seoul, Republic of Korea",
                    "05066", "서울특별시", "광진구", "127.0700", "37.5351", null, "자양동"),
            new GeocodedAddress(
                    "경기도 성남시 분당구 불정로 6", "경기도 성남시 분당구 정자동 178-1",
                    "6, Buljeong-ro, Bundang-gu, Seongnam-si, Gyeonggi-do, Republic of Korea",
                    "13561", "경기도", "성남시 분당구", "127.1054328", "37.3595963", null, "정자동"));

    @Override
    public GeocodedPage search(String query, int page, int count) {
        log.info("[FakeGeocoding] query='{}' — 고정 목록에서 포함 필터로 응답한다", query);
        List<GeocodedAddress> hits = SAMPLE.stream()
                .filter(address -> address.roadAddress().contains(query)
                        || address.jibunAddress().contains(query))
                .toList();
        return new GeocodedPage(hits.size(), page, page == 1 ? hits : List.of());
    }
}
