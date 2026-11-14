package com.kovil.domain.auth.service;

import com.kovil.domain.user.enums.AuthType;
import com.kovil.infra.oauth.OAuthAppSchemeProperties;
import com.kovil.infra.oauth.OAuthReturnTarget;
import com.kovil.infra.oauth.OAuthStateStore;
import com.kovil.infra.oauth.OAuthWebProperties;
import com.kovil.infra.oauth.apple.AppleCallbackNameStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class OAuthCallbackBounce {

    private final OAuthAppSchemeProperties properties;
    private final OAuthWebProperties webProperties;
    private final OAuthStateStore stateStore;
    private final AppleCallbackNameStore appleNameStore;

    // Apple 웹의 form_post. 이름은 이 POST 에만 오므로 살아 있는 state 일 때만 받아 둔다.
    // 모르는 state 로 아무 값이나 쌓지 못하게 하려는 것이다.
    public String applePage(String code, String state, String error, String userPayload) {
        if (stateStore.returnTargetOf(state) != null) {
            appleNameStore.save(state, userPayload);
        }
        return page("apple", code, state, error);
    }

    // 돌아갈 곳은 authorize 때 state 에 묶어 둔 값으로 고른다. 모르는 state(만료 · 위조)는 앱으로 보낸다.
    // 앱이 그 code 를 교환하려 해도 state 가 없어 OAUTH_STATE_INVALID 로 끝난다.
    public String page(String provider, String code, String state, String error) {
        boolean web = stateStore.returnTargetOf(state) == OAuthReturnTarget.WEB;
        String target = targetUrl(web, provider, code, state, error);
        String href = escapeHtml(target);
        String message = web ? "로그인을 마치는 중입니다…" : "앱으로 돌아가는 중입니다…";
        String button = web ? "계속하기" : "앱으로 돌아가기";
        return """
                <!doctype html>
                <html lang="ko">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <meta name="robots" content="noindex,nofollow">
                <title>로그인 중</title>
                <style>
                  body{margin:0;min-height:100svh;display:flex;align-items:center;justify-content:center;
                    background:#fff;color:#111827;
                    font-family:-apple-system,BlinkMacSystemFont,"Apple SD Gothic Neo","Noto Sans KR",sans-serif}
                  .box{display:flex;flex-direction:column;align-items:center;gap:14px;padding:24px;text-align:center}
                  .msg{font-size:15px;color:#4b5563}
                  .go{display:flex;align-items:center;height:48px;padding:0 20px;border-radius:12px;
                    background:#1d6ded;color:#fff;font-size:15px;font-weight:600;text-decoration:none}
                </style>
                </head>
                <body>
                <div class="box">
                  <p class="msg">%s</p>
                  <a class="go" id="go" href="%s">%s</a>
                </div>
                <script>
                  // 자동 이동. 실패하면 위 버튼이 그대로 남아 사람이 직접 누른다 —
                  // 앱이 없거나 스킴을 막는 브라우저에서 빈 화면으로 끝나지 않게 한다.
                  location.replace(document.getElementById('go').href);
                </script>
                </body>
                </html>
                """.formatted(message, href, button);
    }

    // 스킴 · 웹 주소와 경로는 우리가 정하고, 외부 입력은 값 자리에만 인코딩해 넣는다.
    private String targetUrl(boolean web, String provider, String code, String state, String error) {
        Map<String, String> params = new LinkedHashMap<>();
        put(params, "code", code);
        put(params, "state", state);
        put(params, "error", error);

        StringBuilder url = new StringBuilder(web ? webProperties.baseUrl() + "/oauth/" : properties.scheme() + "://oauth/")
                .append(normalizeProvider(provider));
        char sep = '?';
        for (Map.Entry<String, String> e : params.entrySet()) {
            url.append(sep).append(e.getKey()).append('=').append(encode(e.getValue()));
            sep = '&';
        }
        return url.toString();
    }

    private String normalizeProvider(String provider) {
        if (provider == null) {
            return "";
        }
        String upper = provider.toUpperCase(Locale.ROOT);
        for (AuthType type : AuthType.values()) {
            if (type != AuthType.LOCAL && type.name().equals(upper)) {
                return type.name().toLowerCase(Locale.ROOT);
            }
        }
        return "";
    }

    private void put(Map<String, String> params, String key, String value) {
        if (value != null && !value.isBlank()) {
            params.put(key, value);
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String escapeHtml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
