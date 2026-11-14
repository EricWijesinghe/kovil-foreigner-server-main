package com.kovil.infra.oauth.apple;

import com.kovil.infra.oauth.apple.dto.AppleTokenResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

@FeignClient(name = "appleTokenClient", url = "${oauth.apple.token-uri}")
public interface AppleTokenClient {

    // 구글과 동일한 함정 — 본문 없는 POST 는 거절당한다. GoogleTokenClient 주석 참고.
    @PostMapping(consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    AppleTokenResponse getToken(@RequestBody Map<String, ?> form);
}
