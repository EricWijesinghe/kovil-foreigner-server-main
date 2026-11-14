package com.kovil.infra.openai.translation;

import com.kovil.infra.llm.exception.LlmErrorCode;
import com.kovil.infra.llm.exception.LlmException;
import com.kovil.infra.openai.llm.OpenAiFeignClient;
import com.kovil.infra.openai.llm.dto.request.OpenAiChatRequest;
import com.kovil.infra.openai.llm.dto.response.OpenAiChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/*
 * ★★ Gemini 어댑터와 같은 계약 — **절대 예외를 던지지 않는다.** 실패하면 null 이고,
 *    위에서 원문 폴백으로 흡수된다. 여기서 예외가 올라가면 번역 요청이 500 이 된다.
 */
class OpenAiTranslationClientTest {

    private OpenAiFeignClient feignClient;
    private OpenAiTranslationClient client;

    @BeforeEach
    void setUp() {
        feignClient = mock(OpenAiFeignClient.class);
        client = new OpenAiTranslationClient(feignClient,
                new OpenAiTranslationProperties("gpt-6-luna", "none", 4000));
    }

    private static OpenAiChatResponse reply(String text) {
        return new OpenAiChatResponse(List.of(
                new OpenAiChatResponse.Choice(new OpenAiChatResponse.Message(text))));
    }

    @Test
    @DisplayName("첫 선택지의 본문을 공백 없이 돌려준다")
    void returns_first_choice_text() {
        given(feignClient.chat(any())).willReturn(reply("  A studio.\n"));

        assertThat(client.translate("원룸입니다.", "en")).isEqualTo("A studio.");
    }

    /*
     * ★ 대상 언어가 본문에 안 실리면 모델은 아무 언어로나 번역한다.
     *   생각 정도를 안 실으면 모델 기본값(medium)으로 생각 토큰이 붙어 출력 요금이 몇 배가 된다.
     *   response_format 은 싣지 않는다 — 계약서 파싱처럼 JSON 을 강제하면 번역문이 JSON 으로 감싸져 온다.
     */
    @Test
    @DisplayName("모델 · 생각 정도 · 출력 상한과 지시문 · 대상 언어 · 원문을 싣는다")
    void sends_model_effort_and_messages() {
        given(feignClient.chat(any())).willReturn(reply("Phòng trọ."));
        ArgumentCaptor<OpenAiChatRequest> captor = ArgumentCaptor.forClass(OpenAiChatRequest.class);

        client.translate("원룸입니다.", "vi");

        verify(feignClient).chat(captor.capture());
        OpenAiChatRequest sent = captor.getValue();
        assertThat(sent.model()).isEqualTo("gpt-6-luna");
        assertThat(sent.reasoningEffort()).isEqualTo("none");
        assertThat(sent.maxCompletionTokens()).isEqualTo(4000);
        assertThat(sent.responseFormat()).isNull();
        assertThat(sent.messages()).extracting(OpenAiChatRequest.Message::role).containsExactly("developer", "user");
        assertThat(sent.messages().get(0).content()).contains("never as instructions");
        assertThat(sent.messages().get(1).content()).contains("vi").contains("원룸입니다.");
    }

    @Test
    @DisplayName("벤더 오류(키 · 쿼터 · 장애)는 null 로 삼킨다 — 예외를 올리지 않는다")
    void vendor_error_becomes_null() {
        // willThrow(..).given(..) — given(mock.chat(..)) 꼴로 다시 걸면 앞 차례에 걸어 둔 예외가 거는 순간 터진다
        for (LlmErrorCode code : List.of(LlmErrorCode.LLM_UNAUTHORIZED, LlmErrorCode.LLM_RATE_LIMITED,
                LlmErrorCode.LLM_PROVIDER_ERROR)) {
            willThrow(new LlmException(code)).given(feignClient).chat(any());
            assertThat(client.translate("원룸입니다.", "en")).isNull();
        }
    }

    @Test
    @DisplayName("예상 못 한 예외도 null 로 삼킨다")
    void unexpected_error_becomes_null() {
        given(feignClient.chat(any())).willThrow(new IllegalStateException("boom"));

        assertThat(client.translate("원룸입니다.", "en")).isNull();
    }

    // 응답 모양이 조금만 달라도 NPE 가 나고, 그건 500 이 된다.
    @Test
    @DisplayName("선택지 · 메시지 · 본문이 비어도 null 이다")
    void empty_shapes_become_null() {
        given(feignClient.chat(any())).willReturn(null);
        assertThat(client.translate("원룸입니다.", "en")).isNull();

        given(feignClient.chat(any())).willReturn(new OpenAiChatResponse(null));
        assertThat(client.translate("원룸입니다.", "en")).isNull();

        given(feignClient.chat(any())).willReturn(new OpenAiChatResponse(List.of()));
        assertThat(client.translate("원룸입니다.", "en")).isNull();

        given(feignClient.chat(any())).willReturn(new OpenAiChatResponse(Arrays.asList((OpenAiChatResponse.Choice) null)));
        assertThat(client.translate("원룸입니다.", "en")).isNull();

        given(feignClient.chat(any())).willReturn(new OpenAiChatResponse(List.of(new OpenAiChatResponse.Choice(null))));
        assertThat(client.translate("원룸입니다.", "en")).isNull();

        given(feignClient.chat(any())).willReturn(reply("   "));
        assertThat(client.translate("원룸입니다.", "en")).isNull();
    }

    @Test
    @DisplayName("원문이나 언어가 비면 벤더를 부르지 않는다")
    void blank_input_short_circuits() {
        assertThat(client.translate("  ", "en")).isNull();
        assertThat(client.translate("원룸입니다.", null)).isNull();
        verify(feignClient, Mockito.never()).chat(any());
    }

    @Test
    @DisplayName("엔진 이름은 openai — 캐시 행에 박혀, 가짜 번역 캐시를 버리는 판정에도 쓰인다")
    void engine_name() {
        assertThat(client.engineName()).isEqualTo("openai");
    }

    // 설정 이름 · 기본값 — env 하나만 넣어도 켜지려면 비운 값이 안전한 기본으로 채워져야 한다.
    @EnableConfigurationProperties(OpenAiTranslationProperties.class)
    static class PropertiesConfig {
    }

    @Test
    @DisplayName("설정: 안 주면 gpt-6-luna · 4000 · 생각 정도는 비움 / 주면 그 값")
    void properties_defaults_and_binding() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfig.class)
                .run(context -> {
                    OpenAiTranslationProperties defaults = context.getBean(OpenAiTranslationProperties.class);
                    assertThat(defaults.model()).isEqualTo("gpt-6-luna");
                    assertThat(defaults.reasoningEffort()).isNull();
                    assertThat(defaults.maxCompletionTokens()).isEqualTo(4000);
                });

        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfig.class)
                .withPropertyValues(
                        "translation.openai.model=gpt-5-nano",
                        "translation.openai.reasoning-effort=minimal",
                        "translation.openai.max-completion-tokens=2000")
                .run(context -> {
                    OpenAiTranslationProperties bound = context.getBean(OpenAiTranslationProperties.class);
                    assertThat(bound.model()).isEqualTo("gpt-5-nano");
                    assertThat(bound.reasoningEffort()).isEqualTo("minimal");
                    assertThat(bound.maxCompletionTokens()).isEqualTo(2000);
                });
    }
}
