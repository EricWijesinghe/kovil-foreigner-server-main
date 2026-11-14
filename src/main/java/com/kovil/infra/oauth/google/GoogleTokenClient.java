package com.kovil.infra.oauth.google;

import com.kovil.infra.oauth.google.dto.GoogleTokenResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

@FeignClient(name = "googleTokenClient", url = "${oauth.google.token-uri}")
public interface GoogleTokenClient {

    // ★ @RequestParam 으로 선언하면 Feign 은 값을 쿼리스트링에 붙이고 **본문 없이** POST 한다.
    //   구글 토큰 엔드포인트는 본문(Content-Length)을 요구하므로 그 요청은 411 Length Required 로
    //   죽는다(실사고 2026-08-22 — 동의까지 끝낸 사용자가 마지막 단계에서 튕겼다).
    //   선례인 카카오가 쿼리스트링을 받아준다고 해서 구글도 그럴 거라 짐작하면 안 된다.
    //   덤: client_secret 이 URL 이 아닌 본문에 실려 예외 메시지·액세스 로그에 남지 않는다.
    @PostMapping(consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    GoogleTokenResponse getToken(@RequestBody Map<String, ?> form);
}
