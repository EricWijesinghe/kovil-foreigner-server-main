package com.kovil.infra.kakao.geocoding;

import com.kovil.infra.kakao.geocoding.dto.response.KakaoAddressResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(
        name = "kakaoGeocodingClient",
        url = "${kakao.geocoding.base-url:https://dapi.kakao.com}",
        configuration = KakaoGeocodingFeignConfig.class
)
public interface KakaoGeocodingFeignClient {

    /** size는 카카오 상한 30. page는 1부터. */
    @GetMapping("/v2/local/search/address.json")
    KakaoAddressResponse searchAddress(
            @RequestParam("query") String query,
            @RequestParam("page") int page,
            @RequestParam("size") int size);
}
