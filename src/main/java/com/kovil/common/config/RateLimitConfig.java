package com.kovil.common.config;

import com.kovil.common.ratelimit.ClientIpResolver;
import com.kovil.common.ratelimit.RateLimitInterceptor;
import com.kovil.common.ratelimit.RateLimitProperties;
import com.kovil.common.ratelimit.RateLimitStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// 인터셉터도 등록부도 전부 @Bean인 이유: @WebMvcTest 슬라이스는 WebMvcConfigurer·HandlerInterceptor
// "타입"의 컴포넌트를 스캔에 포함한다. 클래스 레벨 @Component면 모든 컨트롤러 슬라이스 테스트가
// 의존성(@Component 스토어 — 슬라이스 비스캔)을 찾다 깨진다. @Configuration 자체는 슬라이스에 안 잡힌다.
@Configuration
public class RateLimitConfig {

    @Bean
    public RateLimitInterceptor rateLimitInterceptor(
            RateLimitStore store, ClientIpResolver ipResolver, RateLimitProperties properties) {
        return new RateLimitInterceptor(store, ipResolver, properties);
    }

    @Bean
    public WebMvcConfigurer rateLimitWebMvcConfigurer(RateLimitInterceptor rateLimitInterceptor) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(rateLimitInterceptor).addPathPatterns("/api/**");
            }
        };
    }
}
