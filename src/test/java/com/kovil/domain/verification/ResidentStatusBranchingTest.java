package com.kovil.domain.verification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

// ★ INV-03의 소스 게이트. "요구 문서는 언제나 resident_status_documents에서 읽는다"는 규약은
// 리뷰어의 기억이 아니라 빌드가 지켜야 한다. 신분 코드가 코드에 박히는 순간 잡는다.
//
// 실패하면 해소는 조건문을 감추는 게 아니라 **매트릭스 데이터로 옮기는 것**이다.
class ResidentStatusBranchingTest {

    private static final Path MAIN = Path.of("src", "main", "java");

    // 시드에 있는 신분 코드. 늘어나면 여기도 늘린다.
    private static final List<String> STATUS_CODES = List.of("STUDENT", "WORKER");

    @Test
    @DisplayName("운영 코드 어디에도 신분 코드가 하드코딩돼 있지 않다")
    void no_resident_status_code_in_source() throws IOException {
        List<String> violations = new ArrayList<>();

        try (Stream<Path> sources = Files.walk(MAIN)) {
            sources.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
                String source;
                try {
                    source = Files.readString(path, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new IllegalStateException(path.toString(), e);
                }
                for (String code : STATUS_CODES) {
                    // 주석·문서(Swagger example)에 예시로 적는 것은 분기가 아니다 —
                    // 문자열 리터럴로 비교·분기하는 자리만 잡는다.
                    Pattern branching = Pattern.compile(
                            "(equals|equalsIgnoreCase|==|case)\\s*\\(?\\s*\"" + code + "\"");
                    if (branching.matcher(source).find()) {
                        violations.add(path.getFileName() + " ← " + code);
                    }
                }
            });
        }

        assertThat(violations)
                .as("신분 코드로 분기하고 있다 — 조건문을 감추지 말고 resident_status_documents로 옮겨라")
                .isEmpty();
    }

    @Test
    @DisplayName("게이트가 실제로 동작한다 — 분기 패턴을 넣으면 걸린다")
    void gate_actually_detects() {
        String offending = "if (statusCode.equals(\"STUDENT\")) { requireEnrollmentCert(); }";
        Pattern branching = Pattern.compile(
                "(equals|equalsIgnoreCase|==|case)\\s*\\(?\\s*\"STUDENT\"");

        assertThat(branching.matcher(offending).find())
                .as("게이트가 무력화되면 INV-03이 조용히 깨진다")
                .isTrue();
    }

    @Test
    @DisplayName("시드의 신분 코드가 검사 목록과 일치한다")
    void status_codes_match_seed() throws IOException {
        String seed = Files.readString(
                Path.of("src", "main", "resources", "db", "migration", "V2__seed_master_data.sql"),
                StandardCharsets.UTF_8);

        for (String code : STATUS_CODES) {
            assertThat(seed.toUpperCase(Locale.ROOT))
                    .as("%s가 시드에 없다 — 검사 목록이 낡았다", code)
                    .contains("'" + code + "'");
        }
    }
}
