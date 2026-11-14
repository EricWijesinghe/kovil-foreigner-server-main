package com.kovil.infra.oauth.facebook;

import com.kovil.infra.oauth.facebook.dto.FacebookTokenResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

// ⚠ Facebook의 코드 교환은 GET이다(공식 문서 확인). 선례 Kakao의 POST form과 다르다.
@FeignClient(name = "facebookTokenClient", url = "${oauth.facebook.token-uri}")
public interface FacebookTokenClient {

    @GetMapping
    FacebookTokenResponse getToken(
            @RequestParam("client_id") String clientId,
            @RequestParam("client_secret") String clientSecret,
            @RequestParam("redirect_uri") String redirectUri,
            @RequestParam("code") String code
    );
}
