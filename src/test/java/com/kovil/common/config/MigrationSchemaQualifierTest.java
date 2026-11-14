package com.kovil.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * ★★ 마이그레이션은 **연결된 스키마**에 걸려야 한다. 테이블 이름에 스키마를 박으면(kovil.app_update_policies)
 *    운영 DB 이름이 마침 같을 때만 통하고, CI 의 일회용 검증 스키마(wjf_migration_check)에서는 "테이블이 없다"로 깨진다.
 *    같은 서버에 그 이름의 DB 가 따로 있으면 **엉뚱한 DB 를 고친다.**
 *    테스트 H2 는 Flyway 를 돌리지 않아 이 실수가 로컬 게이트로는 안 보인다 — 파일을 직접 읽어 막는다.
 *    (IDE 의 SQL 편집기가 자동 완성으로 스키마를 붙이기 쉽다.)
 */
class MigrationSchemaQualifierTest {

    private static final Path MIGRATION_DIR = Path.of("src/main/resources/db/migration");

    // 테이블이 오는 자리 뒤에 `이름.이름` — 별칭이나 칼럼 참조(l.id)는 이 키워드들 바로 뒤에 오지 않는다.
    private static final Pattern QUALIFIED_TABLE = Pattern.compile(
            "\\b(TABLE|INTO|UPDATE|FROM|JOIN|REFERENCES)\\s+`?[A-Za-z_][A-Za-z0-9_]*`?\\s*\\.\\s*`?[A-Za-z_]",
            Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("마이그레이션 SQL 은 테이블 이름에 스키마를 박지 않는다")
    void migrations_do_not_qualify_schema() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.list(MIGRATION_DIR)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".sql")).sorted().toList()) {
                String sql = stripComments(Files.readString(file));
                Matcher m = QUALIFIED_TABLE.matcher(sql);
                while (m.find()) {
                    violations.add(file.getFileName() + ": " + m.group());
                }
            }
        }
        assertThat(violations)
                .as("스키마가 박힌 테이블 이름 — Flyway 가 연결한 스키마에 걸리도록 이름만 쓴다")
                .isEmpty();
    }

    private static String stripComments(String sql) {
        return sql.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)--.*$", " ");
    }
}
