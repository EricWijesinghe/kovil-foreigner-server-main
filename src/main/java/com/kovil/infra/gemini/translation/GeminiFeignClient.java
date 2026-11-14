package com.kovil.infra.gemini.translation;

import com.kovil.infra.gemini.translation.dto.request.GeminiGenerateRequest;
import com.kovil.infra.gemini.translation.dto.response.GeminiGenerateResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

@FeignClient(
        name = "geminiClient",
        url = "${translation.gemini.api-url:https://generativelanguage.googleapis.com}",
        configuration = GeminiFeignConfig.class
)

public interface GeminiFeignClient {

    @PostMapping(value = "/v1beta/models/{model}:generateContent",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    GeminiGenerateResponse generate(@PathVariable("model") String model,
                                    @RequestHeader("x-goog-api-key") String apiKey,
                                    @RequestBody GeminiGenerateRequest request);
}
