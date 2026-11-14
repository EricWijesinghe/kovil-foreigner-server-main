package com.kovil.infra.oauth.facebook;

import com.kovil.infra.oauth.facebook.dto.FacebookUserInfoResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "facebookUserInfoClient", url = "${oauth.facebook.user-info-uri}")
public interface FacebookUserInfoClient {

    // Graph API는 fields를 명시하지 않으면 id와 name만 준다.
    @GetMapping
    FacebookUserInfoResponse getUserInfo(
            @RequestParam("fields") String fields,
            @RequestParam("access_token") String accessToken
    );
}
