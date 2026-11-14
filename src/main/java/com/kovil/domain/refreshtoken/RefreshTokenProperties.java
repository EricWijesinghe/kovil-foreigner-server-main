package com.kovil.domain.refreshtoken;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "refresh-token")
public record RefreshTokenProperties(
        Duration ttl,
        int maxActivePerUser,
        // 회전 직후 옛 토큰 재사용을 응답 유실로 봐 주는 창. 이 안이고 자식이 안 쓰였으면 그 자식만 끊음.
        Duration reuseGrace
) {
}
