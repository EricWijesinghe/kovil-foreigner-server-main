package com.kovil.common.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★ 에러 문구 번들 완전성 — 코드가 늘 때 번역이 따라오지 않으면 여기서 잡는다.
 *
 * <p>실사고 배경: {@code CONTRACT_SCHEDULE_LOCKED}를 추가하면서 번들 두 개를 모두 빠뜨렸다.
 * 컴파일도 테스트도 통과했는데, {@code ErrorMessageResolver}가 키를 못 찾으면 예외 대신
 * enum의 defaultMessage(영문)로 조용히 폴백하기 때문이다. 그 결과 **한국어 전용 관리자 화면에
 * 영어 문구**가 떴다. 기존 ErrorMessageResolverTest는 로케일 해석만 보고 완전성은 보지 않았다.
 */
class ErrorBundleCompletenessTest {

    private static final String BASE_PACKAGE = "com.kovil";

    private static Properties load(String resource) {
        Properties properties = new Properties();
        try (InputStream stream = ErrorBundleCompletenessTest.class.getResourceAsStream(resource)) {
            assertThat(stream).as("%s 가 클래스패스에 있어야 한다", resource).isNotNull();
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(resource + " 를 읽지 못했다", e);
        }
        return properties;
    }

    /** ErrorCode를 구현한 모든 enum의 상수를 모은다 — 도메인마다 파일이 갈려 있어 스캔이 유일한 전수 방법이다. */
    private static List<ErrorCode> allErrorCodes() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false) {
                    @Override
                    protected boolean isCandidateComponent(
                            AnnotatedBeanDefinition beanDefinition) {
                        return true; // enum·인터페이스도 후보로 — 기본 구현은 구체 클래스만 통과시킨다
                    }
                };
        scanner.addIncludeFilter(new AssignableTypeFilter(ErrorCode.class));

        List<ErrorCode> codes = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(BASE_PACKAGE)) {
            Class<?> type;
            try {
                type = Class.forName(definition.getBeanClassName());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
            if (!type.isEnum() || !ErrorCode.class.isAssignableFrom(type)) {
                continue;
            }
            for (Object constant : type.getEnumConstants()) {
                codes.add((ErrorCode) constant);
            }
        }
        assertThat(codes).as("ErrorCode enum을 하나도 못 찾았다 — 스캔이 깨진 것이다").isNotEmpty();
        return codes;
    }

    @Test
    @DisplayName("모든 ErrorCode가 기본·한국어 번들 양쪽에 문구를 갖는다")
    void every_error_code_has_a_message_in_both_bundles() {
        Properties base = load("/messages/errors.properties");
        Properties korean = load("/messages/errors_ko.properties");

        Set<String> missingInBase = new TreeSet<>();
        Set<String> missingInKorean = new TreeSet<>();
        for (ErrorCode code : allErrorCodes()) {
            if (!base.containsKey(code.getCode())) {
                missingInBase.add(code.getCode());
            }
            if (!korean.containsKey(code.getCode())) {
                missingInKorean.add(code.getCode());
            }
        }

        // 없으면 예외가 아니라 영문 defaultMessage로 조용히 새어 나간다 — 그래서 여기서 막는다.
        assertThat(missingInBase).as("기본(영문) 번들에 빠진 코드").isEmpty();
        assertThat(missingInKorean).as("한국어 번들에 빠진 코드 — 관리자 화면에 영어가 뜬다").isEmpty();
    }

    // 키만 있고 값이 비면 완전성 검사를 지나 빈 문구가 화면에 뜬다.
    @Test
    @DisplayName("두 번들 모두 빈 문구가 없다")
    void no_blank_messages() {
        for (String resource : new String[]{"/messages/errors.properties", "/messages/errors_ko.properties"}) {
            Properties bundle = load(resource);
            Set<String> blank = new TreeSet<>();
            for (String key : bundle.stringPropertyNames()) {
                if (bundle.getProperty(key).isBlank()) {
                    blank.add(key);
                }
            }
            assertThat(blank).as("%s 의 빈 문구", resource).isEmpty();
        }
    }

    // 그린 구역이 너무 넓을 때는 '다시 그려라'가 아니라 '좁혀라'라고 말해야 한다 — 문구를 베껴 넣으면 사용자가 같은 구역을 다시 그린다.
    @Test
    @DisplayName("그린 구역 두 에러의 문구는 서로 다르다")
    void polygon_messages_are_distinct() {
        for (String resource : new String[]{"/messages/errors.properties", "/messages/errors_ko.properties"}) {
            Properties bundle = load(resource);
            assertThat(bundle.getProperty("LISTING_POLYGON_TOO_LARGE")).as(resource)
                    .isNotEqualTo(bundle.getProperty("LISTING_POLYGON_INVALID"));
        }
    }

    @Test
    @DisplayName("두 번들의 키 집합이 정확히 같다 — 한쪽만 늘어난 채로 두지 않는다")
    void bundles_have_identical_keys() {
        Set<String> base = new TreeSet<>(load("/messages/errors.properties").stringPropertyNames());
        Set<String> korean = new TreeSet<>(load("/messages/errors_ko.properties").stringPropertyNames());

        assertThat(korean).as("한국어 번들에만 없는 키").containsAll(base);
        assertThat(base).as("기본 번들에만 없는 키").containsAll(korean);
    }
}
