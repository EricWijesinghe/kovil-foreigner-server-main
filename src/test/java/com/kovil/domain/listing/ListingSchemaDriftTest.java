package com.kovil.domain.listing;

import com.kovil.domain.rentalrequest.entity.RentalRequest;
import com.kovil.domain.rentalrequest.entity.RentalRequestClaimEvent;
import com.kovil.domain.transit.entity.SubwayLine;
import com.kovil.domain.transit.entity.SubwayStation;
import com.kovil.domain.transit.entity.SubwayStationLine;
import com.kovil.domain.university.entity.University;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * ★★ 마이그레이션 SQL ↔ JPA 엔티티 전수 대조.
 *
 *    왜 이 테스트가 필요한가: 이 저장소의 테스트 프로파일은 H2 `create-drop` 이다.
 *    **엔티티로 테이블을 만들기 때문에 둘이 어긋나도 영원히 초록이다.**
 *    dev·prod 에는 `ddl-auto` 설정이 아예 없어(= none) Hibernate 가 부팅 때 검증도 하지 않는다.
 *    그래서 불일치는 **운영에서 첫 INSERT 가 터질 때** 드러난다.
 *
 *    실제로 그렇게 터졌다(실측 2026-10-05): `listing_images`·`listing_options` 등 **6개 테이블**이
 *    `BaseEntity` 가 요구하는 created_at·updated_at 을 갖고 있지 않아
 *    `Unknown column 'created_at' in 'field list'` 로 **매물 등록이 100% 실패**했다.
 *    단위 테스트 1,117개가 전부 통과한 상태였다.
 *
 * ★ 상속 컬럼(BaseEntity·BaseTimeEntity)을 반드시 포함해서 센다 — 빠뜨리면 바로 그 사고를 놓친다.
 */
class ListingSchemaDriftTest {

    private static final Path MIGRATION_DIR = Path.of("src/main/resources/db/migration");
    // 매물과 그 표시 주체(사무소)를 둘러싼 파트너 쪽 테이블(부문 · 변경 신청 · 정지 이력)도 같은 사고가 난다 — 함께 본다.
    private static final List<String> ENTITY_PACKAGES = List.of(
            "com.kovil.domain.listing.entity",
            "com.kovil.domain.partner.entity");

    /*
     * 패키지째가 아니라 클래스만 골라 더 보는 것 — 임대 요청(rental_requests)은 사무소가 잡는 칸(맡은 사무소 · 잡은 시각 ·
     * 결과)이 생기고 담당자 칸이 빠졌고, 잡기 이력(rental_request_claim_events)이 새로 생겼다. 같은 패키지의 질문지 · 답변
     * 테이블은 옛 마이그레이션이 한 문장에 칼럼 여럿을 붙여(ADD COLUMN a, ADD COLUMN b) 이 파서가 첫 칼럼만 읽는다 —
     * 넣으면 거짓 실패가 난다.
     */
    private static final List<Class<?>> EXTRA_ENTITIES = List.of(
            RentalRequest.class,
            RentalRequestClaimEvent.class,
            // 행은 dml/04 가 채운다 — 테스트(H2)는 엔티티로 테이블을 만들어 V33 과 어긋나도 모른다. 여기서 칸을 맞춘다.
            University.class,
            // 지하철도 같다 — 행은 dml/05 가 채우고, V34 와 어긋나면 운영의 첫 조회에서야 터진다.
            SubwayLine.class,
            SubwayStation.class,
            SubwayStationLine.class);

    @Test
    @DisplayName("매물 엔티티가 요구하는 모든 컬럼이 마이그레이션에 실재한다 — 상속 컬럼 포함")
    void every_entity_column_exists_in_migrations() throws Exception {
        Map<String, Set<String>> ddl = parseMigrations();
        List<String> violations = new ArrayList<>();

        for (Class<?> entity : listingEntities()) {
            String table = tableNameOf(entity);
            Set<String> columns = ddl.get(table);
            if (columns == null) {
                violations.add(entity.getSimpleName() + " → 테이블 " + table + " 이 마이그레이션에 없다");
                continue;
            }
            for (String column : columnsOf(entity)) {
                if (!columns.contains(column)) {
                    violations.add(entity.getSimpleName() + " → " + table + "." + column + " 컬럼이 없다");
                }
            }
        }

        assertThat(violations)
                .as("엔티티가 요구하는 컬럼이 DDL 에 없다 — 운영에서 INSERT/SELECT 가 터진다 "
                        + "(H2 create-drop 테스트는 이 불일치를 절대 못 잡는다)")
                .isEmpty();
    }

