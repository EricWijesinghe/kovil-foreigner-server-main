package com.kovil.infra.oauth.apple;

// 앱이 보낸 identityToken 은 제3자(앱)를 거쳐 온 토큰이다. 디코드만 해서는 안 되고 JWKS 서명 검증이 필수다.
// 서버 간 알림도 같은 Apple 키로 서명된 JWS 라 같은 자리에서 검증한다.
public interface AppleIdTokenVerifier {

    // audience 는 그 토큰을 받은 쪽의 client_id 다. 네이티브는 번들 ID, 웹은 Services ID.
    AppleIdTokenClaims verify(String idToken, String nonce, String audience);

    // aud 는 우리 client_id 둘 중 하나면 된다. 알림에는 nonce 가 없다.
    AppleAccountEvent verifyEvent(String payload);
}
