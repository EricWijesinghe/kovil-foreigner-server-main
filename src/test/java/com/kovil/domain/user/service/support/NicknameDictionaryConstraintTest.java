package com.kovil.domain.user.service.support;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

// 사전 리소스의 CI 게이트(FR-107). 사전을 고치는 순간 이 테스트가 빌드에서 검증한다.
// 위반이 나오면 해소는 런타임 필터 추가가 아니라 "사전에서 단어 제거"다 —
// 그래서 blocklist는 test 리소스에만 있고 운영 코드가 참조할 수 없다.
class NicknameDictionaryConstraintTest {

    private static List<String> adjectives;
    private static List<String> nouns;
    private static List<String> blocklist;

    @BeforeAll
    static void load() {
        JsonMapper mapper = JsonMapper.builder().build();
        adjectives = words(mapper, "nickname/adjectives.json");
        nouns = words(mapper, "nickname/nouns.json");
        blocklist = words(mapper, "nickname/blocklist.json").stream()
                .map(word -> word.toLowerCase(Locale.ROOT)).toList();
    }

    private static List<String> words(JsonMapper mapper, String path) {
        try (InputStream in = NicknameDictionaryConstraintTest.class.getClassLoader()
                .getResourceAsStream(path)) {
            JsonNode root = mapper.readTree(in);
            List<String> result = new ArrayList<>();
            root.path("words").forEach(node -> result.add(node.asString()));
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("사전 리소스를 읽을 수 없습니다: " + path, e);
        }
    }

    // ★ 이 테스트가 FR-107의 핵심이다. 4천여 조합을 전수 대조해 한 건이라도 걸리면 빌드를 깬다.
    // 실제로 이 게이트가 "compass"(← ass)를 잡아냈고, 사전에서 제거해 해소했다.
    @Test
    @DisplayName("전 조합 × 금칙어 대조 — 한 건이라도 걸리면 빌드 실패")
    void noCombinationContainsBlockedWord() {
        assertThat(blocklist).as("금칙어 목록이 비어 있으면 이 게이트는 아무것도 막지 못한다").isNotEmpty();

        Set<String> violations = new HashSet<>();
        for (String adjective : adjectives) {
            for (String noun : nouns) {
                String combo = (adjective + noun).toLowerCase(Locale.ROOT);
                for (String blocked : blocklist) {
                    if (combo.contains(blocked)) {
                        violations.add(adjective + "+" + noun + " ← " + blocked);
                    }
                }
            }
        }

        assertThat(violations).as("금칙 조합 발견 — 런타임 필터를 넣지 말고 사전에서 해당 단어를 제거하라").isEmpty();
    }

    // 단어 하나하나도 금칙어를 품으면 안 된다 — 조합 검사가 잡지만 원인을 좁혀서 알려준다.
    @Test
    @DisplayName("개별 단어에도 금칙어가 없다")
    void noWordContainsBlockedWord() {
        for (String word : concat(adjectives, nouns)) {
            for (String blocked : blocklist) {
                assertThat(word.toLowerCase(Locale.ROOT))
                        .as("'%s'가 금칙어 '%s'를 품고 있다", word, blocked)
                        .doesNotContain(blocked);
            }
        }
    }

    @Test
    @DisplayName("사전에 중복이 없고 조합 공간이 충분하다")
    void noDuplicatesAndEnoughSpace() {
        assertThat(Set.copyOf(adjectives)).as("형용사 중복").hasSize(adjectives.size());
        assertThat(Set.copyOf(nouns)).as("명사 중복").hasSize(nouns.size());
        // 조합 × 4자리 숫자가 실제 공간이다. 조합만으로도 4천 이상을 요구해 사전이 빈약해지는 것을 막는다.
        assertThat((long) adjectives.size() * nouns.size()).isGreaterThanOrEqualTo(4_000);
    }

    // 닉네임 컬럼은 VARCHAR(50)이다. 형용사+명사+6자리가 그 안에 들어와야 한다.
    @Test
    @DisplayName("단어 길이가 컬럼 한계 안에 들어온다")
    void wordLengthFitsColumn() {
        assertThat(adjectives).allSatisfy(word ->
                assertThat(word.length()).as("형용사 '%s'", word).isBetween(3, 12));
        assertThat(nouns).allSatisfy(word ->
                assertThat(word.length()).as("명사 '%s'", word).isBetween(3, 12));

        int longest = adjectives.stream().mapToInt(String::length).max().orElse(0)
                + nouns.stream().mapToInt(String::length).max().orElse(0) + 6;
        assertThat(longest).as("최장 닉네임이 users.nickname VARCHAR(50)을 넘는다").isLessThanOrEqualTo(50);
    }

    // 영문·숫자만 허용한다 — 정규화가 소문자 변환뿐이라는 전제가 여기에 걸려 있다.
    @Test
    @DisplayName("사전은 영소문자만 담는다")
    void wordsAreLowercaseAscii() {
        assertThat(concat(adjectives, nouns)).allSatisfy(word ->
                assertThat(word).as("'%s'", word).matches("^[a-z]+$"));
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }
}