    /*
     * ★ 반대 방향도 본다. DDL 에만 있는 컬럼은 즉시 터지지는 않지만, **아무도 읽지 않는 데이터**다 —
     *   쓰려고 만든 칸이 매핑에서 빠졌다는 신호이므로 설계 의도와 어긋난다.
     *   의도적으로 코드 밖에 두는 칸은 아래 목록에 적어 둔다(= 그 선택을 기록으로 남긴다).
     */
    private static final Set<String> DDL_ONLY_BY_DESIGN = Set.of(
            // 지금은 없다. 생기면 여기에 "테이블.컬럼" 과 이유를 적는다.
    );

    @Test
    @DisplayName("매물 테이블의 모든 컬럼이 엔티티에 매핑돼 있다 — 아무도 안 읽는 칸을 만들지 않는다")
    void every_migration_column_is_mapped() throws Exception {
        Map<String, Set<String>> ddl = parseMigrations();
        List<String> violations = new ArrayList<>();

        for (Class<?> entity : listingEntities()) {
            String table = tableNameOf(entity);
            Set<String> columns = ddl.get(table);
            if (columns == null) {
                continue;
            }
            Set<String> mapped = columnsOf(entity);
            for (String column : columns) {
                if (!mapped.contains(column) && !DDL_ONLY_BY_DESIGN.contains(table + "." + column)) {
                    violations.add(table + "." + column + " 이 " + entity.getSimpleName() + " 에 없다");
                }
            }
        }

        assertThat(violations).as("DDL 에만 있는 컬럼이다 — 매핑을 넣거나 DDL_ONLY_BY_DESIGN 에 이유와 함께 적어라")
                .isEmpty();
    }

    // ───────────────────────── 내부

    private List<Class<?>> listingEntities() throws ClassNotFoundException {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        List<Class<?>> found = new ArrayList<>();
        for (String entityPackage : ENTITY_PACKAGES) {
            List<Class<?>> inPackage = new ArrayList<>();
            for (BeanDefinition bd : scanner.findCandidateComponents(entityPackage)) {
                inPackage.add(Class.forName(bd.getBeanClassName()));
            }
            assertThat(inPackage).as("%s 엔티티를 하나도 못 찾았다 — 스캔이 깨졌다", entityPackage).isNotEmpty();
            found.addAll(inPackage);
        }
        found.addAll(EXTRA_ENTITIES);
        return found;
    }

    private String tableNameOf(Class<?> entity) {
        Table table = entity.getAnnotation(Table.class);
        return (table != null && !table.name().isBlank())
                ? table.name() : toSnake(entity.getSimpleName());
    }

