package com.kovil.domain.partner;

import com.kovil.domain.partner.enums.OfficeChangeRequestStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * 변경 신청 상태의 DB ENUM 과 자바 enum 이 같은 값 목록인가.
 * ★ 테스트는 H2 create-drop 이라 엔티티에서 만든 VARCHAR 를 쓴다 — 마이그레이션 ENUM 에 값이 빠져도 로컬 게이트는 초록이고,
 *   운영 MySQL 에서 그 값을 쓰는 순간(취소) "Data truncated" 로 500 이 난다. 마지막으로 정의한 ENUM 을 V 파일에서 읽어 대조한다.
 */
class OfficeChangeStatusMigrationTest {

    private static final Path MIGRATION_DIR = Path.of("src/main/resources/db/migration");
    private static final Pattern VERSION = Pattern.compile("^V(\\d+)__");
    private static final Pattern STATUS_ENUM = Pattern.compile(
            "status\\s+ENUM\\s*\\(([^)]*)\\)([^,;]*)", Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("마지막 마이그레이션의 status ENUM = 자바 enum 값 목록 · NOT NULL DEFAULT 'PENDING'")
    void latest_enum_matches_java_enum() throws IOException {
        List<Path> files;
        try (Stream<Path> listed = Files.list(MIGRATION_DIR)) {
            files = listed.filter(f -> VERSION.matcher(f.getFileName().toString()).find())
                    .sorted(Comparator.comparingInt(OfficeChangeStatusMigrationTest::versionOf))
                    .toList();
        }

        String values = null;
        String tail = null;
        for (Path file : files) {
            for (String statement : stripComments(Files.readString(file)).split(";")) {
                if (!statement.contains("brokerage_office_change_requests")) {
                    continue;
                }
                Matcher m = STATUS_ENUM.matcher(statement);
                while (m.find()) {
                    values = m.group(1);
                    tail = m.group(2);
                }
            }
        }

        assertThat(values).as("brokerage_office_change_requests.status ENUM 정의").isNotNull();
        List<String> dbValues = new ArrayList<>();
        for (String v : values.split(",")) {
            dbValues.add(v.trim().replace("'", ""));
        }
        assertThat(dbValues).containsExactlyElementsOf(
                Arrays.stream(OfficeChangeRequestStatus.values()).map(Enum::name).toList());
        assertThat(tail.replaceAll("\\s+", " ")).contains("NOT NULL DEFAULT 'PENDING'");
    }

    private static int versionOf(Path file) {
        Matcher m = VERSION.matcher(file.getFileName().toString());
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    private static String stripComments(String sql) {
        return sql.replaceAll("(?m)--[^\\n]*", "").replaceAll("(?s)/\\*.*?\\*/", "");
    }
}
