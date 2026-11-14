package com.kovil.infra.gemini.translation;

import com.kovil.infra.gemini.translation.dto.request.GeminiGenerateRequest;
import com.kovil.infra.gemini.translation.dto.response.GeminiGenerateResponse;
import com.kovil.infra.translation.TranslationVendorException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/*
 * ★★ 이 어댑터의 계약은 하나다 — **절대 예외를 던지지 않는다.** 실패하면 null 이고,
 *    위에서 원문 폴백으로 흡수된다. 여기서 예외가 올라가면 매물 상세가 통째로 500 이 된다.
 */
class GeminiTranslationClientTest {

    private GeminiFeignClient feignClient;
    private GeminiTranslationClient client;

    @BeforeEach
    void setUp() {
        feignClient = mock(GeminiFeignClient.class);
        client = new GeminiTranslationClient(feignClient,
                new GeminiProperties("test-key", "gemini-3.1-flash-lite", 20));
    }

    private static GeminiGenerateResponse reply(String text) {
        return new GeminiGenerateResponse(List.of(new GeminiGenerateResponse.Candidate(
                new GeminiGenerateResponse.Content(List.of(
                        new GeminiGenerateResponse.Part(text))), "STOP")));
    }

    @Test
    @DisplayName("첫 후보의 본문을 공백 없이 돌려준다")
    void returns_first_candidate_text() {
        given(feignClient.generate(any(), any(), any())).willReturn(reply("  A studio.\n"));

        assertThat(client.translate("원룸입니다.", "en")).isEqualTo("A studio.");
    }

    // 키는 헤더로 간다 — 쿼리스트링에 실으면 접근 로그·프록시 로그에 그대로 남는다.
    @Test
    @DisplayName("모델과 API 키를 설정대로 싣는다")
    void sends_model_and_key() {
        given(feignClient.generate(any(), any(), any())).willReturn(reply("A studio."));

        client.translate("원룸입니다.", "en");

        verify(feignClient).generate(eq("gemini-3.1-flash-lite"), eq("test-key"), any());
    }

    /*
     * ★ 대상 언어가 본문에 안 실리면 모델은 아무 언어로나 번역한다 —
     *   베트남 학생에게 영어가 가도 아무도 모른다(둘 다 한국어가 아니라서 "번역됨"으로 보인다).
     */
    @Test
    @DisplayName("요청 본문에 대상 언어 코드와 원문이 함께 실린다")
    void carries_target_language_and_source_text() {
        given(feignClient.generate(any(), any(), any())).willReturn(reply("Phòng trọ."));
        ArgumentCaptor<GeminiGenerateRequest> captor =
                ArgumentCaptor.forClass(GeminiGenerateRequest.class);

        client.translate("원룸입니다.", "vi");

        verify(feignClient).generate(any(), any(), captor.capture());
        String sent = captor.getValue().contents().get(0).parts().get(0).text();
        assertThat(sent).contains("vi").contains("원룸입니다.");
        // 번역은 창작이 아니다 — 같은 입력에 같은 출력이어야 캐시가 의미를 갖는다.
        assertThat(captor.getValue().generationConfig().temperature()).isZero();
    }

    @Test
    @DisplayName("벤더 오류는 null 로 삼킨다 — 예외를 올리지 않는다")
    void vendor_error_becomes_null() {
        given(feignClient.generate(any(), any(), any()))
                .willThrow(new TranslationVendorException(TranslationVendorException.Reason.RATE_LIMITED));

        assertThat(client.translate("원룸입니다.", "en")).isNull();
    }

    @Test
    @DisplayName("예상 못 한 예외도 null 로 삼킨다")
    void unexpected_error_becomes_null() {
        given(feignClient.generate(any(), any(), any())).willThrow(new IllegalStateException("boom"));

        assertThat(client.translate("원룸입니다.", "en")).isNull();
    }

    // 응답 모양이 조금만 달라도 NPE 가 나고, 그건 500 이 되어 매물 조회를 막는다.
    @Test
    @DisplayName("후보·본문·조각이 비어도 null 이다")
    void empty_shapes_become_null() {
        given(feignClient.generate(any(), any(), any())).willReturn(new GeminiGenerateResponse(null));
        assertThat(client.translate("원룸입니다.", "en")).isNull();

        given(feignClient.generate(any(), any(), any())).willReturn(new GeminiGenerateResponse(List.of()));
        assertThat(client.translate("원룸입니다.", "en")).isNull();

        given(feignClient.generate(any(), any(), any())).willReturn(new GeminiGenerateResponse(
                List.of(new GeminiGenerateResponse.Candidate(null, "SAFETY"))));
        assertThat(client.translate("원룸입니다.", "en")).isNull();

        given(feignClient.generate(any(), any(), any())).willReturn(reply("   "));
        assertThat(client.translate("원룸입니다.", "en")).isNull();
    }

    @Test
    @DisplayName("원문이나 언어가 비면 벤더를 부르지 않는다")
    void blank_input_short_circuits() {
        assertThat(client.translate("  ", "en")).isNull();
        assertThat(client.translate("원룸입니다.", null)).isNull();
        verify(feignClient, Mockito.never()).generate(any(), any(), any());
    }

    @Test
    @DisplayName("엔진 이름은 gemini — 캐시 행에 박혀 품질 추적의 유일한 단서가 된다")
    void engine_name() {
        assertThat(client.engineName()).isEqualTo("gemini");
    }
}
