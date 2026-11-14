package com.kovil.common.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★ 모든 언어 번들(errors_* · notifications_*)이 기본(영문) 번들과 같은 모양인지 본다.
 *
 * <p>ErrorBundleCompletenessTest 는 ko 만 본다. 언어가 20개가 된 뒤로는 한 언어만 키가 빠져도
 * 그 언어 사용자에게 조용히 영어가 뜬다(예외 없음). 같은 규칙을 Rust 린터(core/kovil-l10n lint)도 CI 에서 본다 —
 * 이 테스트는 Java 빌드만 돌려도 걸리게 하려고 둔다.
 */
class LocaleBundleParityTest {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d+)[},]");
    // MessageFormat 에서 홑따옴표는 두 개씩만 글자다('' -> '). 홀수 개가 이어지면 마지막 하나가 인용을 열어
    // 그 뒤 {1} 이 글자 그대로 나간다 — 연속 따옴표 묶음의 길이가 홀수인지 본다.
    private static final Pattern QUOTE_RUN = Pattern.compile("'+");
    private static final int MIN_LOCALES = 20;

    static Stream<String> bundles() {
        return Stream.of("errors", "notifications");
    }

    private static Properties load(Resource resource) {
        Properties p = new Properties();
        try (InputStream in = resource.getInputStream()) {
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(resource.getFilename() + " 를 읽지 못했다", e);
        }
        return p;
    }

    private static Resource[] localeFiles(String bundle) throws IOException {
        return new PathMatchingResourcePatternResolver().getResources("classpath:/messages/" + bundle + "_*.properties");
    }

    private static Set<Integer> placeholders(String value) {
        Set<Integer> found = new TreeSet<>();
        Matcher m = PLACEHOLDER.matcher(value);
        while (m.find()) {
            found.add(Integer.parseInt(m.group(1)));
        }
        return found;
    }

    @ParameterizedTest
    @MethodSource("bundles")
    @DisplayName("모든 언어 번들이 기본 번들과 같은 키 · 같은 자리표시자를 갖고 빈 문구가 없다")
    void every_locale_matches_the_base(String bundle) throws IOException {
        Properties base = load(new PathMatchingResourcePatternResolver()
                .getResource("classpath:/messages/" + bundle + ".properties"));
        Resource[] files = localeFiles(bundle);
        assertThat(files.length + 1).as("%s 언어 수(기본 포함)", bundle).isGreaterThanOrEqualTo(MIN_LOCALES);

        for (Resource file : files) {
            Properties locale = load(file);
            String name = file.getFilename();
            assertThat(new TreeSet<>(locale.stringPropertyNames())).as("%s 키 집합", name)
                    .isEqualTo(new TreeSet<>(base.stringPropertyNames()));
            for (String key : base.stringPropertyNames()) {
                String value = locale.getProperty(key);
                assertThat(value).as("%s %s 가 비었다", name, key).isNotBlank();
                assertThat(placeholders(value)).as("%s %s 자리표시자", name, key)
                        .isEqualTo(placeholders(base.getProperty(key)));
                if (!placeholders(value).isEmpty()) {
                    assertThat(hasOddQuoteRun(value))
                            .as("%s %s 에 짝이 안 맞는 홑따옴표 — '' 로 쓰거나 ’ 를 쓴다", name, key).isFalse();
                }
            }
        }
    }

    @ParameterizedTest
    @MethodSource("bundles")
    @DisplayName("errors 와 notifications 의 언어 목록이 같다 — 한쪽만 번역된 언어를 두지 않는다")
    void both_bundles_cover_the_same_locales(String ignored) throws IOException {
        assertThat(suffixes("errors")).isEqualTo(suffixes("notifications"));
    }

    static boolean hasOddQuoteRun(String value) {
        Matcher m = QUOTE_RUN.matcher(value);
        while (m.find()) {
            if (m.group().length() % 2 == 1) {
                return true;
            }
        }
        return false;
    }

    @org.junit.jupiter.api.Test
    @DisplayName("홀수 길이 따옴표 묶음을 잡는다 — 세 개도 하나와 같다")
    void odd_quote_runs_are_detected() {
        assertThat(hasOddQuoteRun("Don't {1}")).isTrue();
        assertThat(hasOddQuoteRun("Don'''t {1}")).isTrue();
        assertThat(hasOddQuoteRun("Don''t {1}")).isFalse();
        assertThat(hasOddQuoteRun("Don’t {1}")).isFalse();
    }

    private static Set<String> suffixes(String bundle) throws IOException {
        Set<String> out = new TreeSet<>();
        Arrays.stream(localeFiles(bundle)).map(Resource::getFilename)
                .forEach(f -> out.add(f.substring(bundle.length() + 1, f.length() - ".properties".length())));
        return out;
    }
}
