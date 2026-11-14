package com.kovil.common.config;

import com.kovil.domain.appversion.enums.AppKind;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * ★ **마이그레이션이 운영에서 처음 실행되는 것을 막는다.**
 *
 * 그동안 그랬다: 테스트 프로파일은 H2 + `flyway.enabled=false` 이고, CI 는 mysql:8.0 서비스를
 * 띄워 놓고도 전부 `SPRING_PROFILES_ACTIVE=test` 로 돌려 **그 MySQL 을 쓰지 않았다**
 * (CI 주석은 "Flyway 마이그레이션 검증용 · 첫 게이트"라고 적혀 있었는데 배선이 없었다).
 * 그래서 V1~V24 가 배포 때 처음 실행됐다 — 롤백이 어려운 배포에서 가장 나쁜 조합이다.
 *
 * 이 테스트는 **스프링을 띄우지 않는다.** Flyway 자바 API 로 진짜 MySQL 에 마이그레이션을
 * 그대로 먹인다. 엔티티·ddl-auto 가 만든 스키마가 아니라 **운영이 받을 그 SQL** 을 검증한다.
 *
 * MIGRATION_DB_URL 이 없으면 통째로 건너뛴다 — 로컬에는 MySQL 이 없을 수 있고, 있어도
 * 남의 DB 를 건드리면 안 된다.
 */
@EnabledIfEnvironmentVariable(named = "MIGRATION_DB_URL", matches = ".+")
class FlywayMigrationTest {

    /**
     * ★ 안전장치. 이 이름의 스키마가 아니면 **아무것도 하지 않는다.**
     *
     * <p>환경변수 하나 잘못 넣어 운영 DB 를 가리키는 일은 실제로 일어난다. 이 테스트는 스키마를
     * 통째로 바꾸므로 그 사고의 대가가 크다 — 그래서 이름으로 한 번 더 막는다.
     */
    private static final String REQUIRED_SCHEMA = "wjf_migration_check";

    private static String url() {
        return System.getenv("MIGRATION_DB_URL");
    }

    @Test
    @DisplayName("V1~최신 마이그레이션이 진짜 MySQL 에 그대로 올라간다")
    void migrations_apply_to_a_real_mysql() throws Exception {
        assertThat(url())
                .describedAs("MIGRATION_DB_URL 은 %s 스키마만 가리킬 수 있다 — 다른 DB 를 "
                        + "건드리지 않기 위한 안전장치다", REQUIRED_SCHEMA)
                .contains("/" + REQUIRED_SCHEMA);

        // ★ 빈 스키마에서 시작해야 의미가 있다. clean() 을 쓰지 않는 이유: 지우는 힘을 가진
        //   코드를 남겨 두지 않는다 — CI 는 매 실행 새 컨테이너라 지울 것도 없다.
        assertThat(tableCount())
                .describedAs("스키마가 비어 있지 않다 — 마이그레이션 검증은 빈 스키마에서만 한다")
                .isZero();

        MigrateResult result = Flyway.configure()
                .dataSource(url(), env("MIGRATION_DB_USER"), env("MIGRATION_DB_PASSWORD"))
                .locations("classpath:db/migration")
                .load()
                .migrate();

        assertThat(result.success).isTrue();
        // 파일 수와 실행 수가 어긋나면 누군가 파일을 두고 버전을 건너뛴 것이다.
        assertThat(result.migrationsExecuted).isEqualTo(migrationFileCount());
        // 스키마가 실제로 생겼는지 — "성공했다는 보고"만 믿지 않는다.
        assertThat(tableCount()).isGreaterThan(40);

        assertAppUpdatePolicies();
    }

    /*
     * 파트너 앱 정책 행을 만드는 곳은 V29 하나다 — 관리자 API 는 있는 행을 고칠 뿐 만들지 못하고, H2 테스트는 Flyway 를 돌리지 않는다.
     * ★ 철자는 자바에서 비교한다. 테이블 콜레이션(utf8mb4_0900_ai_ci)은 대소문자를 가리지 않아 SQL 비교로는 'Partner' 도 통과한다.
     *   엔티티는 @Enumerated(STRING) 이라 철자가 어긋나면 findAll 이 터지고, 버전 판정은 그 예외를 삼켜 전부 OK 로 보낸다.
     */
    private static void assertAppUpdatePolicies() throws SQLException {
        List<String> apps = new ArrayList<>();
        List<String> pairs = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(
                url(), env("MIGRATION_DB_USER"), env("MIGRATION_DB_PASSWORD"));
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT app, platform FROM app_update_policies")) {
            while (rs.next()) {
                apps.add(rs.getString("app"));
                pairs.add(rs.getString("app") + "/" + rs.getString("platform"));
            }
        }
        assertThat(pairs).containsExactlyInAnyOrder("STUDENT/IOS", "STUDENT/ANDROID", "PARTNER/IOS", "PARTNER/ANDROID");
        assertThat(apps).isSubsetOf(Arrays.stream(AppKind.values()).map(Enum::name).toList());

        // 앱마다 플랫폼 한 행 — 옛 platform 단독 유니크가 남아 있으면 파트너 행이 들어갈 수 없다.
        assertThat(indexColumns("uk_app_update_policies_app_platform")).containsExactly("0:app", "0:platform");
        assertThat(indexColumns("uk_app_update_policies_platform")).isEmpty();
    }

    private static List<String> indexColumns(String indexName) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(
                url(), env("MIGRATION_DB_USER"), env("MIGRATION_DB_PASSWORD"));
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT NON_UNIQUE, COLUMN_NAME FROM information_schema.STATISTICS "
                     + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'app_update_policies' AND INDEX_NAME = ? "
                     + "ORDER BY SEQ_IN_INDEX")) {
            statement.setString(1, REQUIRED_SCHEMA);
            statement.setString(2, indexName);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    columns.add(rs.getInt("NON_UNIQUE") + ":" + rs.getString("COLUMN_NAME"));
                }
            }
        }
        return columns;
    }

    private static String env(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value;
    }

    private static int tableCount() throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                url(), env("MIGRATION_DB_USER"), env("MIGRATION_DB_PASSWORD"));
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT COUNT(*) FROM information_schema.tables "
                     + "WHERE table_schema = '" + REQUIRED_SCHEMA + "'")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static long migrationFileCount() throws IOException {
        Path dir = Path.of("src", "main", "resources", "db", "migration");
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".sql")).count();
        }
    }
}
