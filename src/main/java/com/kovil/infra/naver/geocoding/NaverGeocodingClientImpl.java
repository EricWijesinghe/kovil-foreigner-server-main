package com.kovil.infra.naver.geocoding;

import com.kovil.infra.geocoding.GeocodedAddress;
import com.kovil.infra.geocoding.GeocodedPage;
import com.kovil.infra.geocoding.GeocodingClient;
import com.kovil.infra.geocoding.exception.GeocodingException;
import com.kovil.infra.naver.geocoding.dto.response.NaverGeocodeResponse;
import feign.RetryableException;
import feign.codec.DecodeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

import static com.kovil.infra.geocoding.exception.GeocodingErrorCode.GEOCODING_PROVIDER_ERROR;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "geocoding.provider", havingValue = "naver")
public class NaverGeocodingClientImpl implements GeocodingClient {

    private final NaverGeocodingFeignClient feignClient;

    @Override
    public GeocodedPage search(String query, int page, int count) {
        NaverGeocodeResponse response;
        try {
            response = feignClient.geocode(query, page, count);
        } catch (RetryableException | DecodeException e) {
            // 응답 없음(연결 실패 · 시한) · 본문 못 읽음은 오류 디코더를 안 거침. 재시도는 안 함(호출당 과금).
            // 메시지에 검색어(주소)가 든 URL 이 있어 예외 종류만 남김.
            log.warn("[geocoding] 네이버 주소 검색 실패 — {}", e.getClass().getSimpleName());
            throw new GeocodingException(GEOCODING_PROVIDER_ERROR);
        }

        // 벤더는 실패도 200이 아닌 4xx/5xx로 주지만, status 필드가 OK가 아닌 200도 방어한다.
        if (response == null || !"OK".equals(response.status())) {
            throw new GeocodingException(GEOCODING_PROVIDER_ERROR);
        }

        List<GeocodedAddress> addresses = response.addresses() == null ? List.of()
                : response.addresses().stream().map(this::toAddress).toList();
        long total = response.meta() == null || response.meta().totalCount() == null
                ? addresses.size() : response.meta().totalCount();
        return new GeocodedPage(total, page, addresses);
    }

    private GeocodedAddress toAddress(NaverGeocodeResponse.Address address) {
        return new GeocodedAddress(
                address.roadAddress(),
                address.jibunAddress(),
                address.englishAddress(),
                element(address, "POSTAL_CODE"),
                element(address, "SIDO"),
                element(address, "SIGUGUN"),
                address.x(),
                address.y(),
                // 네이버 지오코딩은 법정동 코드를 주지 않는다(addressElements 의 code 는 문서에 뜻이 없고 예시가 빈 값).
                null,
                element(address, "DONGMYUN"));
    }

    // addressElements에서 해당 타입의 longName을 캐낸다 — 없으면 null(값을 지어내지 않는다).
    private String element(NaverGeocodeResponse.Address address, String type) {
        if (address.addressElements() == null) {
            return null;
        }
        return address.addressElements().stream()
                .filter(el -> el.types() != null && el.types().contains(type))
                .map(NaverGeocodeResponse.AddressElement::longName)
                .flatMap(name -> Optional.ofNullable(name).stream())
                .filter(name -> !name.isBlank())
                .findFirst()
                .orElse(null);
    }
}
