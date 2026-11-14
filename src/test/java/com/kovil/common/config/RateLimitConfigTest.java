package com.kovil.common.config;

import com.kovil.common.ratelimit.ClientIpResolver;
import com.kovil.common.ratelimit.RateLimitInterceptor;
import com.kovil.common.ratelimit.RateLimitProperties;
import com.kovil.common.ratelimit.RateLimitStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.config.annotation.InterceptorRegistration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class RateLimitConfigTest {

    @Mock
    private InterceptorRegistry registry;

    @Mock
    private InterceptorRegistration registration;

    @Mock
    private RateLimitStore store;

    @Test
    @DisplayName("인터셉터는 /api/** 에만 등록된다 — actuator·error는 리미터를 타지 않는다")
    void interceptor_registeredOnApiPathsOnly() {
        RateLimitConfig config = new RateLimitConfig();
        RateLimitInterceptor interceptor = config.rateLimitInterceptor(
                store,
                new ClientIpResolver(new RateLimitProperties(false, null, null)),
                new RateLimitProperties(false, null, null));

        given(registry.addInterceptor(any())).willReturn(registration);

        config.rateLimitWebMvcConfigurer(interceptor).addInterceptors(registry);

        then(registry).should().addInterceptor(interceptor);
        then(registration).should().addPathPatterns("/api/**");
    }
}
