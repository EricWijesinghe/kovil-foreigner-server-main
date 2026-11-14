package com.kovil.common.ratelimit;

import com.kovil.common.security.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Optional;

@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final String GLOBAL_KEY = "global";
    private static final String GUEST_KEY = "guest";

    private final RateLimitStore store;
    private final ClientIpResolver ipResolver;
    private final RateLimitProperties properties;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!properties.enabled()) {
            return true;
        }
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }

        check(RateLimitPolicy.DEFAULT_BACKSTOP, request);

        RateLimited annotation = handlerMethod.getMethodAnnotation(RateLimited.class);
        if (annotation != null) {
            for (RateLimitPolicy policy : annotation.value()) {
                if (policy.getKeyType() == RateLimitKeyType.MANUAL) {
                    continue;
                }
                if (policy.getKeyType() == RateLimitKeyType.GUEST_GLOBAL && currentUserUuid().isPresent()) {
                    continue;
                }
                check(policy, request);
            }
        }
        return true;
    }

    private void check(RateLimitPolicy policy, HttpServletRequest request) {
        RateLimitStore.RateLimitResult result =
                store.tryConsume(policy, resolveKey(policy.getKeyType(), request));
        if (!result.allowed()) {
            throw new RateLimitException(result.retryAfterSeconds());
        }
    }

    private String resolveKey(RateLimitKeyType keyType, HttpServletRequest request) {
        return switch (keyType) {
            case GLOBAL -> GLOBAL_KEY;
            case GUEST_GLOBAL -> GUEST_KEY;
            case IP -> ipResolver.resolve(request);
            case USER, USER_OR_IP -> currentUserUuid().orElseGet(() -> ipResolver.resolve(request));
            case MANUAL -> throw new IllegalStateException("MANUAL 정책은 인터셉터가 다루지 않는다");
        };
    }

    private Optional<String> currentUserUuid() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user) {
            return Optional.of(user.userUuid());
        }
        return Optional.empty();
    }
}
