package com.kovil.domain.auth.service;

import com.kovil.infra.oauth.OAuthAppSchemeProperties;
import com.kovil.infra.oauth.OAuthReturnTarget;
import com.kovil.infra.oauth.OAuthStateStore;
import com.kovil.infra.oauth.OAuthWebProperties;
import com.kovil.infra.oauth.apple.AppleCallbackNameStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

/**
 * 되돌림 페이지 — provider 가 리디렉션한 브라우저를 앱으로 넘기는 유일한 다리.
 *
 * 이게 없어서 운영에서 소셜 로그인이 완주하지 못했다(2026-09-01: 리디렉션 URI 에 핸들러가 없어
 * 401, 사용자는 브라우저에 갇힌 채 앱은 취소로 처리). 그래서 여기서 고정하는 것은
 * "앱으로 갈 수 있는가"와 "이 페이지가 공격 도구가 되지 않는가" 둘이다.
 */
class OAuthCallbackBounceTest {

    private final OAuthStateStore stateStore = mock(OAuthStateStore.class);
    private final AppleCallbackNameStore nameStore = mock(AppleCallbackNameStore.class);
    private final OAuthCallbackBounce bounce = new OAuthCallbackBounce(new OAuthAppSchemeProperties("kovil"),
            new OAuthWebProperties("https://kovil.co.kr/"), stateStore, nameStore);

    @Nested
    @DisplayName("앱으로 넘기기")
    class Handoff {

        @Test
        @DisplayName("code·state 를 앱 스킴 주소에 실어 준다")
        void carries_code_and_state() {
            String html = bounce.page("google", "CODE-1", "STATE-1", null);

            assertThat(html).contains("kovil://oauth/google?code=CODE-1&amp;state=STATE-1");
        }

        // 앱의 parseProviderParam 은 대문자로 비교한다 — 경로가 어떻게 오든 소문자 스킴 경로로 보낸다.
        @Test
        @DisplayName("provider 대소문자가 섞여 와도 앱이 아는 형태로 정규화한다")
        void normalizes_provider_case() {
            assertThat(bounce.page("GOOGLE", "c", "s", null))
                    .contains("://oauth/google?");
            assertThat(bounce.page("FaceBook", "c", "s", null))
                    .contains("://oauth/facebook?");
        }

        // 거부를 여기서 화면으로 처리하면 사용자가 브라우저에 갇힌다 — 판단은 앱이 한다.
        @Test
        @DisplayName("사용자가 거부해 code 가 없어도 앱으로 되돌린다")
        void denial_still_returns_to_app() {
            String html = bounce.page("google", null, null, "access_denied");

            assertThat(html).contains("kovil://oauth/google?error=access_denied");
        }

        @Test
        @DisplayName("스크립트가 실패해도 사람이 누를 링크가 남는다")
        void manual_link_survives_script_failure() {
            String html = bounce.page("google", "c", "s", null);

            assertThat(html).contains("앱으로 돌아가기");
            assertThat(html).contains("id=\"go\"");
        }
    }

    @Nested
    @DisplayName("공격 표면 차단")
    class Hardening {

        // ★ 오픈 리디렉트 방지. 경로 세그먼트를 그대로 실으면 우리 도메인으로 시작하는
        //   피싱 링크를 누구나 만들 수 있다.
        @Test
        @DisplayName("모르는 provider 는 스킴 경로에 반영하지 않는다")
        void unknown_provider_is_not_reflected() {
            String html = bounce.page("evil.com/steal", "c", "s", null);

            assertThat(html).doesNotContain("evil.com");
            assertThat(html).contains("kovil://oauth/?");
        }

        @Test
        @DisplayName("LOCAL 은 소셜 provider 가 아니므로 거부한다")
        void local_is_not_a_social_provider() {
            assertThat(bounce.page("local", "c", "s", null)).contains("://oauth/?");
        }

        // ★ XSS 방지. code·state 는 공격자가 정할 수 있다.
        @Test
        @DisplayName("code 에 태그를 넣어도 HTML 로 새지 않는다")
        void code_cannot_inject_html() {
            String html = bounce.page("google", "<script>alert(1)</script>", "s", null);

            assertThat(html).doesNotContain("<script>alert(1)</script>");
            assertThat(html).contains("%3Cscript%3E");
        }

