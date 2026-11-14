package com.kovil.common.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.condition.PathPatternsRequestCondition;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * ★★ `RateLimitYamlBindingTest` 는 **정책에 숫자가 있는지**만 본다. 그것만으로는
 *    **아무 핸들러에도 안 붙은 정책**을 잡지 못한다 — enum 에 있고 yaml 에 있고 부팅도 되는데
 *    그 엔드포인트만 **한도 없이 열려 있다.** 비용이 드는 경로에서 그건 그대로 청구서다.
 *
 * 이 테스트가 보는 것은 "선언"이 아니라 **배선**이다.
 */
@SpringBootTest
class RateLimitWiringPolicyTest {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    /*
     * MANUAL 은 인터셉터가 다루지 않는다 — 번호 해시처럼 요청에서 못 뽑는 키라 코드가 직접 센다.
     * DEFAULT_BACKSTOP 은 애너테이션 없이 **모든 요청**에 걸린다.
     */
    private static final Set<RateLimitPolicy> NOT_ANNOTATION_DRIVEN = EnumSet.of(
            RateLimitPolicy.DEFAULT_BACKSTOP);

    @Test
    @DisplayName("애너테이션으로 거는 정책은 하나도 빠짐없이 어떤 핸들러엔가 붙어 있다")
    void every_policy_is_actually_wired() {
        Set<RateLimitPolicy> wired = new HashSet<>();
        for (HandlerMethod handler : handlerMapping.getHandlerMethods().values()) {
            RateLimited annotation = handler.getMethodAnnotation(RateLimited.class);
            if (annotation != null) {
                wired.addAll(Arrays.asList(annotation.value()));
            }
        }

        List<RateLimitPolicy> orphans = Arrays.stream(RateLimitPolicy.values())
                .filter(p -> p.getKeyType() != RateLimitKeyType.MANUAL)
                .filter(p -> !NOT_ANNOTATION_DRIVEN.contains(p))
                .filter(p -> !wired.contains(p))
                .toList();

        assertThat(orphans)
                .as("enum·yaml 에만 있고 핸들러에 안 붙은 정책이다 — 그 엔드포인트는 한도 없이 열려 있다")
                .isEmpty();
    }

    /*
     * ★ 비용과 노출이 걸린 경로는 **경로째로 못 박는다.** 위 테스트는 "어딘가 붙어 있다"까지만
     *   보장하므로, 정책이 엉뚱한 엔드포인트로 옮겨 가도 통과한다.
     */
    @Test
    @DisplayName("비용·노출 경로에 기대한 정책이 그대로 붙어 있다")
    void cost_and_exposure_endpoints_carry_their_policies() {
        Map<String, Set<RateLimitPolicy>> actual = policiesByRoute();

        // 호출당 과금 — 캐시가 막아 주는 것은 같은 매물·같은 언어·같은 버전뿐이다.
        assertThat(actual.get("POST /api/v1/listings/{listingUuid}/description/translate"))
                .containsExactlyInAnyOrder(
                        RateLimitPolicy.LISTING_TRANSLATE, RateLimitPolicy.LISTING_TRANSLATE_GLOBAL);

        // 중개사 번호가 나가는 유일한 경로. 한도가 없으면 상세에서 번호를 뺀 방어가 통째로 무의미하다.
        assertThat(actual.get("POST /api/v1/listings/{listingUuid}/inquiries"))
                .containsExactlyInAnyOrder(
                        RateLimitPolicy.LISTING_INQUIRY, RateLimitPolicy.LISTING_INQUIRY_GLOBAL);

        // 비로그인 상세마다 카카오를 최대 8번 부른다 — 개인 축 + 하루 카카오 총량 축.
        assertThat(actual.get("GET /api/v1/listings/{listingUuid}/nearby"))
                .containsExactlyInAnyOrder(
                        RateLimitPolicy.LISTING_NEARBY, RateLimitPolicy.LISTING_NEARBY_GLOBAL);

        // 신고 남발은 관리자 대기열을 덮는다.
        assertThat(actual.get("POST /api/v1/listings/{listingUuid}/reports"))
                .containsExactly(RateLimitPolicy.LISTING_REPORT);

        assertThat(actual.get("POST /api/v1/broker/listings"))
                .containsExactly(RateLimitPolicy.LISTING_CREATE);

        /*
         * ★ 수정은 "남용 방지"가 아니라 **비용 방어**다 — 설명을 고치면 description_version 이 올라
         *   그 매물의 번역 캐시가 전부 무효화된다. 비용을 유발하는 쪽은 중개사인데 과금은
         *   유저의 번역 요청에서 난다.
         */
        assertThat(actual.get("PUT /api/v1/broker/listings/{listingUuid}"))
                .containsExactly(RateLimitPolicy.LISTING_UPDATE);

        /*
         * ★ 비로그인 공개 · 호출당 과금(가입 중 소재지 검색). 순서까지 못 박는다 — 앞에서 막히면 뒤 카운터를 쓰지 않으니,
         *   비로그인 전체 상한(GUEST)이 앞에 오면 IP 하나가 분당 백스톱 속도로 하루치를 먹어 그날 가입하는 중개사 전부가 429 다.
         */
        assertThat(orderedPoliciesOf("GET /api/v1/partner/address/search"))
                .containsExactly(RateLimitPolicy.ADDRESS_SEARCH, RateLimitPolicy.ADDRESS_SEARCH_DAILY,
                        RateLimitPolicy.ADDRESS_SEARCH_GUEST, RateLimitPolicy.ADDRESS_SEARCH_BURST,
                        RateLimitPolicy.ADDRESS_SEARCH_GLOBAL);
        // 고른 주소의 좌표 — 호출마다 네이버 과금
        assertThat(orderedPoliciesOf("GET /api/v1/partner/address/coordinate"))
                .containsExactly(RateLimitPolicy.ADDRESS_COORDINATE, RateLimitPolicy.ADDRESS_COORDINATE_DAILY,
                        RateLimitPolicy.ADDRESS_COORDINATE_GLOBAL);
    }

