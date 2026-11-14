package com.kovil.infra.oauth;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * PKCE(RFC 7636) S256 **서버 자체 대조**.
 *
 * ★ 왜 필요한가 — 되돌림 페이지가 인가 코드를 **커스텀 스킴 URL 에 실어** 앱으로 넘기는 이상,
 *   같은 스킴(`kovil://`)을 함께 등록한 다른 앱이 code·state 를 가로챌 수 있다
 *   (RFC 8252 §8.1). RFC 8252 §6 이 네이티브 클라이언트에 PKCE 를 MUST 로 규정하는 이유다.
 *
 * ★ 여기는 형식과 짝만 거르는 1차 관문일 뿐, **훔친 code 를 무력화하는 것은 이 클래스가 아니다.**
 *   공격자는 우리 authorize 엔드포인트에서 자기 verifier·challenge 짝을 새로 발급받을 수 있어,
 *   서버가 제 저장소끼리 대조하는 것만으로는 훔친 code 를 걸러내지 못한다. client_secret 을
 *   우리가 쥐고 있어도 소용없다 — 공격자는 provider 와 직접 교환할 필요가 없고, **우리 서버가
 *   자기 secret 으로 대신 교환해 준다.** 2026-09-01 계정 탈취로 실증했다.
 *
 * ★ 실제 결속은 provider 가 건다. challenge 는 인가 URL 로, verifier 는 토큰 교환으로 **둘 다**
 *   나가야 한다(GoogleOAuthClient). 한쪽이라도 빠지면 위 탈취가 그대로 되살아나므로,
 *   지우기 전에 GooglePkceExchangeTest·AuthorizationUriEncodingTest 를 먼저 읽는다.
 */
@Component
public class PkceValidator {

    // RFC 7636 §4.1 — unreserved characters 로만 이루어진 43~128자.
    private static final Pattern VERIFIER = Pattern.compile("^[A-Za-z0-9\\-._~]{43,128}$");
    // S256 결과는 32바이트를 패딩 없는 base64url 로 적은 43자다.
    private static final Pattern CHALLENGE = Pattern.compile("^[A-Za-z0-9\\-_]{43}$");

    public boolean isValidChallenge(String challenge) {
        return challenge != null && CHALLENGE.matcher(challenge).matches();
    }

    public boolean matches(String verifier, String challenge) {
        if (verifier == null || !VERIFIER.matcher(verifier).matches() || !isValidChallenge(challenge)) {
            return false;
        }
        // 상수 시간 비교 — 바이트별 조기 반환은 challenge 를 한 글자씩 맞춰 볼 여지를 준다.
        return MessageDigest.isEqual(
                challengeOf(verifier).getBytes(StandardCharsets.US_ASCII),
                challenge.getBytes(StandardCharsets.US_ASCII));
    }

    // Apple 웹은 이 값을 nonce 로 실어 id_token 에 박히게 한다(AppleOAuthClient).
    public String challengeOf(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 쓸 수 없다", e);
        }
    }
}