        @Test
        @DisplayName("따옴표로 href 속성을 탈출할 수 없다")
        void quotes_cannot_break_out_of_attribute() {
            String html = bounce.page("google", "\" onload=\"alert(1)", "s", null);

            assertThat(html).doesNotContain("onload=\"alert(1)");
        }

        @Test
        @DisplayName("앰퍼샌드가 든 값이 파라미터를 늘리지 않는다")
        void ampersand_cannot_forge_extra_params() {
            String html = bounce.page("google", "c&state=FORGED", "s", null);

            assertThat(html).contains("code=c%26state%3DFORGED");
            assertThat(html).contains("state=s");
        }

        @Test
        @DisplayName("검색엔진에 색인되지 않게 막는다")
        void not_indexable() {
            assertThat(bounce.page("google", "c", "s", null)).contains("noindex");
        }
    }

    @Nested
    @DisplayName("웹으로 넘기기")
    class WebHandoff {

        @Test
        @DisplayName("authorize 때 WEB 으로 묶인 state 면 웹 주소로 code·state 를 넘긴다")
        void web_state_goes_to_the_web() {
            given(stateStore.returnTargetOf("STATE-W")).willReturn(OAuthReturnTarget.WEB);

            String html = bounce.page("google", "CODE-1", "STATE-W", null);

            assertThat(html).contains("https://kovil.co.kr/oauth/google?code=CODE-1&amp;state=STATE-W");
            assertThat(html).doesNotContain("kovil://");
            assertThat(html).doesNotContain("앱으로 돌아가기");
        }

        // 웹 거부도 웹이 판단한다. 앱 스킴으로 보내면 PC 브라우저가 "열 수 없는 주소"에서 멈춘다.
        @Test
        @DisplayName("웹 시도의 거부도 웹으로 되돌린다")
        void web_denial_goes_to_the_web() {
            given(stateStore.returnTargetOf("STATE-W")).willReturn(OAuthReturnTarget.WEB);

            assertThat(bounce.page("apple", null, "STATE-W", "user_cancelled_authorize"))
                    .contains("https://kovil.co.kr/oauth/apple?state=STATE-W&amp;error=user_cancelled_authorize");
        }

        // ★ 열린 리디렉션 방지. 목적지는 state 에 묶인 이름(APP · WEB)과 서버 설정으로만 정해진다.
        @Test
        @DisplayName("모르는 state 는 앱으로 보낸다 · 요청 값으로 웹 주소를 바꿀 수 없다")
        void unknown_state_falls_back_to_app() {
            String html = bounce.page("google", "c", "https://evil.example.test", null);

            assertThat(html).contains("kovil://oauth/google?");
            assertThat(html).doesNotContain("https://evil.example.test/");
        }

        @Test
        @DisplayName("웹 주소 설정은 http(s) 만 받는다")
        void web_base_url_must_be_http() {
            assertThatThrownBy(() -> new OAuthWebProperties("javascript:alert(1)"))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(new OAuthWebProperties("http://localhost:8081//").baseUrl()).isEqualTo("http://localhost:8081");
        }
    }

    @Nested
    @DisplayName("Apple form_post")
    class AppleFormPost {

        @Test
        @DisplayName("살아 있는 state 면 user 원문을 받아 두고 같은 되돌림 페이지를 준다")
        void live_state_keeps_the_name() {
            given(stateStore.returnTargetOf("STATE-A")).willReturn(OAuthReturnTarget.WEB);

            String html = bounce.applePage("CODE-A", "STATE-A", null, "{\"name\":{\"firstName\":\"Van A\"}}");

            then(nameStore).should().save("STATE-A", "{\"name\":{\"firstName\":\"Van A\"}}");
            assertThat(html).contains("https://kovil.co.kr/oauth/apple?code=CODE-A&amp;state=STATE-A");
        }

        // 모르는 state 로 아무 이름이나 Redis 에 쌓지 못하게 한다.
        @Test
        @DisplayName("모르는 state 면 이름을 받아 두지 않는다")
        void unknown_state_keeps_nothing() {
            bounce.applePage("c", "forged", null, "{\"name\":{\"firstName\":\"X\"}}");

            then(nameStore).should(never()).save(any(), any());
        }
    }
}
