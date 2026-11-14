package com.kovil.infra.oauth.google;

import com.kovil.infra.oauth.google.dto.GoogleUserInfoResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;

@FeignClient(name = "googleUserInfoClient", url = "${oauth.google.user-info-uri}")
public interface GoogleUserInfoClient {

    @GetMapping
    GoogleUserInfoResponse getUserInfo(@RequestHeader("Authorization") String authorization);
}
