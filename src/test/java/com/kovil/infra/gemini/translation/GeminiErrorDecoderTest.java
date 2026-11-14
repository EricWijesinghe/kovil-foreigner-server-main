package com.kovil.infra.gemini.translation;

import com.kovil.infra.translation.TranslationVendorException;
import feign.Request;
import feign.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// 크리덴셜(401/403)·한도(429/503)·그 외를 갈라 놓는다 — 뭉치면 "키를 고쳐라"와 "기다려라"가 섞인다.
class GeminiErrorDecoderTest {

    private final GeminiErrorDecoder decoder = new GeminiErrorDecoder();

    private static Response response(int status) {
        return Response.builder()
                .status(status)
                .request(Request.create(Request.HttpMethod.POST, "/v1beta/models/x:generateContent",
                        Map.of(), null, StandardCharsets.UTF_8, null))
                .build();
    }

    private TranslationVendorException.Reason decode(int status) {
        return ((TranslationVendorException) decoder.decode("m", response(status))).getReason();
    }

    @Test
    @DisplayName("401·403 → 크리덴셜, 429·503 → 한도, 그 외 → 벤더 오류")
    void status_mapping() {
        assertThat(decode(401)).isEqualTo(TranslationVendorException.Reason.UNAUTHORIZED);
        assertThat(decode(403)).isEqualTo(TranslationVendorException.Reason.UNAUTHORIZED);
        assertThat(decode(429)).isEqualTo(TranslationVendorException.Reason.RATE_LIMITED);
        // 503 은 모델 과부하다 — 429 와 처방이 같다(조금 뒤에 다시).
        assertThat(decode(503)).isEqualTo(TranslationVendorException.Reason.RATE_LIMITED);
        assertThat(decode(500)).isEqualTo(TranslationVendorException.Reason.PROVIDER_ERROR);
    }
}
