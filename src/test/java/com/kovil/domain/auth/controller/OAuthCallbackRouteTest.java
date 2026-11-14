package com.kovil.domain.auth.controller;

import com.kovil.domain.auth.service.OAuthCallbackBounce;
import com.kovil.infra.oauth.OAuthAppSchemeProperties;
import com.kovil.infra.oauth.OAuthReturnTarget;
import com.kovil.infra.oauth.OAuthStateStore;
import com.kovil.infra.oauth.OAuthWebProperties;
import com.kovil.infra.oauth.apple.AppleCallbackNameStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.hamcrest.Matchers;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 경로 매핑 자체가 이 버그의 실체였다 — 서비스가 아무리 옳아도 /oauth/{p}/callback 이
// 라우팅되지 않으면 401 이 그대로 난다. 그 배선을 고정한다.
class OAuthCallbackRouteTest {

    private final OAuthStateStore stateStore = mock(OAuthStateStore.class);
    private final AppleCallbackNameStore nameStore = mock(AppleCallbackNameStore.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new OAuthCallbackController(new OAuthCallbackBounce(new OAuthAppSchemeProperties("kovil"),
                    new OAuthWebProperties("https://kovil.co.kr"), stateStore, nameStore)))
            .build();

    @Test
    @DisplayName("GET /oauth/{provider}/callback 이 HTML 되돌림 페이지를 준다")
    void callback_is_routed() throws Exception {
        mvc.perform(get("/oauth/google/callback").param("code", "C").param("state", "S"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(Matchers.containsString(
                        "kovil://oauth/google?code=C&amp;state=S")));
    }

    @Test
    @DisplayName("파라미터가 하나도 없어도 500 이 아니라 페이지를 준다")
    void bare_call_does_not_explode() throws Exception {
        mvc.perform(get("/oauth/google/callback")).andExpect(status().isOk());
    }

    // Apple 웹은 결과를 POST 폼으로 보낸다. GET 만 받으면 Apple 로그인을 마친 사람이 405 화면에 갇힌다.
    @Test
    @DisplayName("POST /oauth/apple/callback 폼을 받아 웹으로 넘기고 이름을 받아 둔다")
    void apple_form_post_is_routed() throws Exception {
        given(stateStore.returnTargetOf("S")).willReturn(OAuthReturnTarget.WEB);

        mvc.perform(post("/oauth/apple/callback")
                        .contentType("application/x-www-form-urlencoded")
                        .param("code", "C").param("state", "S").param("id_token", "ignored")
                        .param("user", "{\"name\":{\"firstName\":\"Van A\"}}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(Matchers.containsString(
                        "https://kovil.co.kr/oauth/apple?code=C&amp;state=S")));
        then(nameStore).should().save("S", "{\"name\":{\"firstName\":\"Van A\"}}");
    }
}
