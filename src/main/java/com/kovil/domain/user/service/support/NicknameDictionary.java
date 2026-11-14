package com.kovil.domain.user.service.support;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

// 부팅 시 1회 메모리 로드한다. 사전을 DB가 아니라 리소스에 두는 이유: 변경이 Git 이력에 남고
// CI 조합 테스트가 그 변경을 검증하기 때문이다(FR-107).
//
// ⚠ 여기에 blocklist는 없다. 자동 생성 경로의 위생은 빌드 타임 CI 테스트가 소유하며,
// blocklist 파일은 test 리소스에만 존재해 런타임에서 참조 자체가 불가능하다(FR-107 "런타임 필터링 금지").
@Slf4j
@Getter
@Component
@RequiredArgsConstructor
public class NicknameDictionary {

    private static final String ADJECTIVES_PATH = "nickname/adjectives.json";
    private static final String NOUNS_PATH = "nickname/nouns.json";

    private final JsonMapper jsonMapper;

    private List<String> adjectives;
    private List<String> nouns;

    @PostConstruct
    void load() {
        this.adjectives = readWords(ADJECTIVES_PATH);
        this.nouns = readWords(NOUNS_PATH);

        requireNonEmpty(adjectives, nouns);
        log.info("닉네임 사전 로드 — 형용사 {}개 × 명사 {}개 = 조합 {}개", adjectives.size(), nouns.size(), combinationSpace());
    }

    // 빈 사전으로 뜨면 그 뒤 가입자 전원이 닉네임 없이 만들어진다. 배포 사고이므로 기동에서 죽인다.
    static void requireNonEmpty(List<String> adjectives, List<String> nouns) {
        if (adjectives.isEmpty() || nouns.isEmpty()) {
            throw new IllegalStateException("닉네임 사전이 비어 있습니다 — 리소스 배포를 확인하세요. adjectives="
                    + adjectives.size() + " nouns=" + nouns.size());
        }
    }

    public long combinationSpace() {
        return (long) adjectives.size() * nouns.size();
    }

    private List<String> readWords(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            JsonNode words = jsonMapper.readTree(in).path("words");
            if (!words.isArray()) {
                throw new IllegalStateException(path + " 형식이 올바르지 않습니다 — { \"words\": [...] } 이어야 합니다");
            }
            List<String> result = new ArrayList<>();
            words.forEach(node -> result.add(node.asString()));
            return List.copyOf(result);
        } catch (IOException e) {
            throw new UncheckedIOException("닉네임 사전을 읽을 수 없습니다: " + path, e);
        }
    }
}
