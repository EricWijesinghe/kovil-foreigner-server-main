package com.kovil.infra.kakao.geocoding;

import com.kovil.infra.geocoding.GeocodedAddress;
import com.kovil.infra.geocoding.GeocodedPage;
import com.kovil.infra.geocoding.GeocodingClient;
import com.kovil.infra.geocoding.exception.GeocodingException;
import com.kovil.infra.kakao.geocoding.dto.response.KakaoAddressResponse;
import feign.RetryableException;
import feign.codec.DecodeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.kovil.infra.geocoding.exception.GeocodingErrorCode.GEOCODING_PROVIDER_ERROR;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "geocoding.provider", havingValue = "kakao")
public class KakaoGeocodingClientImpl implements GeocodingClient {

    // 카카오 로컬 API의 size 상한 — 넘기면 400이다.
    private static final int MAX_SIZE = 30;

    private final KakaoGeocodingFeignClient feignClient;

    @Override
    public GeocodedPage search(String query, int page, int count) {
        KakaoAddressResponse response;
        try {
            response = feignClient.searchAddress(query, page, Math.min(count, MAX_SIZE));
        } catch (RetryableException | DecodeException e) {
            // 응답 없음(연결 실패 · 시한) · 본문 못 읽음은 오류 디코더를 안 거침. 재시도는 안 함(호출당 과금).
            // 메시지에 검색어(주소)가 든 URL 이 있어 예외 종류만 남김.
            log.warn("[geocoding] 카카오 주소 검색 실패 — {}", e.getClass().getSimpleName());
            throw new GeocodingException(GEOCODING_PROVIDER_ERROR);
        }

        List<GeocodedAddress> addresses = response == null || response.documents() == null ? List.of()
                : response.documents().stream().map(this::toAddress).toList();
        long total = response == null || response.meta() == null || response.meta().pageableCount() == null
                ? addresses.size() : response.meta().pageableCount();
        return new GeocodedPage(total, page, addresses);
    }

    private GeocodedAddress toAddress(KakaoAddressResponse.Document doc) {
        KakaoAddressResponse.RoadAddress road = doc.roadAddress();
        KakaoAddressResponse.Address jibun = doc.address();
        return new GeocodedAddress(
                // 도로명이 없는 지번 전용 주소는 검색 매칭 표기를 싣는다 —
                // 빈 행을 보여주는 것보다 사람이 확인하고 고르는 편이 낫다.
                road != null ? road.addressName() : doc.addressName(),
                jibun != null && jibun.addressName() != null ? jibun.addressName() : doc.addressName(),
                // 카카오는 영문 주소를 주지 않는다 — 지어내지 않는다.
                null,
                road != null ? road.zoneNo() : null,
                jibun != null && jibun.region1() != null ? jibun.region1()
                        : road != null ? road.region1() : null,
                jibun != null && jibun.region2() != null ? jibun.region2()
                        : road != null ? road.region2() : null,
                doc.longitude(),
                doc.latitude(),
                jibun != null ? blankToNull(jibun.legalDongCode()) : null,
                jibun != null ? blankToNull(jibun.region3()) : null);
    }

    // 카카오는 없는 칸을 빈 문자열로 준다.
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
