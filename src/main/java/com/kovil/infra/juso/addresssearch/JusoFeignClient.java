package com.kovil.infra.juso.addresssearch;

import com.kovil.infra.juso.addresssearch.dto.JusoSearchResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

// 기본값을 .invalid 호스트로 — 미설정 부팅 보호.
@FeignClient(name = "jusoSearchClient", url = "${juso.search.base-url:https://juso-search.invalid}")
public interface JusoFeignClient {

    // POST 폼으로 부른다. GET 이면 승인키가 쿼리에 실리는데, Feign 은 실패할 때 요청 URL 을 예외 메시지에 그대로 넣어 키가 로그로 샌다.
    @PostMapping(value = "/addrlink/addrLinkApi.do", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    JusoSearchResponse search(@RequestBody Map<String, ?> form);
}
