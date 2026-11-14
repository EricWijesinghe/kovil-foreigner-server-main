package com.kovil.infra.oauth.apple;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

// JWKS 원문을 그대로 받는다. 키 해석은 jjwt 의 Jwks 파서가 한다.
@FeignClient(name = "appleKeysClient", url = "${oauth.apple.keys-uri}")
public interface AppleKeysClient {

    @GetMapping
    String getKeys();
}
