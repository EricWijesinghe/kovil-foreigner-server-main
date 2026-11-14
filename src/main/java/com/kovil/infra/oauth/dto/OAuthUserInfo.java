package com.kovil.infra.oauth.dto;

/**
 * 프로바이더가 준 신원. **셋이 서로 다른 것을 준다.**
 *
 * <ul>
 *   <li>Google — {@code profile} scope가 있으면 {@code name}을 주지만 <b>보장하지 않는다</b>(공식 문서 명시).</li>
 *   <li>Facebook — {@code public_profile}(전 앱 자동 부여)로 {@code name}이 온다. 이메일은 거부될 수 있다.</li>
 *   <li>Apple — id_token에 <b>이름이 없다.</b> 최초 인가 <b>단 한 번</b> form_post의 {@code user} 필드로만 온다.
 *       그때 못 받으면 사용자가 앱 접근을 해제하고 다시 인가하기 전까지 영영 못 받는다.</li>
 * </ul>
 *
 * 그래서 {@code name}은 언제나 null일 수 있다 — 받으면 쓰고, 없으면 여권 성명으로 대신한다.
 */
// encryptedRefreshToken · refreshTokenClientId 는 Apple 만 싣는다(탈퇴 철회용 암호문과 그 토큰을 낸 client_id).
public record OAuthUserInfo(String providerUserId, String email, String name,
                            String encryptedRefreshToken, String refreshTokenClientId) {

    public OAuthUserInfo(String providerUserId, String email, String name) {
        this(providerUserId, email, name, null, null);
    }
}
