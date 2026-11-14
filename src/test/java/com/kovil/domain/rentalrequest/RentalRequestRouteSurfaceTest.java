package com.kovil.domain.rentalrequest;

import com.kovil.domain.user.enums.UserRole;
import com.kovil.infra.jwt.JwtTokenProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * 임대 요청의 경로 표면을 **진짜 컨텍스트의 매핑 전체**에서 본다 — 슬라이스 테스트는 자기가 실은 컨트롤러만 알아,
 * 옛 담당자 경로가 다른 컨트롤러로 되살아나도 모른다.
 *   · 담당자 경로(/api/v1/manager/**)는 하나도 없다 — 관리자 토큰으로 불러도 404 다.
 *   · 관리자 열람은 GET 뿐이고 RENTAL_REQUEST_VIEW 가 지킨다. 파트너 처리는 RENTAL_REQUEST_HANDLE 이 지킨다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RentalRequestRouteSurfaceTest {

    private static final String ADMIN_PREFIX = "/api/v1/admin/rental-requests";
    private static final String PARTNER_PREFIX = "/api/v1/partner/rental-requests";

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JwtTokenProvider jwtTokenProvider;

    private record Route(String path, Set<RequestMethod> methods, List<String> guards, String handler) {
    }

    private List<Route> routes() {
        List<Route> routes = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            HandlerMethod handler = entry.getValue();
            if (!handler.getBeanType().getPackageName().startsWith("com.kovil")
                    || entry.getKey().getPathPatternsCondition() == null) {
                continue;
            }
            List<String> guards = new ArrayList<>();
            PreAuthorize onType = AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(), PreAuthorize.class);
            PreAuthorize onMethod = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), PreAuthorize.class);
            if (onType != null) {
                guards.add(onType.value());
            }
            if (onMethod != null) {
                guards.add(onMethod.value());
            }
            for (Object pattern : entry.getKey().getPathPatternsCondition().getPatterns()) {
                routes.add(new Route(pattern.toString(), entry.getKey().getMethodsCondition().getMethods(), guards,
                        handler.getBeanType().getSimpleName() + "#" + handler.getMethod().getName()));
            }
        }
        return routes;
    }

    @Test
    @DisplayName("담당자 경로(/api/v1/manager/**)는 매핑이 하나도 없다")
    void no_manager_routes() {
        assertThat(routes()).filteredOn(route -> route.path().startsWith("/api/v1/manager"))
                .extracting(Route::handler).isEmpty();
    }

    @Test
    @DisplayName("관리자 임대 요청 열람은 GET 두 개뿐이고 전부 RENTAL_REQUEST_VIEW 가 지킨다")
    void admin_routes_are_read_only_and_view_guarded() {
        List<Route> admin = routes().stream().filter(route -> route.path().startsWith(ADMIN_PREFIX)).toList();

        assertThat(admin).extracting(Route::path)
                .containsExactlyInAnyOrder(ADMIN_PREFIX, ADMIN_PREFIX + "/{requestUuid}");
        assertThat(admin).allSatisfy(route -> {
            assertThat(route.methods()).as(route.handler()).containsExactly(RequestMethod.GET);
            assertThat(route.guards()).as(route.handler())
                    .containsExactly("hasAuthority('PERM_RENTAL_REQUEST_VIEW')");
        });
    }

    @Test
    @DisplayName("파트너 임대 요청 처리 여섯 경로는 전부 RENTAL_REQUEST_HANDLE 이 지킨다")
    void partner_routes_are_handle_guarded() {
        List<Route> partner = routes().stream().filter(route -> route.path().startsWith(PARTNER_PREFIX)).toList();

        assertThat(partner).hasSize(6);
        assertThat(partner).allSatisfy(route -> assertThat(route.guards()).as(route.handler())
                .containsExactly("hasAuthority('PERM_RENTAL_REQUEST_HANDLE')"));
    }

    @Test
    @DisplayName("옛 담당자 경로는 관리자 토큰으로 불러도 404 다(인가 오류가 아니라 없는 경로)")
    void retired_manager_paths_are_not_found() throws Exception {
        String admin = "Bearer " + jwtTokenProvider.issueAccessToken("route-surface-admin", UserRole.ADMIN);
        for (RequestBuilder request : List.of(
                get("/api/v1/manager/rental-requests").header("Authorization", admin),
                get("/api/v1/manager/rental-requests/r-1").header("Authorization", admin),
                post("/api/v1/manager/rental-requests/r-1/start").header("Authorization", admin),
                post("/api/v1/manager/rental-requests/r-1/assign").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"managerUuid\":\"m-1\"}"),
                post("/api/v1/manager/rental-requests/r-1/memos").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"메모\"}"),
                post("/api/v1/manager/rental-requests/r-1/close").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"종료\"}"),
                get("/api/v1/manager/staff").header("Authorization", admin))) {
            mockMvc.perform(request)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
        }
    }
}