    // 상속 체인을 끝까지 올라가며 컬럼을 모은다 — BaseEntity 의 created_at·updated_at 이 여기서 들어온다.
    private Set<String> columnsOf(Class<?> entity) {
        Set<String> columns = new LinkedHashSet<>();
        for (Class<?> c = entity; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()
                        || f.isAnnotationPresent(Transient.class)) {
                    continue;
                }
                // 연관 매핑은 컬럼 이름 규칙이 달라 이 테스트의 범위 밖이다(매물 도메인은 FK 를 id 로 들고 있다).
                if (f.isAnnotationPresent(OneToMany.class)
                        || f.isAnnotationPresent(ManyToOne.class)
                        || f.isAnnotationPresent(OneToOne.class)
                        || f.isAnnotationPresent(ManyToMany.class)) {
                    continue;
                }
                Column column = f.getAnnotation(Column.class);
                if (column != null && !column.name().isBlank()) {
                    columns.add(column.name());
                } else if (column != null || f.isAnnotationPresent(Id.class)
                        || isPersistentField(f)) {
                    // @Column 없이 선언된 필드는 네이밍 전략이 snake_case 로 바꾼다.
                    columns.add(toSnake(f.getName()));
                }
            }
        }
        return columns;
    }

    private boolean isPersistentField(Field f) {
        return !Modifier.isTransient(f.getModifiers());
    }

    private static final Pattern CREATE_TABLE =
            Pattern.compile("CREATE TABLE\\s+(?:IF NOT EXISTS\\s+)?`?([a-z_]+)`?\\s*\\((.*?)\\n\\)\\s*ENGINE",
                    Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    // 마이그레이션 전체를 읽어 테이블별 컬럼 집합을 만든다. ALTER … ADD COLUMN 도 반영한다.
    private Map<String, Set<String>> parseMigrations() throws IOException {
        Map<String, Set<String>> tables = new HashMap<>();
        List<Path> files;
        try (var stream = Files.list(MIGRATION_DIR)) {
            // ★ 버전 숫자 순서로 읽는다. 이름 순서면 V2~V9 가 V28 뒤에 읽혀, 나중 버전이 지운 칼럼을 옛 버전이 되살린다.
            files = stream.filter(p -> p.getFileName().toString().endsWith(".sql"))
                    .sorted(Comparator.comparingInt(ListingSchemaDriftTest::versionOf)).toList();
        }
        for (Path file : files) {
            String sql = Files.readString(file);
            Matcher m = CREATE_TABLE.matcher(sql);
            while (m.find()) {
                tables.put(m.group(1).toLowerCase(), columnNamesIn(m.group(2)));
            }
            Matcher alter = Pattern.compile(
                    "ALTER TABLE\\s+`?([a-z_]+)`?\\s+ADD COLUMN\\s+`?([a-z_]+)`?",
                    Pattern.CASE_INSENSITIVE).matcher(sql);
            while (alter.find()) {
                tables.computeIfAbsent(alter.group(1).toLowerCase(), k -> new LinkedHashSet<>())
                        .add(alter.group(2).toLowerCase());
            }
            Matcher drop = Pattern.compile(
                    "ALTER TABLE\\s+`?([a-z_]+)`?\\s+DROP COLUMN\\s+`?([a-z_]+)`?",
                    Pattern.CASE_INSENSITIVE).matcher(sql);
            while (drop.find()) {
                Set<String> cols = tables.get(drop.group(1).toLowerCase());
                if (cols != null) {
                    cols.remove(drop.group(2).toLowerCase());
                }
            }
            Matcher dropped = Pattern.compile("DROP TABLE\\s+(?:IF EXISTS\\s+)?`?([a-z_]+)`?",
                    Pattern.CASE_INSENSITIVE).matcher(sql);
            while (dropped.find()) {
                tables.remove(dropped.group(1).toLowerCase());
            }
        }
        return tables;
    }

    private static int versionOf(Path file) {
        Matcher m = Pattern.compile("^V(\\d+)__").matcher(file.getFileName().toString());
        assertThat(m.find()).as("마이그레이션 파일 이름이 V<숫자>__ 로 시작하지 않는다: %s", file).isTrue();
        return Integer.parseInt(m.group(1));
    }

    private static final Set<String> NOT_A_COLUMN = Set.of(
            "primary", "unique", "key", "index", "constraint", "foreign", "fulltext", "spatial", "check");

    /*
     * ★ 쪼개기 전에 **주석과 문자열 리터럴을 먼저 지운다.** 안 지우면 COMMENT '…, …' 안의 쉼표가
     *   컬럼 구분자로 읽히고, 괄호가 들어 있으면 깊이 추적까지 어긋나 테이블 전체가 깨진다.
     */
    private Set<String> columnNamesIn(String body) {
        String cleaned = stripStrings(stripLineComments(body));
        Set<String> columns = new LinkedHashSet<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        List<String> parts = new ArrayList<>();
        for (char ch : cleaned.toCharArray()) {
            if (ch == '(') depth++;
            if (ch == ')') depth--;
            if (ch == ',' && depth == 0) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        parts.add(current.toString());

        for (String raw : parts) {
            String trimmed = raw.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            String first = trimmed.split("\\s+")[0].replace("`", "").toLowerCase();
            if (NOT_A_COLUMN.contains(first) || first.isEmpty()) {
                continue;
            }
            columns.add(first);
        }
        return columns;
    }

    private String stripLineComments(String sql) {
        StringBuilder out = new StringBuilder();
        for (String line : sql.split("\n", -1)) {
            int i = indexOfCommentStart(line);
            out.append(i < 0 ? line : line.substring(0, i)).append('\n');
        }
        return out.toString();
    }

    // 문자열 안의 '--' 는 주석이 아니다.
    private int indexOfCommentStart(String line) {
        boolean inString = false;
        for (int i = 0; i < line.length() - 1; i++) {
            char c = line.charAt(i);
            if (c == '\'') {
                inString = !inString;
            } else if (!inString && c == '-' && line.charAt(i + 1) == '-') {
                return i;
            }
        }
        return -1;
    }

    private String stripStrings(String sql) {
        StringBuilder out = new StringBuilder();
        boolean inString = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'') {
                inString = !inString;
                out.append(' ');
                continue;
            }
            out.append(inString ? ' ' : c);
        }
        return out.toString();
    }

    private String toSnake(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }
}
