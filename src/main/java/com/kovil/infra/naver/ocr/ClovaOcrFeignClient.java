package com.kovil.infra.naver.ocr;

import com.kovil.infra.naver.ocr.dto.request.ClovaOcrRequest;
import com.kovil.infra.naver.ocr.dto.response.ClovaOcrResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(
        name = "clovaOcrClient",
        url = "${clova.ocr.invoke-url:https://clova.invalid}",
        configuration = ClovaOcrFeignConfig.class
)
public interface ClovaOcrFeignClient {

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ClovaOcrResponse recognize(@RequestBody ClovaOcrRequest request);
}
