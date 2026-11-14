package com.kovil.domain.community.service.support;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

// 실데이터 없이 정한 초기 계수라 재조정을 전제로 설정에 뺐다 — 배포 없이 바꾼다.
// 화면마다 계수를 각자 들면 "인기"가 화면마다 다른 이야기를 하게 되므로 출처는 이 레코드 하나다.
@ConfigurationProperties(prefix = "community.popular")
public record CommunityPopularProperties(
        double likeWeight,
        double commentWeight,
        double decayExponent,
        Duration cacheTtl,
        int poolSize
) {
    public CommunityPopularProperties {
        if (poolSize <= 0) {
            throw new IllegalStateException("community.popular.pool-size는 1 이상이어야 합니다: " + poolSize);
        }
    }
}
