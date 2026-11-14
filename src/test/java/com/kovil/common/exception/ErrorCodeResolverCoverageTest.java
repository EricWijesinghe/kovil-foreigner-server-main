package com.kovil.common.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ErrorCodeResolver 등록 전수 대조 (AUDIT_2026_08_26).
 *
 * <p>★ enum을 만들고 등록을 잊으면 컴파일도 테스트도 통과하는데, 그 도메인의 검증 실패가
 * 전부 INVALID_REQUEST로 뭉개진다 — 커뮤니티·마스터·지오코딩이 실제로 그렇게 새고 있었다.
 * 이 테스트는 클래스패스를 훑어 "구현했는데 등록 안 한 enum"을 기계적으로 잡는다.
 */
class ErrorCodeResolverCoverageTest {

    @Test
    @DisplayName("ErrorCode를 구현한 모든 enum이 리졸버에 등록돼 있다 — 빠지면 그 도메인 에러가 뭉개진다")
    void every_error_code_enum_is_registered() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(ErrorCode.class));

        Set<String> implemented = scanner
                .findCandidateComponents("com.kovil").stream()
                .map(BeanDefinition::getBeanClassName)
                .collect(Collectors.toSet());

        ErrorCodeResolver resolver = new ErrorCodeResolver();
        List<String> unresolved = implemented.stream()
                .filter(className -> {
                    try {
                        Object[] constants = Class.forName(className).getEnumConstants();
                        if (constants == null || constants.length == 0) return false; // enum 아님
                        String code = ((ErrorCode) constants[0]).getCode();
                        ErrorCode resolved = resolver.resolve(code);
                        // 등록 안 된 enum은 INVALID_REQUEST 폴백으로 떨어진다(자기 코드가 그 이름이 아닌 한).
                        return !resolved.getCode().equals(code);
                    } catch (ClassNotFoundException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .sorted()
                .toList();

        assertThat(unresolved)
                .as("리졸버에 등록되지 않은 ErrorCode enum — ERROR_CODE_CLASSES에 추가할 것")
                .isEmpty();
    }
}
