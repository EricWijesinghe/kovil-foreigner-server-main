package com.kovil.domain.rentalrequest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * ★★ 맡은 사무소 FK 전용 인덱스(idx_rr_office_fk)를 **게이트에서** 지킨다 — V 파일을 직접 읽는 정적 검사다.
 *
 *    왜: 그 인덱스는 (office_id, status, id) 복합 인덱스의 앞부분과 겹쳐 "중복 인덱스"로 보인다. 지우면 MySQL 은 말없이 FK 를
 *    복합 인덱스로 옮겨 붙이고, 그때부터 상태만 바꾸는 UPDATE(유학생 취소 · 탈퇴)가 FK 검사로 사무소 행을 S 로 잡아
 *    사무소 행을 쥔 놓기 · 종료 · 대기 복귀와 교착한다(MySQL 9.5 재현 — 40라운드 중 12번). 게이트는 H2 라 그래도 그린이고,
 *    실 MySQL 테스트는 환경변수가 없으면 건너뛴다. 그래서 여기서 막는다:
 *      · V28 이 그 인덱스를 (office_id) 단독으로 만든다 — 복합 인덱스 · FK 보다 **먼저**(InnoDB 는 그 칸으로 시작하는 인덱스 중
 *        앞선 것에 FK 를 얹는다).
 *      · FK(fk_rr_office)는 office_id → brokerage_offices 다.
 *      · 그 뒤 어떤 문장도 그 인덱스를 지우거나 이름을 바꾸지 않고, FK 를 지웠다 다시 붙이지 않는다(다시 붙일 때 어느 인덱스에
 *        얹힐지는 그때의 인덱스 순서가 정한다 — 이 검사를 다시 생각하게 막아 둔다).
 *      · V28 의 경고 주석이 그 자리에 남아 있다.
 *    잠금 동작 자체(상태만 바꾸는 UPDATE 가 잠긴 사무소 행을 기다리지 않는다)는 RentalRequestV28MigrationMySqlTest 가 실 MySQL 로 본다
 *    (CI 의 마이그레이션 검증 단계).
 *
 * ★ 주석과 문자열 리터럴을 지운 뒤에 찾는다 — 인덱스 COMMENT 안에 "idx_rr_office 와 겹쳐 보여도…" 같은 이름이 들어 있다.
 */
class RentalRequestOfficeFkIndexGuardTest {

    private static final Path MIGRATION_DIR = Path.of("src/main/resources/db/migration");
    private static final int GUARDED_VERSION = 28;
    private static final String FK_INDEX = "idx_rr_office_fk";
    private static final String WARNING = "복합 인덱스의 앞부분과 겹쳐 중복처럼 보여도 지우지 말 것 — 지우면 놓기 · 종료와 신청자 탈퇴가 교착한다";

    private record Stmt(int version, String file, String sql) {
    }

    // rental_requests 에 인덱스를 붙이는 절 — 이름과 칼럼을 꺼낸다(ALTER … ADD [UNIQUE] INDEX|KEY · CREATE [UNIQUE] INDEX … ON).
    private static final Pattern ADD_INDEX_CLAUSE = Pattern.compile(
            "\\badd (?:unique )?(?:index|key) ([a-z0-9_]+) \\(([^)]*)\\)");
    private static final Pattern CREATE_INDEX = Pattern.compile(
            "create (?:unique )?index ([a-z0-9_]+) on rental_requests \\(([^)]*)\\)");
    private static final Pattern ADD_FK = Pattern.compile(
            "alter table rental_requests\\b.*?\\badd constraint fk_rr_office foreign key \\(office_id\\) "
                    + "references brokerage_offices \\(id\\)");
    private static final Pattern TOUCHES_FK_INDEX = Pattern.compile(
            "\\b(?:drop (?:index|key)|rename (?:index|key)) idx_rr_office_fk\\b");
    private static final Pattern TOUCHES_FK = Pattern.compile("\\bdrop (?:foreign key|constraint) fk_rr_office\\b");

