package com.kovil.domain.auth.controller;

import com.kovil.domain.auth.service.OAuthCallbackBounce;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Auth · OAuth 되돌림", description = """
        provider 가 리디렉션한 브라우저를 앱으로 넘기는 **되돌림 페이지**. 사람이 부르는 API 가 아니다.

        구글·메타는 이 앱을 웹 클라이언트로 보기 때문에 리디렉션 URI 에 https 만 허용하고
        커스텀 스킴(`앱://`)을 거부한다. 그래서 provider → 이 페이지 → 앱 스킴 순으로 온다.
        **이 페이지는 code 를 교환하지 않는다** — 값을 그대로 앱에 넘기기만 하고,
        교환은 앱이 `POST /api/v1/auth/oauth/{provider}` 로 한다.

        돌아갈 곳은 1단계 authorize 의 `returnTo` 로 정해진다. `APP` 이면 `kovil://oauth/{provider}`,
        `WEB` 이면 웹 주소(운영 `https://kovil.co.kr`)의 `/oauth/{provider}` 다. 둘 다 `code` · `state` · `error` 를 쿼리로 싣는다.
        """)
@RestController
@RequiredArgsConstructor
public class OAuthCallbackController {

    private final OAuthCallbackBounce bounce;

    @Operation(summary = "소셜 로그인 되돌림", description = """
            비로그인 경로다 — 사용자는 아직 우리 토큰을 갖고 있지 않다.

            성공이든 사용자 거부든 **항상 앱으로 되돌린다.** 거부(`error`)를 여기서 화면으로
            처리하면 사용자가 브라우저에 갇힌다 — 판단은 앱이 한다.
            """)
    @GetMapping(value = "/oauth/{provider}/callback", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> callback(
            @PathVariable String provider,
            @Parameter(description = "인가 코드. 사용자가 거부하면 없다.")
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @Parameter(description = "provider 가 보낸 거부·실패 사유")
            @RequestParam(required = false) String error) {
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(bounce.page(provider, code, state, error));
    }

    @Operation(summary = "Apple 웹 로그인 되돌림 (form_post)", description = """
            Apple 은 웹 로그인 결과를 **POST 폼**(`response_mode=form_post`)으로 보낸다. Services ID 의 Return URL 이 이 주소다.
            하는 일은 GET 되돌림과 같고, 처음 인가할 때만 오는 `user`(이름)를 서버에 받아 두는 것만 다르다.
            이름은 이어지는 `POST /api/v1/auth/oauth/apple` 이 가입에 쓴다. 클라이언트는 `user` 를 따로 넘기지 않는다.
            `id_token` 도 함께 오지만 쓰지 않는다. 서버가 code 를 교환해 받은 id_token 을 검증한다.
            """)
    @PostMapping(value = "/oauth/apple/callback", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> appleCallback(
            @Parameter(description = "인가 코드. 사용자가 취소하면 없다.")
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @Parameter(description = "처음 인가할 때만 오는 이름 · 이메일 JSON 원문")
            @RequestParam(required = false) String user,
            @Parameter(description = "Apple 이 보낸 거부 · 실패 사유(예: user_cancelled_authorize)")
            @RequestParam(required = false) String error) {
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(bounce.applePage(code, state, error, user));
    }
}
