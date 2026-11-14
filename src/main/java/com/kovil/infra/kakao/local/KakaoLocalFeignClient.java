package com.kovil.infra.kakao.local;

import com.kovil.infra.kakao.local.dto.response.KakaoCategoryResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(
        name = "kakaoLocalFeignClient",
        url = "${kakao.local.base-url:https://dapi.kakao.com}",
        configuration = KakaoLocalFeignConfig.class
)
public interface KakaoLocalFeignClient {

    @GetMapping("/v2/local/search/category.json")
    KakaoCategoryResponse searchCategory(
            @RequestParam("category_group_code") String categoryGroupCode,
            @RequestParam("x") String x,
            @RequestParam("y") String y,
            @RequestParam("radius") int radius,
            @RequestParam("size") int size,
            @RequestParam("sort") String sort
    );

}
