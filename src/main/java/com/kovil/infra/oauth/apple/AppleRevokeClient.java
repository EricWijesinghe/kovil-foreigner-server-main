package com.kovil.infra.oauth.apple;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

// 토큰 교환과 같은 함정이다. 본문 없는 POST 는 거절당한다. GoogleTokenClient 주석 참고.
// 이미 무효인 토큰이어도 200 에 본문이 없다.
@FeignClient(name = "appleRevokeClient", url = "${oauth.apple.revoke-uri}")
public interface AppleRevokeClient {

    @PostMapping(consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    void revoke(@RequestBody Map<String, ?> form);
}
