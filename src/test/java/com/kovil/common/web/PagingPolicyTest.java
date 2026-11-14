package com.kovil.common.web;

import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.user.repository.UserRepository;
import com.kovil.infra.jwt.JwtTokenProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.MethodParameter;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 웹 계층은 Spring Data의 Pageable 바인딩을 쓰지 않는다(Paging 참조). 이 정책이 코드 리뷰가
// 아니라 테스트로 고정되는 이유: 위반은 컴파일도 슬라이스 테스트도 통과하고, **실서버에서
// 실 HTTP 파라미터가 들어올 때만** 터진다 — 실제로 ?sort=LATEST가 500을 냈다.
@SpringBootTest
@AutoConfigureMockMvc
class PagingPolicyTest {

    // MVC와 actuator가 각각 핸들러 매핑을 등록한다 — 이름으로 집는다.
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository userRepository;

    @Autowired
    JwtTokenProvider jwtTokenProvider;

    @Test
    @DisplayName("어떤 컨트롤러도 Pageable·Sort를 인자로 받지 않는다 — 페이지는 명시적 page/size다")
    void no_controller_binds_pageable_or_sort() {
        List<String> violations = new ArrayList<>();
        for (Map.Entry<?, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            HandlerMethod handler = entry.getValue();
            if (!handler.getBeanType().getPackageName().startsWith("com.kovil")) {
                continue;
            }
            for (MethodParameter parameter : handler.getMethodParameters()) {
                Class<?> type = parameter.getParameterType();
                if (Pageable.class.isAssignableFrom(type) || Sort.class.isAssignableFrom(type)) {
                    violations.add(handler.getBeanType().getSimpleName()
                            + "#" + handler.getMethod().getName());
                }
            }
        }
        assertThat(violations)
                .as("Pageable 웹 바인딩은 예약어(page·size·sort)로 공개 계약에 끼어든다 — Paging.of(page, size)를 써라")
                .isEmpty();
    }

    // 실사고 재현: sort=LATEST가 도메인 enum과 Pageable 정렬로 이중 해석되어
    // ORDER BY p.LATEST가 생성 → 500이었다. 실 바인딩 + 실 쿼리 조합으로만 잡힌다.
    @Test
    @Transactional
    @DisplayName("피드에 ?sort=LATEST&page=0&size=20 — 예약어 충돌 없이 200이다")
    void feed_with_sort_param_returns_200() throws Exception {
        User viewer = userRepository.save(User.builder()
                .uuid("paging-policy-viewer").status(UserStatus.ACTIVE).role(UserRole.USER)
                .preferredLanguage("ko").build());
        String token = "Bearer " + jwtTokenProvider.issueAccessToken(viewer.getUuid(), UserRole.USER);

        mockMvc.perform(get("/api/v1/community/posts")
                        .param("sort", "LATEST").param("myLanguageOnly", "true")
                        .param("page", "0").param("size", "20")
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @Transactional
    @DisplayName("페이지 파라미터가 어긋나도 500이 아니라 클램프된다")
    void out_of_range_paging_is_clamped() throws Exception {
        User viewer = userRepository.save(User.builder()
                .uuid("paging-policy-clamp").status(UserStatus.ACTIVE).role(UserRole.USER)
                .preferredLanguage("ko").build());
        String token = "Bearer " + jwtTokenProvider.issueAccessToken(viewer.getUuid(), UserRole.USER);

        mockMvc.perform(get("/api/v1/community/posts")
                        .param("page", "-1").param("size", "99999")
                        .header("Authorization", token))
                .andExpect(status().isOk());
    }
}
