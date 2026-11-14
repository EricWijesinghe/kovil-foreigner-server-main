package com.kovil.infra.oauth.apple;

// 서명 · iss · aud · exp · nonce 를 통과한 id_token 에서 꺼낸 값. 식별은 sub 로만 한다. email 은 릴레이 주소일 수 있다.
// emailVerified · privateEmail 은 Apple 이 주지 않으면 null 이다.
public record AppleIdTokenClaims(String sub, String email, Boolean emailVerified, Boolean privateEmail) {
}
