package com.kovil.infra.naver.geocoding;

import com.kovil.infra.naver.geocoding.dto.response.NaverGeocodeResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

// 기본값을 .invalid 호스트로 — 미설정 부팅 보호.
@FeignClient(
        name = "naverGeocodingClient",
        url = "${naver.geocoding.base-url:https://naver-geocoding.invalid}",
        configuration = NaverGeocodingFeignConfig.class
)
public interface NaverGeocodingFeignClient {

    @GetMapping("/map-geocode/v2/geocode")
    NaverGeocodeResponse geocode(
            @RequestParam("query") String query,
            @RequestParam("page") int page,
            @RequestParam("count") int count);
}
