package com.kovil.common.config;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// 스프링 기본 OSIV(spring.jpa.open-in-view)를 끄고 여기서 건다 — 외부 벤더를 부르는 경로만 빼려고.
// OSIV 아래에서는 요청 안 첫 조회가 잡은 DB 커넥션을 요청이 끝날 때까지 쥔다(DELAYED_ACQUISITION_AND_HOLD).
// 카카오 · 번역 벤더가 느리면 그동안 커넥션이 묶여 풀(운영 10개)이 마르고 API 전체가 멈춘다.
@Configuration
public class OpenEntityManagerInViewConfig {

    static final String[] VENDOR_CALL_PATHS = {
            "/api/v1/listings/*/nearby",
            "/api/v1/listings/*/description/translate"
    };

    @Bean
    public WebMvcConfigurer openEntityManagerInViewWebMvcConfigurer(EntityManagerFactory entityManagerFactory) {
        OpenEntityManagerInViewInterceptor interceptor = new OpenEntityManagerInViewInterceptor();
        interceptor.setEntityManagerFactory(entityManagerFactory);
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addWebRequestInterceptor(interceptor).excludePathPatterns(VENDOR_CALL_PATHS);
            }
        };
    }
}
