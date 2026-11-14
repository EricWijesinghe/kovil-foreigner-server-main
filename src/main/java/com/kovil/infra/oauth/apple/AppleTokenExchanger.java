package com.kovil.infra.oauth.apple;

import com.kovil.infra.oauth.apple.dto.AppleTokenResponse;

public interface AppleTokenExchanger {

    // 네이티브. client_id 는 번들 ID 이고 redirect_uri 를 싣지 않는다.
    // authorizationCode 를 교환해 refresh_token 을 돌려준다. 교환 응답의 sub 가 expectedSub 와 다르면 거절한다.
    String exchange(String authorizationCode, String expectedSub);

    // 웹. client_id 는 Services ID 이고 인가 요청에 넣은 redirect_uri 를 같이 싣는다.
    // 응답의 id_token 은 호출부가 서명 · nonce 까지 검증한다. refresh_token · id_token 이 없으면 거절한다.
    AppleTokenResponse exchangeWeb(String authorizationCode);

    // clientId 는 그 토큰을 받은 쪽 값이어야 한다(Apple 이 client_id 와 토큰의 짝을 본다).
    // 실패해도 던지지 않는다. 탈퇴는 이미 끝났고 되돌릴 것이 없다.
    void revoke(String refreshToken, String clientId);
}
