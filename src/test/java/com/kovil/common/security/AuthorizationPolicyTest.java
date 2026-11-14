package com.kovil.common.security;

import com.kovil.domain.user.enums.Permission;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * 인가는 **능력(Permission)** 으로 묻는다. 이 테스트가 코드 리뷰가 아니라 자동 검사로 있는 이유:
 * hasRole('MANAGER') 로 되돌려도 **컴파일도 되고 기존 테스트도 전부 통과한다.** 역할이 늘어난
 * 뒤에야 "행정사가 계약 화면을 본다" 같은 형태로 드러나고, 그때는 이미 배포된 뒤다.
 */
@SpringBootTest
class AuthorizationPolicyTest {

    private static final Pattern AUTHORITY =
            Pattern.compile("hasAuthority\\('PERM_([A-Z_]+)'\\)");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    @Test
    @DisplayName("@PreAuthorize 는 역할이 아니라 권한으로 묻는다 — hasRole 은 남아 있으면 안 된다")
    void guards_ask_for_permissions_not_roles() {
        List<String> violations = new ArrayList<>();

        for (Map.Entry<?, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            HandlerMethod handler = entry.getValue();
            if (!handler.getBeanType().getPackageName().startsWith("com.kovil")) {
                continue;
            }
            for (String expression : expressionsOf(handler)) {
                if (expression.contains("hasRole(") || expression.contains("hasAnyRole(")) {
                    violations.add(handler.getBeanType().getSimpleName()
                            + "#" + handler.getMethod().getName() + " → " + expression);
                }
            }
        }

        assertThat(violations)
                .describedAs("역할로 묻는 곳이 남아 있다 — hasAuthority('PERM_…') 로 바꾼다")
                .isEmpty();
    }

    // 오타는 조용히 **아무도 통과 못 하는 문**을 만든다(존재하지 않는 권한은 누구도 갖지 않는다).
    // 컨트롤러가 404 가 아니라 403 을 내므로 "기능이 사라졌다"로 보이고 원인을 찾기 어렵다.
    @Test
    @DisplayName("@PreAuthorize 가 가리키는 권한은 전부 Permission 에 실재한다")
    void every_guarded_permission_exists() {
        Set<String> known = new LinkedHashSet<>(
                Arrays.stream(Permission.values()).map(Enum::name).toList());
        List<String> unknown = new ArrayList<>();

        for (Map.Entry<?, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            HandlerMethod handler = entry.getValue();
            if (!handler.getBeanType().getPackageName().startsWith("com.kovil")) {
                continue;
            }
            for (String expression : expressionsOf(handler)) {
                Matcher matcher = AUTHORITY.matcher(expression);
                while (matcher.find()) {
                    if (!known.contains(matcher.group(1))) {
                        unknown.add(handler.getBeanType().getSimpleName() + " → " + matcher.group(1));
                    }
                }
            }
        }

        assertThat(unknown)
                .describedAs("Permission 에 없는 이름이다 — 오타면 아무도 통과하지 못한다")
                .isEmpty();
    }

    /*
     * ★ 가장 위험한 누락은 "잘못된 가드"가 아니라 **가드 없음**이다.
     *
     * SecurityConfig 는 URL 로 역할을 나누지 않는다(`anyRequest().authenticated()`). 즉
     * @PreAuthorize 가 빠진 /admin·/manager 엔드포인트는 **로그인한 아무나**(일반 유저 포함)
     * 부를 수 있다. 403 도 401 도 아니고 그냥 200 이라 로그에도 흔적이 남지 않는다.
     *
     * 지금은 0 건이다(2026-09-16 전수 확인). 이 테스트는 그 상태를 고정한다.
     */
    @Test
    @DisplayName("staff 경로(/admin·/manager)에 가드 없는 엔드포인트는 없다")
    void staff_endpoints_are_never_unguarded() {
        List<String> unguarded = new ArrayList<>();

        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            HandlerMethod handler = entry.getValue();
            if (!handler.getBeanType().getPackageName().startsWith("com.kovil")) {
                continue;
            }
            if (!expressionsOf(handler).isEmpty()) {
                continue;
            }
            for (String path : pathsOf(entry.getKey())) {
                if (path.contains("/admin/") || path.contains("/manager/")) {
                    unguarded.add(handler.getBeanType().getSimpleName()
                            + "#" + handler.getMethod().getName() + " → " + path);
                }
            }
        }

        assertThat(unguarded)
                .describedAs("가드가 없다 — 로그인한 일반 유저가 그대로 호출한다")
                .isEmpty();
    }

    private List<String> pathsOf(RequestMappingInfo info) {
        if (info.getPathPatternsCondition() == null) {
            return List.of();
        }
        return info.getPathPatternsCondition().getPatterns().stream()
                .map(Object::toString)
                .toList();
    }

    // 클래스와 메서드 양쪽을 본다 — 확인서 수정처럼 **메서드만 따로 좁히는** 자리가 있다.
    private List<String> expressionsOf(HandlerMethod handler) {
        List<String> found = new ArrayList<>();
        PreAuthorize onType = AnnotatedElementUtils
                .findMergedAnnotation(handler.getBeanType(), PreAuthorize.class);
        PreAuthorize onMethod = AnnotatedElementUtils
                .findMergedAnnotation(handler.getMethod(), PreAuthorize.class);
        if (onType != null) {
            found.add(onType.value());
        }
        if (onMethod != null) {
            found.add(onMethod.value());
        }
        return found;
    }
}
