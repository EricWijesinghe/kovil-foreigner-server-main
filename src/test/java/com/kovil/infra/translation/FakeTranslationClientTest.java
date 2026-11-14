package com.kovil.infra.translation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * ★★ 페이크는 **번역한 척하지 않는다.** 원문을 그대로 돌려주면 키 없이 배포됐을 때
 *    "번역이 잘 안 됐네"로 넘어가지만, 접두사가 붙으면 **첫 화면에서 들킨다.**
 */
class FakeTranslationClientTest {

    private final FakeTranslationClient client = new FakeTranslationClient();

    @Test
    @DisplayName("번역한 척하지 않는다 — 페이크임이 결과에 드러난다")
    void marks_itself_as_fake() {
        String out = client.translate("원룸입니다.", "en");

        assertThat(out).contains("FAKE").contains("en").contains("원룸입니다.");
        assertThat(out).isNotEqualTo("원룸입니다.");
    }

    @Test
    @DisplayName("빈 입력은 null — 실제 구현체와 같은 계약이다")
    void blank_input_is_null() {
        assertThat(client.translate(null, "en")).isNull();
        assertThat(client.translate("   ", "en")).isNull();
    }

    @Test
    @DisplayName("엔진 이름이 fake 라 캐시 행만 봐도 가짜 번역을 골라낼 수 있다")
    void engine_name() {
        assertThat(client.engineName()).isEqualTo("fake");
    }
}
