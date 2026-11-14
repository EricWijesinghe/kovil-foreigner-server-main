package com.kovil.domain.user.service.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NicknameGeneratorTest {

    private NicknameGenerator generator;
    private NicknameDictionary dictionary;

    @BeforeEach
    void setUp() {
        dictionary = new NicknameDictionary(JsonMapper.builder().build());
        dictionary.load();
        generator = new NicknameGenerator(dictionary);
    }

    // FR-106 — 영어 형용사+명사+숫자.
    @Test
    @DisplayName("형용사+명사+숫자 형식이고 자리수를 지킨다")
    void format_is_adjective_noun_digits() {
        IntStream.range(0, 500).forEach(i ->
                assertThat(generator.generate(4)).matches("^[A-Z][a-z]+[A-Z][a-z]+\\d{4}$"));
        assertThat(generator.generate(6)).matches("^[A-Z][a-z]+[A-Z][a-z]+\\d{6}$");
    }

    @Test
    @DisplayName("앞자리 0도 유지된다 — 자리수가 곧 변형 공간이다")
    void leading_zeros_are_kept() {
        // 4자리에서 앞자리 0은 1/10 확률이라 500회면 사실상 확실하다.
        boolean sawLeadingZero = IntStream.range(0, 500)
                .mapToObj(i -> generator.generate(4))
                .anyMatch(nickname -> nickname.matches(".*[a-z]0\\d{3}$"));

        assertThat(sawLeadingZero).isTrue();
    }

    @Test
    @DisplayName("연속 생성이 충분히 흩어진다")
    void values_are_spread() {
        Set<String> generated = new HashSet<>();
        IntStream.range(0, 1000).forEach(i -> generated.add(generator.generate(4)));

        assertThat(generated).hasSizeGreaterThan(950);
    }

    // 정규화가 소문자 변환뿐이라는 전제는 사전이 영문·숫자만 담기 때문에 성립한다.
    @Test
    @DisplayName("정규화는 소문자 변환이다 — 유니크 판정 축이 이 값이다")
    void normalize_is_lowercase() {
        assertThat(generator.normalize("CalmRiver0042")).isEqualTo("calmriver0042");
        assertThat(generator.normalize("  CalmRiver0042  ")).isEqualTo("calmriver0042");
    }

    @Test
    @DisplayName("빈 사전이면 부팅 가드가 기동을 멈춘다")
    void empty_dictionary_fails_boot() {
        assertThatThrownBy(() -> NicknameDictionary.requireNonEmpty(List.of(), List.of("river")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("비어 있습니다");
        assertThatThrownBy(() -> NicknameDictionary.requireNonEmpty(List.of("calm"), List.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("조합 공간은 형용사 × 명사다")
    void combination_space() {
        assertThat(dictionary.combinationSpace())
                .isEqualTo((long) dictionary.getAdjectives().size() * dictionary.getNouns().size());
    }

}