    @Test
    @DisplayName("V28 이 FK 전용 인덱스를 (office_id) 단독으로, 복합 인덱스 · FK 보다 먼저 만든다 — FK 는 office_id → 사무소")
    void fk_index_is_created_alone_and_first() throws IOException {
        List<Stmt> statements = statements(migrations());

        int fkIndexAt = -1;
        int firstOfficeLeadingIndexAt = -1;
        String firstOfficeLeadingIndex = null;
        int fkAt = -1;
        for (int i = 0; i < statements.size(); i++) {
            Stmt stmt = statements.get(i);
            for (AddedIndex index : indexesAddedIn(stmt)) {
                if (index.name().equals(FK_INDEX)) {
                    assertThat(fkIndexAt).as("%s 를 두 번 만든다(%s)", FK_INDEX, stmt.file()).isEqualTo(-1);
                    assertThat(stmt.version()).as("%s 는 V%d 가 만든다", FK_INDEX, GUARDED_VERSION)
                            .isEqualTo(GUARDED_VERSION);
                    assertThat(index.columns()).as("%s 는 office_id 단독이어야 한다", FK_INDEX)
                            .containsExactly("office_id");
                    fkIndexAt = i;
                }
                if (index.columns().get(0).equals("office_id") && firstOfficeLeadingIndexAt < 0) {
                    firstOfficeLeadingIndexAt = i;
                    firstOfficeLeadingIndex = index.name();
                }
            }
            if (ADD_FK.matcher(stmt.sql()).find()) {
                assertThat(fkAt).as("fk_rr_office 를 두 번 붙인다(%s)", stmt.file()).isEqualTo(-1);
                fkAt = i;
            }
        }

        assertThat(fkIndexAt).as("V%d 에 %s (office_id) 를 만드는 문장이 없다", GUARDED_VERSION, FK_INDEX)
                .isNotNegative();
        assertThat(firstOfficeLeadingIndex)
                .as("rental_requests 에서 office_id 로 시작하는 첫 인덱스가 %s 여야 FK 가 거기 얹힌다", FK_INDEX)
                .isEqualTo(FK_INDEX);
        assertThat(fkAt).as("fk_rr_office FOREIGN KEY (office_id) REFERENCES brokerage_offices (id) 가 없다")
                .isNotNegative();
        assertThat(fkAt).as("FK 는 %s 를 만든 **뒤에** 붙인다", FK_INDEX).isGreaterThan(fkIndexAt);
        assertThat(statements.get(fkAt).version()).isEqualTo(GUARDED_VERSION);
    }

    @Test
    @DisplayName("만든 뒤로 어떤 문장도 FK 전용 인덱스를 지우거나 이름을 바꾸지 않고, FK 를 지우지 않는다")
    void nothing_later_drops_the_fk_index() throws IOException {
        List<Stmt> statements = statements(migrations());
        int createdAt = -1;
        for (int i = 0; i < statements.size(); i++) {
            if (indexesAddedIn(statements.get(i)).stream().anyMatch(index -> index.name().equals(FK_INDEX))) {
                createdAt = i;
            }
        }
        assertThat(createdAt).as("%s 를 만드는 문장을 못 찾았다 — 검사가 헛돈다", FK_INDEX).isNotNegative();

        List<String> violations = new ArrayList<>();
        for (Stmt stmt : statements.subList(createdAt + 1, statements.size())) {
            violations.addAll(violationsIn(stmt));
        }
        assertThat(violations)
                .as("%s 는 복합 인덱스와 겹쳐 보여도 지우면 놓기 · 종료와 신청자 탈퇴가 교착한다(V%d 주석)", FK_INDEX, GUARDED_VERSION)
                .isEmpty();
    }

    @Test
    @DisplayName("V28 의 FK 전용 인덱스 옆에 '지우지 말 것' 경고 주석이 남아 있다")
    void warning_comment_stays() throws IOException {
        String sql = migrations().stream()
                .filter(source -> source.version() == GUARDED_VERSION)
                .findFirst().orElseThrow().sql();

        assertThat(sql).contains(WARNING);
        assertThat(sql.indexOf(WARNING)).as("경고는 그 인덱스를 만드는 문장 바로 앞에 있다")
                .isLessThan(sql.indexOf("ADD INDEX " + FK_INDEX));
    }

    /*
     * 검사기 자체가 헛돌지 않는지 — 지우는 문장의 여러 모양은 잡고, 주석 · 문자열 안의 이름은 잡지 않는다.
     * (실제 V 파일에는 지우는 문장이 없어 위 검사만으로는 검출기가 살아 있는지 알 수 없다.)
     */
    @Test
    @DisplayName("검출기는 지우는 문장의 여러 모양을 잡고, 주석 · 문자열 안의 이름은 잡지 않는다")
    void detector_catches_drops_and_ignores_comments() {
        List<String> caught = List.of(
                "ALTER TABLE rental_requests DROP INDEX idx_rr_office_fk;",
                "ALTER TABLE `rental_requests`\n    DROP KEY `idx_rr_office_fk`;",
                "DROP INDEX idx_rr_office_fk ON rental_requests;",
                "ALTER TABLE rental_requests RENAME INDEX idx_rr_office_fk TO idx_rr_office_only;",
                "ALTER TABLE rental_requests ADD COLUMN x INT, DROP INDEX idx_rr_office_fk;",
                "ALTER TABLE rental_requests DROP FOREIGN KEY fk_rr_office;");
        for (String sql : caught) {
            assertThat(statements(List.of(new Source(99, "V99__probe.sql", sql))).stream()
                    .flatMap(stmt -> violationsIn(stmt).stream()).toList())
                    .as(sql).isNotEmpty();
        }

        List<String> ignored = List.of(
                "-- DROP INDEX idx_rr_office_fk 를 하지 말 것\nSELECT 1;",
                "/* ALTER TABLE rental_requests DROP INDEX idx_rr_office_fk; */ SELECT 1;",
                "ALTER TABLE rental_requests MODIFY status VARCHAR(20) COMMENT 'drop index idx_rr_office_fk 금지';",
                "ALTER TABLE rental_requests DROP INDEX idx_rr_office_fk_old;");
        for (String sql : ignored) {
            assertThat(statements(List.of(new Source(99, "V99__probe.sql", sql))).stream()
                    .flatMap(stmt -> violationsIn(stmt).stream()).toList())
                    .as(sql).isEmpty();
        }
    }