    // 인터셉터는 어노테이션 순서대로 판정하고 첫 거절에서 멈춘다 — 전역 축이 앞이면 한 사람의 폭주가 전역 카운터를 먼저 먹는다.
    @Test
    @DisplayName("전역 캡(GLOBAL · GUEST_GLOBAL)은 개인 축 정책보다 뒤에 걸린다")
    void global_caps_come_after_per_caller_limits() {
        List<String> violations = new ArrayList<>();
        handlerMapping.getHandlerMethods().forEach((info, handler) -> {
            RateLimited annotation = handler.getMethodAnnotation(RateLimited.class);
            if (annotation == null) {
                return;
            }
            boolean seenGlobal = false;
            for (RateLimitPolicy policy : annotation.value()) {
                if (isGlobalCap(policy)) {
                    seenGlobal = true;
                } else if (isPerCaller(policy) && seenGlobal) {
                    violations.addAll(routesOf(info));
                    break;
                }
            }
        });

        assertThat(violations).as("전역 캡 뒤에 개인 축이 온 경로다").isEmpty();
    }

    /*
     * ★ 전역 캡이 있는 축은 **개인 한도와 짝으로** 걸려야 한다. 전역만 걸면 한 사람이 캡을 통째로
     *   먹고, 개인만 걸면 계정을 양산해 우회한다 — 번호 수집도 번역 과금도 둘 다 그 모양이다.
     */
    @Test
    @DisplayName("전역 캡은 개인 한도와 같은 핸들러에 함께 걸린다")
    void global_caps_are_paired_with_a_per_user_limit() {
        List<String> violations = new ArrayList<>();
        policiesByRoute().forEach((route, policies) -> {
            boolean hasGlobal = policies.stream().anyMatch(RateLimitWiringPolicyTest::isGlobalCap);
            boolean hasPerCaller = policies.stream().anyMatch(RateLimitWiringPolicyTest::isPerCaller);
            if (hasGlobal && !hasPerCaller) {
                violations.add(route);
            }
        });

        assertThat(violations)
                .as("전역 캡만 걸린 경로다 — 한 사람이 하루치를 통째로 먹는다")
                .isEmpty();
    }

    // 비로그인 전체 한 키(GUEST_GLOBAL)도 전역 캡이다 — 개인 축으로 세면 그것만 건 경로가 짝 검사를 지나간다.
    private static boolean isGlobalCap(RateLimitPolicy policy) {
        return policy.getKeyType() == RateLimitKeyType.GLOBAL || policy.getKeyType() == RateLimitKeyType.GUEST_GLOBAL;
    }

    private static boolean isPerCaller(RateLimitPolicy policy) {
        return switch (policy.getKeyType()) {
            case IP, USER, USER_OR_IP -> true;
            case GLOBAL, GUEST_GLOBAL, MANUAL -> false;
        };
    }

    private List<RateLimitPolicy> orderedPoliciesOf(String route) {
        List<RateLimitPolicy> found = new ArrayList<>();
        handlerMapping.getHandlerMethods().forEach((info, handler) -> {
            RateLimited annotation = handler.getMethodAnnotation(RateLimited.class);
            if (annotation != null && routesOf(info).contains(route)) {
                found.addAll(Arrays.asList(annotation.value()));
            }
        });
        return found;
    }

    private Map<String, Set<RateLimitPolicy>> policiesByRoute() {
        Map<String, Set<RateLimitPolicy>> routes = new HashMap<>();
        handlerMapping.getHandlerMethods().forEach((info, handler) -> {
            RateLimited annotation = handler.getMethodAnnotation(RateLimited.class);
            if (annotation == null) {
                return;
            }
            for (String route : routesOf(info)) {
                routes.computeIfAbsent(route, k -> new HashSet<>())
                        .addAll(Arrays.asList(annotation.value()));
            }
        });
        return routes;
    }

    private List<String> routesOf(RequestMappingInfo info) {
        PathPatternsRequestCondition patterns = info.getPathPatternsCondition();
        if (patterns == null) {
            return List.of();
        }
        List<String> routes = new ArrayList<>();
        for (var method : info.getMethodsCondition().getMethods()) {
            patterns.getPatternValues().forEach(path -> routes.add(method.name() + " " + path));
        }
        return routes;
    }
}