    // ───────────────────────── 내부

    private record Source(int version, String file, String sql) {
    }

    private record AddedIndex(String name, List<String> columns) {
    }

    // 이 문장이 rental_requests 에 붙이는 인덱스들 — ALTER 한 문장에 절이 여럿이어도 모두 꺼낸다.
    private static List<AddedIndex> indexesAddedIn(Stmt stmt) {
        List<AddedIndex> added = new ArrayList<>();
        if (stmt.sql().startsWith("alter table rental_requests ")) {
            Matcher m = ADD_INDEX_CLAUSE.matcher(stmt.sql());
            while (m.find()) {
                added.add(new AddedIndex(m.group(1), columns(m.group(2))));
            }
        }
        Matcher c = CREATE_INDEX.matcher(stmt.sql());
        while (c.find()) {
            added.add(new AddedIndex(c.group(1), columns(c.group(2))));
        }
        added.removeIf(index -> index.columns().isEmpty());
        return added;
    }

    private static List<String> violationsIn(Stmt stmt) {
        List<String> found = new ArrayList<>();
        if (TOUCHES_FK_INDEX.matcher(stmt.sql()).find()) {
            found.add(stmt.file() + " → " + FK_INDEX + " 를 지우거나 이름을 바꾼다: " + stmt.sql());
        }
        if (TOUCHES_FK.matcher(stmt.sql()).find()) {
            found.add(stmt.file() + " → fk_rr_office 를 지운다(다시 붙일 때 어느 인덱스에 얹힐지 확인하라): " + stmt.sql());
        }
        return found;
    }

    private static List<Source> migrations() throws IOException {
        List<Path> files;
        try (var stream = Files.list(MIGRATION_DIR)) {
            files = stream.filter(p -> p.getFileName().toString().endsWith(".sql"))
                    .sorted(Comparator.comparingInt(RentalRequestOfficeFkIndexGuardTest::versionOf))
                    .toList();
        }
        assertThat(files).as("마이그레이션을 못 찾았다 — 경로가 깨졌다").isNotEmpty();
        List<Source> sources = new ArrayList<>();
        for (Path file : files) {
            sources.add(new Source(versionOf(file), file.getFileName().toString(), Files.readString(file)));
        }
        return sources;
    }

    // 주석 · 문자열을 지우고 ; 로 나눈 뒤, 소문자 · 공백 하나 · 백틱 없음으로 고른다.
    private static List<Stmt> statements(List<Source> sources) {
        List<Stmt> statements = new ArrayList<>();
        for (Source source : sources) {
            String cleaned = stripCommentsAndStrings(source.sql());
            for (String raw : cleaned.split(";")) {
                String sql = raw.replace("`", "").replaceAll("\\s+", " ").strip().toLowerCase();
                if (!sql.isEmpty()) {
                    statements.add(new Stmt(source.version(), source.file(), sql));
                }
            }
        }
        return statements;
    }

    // -- 줄 주석 · /* */ 블록 주석 · '…' 문자열을 공백으로 바꾼다. 문자열 안의 -- 와 /* 는 주석이 아니다.
    private static String stripCommentsAndStrings(String sql) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '\'') {
                int end = i + 1;
                while (end < sql.length()) {
                    if (sql.charAt(end) == '\'' && end + 1 < sql.length() && sql.charAt(end + 1) == '\'') {
                        end += 2;
                        continue;
                    }
                    if (sql.charAt(end) == '\'') {
                        break;
                    }
                    end++;
                }
                out.append(' ');
                i = end + 1;
            } else if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                int end = sql.indexOf('\n', i);
                i = end < 0 ? sql.length() : end;
            } else if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                out.append(' ');
                i = end < 0 ? sql.length() : end + 2;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static List<String> columns(String list) {
        List<String> columns = new ArrayList<>();
        for (String part : list.split(",")) {
            String column = part.strip().split(" ")[0];
            if (!column.isEmpty()) {
                columns.add(column);
            }
        }
        return columns;
    }

    private static int versionOf(Path file) {
        Matcher m = Pattern.compile("^V(\\d+)__").matcher(file.getFileName().toString());
        assertThat(m.find()).as("마이그레이션 파일 이름이 V<숫자>__ 로 시작하지 않는다: %s", file).isTrue();
        return Integer.parseInt(m.group(1));
    }
}
