package com.kovil.domain.university;

import com.kovil.domain.search.dto.response.PlaceSearchResponse;
import com.kovil.domain.search.enums.PlaceType;
import com.kovil.domain.search.service.PlaceSearchService;
import com.kovil.domain.transit.dto.response.SubwayStationResponse;
import com.kovil.domain.transit.entity.SubwayLine;
import com.kovil.domain.transit.entity.SubwayStation;
import com.kovil.domain.transit.entity.SubwayStationLine;
import com.kovil.domain.transit.repository.SubwayStationRepository;
import com.kovil.domain.transit.service.SubwayStationQueryService;
import com.kovil.domain.university.dto.response.UniversityResponse;
import com.kovil.domain.university.entity.University;
import com.kovil.domain.university.repository.UniversityRepository;
import com.kovil.domain.university.service.UniversityQueryService;
import com.kovil.infra.jwt.BlacklistedTokenStore;
import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptStatementFailedException;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/*
 * 기준 데이터 DML(dml/*.sql)을 **진짜 MySQL** 에, CD 와 같은 방법으로 적용해 본다 — Flyway 로 최신까지 올린 뒤
 * dml/ 맨 위의 *.sql 을 이름 순으로(CD 의 for f in dml/*.sql) 두 번.
 * ★ 운영에는 ddl-auto 가 없다 — 마이그레이션 칸과 DML INSERT 칸이 어긋나면 배포 뒤 DML 단계에서야 터진다. 여기서 먼저 깨진다.
 * ★ 두 번째 적용에서 행 수가 그대로여야 멱등이다(배포마다 다시 돈다). 두 번째 적용은 모든 행이 ON DUPLICATE KEY UPDATE 를 탄다 —
 *   그 갱신 칸 목록에서 빠진 칸이 있으면, 원천이 고친 값이 운영에 영영 안 들어간다. 저장된 값을 틀리게 바꿔 두고 다시 돌려 본다.
 * ★ 행 수는 생성 파일 머리말의 '행 수' 와 같아야 한다 — 변환기와 SQL 이 어긋나면 여기서 보인다.
 * mysql CLI 대신 ScriptUtils 로 돌린다(러너에 CLI 가 없을 수 있다). 반경 MySQL 테스트와 같은 스키마(LISTING_RADIUS_DB_URL)를 쓴다.
 * 테스트끼리 같은 스키마를 쓰므로, 일부러 바꾼 행은 다시 적용하거나 finally 에서 지워 원래대로 돌려 둔다.
 */
@EnabledIfEnvironmentVariable(named = "LISTING_RADIUS_DB_URL", matches = ".+")
@SpringBootTest(properties = {
        "spring.datasource.url=${LISTING_RADIUS_DB_URL}",
        "spring.datasource.username=${LISTING_RADIUS_DB_USER:root}",
        "spring.datasource.password=${LISTING_RADIUS_DB_PASSWORD:}",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQLDialect",
        "spring.flyway.enabled=true"})
class MasterDataDmlMySqlTest {

    private static final String REQUIRED_SCHEMA = "wjf_listing_radius";
    private static final Path DML = Path.of("dml");
    private static final Path UNIVERSITIES = DML.resolve("04_universities.sql");
    private static final Path SUBWAY = DML.resolve("05_subway.sql");

    // 원천의 원격 대학(사이버 · 방송통신 — 이름이 아니라 과정 구분으로만 잡히는 행도 있다)과 KCUE 자리표시 행. 지도 점이 되지 않는다.
    private static final String EXCLUDED_UNIVERSITY_CODES = "'0000273','0000318','0000319','0000320','0000321','0000322',"
            + "'0000323','0000324','0000325','0000326','0000327','0000328','0000329','0000330','0000331','0000332','0000333',"
            + "'0000334','0000335','0000574','0000575','0000576','0003183','0000639'";

    private static final String UNIVERSITY_SNAPSHOT = "SELECT code, name_ko, name_en, campus_name_ko, campus_name_en, "
            + "address_road, lat, lng, region_id, is_active FROM universities ORDER BY code";
    private static final String LINE_SNAPSHOT = "SELECT code, name_ko, name_en, color, is_active, sort_order "
            + "FROM subway_lines ORDER BY code";
    private static final String STATION_SNAPSHOT = "SELECT code, name_ko, name_en, lat, lng, region_id, is_active "
            + "FROM subway_stations ORDER BY code";
    private static final String LINK_SNAPSHOT = "SELECT s.code station, l.code line, link.sort_order "
            + "FROM subway_station_lines link JOIN subway_stations s ON s.id = link.station_id "
            + "JOIN subway_lines l ON l.id = link.line_id ORDER BY s.code, link.sort_order, l.code";

    @Value("${spring.datasource.url}") String url;
    @Value("${spring.datasource.username}") String username;
    @Value("${spring.datasource.password}") String password;
    @Autowired JdbcTemplate jdbc;
    @Autowired UniversityRepository universityRepository;
    @Autowired SubwayStationRepository subwayStationRepository;
    @Autowired UniversityQueryService universityQueryService;
    @Autowired SubwayStationQueryService subwayStationQueryService;
    @Autowired PlaceSearchService placeSearchService;

    @MockitoBean BlacklistedTokenStore blacklistedTokenStore;

    @BeforeAll
    static void guard() {
        assertThat(System.getenv("LISTING_RADIUS_DB_URL"))
                .describedAs("LISTING_RADIUS_DB_URL 은 %s 스키마만 가리킬 수 있다", REQUIRED_SCHEMA)
                .contains("/" + REQUIRED_SCHEMA);
    }

    @Test
    @DisplayName("MySQL — dml/*.sql 을 CD 순서로 두 번 적용해도 행 수가 그대로이고, 활성 행 수가 머리말 행 수와 같다 · 숨겨졌던 행은 다시 보인다")
    void dml_applies_idempotently_on_real_schema() throws Exception {
        List<Path> files = dmlFiles();
        assertThat(files).extracting(p -> p.getFileName().toString()).contains("04_universities.sql", "05_subway.sql");

        applyAll(files);
        List<Long> first = totals();
        // 원천에서 빠졌다가(지난 배포의 숨김 UPDATE) 다시 들어온 학교 · 역 · 노선 — 다음 배포에서 다시 보여야 한다.
        jdbc.update("UPDATE universities SET is_active = 0 WHERE code = '0000019'");
        jdbc.update("UPDATE subway_stations SET is_active = 0 WHERE code = 'ST0147'");
        jdbc.update("UPDATE subway_lines SET is_active = 0 WHERE code = 'SEL-9'");
        applyAll(files);

        assertThat(totals()).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT is_active FROM universities WHERE code = '0000019'", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT is_active FROM subway_stations WHERE code = 'ST0147'", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT is_active FROM subway_lines WHERE code = 'SEL-9'", Boolean.class)).isTrue();

        assertThat(count("SELECT COUNT(*) FROM universities WHERE is_active = 1")).isEqualTo(universityHeaderRows());
        SubwayHeader subway = subwayHeader();
        assertThat(count("SELECT COUNT(*) FROM subway_lines WHERE is_active = 1")).isEqualTo(subway.lines());
        assertThat(count("SELECT COUNT(*) FROM subway_stations WHERE is_active = 1")).isEqualTo(subway.stations());
        assertThat(count("SELECT COUNT(*) FROM subway_station_lines link JOIN subway_stations s ON s.id = link.station_id "
                + "WHERE s.is_active = 1")).isEqualTo(subway.links());
        assertThat(count("SELECT COUNT(*) FROM (SELECT link.station_id FROM subway_station_lines link "
                + "JOIN subway_stations s ON s.id = link.station_id WHERE s.is_active = 1 "
                + "GROUP BY link.station_id HAVING COUNT(*) >= 2) t")).isEqualTo(subway.transfers());
        // 시군구는 주소로 전국에서 붙는다 — 못 정한 역(쪼개진 인천 옛 구 · 주소에 구도 동도 없는 역)은 머리말에 적힌 수만큼만 비어 있다.
        assertThat(count("SELECT COUNT(*) FROM subway_stations WHERE is_active = 1 AND region_id IS NOT NULL"))
                .isEqualTo(subwayPlacedStations());
        assertThat(subwayPlacedStations()).isGreaterThanOrEqualTo(subway.seoul());

        // 행정동(dml/08): INSERT ... SELECT 는 시군구 코드가 regions 에 없으면 오류 없이 0행이 된다 — 머리말 수와 맞춰 잡는다.
        assertThat(count("SELECT COUNT(*) FROM admin_dongs WHERE is_active = 1")).isEqualTo(adminDongHeaderRows());
        // SRID 4326 의 축 순서: POINT(경도, 위도). 서울시청은 중구 명동(1114055000) 안이어야 한다 — 축이 뒤집히면 0행.
        assertThat(jdbc.queryForObject("SELECT code FROM admin_dongs "
                + "WHERE ST_Contains(boundary, ST_SRID(POINT(126.9779, 37.5663), 4326))", String.class))
                .isEqualTo("1114055000");
    }

    @Test
    @DisplayName("MySQL — 다시 돌리면 이미 있는 행의 칸이 파일 값으로 덮인다(ON DUPLICATE KEY UPDATE 에서 빠진 칸이 없다)")
    void rerun_overwrites_changed_rows() throws Exception {
        List<Path> files = dmlFiles();
        applyAll(files);
        List<Map<String, Object>> universities = jdbc.queryForList(UNIVERSITY_SNAPSHOT);
        List<Map<String, Object>> lines = jdbc.queryForList(LINE_SNAPSHOT);
        List<Map<String, Object>> stations = jdbc.queryForList(STATION_SNAPSHOT);
        // 행 id 는 upsert 로 그대로여야 한다 — REPLACE INTO 나 지우고 다시 넣기로 바뀌면 역-노선 · 앱이 아는 행이 끊긴다.
        List<Long> ids = List.of(id("universities", "0000019"), id("subway_lines", "SEL-9"), id("subway_stations", "ST0147"));

        // 재생성 파일이 이름 · 좌표 · 자치구 · 색을 고친 상황 — 저장된 값을 전부 틀린 값으로 바꿔 두고 다시 돌린다.
        jdbc.update("UPDATE universities SET name_ko = '옛', name_en = 'Old', campus_name_ko = '제4캠퍼스', "
                + "campus_name_en = 'Campus 4', address_road = '옛 주소', lat = 0, lng = 0, region_id = NULL, is_active = 0");
        jdbc.update("UPDATE subway_lines SET name_ko = '옛', name_en = 'Old', color = '#000000', sort_order = 99, is_active = 0");
        jdbc.update("UPDATE subway_stations SET name_ko = '옛', name_en = 'Old', lat = 0, lng = 0, region_id = NULL, is_active = 0");
        // DML 은 updated_at 을 쓰지 않는다 — 원천이 고친 행에 시각을 찍는 것은 칸의 ON UPDATE 뿐이다. 옛 시각으로 돌려 두고 본다.
        jdbc.update("UPDATE universities SET updated_at = '2000-01-01 00:00:00' WHERE code = '0000019'");
        jdbc.update("UPDATE subway_stations SET updated_at = '2000-01-01 00:00:00' WHERE code = 'ST0147'");
        applyAll(files);

        assertThat(List.of(id("universities", "0000019"), id("subway_lines", "SEL-9"), id("subway_stations", "ST0147")))
                .isEqualTo(ids);
        assertThat(jdbc.queryForObject("SELECT updated_at FROM universities WHERE code = '0000019'", LocalDateTime.class))
                .isAfter(LocalDateTime.of(2000, 1, 2, 0, 0));
        assertThat(jdbc.queryForObject("SELECT updated_at FROM subway_stations WHERE code = 'ST0147'", LocalDateTime.class))
                .isAfter(LocalDateTime.of(2000, 1, 2, 0, 0));
        assertThat(jdbc.queryForList(UNIVERSITY_SNAPSHOT)).isEqualTo(universities);
        assertThat(jdbc.queryForList(LINE_SNAPSHOT)).isEqualTo(lines);
        assertThat(jdbc.queryForList(STATION_SNAPSHOT)).isEqualTo(stations);

        // 스냅숏만 보면 더러운 로컬 스키마에서 틀린 값끼리 같을 수 있다 — 한 행씩은 파일 값으로 못 박는다.
        Map<String, Object> snu = jdbc.queryForMap("SELECT u.name_ko, u.name_en, u.campus_name_ko, u.address_road, "
                + "u.lat, u.lng, r.code rc FROM universities u LEFT JOIN regions r ON r.id = u.region_id WHERE u.code = '0000019'");
        assertThat(snu).containsEntry("name_ko", "서울대학교").containsEntry("name_en", "Seoul National University")
                .containsEntry("address_road", "서울특별시 관악구 관악로 1").containsEntry("rc", "11620");
        assertThat(snu.get("campus_name_ko")).isNull();
        // 좌표는 7자리 그대로 — V 마이그레이션 칸의 자릿수가 줄면 MySQL 은 strict 모드에서도 오류 없이 반올림한다(Note 1265).
        assertThat((BigDecimal) snu.get("lat")).isEqualByComparingTo("37.4680381");
        assertThat((BigDecimal) snu.get("lng")).isEqualByComparingTo("126.9592943");
        assertThat(jdbc.queryForMap("SELECT campus_name_ko, campus_name_en FROM universities WHERE code = '0000048'"))
                .containsEntry("campus_name_ko", "제2캠퍼스").containsEntry("campus_name_en", "Campus 2");

        assertThat(jdbc.queryForMap("SELECT name_ko, name_en, color, sort_order, is_active FROM subway_lines WHERE code = 'SEL-9'"))
                .containsEntry("name_ko", "9호선").containsEntry("name_en", "Line 9").containsEntry("color", "#BDB092")
                .containsEntry("sort_order", 9).containsEntry("is_active", true);
        Map<String, Object> gimpo = jdbc.queryForMap("SELECT s.name_ko, s.name_en, s.lat, s.lng, r.code rc FROM subway_stations s "
                + "LEFT JOIN regions r ON r.id = s.region_id WHERE s.code = 'ST0147'");
        assertThat(gimpo).containsEntry("name_ko", "김포공항역").containsEntry("name_en", "Gimpo Int'l Airport")
                .containsEntry("rc", "11500");
        assertThat((BigDecimal) gimpo.get("lat")).isEqualByComparingTo("37.5621539");
        assertThat((BigDecimal) gimpo.get("lng")).isEqualByComparingTo("126.8020678");
    }

    @Test
    @DisplayName("MySQL — 역-노선은 파일대로 통째로 다시 맞춘다(빠진 줄은 되살리고, 없는 줄은 지우고, 순서는 되돌린다)")
    void subway_links_are_rebuilt_from_file() throws Exception {
        List<Path> files = dmlFiles();
        applyAll(files);
        List<Map<String, Object>> links = jdbc.queryForList(LINK_SNAPSHOT);

        jdbc.update("DELETE link FROM subway_station_lines link JOIN subway_stations s ON s.id = link.station_id "
                + "JOIN subway_lines l ON l.id = link.line_id WHERE s.code = 'ST0147' AND l.code = 'SEOHAE'");
        jdbc.update("UPDATE subway_station_lines link JOIN subway_stations s ON s.id = link.station_id "
                + "JOIN subway_lines l ON l.id = link.line_id SET link.sort_order = 9 WHERE s.code = 'ST0070' AND l.code = 'SEL-2'");
        jdbc.update("INSERT INTO subway_station_lines (station_id, line_id, sort_order) SELECT s.id, l.id, 3 "
                + "FROM subway_stations s JOIN subway_lines l ON l.code = 'SEL-9' WHERE s.code = 'ST0065'");
        applyAll(files);

        assertThat(jdbc.queryForList(LINK_SNAPSHOT)).isEqualTo(links);
        assertThat(linesOf("ST0147")).containsExactly("SEL-5", "SEL-9", "AREX", "SEOHAE", "GIMPO-GOLD");
        assertThat(linesOf("ST0070")).containsExactly("SEL-2", "SEL-5", "GYEONGUI-JUNGANG", "SUIN-BUNDANG");
        assertThat(linesOf("ST0065")).containsExactly("SEL-2");
    }

    @Test
    @DisplayName("MySQL — 파일에 없는 학교 · 역 · 노선 코드는 다시 적용하면 지우지 않고 숨긴다(is_active = 0)")
    void dml_retires_codes_missing_from_file() throws Exception {
        List<Path> files = dmlFiles();
        applyAll(files);
        try {
            jdbc.update("INSERT INTO universities (code, name_ko, name_en, address_road, lat, lng, is_active) "
                    + "VALUES ('9999999', '사라진대학교', 'Gone University', '어딘가', 37.5000000, 127.0000000, 1)");
            jdbc.update("INSERT INTO subway_lines (code, name_ko, name_en, color, is_active, sort_order) "
                    + "VALUES ('GONE-LINE', '사라진선', 'Gone Line', '#111111', 1, 50)");
            jdbc.update("INSERT INTO subway_stations (code, name_ko, name_en, lat, lng, is_active) "
                    + "VALUES ('ST9999', '사라진역', 'Gone', 37.5000000, 127.0000000, 1)");
            jdbc.update("INSERT INTO subway_station_lines (station_id, line_id, sort_order) SELECT s.id, l.id, 1 "
                    + "FROM subway_stations s JOIN subway_lines l ON l.code = 'GONE-LINE' WHERE s.code = 'ST9999'");

            applyAll(files);

            assertThat(jdbc.queryForList("SELECT is_active FROM universities WHERE code = '9999999'", Integer.class))
                    .as("원천에서 사라진 학교는 지우지 않고 숨긴다").containsExactly(0);
            assertThat(jdbc.queryForList("SELECT is_active FROM subway_lines WHERE code = 'GONE-LINE'", Integer.class))
                    .as("원천에서 사라진 노선은 지우지 않고 숨긴다").containsExactly(0);
            assertThat(jdbc.queryForList("SELECT is_active FROM subway_stations WHERE code = 'ST9999'", Integer.class))
                    .as("원천에서 사라진 역은 지우지 않고 숨긴다 — 앱이 코드를 들고 있을 수 있다").containsExactly(0);
            assertThat(linesOf("ST9999"))
                    .as("파일에 없는 역의 노선 줄은 다시 맞추기(DELETE) 범위 밖이다 — 이번 역들의 것만 지웠다 다시 넣는다")
                    .containsExactly("GONE-LINE");
            assertThat(count("SELECT COUNT(*) FROM universities WHERE is_active = 1")).isEqualTo(universityHeaderRows());
            assertThat(count("SELECT COUNT(*) FROM subway_lines WHERE is_active = 1")).isEqualTo(subwayHeader().lines());
            assertThat(count("SELECT COUNT(*) FROM subway_stations WHERE is_active = 1")).isEqualTo(subwayHeader().stations());
        } finally {
            jdbc.update("DELETE link FROM subway_station_lines link JOIN subway_stations s ON s.id = link.station_id "
                    + "WHERE s.code = 'ST9999'");
            jdbc.update("DELETE FROM subway_stations WHERE code = 'ST9999'");
            jdbc.update("DELETE FROM subway_lines WHERE code = 'GONE-LINE'");
            jdbc.update("DELETE FROM universities WHERE code = '9999999'");
        }
    }

    @Test
    @DisplayName("MySQL — 대학교 행이 변환기 규칙대로다(원격 · 자리표시 없음 · 한국 범위 · 주소의 구 = 자치구 · 캠퍼스 이름 언어)")
    void university_rows_follow_generator_rules() throws Exception {
        applyAll(dmlFiles());

        assertThat(count("SELECT COUNT(*) FROM universities WHERE is_active = 1 AND (code IN (" + EXCLUDED_UNIVERSITY_CODES + ") "
                + "OR name_ko LIKE '%사이버%' OR name_ko LIKE '%방송통신%')")).isZero();
        // 위도와 경도 구간이 겹치지 않으니, 어느 한 행이라도 lat/lng 가 뒤바뀌면 여기서 걸린다.
        assertThat(count("SELECT COUNT(*) FROM universities WHERE is_active = 1 "
                + "AND (lat NOT BETWEEN 33 AND 39 OR lng NOT BETWEEN 124 AND 132)")).isZero();
        // 시군구는 주소로 전국에서 붙는다 — 못 정한 행은 머리말에 적힌 것뿐이다. 서울 주소는 하나도 비지 않는다.
        assertThat(count("SELECT COUNT(*) FROM universities WHERE is_active = 1 AND region_id IS NULL"))
                .isEqualTo(universityUnplacedRows());
        assertThat(count("SELECT COUNT(*) FROM universities WHERE is_active = 1 "
                + "AND address_road LIKE '서울특별시 %' AND region_id IS NULL")).isZero();
        // 서울 학교의 구는 주소의 구와 같다(엉뚱한 · 실재하는 구 코드도 여기서 걸린다).
        assertThat(count("SELECT COUNT(*) FROM universities u JOIN regions r ON r.id = u.region_id "
                + "WHERE u.is_active = 1 AND r.code LIKE '11%' AND u.address_road NOT LIKE CONCAT('서울특별시 ', r.name_ko, ' %')")).isZero();
        // 붙은 지역은 열린 시군구다(시도 행이 아니고, 닫힌 옛 코드가 아니다).
        assertThat(count("SELECT COUNT(*) FROM universities u JOIN regions r ON r.id = u.region_id "
                + "WHERE u.is_active = 1 AND (r.parent_id IS NULL OR r.is_active = 0)")).isZero();
        // 캠퍼스 이름은 ko 칸에 한글, en 칸에 영문 — 변환기의 칸 순서 · CAMPUS_EN 표가 어긋나면 언어가 뒤바뀐다.
        assertThat(jdbc.queryForList("SELECT DISTINCT campus_name_ko, campus_name_en FROM universities "
                        + "WHERE is_active = 1 AND (campus_name_ko IS NOT NULL OR campus_name_en IS NOT NULL)"))
                .extracting(m -> m.get("campus_name_ko") + "=" + m.get("campus_name_en"))
                .containsExactlyInAnyOrder("제2캠퍼스=Campus 2", "제3캠퍼스=Campus 3", "제4캠퍼스=Campus 4", "분교=Branch Campus");
        assertThat(jdbc.queryForObject("SELECT name_ko FROM universities WHERE code = '0000019'", String.class))
                .isEqualTo("서울대학교");
        assertThat(jdbc.queryForMap("SELECT lat, lng FROM universities WHERE code = '0000001'"))
                .satisfies(m -> {
                    assertThat((BigDecimal) m.get("lat")).isEqualByComparingTo("37.7656949");
                    assertThat((BigDecimal) m.get("lng")).isEqualByComparingTo("128.8700048");
                });
    }

    @Test
    @DisplayName("MySQL — 지하철 행이 변환기 규칙대로다(모든 역에 노선 · 점 순서 1..n · 같은 이름 500m 안 두 역 없음 · 한국 범위 · 색 형식 · 전국)")
    void subway_rows_follow_generator_rules() throws Exception {
        applyAll(dmlFiles());

        assertThat(count("SELECT COUNT(*) FROM subway_stations s WHERE s.is_active = 1 AND NOT EXISTS ("
                + "SELECT 1 FROM subway_station_lines link JOIN subway_lines l ON l.id = link.line_id "
                + "WHERE link.station_id = s.id AND l.is_active = 1)")).as("노선 없는 역").isZero();
        assertThat(count("SELECT COUNT(*) FROM (SELECT link.station_id FROM subway_station_lines link "
                + "JOIN subway_stations s ON s.id = link.station_id WHERE s.is_active = 1 GROUP BY link.station_id "
                + "HAVING MIN(link.sort_order) <> 1 OR MAX(link.sort_order) <> COUNT(*) "
                + "OR COUNT(DISTINCT link.sort_order) <> COUNT(*)) t")).as("색 점 순서가 1..n 이 아닌 역").isZero();
        // 위도와 경도 구간이 겹치지 않으니, 어느 한 역이라도 lat/lng 가 뒤바뀌면 여기서 걸린다.
        assertThat(count("SELECT COUNT(*) FROM subway_stations WHERE is_active = 1 "
                + "AND (lat NOT BETWEEN 33 AND 39 OR lng NOT BETWEEN 124 AND 132)")).as("한국 밖 좌표").isZero();
        assertThat(count("SELECT COUNT(*) FROM subway_stations s JOIN regions r ON r.id = s.region_id "
                + "WHERE s.is_active = 1 AND (r.parent_id IS NULL OR r.is_active = 0)")).as("열린 시군구가 아닌 지역").isZero();
        assertThat(jdbc.queryForList("SELECT color FROM subway_lines WHERE is_active = 1", String.class))
                .allMatch(c -> c.matches("#[0-9A-F]{6}"));
        assertThat(jdbc.queryForList("SELECT sort_order FROM subway_lines WHERE is_active = 1 ORDER BY sort_order", Integer.class))
                .containsExactlyElementsOf(IntStream.rangeClosed(1, (int) subwayHeader().lines()).boxed().toList());

        record P(String code, String name, double lat, double lng) {}
        List<P> stations = jdbc.query("SELECT code, name_ko, lat, lng FROM subway_stations WHERE is_active = 1 ORDER BY code",
                (rs, i) -> new P(rs.getString(1), rs.getString(2), rs.getDouble(3), rs.getDouble(4)));
        List<String> close = new ArrayList<>();
        for (int i = 0; i < stations.size(); i++) {
            for (int j = i + 1; j < stations.size(); j++) {
                P a = stations.get(i);
                P b = stations.get(j);
                double d = meters(a.lat(), a.lng(), b.lat(), b.lng());
                if (a.name().equals(b.name()) && d < 500) {
                    close.add(a.code() + "~" + b.code() + " " + a.name() + " " + Math.round(d) + "m");
                }
            }
        }
        assertThat(close).as("같은 이름 · 500m 안 역은 한 마커로 묶는다").isEmpty();
        // 수도권 밖 도시철도도 들어 있다(PO 2026-10-13 — 처음 정한 수도권 23노선을 전국으로 넓혔다).
        assertThat(jdbc.queryForList("SELECT code FROM subway_lines WHERE is_active = 1 AND code IN "
                + "('BUSAN-1', 'BUSAN-2', 'BUSAN-3', 'BUSAN-4', 'BUSAN-GIMHAE', 'DONGHAE', 'DAEGU-1', 'DAEGU-2', 'DAEGU-3', "
                + "'DAEGYEONG', 'GWANGJU-1', 'DAEJEON-1')", String.class)).hasSize(12);
        assertThat(count("SELECT COUNT(*) FROM subway_lines WHERE is_active = 1 AND code = 'MAGLEV'")).as("자기부상은 뺀다").isZero();
        // 이름이 같아도 떨어진 두 역은 따로다 — 2호선 신촌(마포구)과 경의중앙선 신촌(서대문구).
        assertThat(jdbc.queryForList("SELECT CONCAT(s.code, ':', r.code) FROM subway_stations s JOIN regions r ON r.id = s.region_id "
                + "WHERE s.is_active = 1 AND s.name_ko = '신촌역' ORDER BY s.code", String.class))
                .containsExactly("ST0065:11440", "ST0278:11410");
    }

    @Test
    @DisplayName("MySQL — 같은 학교(괄호 꼬리를 뗀 이름)의 활성 점 둘이 1km 안에 겹치지 않는다 — 대학원 합치기 · 모체 고르기 규칙이 깨지면 같은 캠퍼스에 점이 둘 생긴다")
    void no_two_points_of_one_school_within_merge_radius() throws Exception {
        applyAll(dmlFiles());
        record P(String code, String family, double lat, double lng) {}
        List<P> points = jdbc.query("SELECT code, name_ko, lat, lng FROM universities WHERE is_active = 1 ORDER BY code",
                (rs, i) -> new P(rs.getString(1), rs.getString(2).replaceFirst("\\([^()]*\\)$", ""),
                        rs.getDouble(3), rs.getDouble(4)));
        List<String> close = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            for (int j = i + 1; j < points.size(); j++) {
                P a = points.get(i);
                P b = points.get(j);
                double d = meters(a.lat(), a.lng(), b.lat(), b.lng());
                if (a.family().equals(b.family()) && d < 1000) {
                    close.add(a.code() + "~" + b.code() + " " + a.family() + " " + Math.round(d) + "m");
                }
            }
        }
        assertThat(close).isEmpty();
    }

    @Test
    @DisplayName("MySQL — 대학교를 실제 조회(영역 · 지역 코드 · 언어)로 읽는다 — 국문 · 영문 칸이 제 칸에서 나온다")
    void university_layer_reads_applied_data() throws Exception {
        applyAll(dmlFiles());
        // 짧은 이름은 원천에 없고 DML 이 건드리지 않는다 — 국문 · 영문 매핑을 보려면 두 칸을 다르게 채운다.
        jdbc.update("UPDATE universities SET short_name_ko = '가톨릭대', short_name_en = 'CUK' WHERE code = '0000048'");
        try {
            // 배포마다 DML 이 다시 돈다 — 사람이 채운 짧은 이름이 살아남아야 한다(갱신 칸 목록에 short_name 을 넣거나 REPLACE INTO 로 바꾸면 지워진다).
            applyAll(dmlFiles());
            assertThat(jdbc.queryForMap("SELECT short_name_ko, short_name_en FROM universities WHERE code = '0000048'"))
                    .containsEntry("short_name_ko", "가톨릭대").containsEntry("short_name_en", "CUK");
            LocaleContextHolder.setLocale(Locale.KOREAN);
            List<UniversityResponse> sinchon = universityQueryService.findWithin(
                    new BigDecimal("37.555"), new BigDecimal("126.930"), new BigDecimal("37.570"), new BigDecimal("126.945"));
            assertThat(sinchon).filteredOn(u -> u.name().equals("연세대학교"))
                    .singleElement()
                    .satisfies(u -> {
                        assertThat(u.regionCode()).isEqualTo("11410");
                        assertThat(u.lat()).isEqualByComparingTo("37.5638064");
                        assertThat(u.lng()).isEqualByComparingTo("126.9429309");
                    });

            UniversityResponse ko = catholicSecondCampus();
            assertThat(ko.name()).isEqualTo("가톨릭대학교");
            assertThat(ko.shortName()).isEqualTo("가톨릭대");
            assertThat(ko.campusName()).isEqualTo("제2캠퍼스");
            assertThat(ko.regionCode()).isEqualTo("11650");

            LocaleContextHolder.setLocale(Locale.ENGLISH);
            UniversityResponse en = catholicSecondCampus();
            assertThat(en.name()).isEqualTo("The Catholic University of Korea");
            assertThat(en.shortName()).isEqualTo("CUK");
            assertThat(en.campusName()).isEqualTo("Campus 2");
        } finally {
            LocaleContextHolder.resetLocaleContext();
            jdbc.update("UPDATE universities SET short_name_ko = NULL, short_name_en = NULL WHERE code = '0000048'");
        }
    }

    @Test
    @DisplayName("MySQL — 지하철역을 실제 조회(엔티티 조인 + 생성자 표현식)로 읽는다 — 환승역 노선 순서 · 색 · 언어")
    void subway_layer_reads_applied_data() throws Exception {
        applyAll(dmlFiles());
        try {
            LocaleContextHolder.setLocale(Locale.KOREAN);
            SubwayStationResponse gimpo = gimpoAirport();
            assertThat(gimpo.name()).isEqualTo("김포공항역");
            assertThat(gimpo.lat()).isEqualByComparingTo("37.5621539");
            assertThat(gimpo.lng()).isEqualByComparingTo("126.8020678");
            assertThat(gimpo.lines()).extracting(SubwayStationResponse.Line::code)
                    .containsExactly("SEL-5", "SEL-9", "AREX", "SEOHAE", "GIMPO-GOLD");
            assertThat(gimpo.lines()).extracting(SubwayStationResponse.Line::name)
                    .containsExactly("5호선", "9호선", "공항철도", "서해선", "김포골드라인");
            assertThat(gimpo.lines()).extracting(SubwayStationResponse.Line::color)
                    .containsExactly("#996CAC", "#BDB092", "#0090D2", "#8FC31F", "#AD8605");

            List<SubwayStationResponse> sinchon = subwayStationQueryService.findWithin(
                    new BigDecimal("37.553"), new BigDecimal("126.935"), new BigDecimal("37.561"), new BigDecimal("126.944"));
            assertThat(sinchon).filteredOn(s -> s.code().equals("ST0065")).singleElement()
                    .satisfies(s -> {
                        assertThat(s.name()).isEqualTo("신촌역");
                        assertThat(s.lines()).extracting(SubwayStationResponse.Line::code).containsExactly("SEL-2");
                    });
            assertThat(sinchon).filteredOn(s -> s.code().equals("ST0278")).singleElement()
                    .satisfies(s -> assertThat(s.lines()).extracting(SubwayStationResponse.Line::code)
                            .containsExactly("GYEONGUI-JUNGANG"));

            // 부산 환승역 — 원천 환승칸이 '부산 도시철도 2호선' 처럼 도시를 붙여 적는다(서울 2호선으로 읽으면 안 된다).
            List<SubwayStationResponse> seomyeon = subwayStationQueryService.findWithin(
                    new BigDecimal("35.1567300"), new BigDecimal("129.0578435"), new BigDecimal("35.1587300"), new BigDecimal("129.0598435"));
            assertThat(seomyeon).extracting(SubwayStationResponse::code).containsExactly("ST0659");
            assertThat(seomyeon.get(0).name()).isEqualTo("서면역");
            assertThat(seomyeon.get(0).lines()).extracting(SubwayStationResponse.Line::name).containsExactly("부산 1호선", "부산 2호선");
            assertThat(seomyeon.get(0).lines()).extracting(SubwayStationResponse.Line::color).containsExactly("#F06A00", "#81BF48");
            assertThat(linesOf("ST0646")).containsExactly("BUSAN-1", "BUSAN-4", "DONGHAE");

            LocaleContextHolder.setLocale(Locale.ENGLISH);
            SubwayStationResponse gimpoEn = gimpoAirport();
            assertThat(gimpoEn.name()).isEqualTo("Gimpo Int'l Airport");
            assertThat(gimpoEn.lines()).extracting(SubwayStationResponse.Line::name)
                    .containsExactly("Line 5", "Line 9", "AREX", "Seohae Line", "Gimpo Goldline");
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
    }

    // 장소 검색 — LOWER · LIKE ESCAPE '!' · CASE 정렬이 MySQL 콜레이션에서도 H2 와 같은 순서를 내는지 실데이터로 본다.
    @Test
    @DisplayName("MySQL — 장소 검색을 실데이터로 — 순위가 유형보다 먼저 · 부제 · 열린 구 코드 · 언어 · 와일드카드는 글자")
    void place_search_reads_applied_data() throws Exception {
        applyAll(dmlFiles());
        try {
            LocaleContextHolder.setLocale(Locale.KOREAN);
            List<PlaceSearchResponse.Item> sinchon = placeSearchService.search("신촌", 20).items();
            assertThat(sinchon).extracting(PlaceSearchResponse.Item::code).containsExactly("ST0065", "ST0278");
            assertThat(sinchon).extracting(PlaceSearchResponse.Item::subtitle).containsExactly("2호선", "경의중앙선");
            assertThat(sinchon).extracting(PlaceSearchResponse.Item::regionCode).containsExactly("11440", "11410");

            // 대학교 앞부분 일치(1)가 지역 포함(2)보다 먼저다 — 유형 순서(지역 먼저)보다 순위가 앞선다.
            List<PlaceSearchResponse.Item> jangan = placeSearchService.search("장안", 20).items();
            assertThat(jangan).extracting(PlaceSearchResponse.Item::code).containsExactly("0000527", "41111");
            assertThat(jangan.get(1).name()).isEqualTo("수원시 장안구");
            assertThat(jangan.get(1).subtitle()).isEqualTo("경기도");
            assertThat(jangan.get(1).regionCode()).isEqualTo("41111");

            List<PlaceSearchResponse.Item> yonsei = placeSearchService.search("연세", 20).items();
            assertThat(yonsei).extracting(PlaceSearchResponse.Item::code).containsExactly("0000149", "0000150", "0003311");
            assertThat(yonsei.get(0).subtitle()).isEqualTo("서대문구");
            assertThat(yonsei.get(0).regionCode()).isEqualTo("11410");
            assertThat(yonsei.get(0).lat()).isEqualByComparingTo("37.5638064");
            assertThat(yonsei.get(1).subtitle()).isEqualTo("분교 · 원주시");
            assertThat(yonsei.get(1).regionCode()).isEqualTo("51130");

            // 유형마다 10개는 MySQL 의 CASE 순위로 자른다 — '서울' 앞부분 일치 대학교가 10개를 넘어, 코드가 더 작은 포함 일치
            // (남서울대학교 0000245 · 동서울대학교 0000450)는 남지 않는다. 순위 없이 코드로만 자르면 둘이 들어온다.
            assertThat(placeSearchService.search("서울", 50).items())
                    .filteredOn(item -> item.type() == PlaceType.UNIVERSITY)
                    .extracting(PlaceSearchResponse.Item::code)
                    .containsExactly("0000019", "0000036", "0000040", "0000098", "0000125",
                            "0000126", "0000127", "0000205", "0000255", "0000489");

            assertThat(placeSearchService.search("%", 20).items()).isEmpty();
            assertThat(placeSearchService.search("_", 20).items()).isEmpty();

            // 역 완전 일치(0)가 구 앞부분 일치(1)보다 먼저 — 대문자로 불러도 같다.
            LocaleContextHolder.setLocale(Locale.ENGLISH);
            List<PlaceSearchResponse.Item> seodaemun = placeSearchService.search("SEODAEMUN", 20).items();
            assertThat(seodaemun).extracting(PlaceSearchResponse.Item::code).containsExactly("ST0159", "11410");
            assertThat(seodaemun.get(0).subtitle()).isEqualTo("Line 5");
            assertThat(seodaemun.get(1).name()).isEqualTo("Seodaemun-gu");
            assertThat(seodaemun.get(1).subtitle()).isEqualTo("Seoul");
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
    }

    /*
     * ★ H2 테스트는 엔티티로 테이블을 만들어 길이 · 자릿수 · NULL 허용이 V 마이그레이션과 어긋나도 모른다.
     *   운영(MySQL)에서 칸이 엔티티보다 좁으면 긴 값이 잘리거나 저장이 실패하고, 좌표 자릿수가 줄면 오류 없이 반올림된다.
     */
    @Test
    @DisplayName("MySQL — 기준 데이터 테이블의 칸(이름 · 길이 · 자릿수 · NULL 허용 · 기본값)이 엔티티 @Column 과 같다")
    void master_tables_match_entity_mapping() {
        for (Class<?> entity : List.of(University.class, SubwayLine.class, SubwayStation.class, SubwayStationLine.class)) {
            String table = entity.getAnnotation(Table.class).name();
            Map<String, Map<String, Object>> cols = jdbc.queryForList(
                            "SELECT COLUMN_NAME, DATA_TYPE, DATETIME_PRECISION, CHARACTER_MAXIMUM_LENGTH, NUMERIC_PRECISION, NUMERIC_SCALE, "
                                    + "IS_NULLABLE, COLUMN_DEFAULT, EXTRA "
                                    + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?", table)
                    .stream()
                    .collect(Collectors.toMap(r -> ((String) r.get("COLUMN_NAME")).toLowerCase(), r -> r));
            List<String> mapped = new ArrayList<>();
            for (Class<?> c = entity; c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    Column column = f.getAnnotation(Column.class);
                    if (column == null) {
                        continue;
                    }
                    String name = table + "." + column.name();
                    mapped.add(column.name());
                    Map<String, Object> row = cols.get(column.name());
                    assertThat(row).as("%s 칸이 마이그레이션에 없다", name).isNotNull();
                    if (f.getType() == String.class) {
                        assertThat(((Number) row.get("CHARACTER_MAXIMUM_LENGTH")).longValue())
                                .as("%s 길이", name).isEqualTo((long) column.length());
                    }
                    if (f.getType() == BigDecimal.class) {
                        assertThat(((Number) row.get("NUMERIC_PRECISION")).intValue()).as("%s precision", name)
                                .isEqualTo(column.precision());
                        assertThat(((Number) row.get("NUMERIC_SCALE")).intValue()).as("%s scale", name)
                                .isEqualTo(column.scale());
                    }
                    if (f.getType() == Long.class) {
                        assertThat(row.get("DATA_TYPE")).as("%s 형", name).isEqualTo("bigint");
                    }
                    if (f.getType() == Boolean.class) {
                        assertThat(row.get("DATA_TYPE")).as("%s 형", name).isEqualTo("tinyint");
                    }
                    // 상속 칸(created_at · updated_at)도 여기로 온다 — TIMESTAMP 는 2038 한계, 자릿수가 줄면 소수 초가 잘린다.
                    if (f.getType() == LocalDateTime.class) {
                        assertThat(row.get("DATA_TYPE")).as("%s 형", name).isEqualTo("datetime");
                        assertThat(((Number) row.get("DATETIME_PRECISION")).intValue()).as("%s 소수 초 자릿수", name).isEqualTo(6);
                    }
                    // PK 는 @Column 의 nullable 기본값(true)과 달리 언제나 NOT NULL 이다.
                    boolean notNull = !column.nullable() || f.isAnnotationPresent(Id.class);
                    assertThat(row.get("IS_NULLABLE")).as("%s NULL 허용", name).isEqualTo(notNull ? "NO" : "YES");
                }
            }
            assertThat(cols.keySet()).as("%s 의 매핑 안 된 칸", table).containsExactlyInAnyOrderElementsOf(mapped);
            if (cols.containsKey("is_active")) {
                assertThat(String.valueOf(cols.get("is_active").get("COLUMN_DEFAULT"))).as("%s.is_active 기본값", table)
                        .isEqualTo("1");
            }
            // DML(ODKU · 숨김 UPDATE)은 JPA 를 거치지 않는다 — updated_at 을 갱신하는 것은 이 칸 속성뿐이다.
            if (cols.containsKey("updated_at")) {
                assertThat(String.valueOf(cols.get("updated_at").get("EXTRA"))).as("%s.updated_at ON UPDATE", table)
                        .containsIgnoringCase("on update current_timestamp");
            }
        }
    }

    /*
     * 지역(D-96 전국 서비스) — 공식 원천(법정동 · 행정구역분류)으로 만든 dml/03 을 V 마이그레이션(시도 열기) 뒤에 적용한다.
     * ★ 다시 적용해도 이름만 고친다 — 관리자가 닫거나 옮긴 구를 배포가 되돌리면 안 된다.
     */
    @Test
    @DisplayName("MySQL — 지역: 시도 16 · 시군구가 전부 열리고, 옛 광주 · 전남은 닫히며, 다시 적용해도 관리자가 닫은 구는 닫힌 채 · 관리자가 만든 구는 열린 채다")
    void regions_follow_official_source_and_keep_admin_choices() throws Exception {
        List<Path> files = dmlFiles();
        applyAll(files);
        Matcher m = header(DML.resolve("03_regions.sql"), "^-- 행 수: 시도 (\\d+) · 시군구 (\\d+)");
        long sido = Long.parseLong(m.group(1));
        long sigungu = Long.parseLong(m.group(2));

        assertThat(jdbc.queryForList("SELECT code FROM regions WHERE parent_id IS NULL AND is_active = 1 ORDER BY code", String.class))
                .hasSize((int) sido).contains("11", "12", "41", "51", "52").doesNotContain("29", "46");
        assertThat(jdbc.queryForList("SELECT CONCAT(code, ':', is_active) FROM regions WHERE code IN ('29', '46') ORDER BY code",
                String.class)).containsExactly("29:0", "46:0");
        // 열린 시군구 = 자기도 열리고 시도도 열림(매물 게시 · 요청 지역 판정과 같은 기준).
        assertThat(count("SELECT COUNT(*) FROM regions r JOIN regions p ON p.id = r.parent_id "
                + "WHERE r.is_active = 1 AND p.is_active = 1")).isEqualTo(sigungu);
        assertThat(count("SELECT COUNT(*) FROM regions r JOIN regions p ON p.id = r.parent_id "
                + "WHERE p.code = '12' AND r.is_active = 1")).isEqualTo(27);
        assertThat(jdbc.queryForMap("SELECT name_ko, name_en FROM regions WHERE code = '41111'"))
                .containsEntry("name_ko", "수원시 장안구").containsEntry("name_en", "Suwon-si Jangan-gu");
        assertThat(count("SELECT COUNT(*) FROM regions WHERE code = '41110'")).as("일반구가 있는 시 행은 넣지 않는다").isZero();

        Map<String, Object> before = jdbc.queryForMap("SELECT sort_order FROM regions WHERE code = '41111'");
        jdbc.update("UPDATE regions SET is_active = 0, sort_order = 99, name_ko = '옛 이름' WHERE code = '41111'");
        // 관리자 웹으로 만든 원천 밖 지역 — 배포가 닫으면 그 지역 매물은 공개된 채 고치지도 연장하지도 못한다(내림 · 알림은 관리자 경로뿐).
        jdbc.update("INSERT INTO regions (parent_id, code, name_ko, name_en, is_active, sort_order) "
                + "SELECT id, '11999', '관리자구', 'Admin-gu', 1, 50 FROM regions WHERE code = '11'");
        try {
            applyAll(files);
            assertThat(jdbc.queryForMap("SELECT name_ko, is_active, sort_order FROM regions WHERE code = '41111'"))
                    .containsEntry("name_ko", "수원시 장안구")
                    .containsEntry("is_active", false)
                    .containsEntry("sort_order", 99);
            assertThat(jdbc.queryForMap("SELECT name_ko, is_active FROM regions WHERE code = '11999'"))
                    .as("원천에 없는 코드라도 RETIRED 표에 없으면 배포가 닫지 않는다")
                    .containsEntry("name_ko", "관리자구")
                    .containsEntry("is_active", true);
        } finally {
            jdbc.update("UPDATE regions SET is_active = 1, sort_order = ? WHERE code = '41111'", before.get("sort_order"));
            jdbc.update("DELETE FROM regions WHERE code = '11999'");
        }
    }

    // 지역을 지워도 대학교 · 역이 지워지거나 비지 않는다(RESTRICT). 역을 지우면 그 역의 노선 줄만 따라 지워진다(CASCADE).
    @Test
    @DisplayName("MySQL — 기준 데이터 테이블의 FK 와 삭제 규칙")
    void master_foreign_keys_keep_delete_rules() {
        assertThat(jdbc.queryForList("SELECT CONCAT(TABLE_NAME, '.', CONSTRAINT_NAME, ':', REFERENCED_TABLE_NAME, ':', DELETE_RULE) "
                + "FROM information_schema.REFERENTIAL_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE() "
                + "AND TABLE_NAME IN ('universities', 'subway_lines', 'subway_stations', 'subway_station_lines')", String.class))
                .containsExactlyInAnyOrder(
                        "universities.fk_univ_region:regions:RESTRICT",
                        "subway_stations.fk_ss_region:regions:RESTRICT",
                        "subway_station_lines.fk_ssl_station:subway_stations:CASCADE",
                        "subway_station_lines.fk_ssl_line:subway_lines:RESTRICT");
    }

    @Test
    @DisplayName("MySQL — 기준 데이터 테이블의 인덱스(이름 · 유일 여부 · 칼럼 순서)")
    void master_tables_keep_indexes() {
        assertThat(jdbc.queryForList(
                "SELECT CONCAT(TABLE_NAME, '.', INDEX_NAME, ':', IF(NON_UNIQUE = 0, 'U', 'I'), ':', "
                        + "GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX)) "
                        + "FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() "
                        + "AND TABLE_NAME IN ('universities', 'subway_lines', 'subway_stations', 'subway_station_lines') "
                        + "GROUP BY TABLE_NAME, INDEX_NAME, NON_UNIQUE", String.class))
                .containsExactlyInAnyOrder(
                        "universities.PRIMARY:U:id",
                        "universities.uq_univ_code:U:code",
                        "universities.idx_univ_map:I:is_active,lat,lng",
                        "universities.idx_univ_region:I:region_id",
                        "subway_lines.PRIMARY:U:id",
                        "subway_lines.uq_sl_code:U:code",
                        "subway_stations.PRIMARY:U:id",
                        "subway_stations.uq_ss_code:U:code",
                        "subway_stations.idx_ss_map:I:is_active,lat,lng",
                        "subway_stations.idx_ss_region:I:region_id",
                        "subway_station_lines.PRIMARY:U:id",
                        "subway_station_lines.uq_ssl_pair:U:station_id,line_id",
                        "subway_station_lines.idx_ssl_line:I:line_id");
    }

    @Test
    @DisplayName("MySQL — 한 역에 같은 노선 줄은 두 번 들어가지 못한다(uq_ssl_pair) — 생성기가 같은 쌍을 두 번 내면 적용에서 멈춘다")
    void subway_link_pair_is_unique() throws Exception {
        applyAll(dmlFiles());
        // sort_order 9 는 ST0065 에 없는 값 — (station_id, sort_order) 유일로 바뀐 경우도 여기서 걸린다.
        assertThatThrownBy(() -> jdbc.update("INSERT INTO subway_station_lines (station_id, line_id, sort_order) "
                + "SELECT s.id, l.id, 9 FROM subway_stations s JOIN subway_lines l ON l.code = 'SEL-2' WHERE s.code = 'ST0065'"))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(linesOf("ST0065")).containsExactly("SEL-2");
    }

    // 다시 맞추기가 중간에 실패한 배포(INSERT 만 실패)에서 지운 줄이 되돌아가지 않으면 모든 역이 노선 없이 남는다.
    @Test
    @DisplayName("MySQL — 역-노선 다시 넣기가 중간에 실패하면 지운 줄도 되돌아간다(지우기 · 넣기가 한 트랜잭션)")
    void subway_link_rebuild_is_atomic(@TempDir Path tmp) throws Exception {
        applyAll(dmlFiles());
        List<Map<String, Object>> links = jdbc.queryForList(LINK_SNAPSHOT);
        assertThat(links).isNotEmpty();

        String sql = Files.readString(SUBWAY);
        String broken = sql.replace("JOIN subway_lines l ON l.code = x.line_code;", "JOIN subway_lines l ON l.code = x.no_such_column;");
        assertThat(broken).isNotEqualTo(sql);
        Path file = tmp.resolve("05_subway.sql");
        Files.writeString(file, broken);

        // 풀 밖의 연결 — CD 의 mysql CLI 처럼 오류에서 멈추고 끊는다. 세션이 끝나면 서버가 열린 트랜잭션을 되돌린다.
        try (Connection c = DriverManager.getConnection(url, username, password)) {
            assertThatThrownBy(() -> ScriptUtils.executeSqlScript(c,
                    new EncodedResource(new FileSystemResource(file), StandardCharsets.UTF_8)))
                    .isInstanceOf(ScriptStatementFailedException.class);
        }

        assertThat(jdbc.queryForList(LINK_SNAPSHOT)).isEqualTo(links);
        assertThat(linesOf("ST0147")).containsExactly("SEL-5", "SEL-9", "AREX", "SEOHAE", "GIMPO-GOLD");
    }

    /*
     * 운영에는 ddl-auto 가 없다 — id 생성 전략이 V 마이그레이션의 AUTO_INCREMENT 와 어긋나면(SEQUENCE · AUTO 는 *_seq 테이블을 찾는다)
     * H2 는 시퀀스를 만들어 주므로 모르고, 첫 JPA 저장에서야 터진다. 지금은 DML 만 쓰지만 관리 화면이 생길 때를 막는다.
     */
    @Test
    @DisplayName("MySQL — 대학교 · 역을 JPA 로 저장할 수 있다(id 는 AUTO_INCREMENT · 감사 칸이 채워진다)")
    void master_entities_persist_on_real_schema() {
        try {
            University university = universityRepository.saveAndFlush(University.builder()
                    .code("9999998").nameKo("저장대학교").nameEn("Saved University").addressRoad("어딘가")
                    .lat(new BigDecimal("37.5000000")).lng(new BigDecimal("127.0000000")).build());
            SubwayStation station = subwayStationRepository.saveAndFlush(SubwayStation.builder()
                    .code("ST9998").nameKo("저장역").nameEn("Saved").lat(new BigDecimal("37.5000000"))
                    .lng(new BigDecimal("127.0000000")).build());
            assertThat(jdbc.queryForObject("SELECT id FROM universities WHERE code = '9999998'", Long.class))
                    .isEqualTo(university.getId());
            assertThat(jdbc.queryForObject("SELECT id FROM subway_stations WHERE code = 'ST9998'", Long.class))
                    .isEqualTo(station.getId());
            assertThat(jdbc.queryForObject("SELECT created_at IS NOT NULL AND updated_at IS NOT NULL FROM subway_stations "
                    + "WHERE code = 'ST9998'", Boolean.class)).isTrue();
        } finally {
            jdbc.update("DELETE FROM universities WHERE code = '9999998'");
            jdbc.update("DELETE FROM subway_stations WHERE code = 'ST9998'");
        }
    }

    // 반포 상자 안에는 가톨릭대 제2캠퍼스(0000048) 한 점뿐이다.
    private UniversityResponse catholicSecondCampus() {
        List<UniversityResponse> banpo = universityQueryService.findWithin(
                new BigDecimal("37.500"), new BigDecimal("127.003"), new BigDecimal("37.505"), new BigDecimal("127.008"));
        assertThat(banpo).extracting(UniversityResponse::code).containsExactly("0000048");
        return banpo.get(0);
    }

    // 김포공항역(ST0147) 둘레 ±0.001° 상자 — 그 안의 역은 김포공항역 하나다.
    private SubwayStationResponse gimpoAirport() {
        List<SubwayStationResponse> found = subwayStationQueryService.findWithin(
                new BigDecimal("37.5611539"), new BigDecimal("126.8010678"), new BigDecimal("37.5631539"), new BigDecimal("126.8030678"));
        assertThat(found).extracting(SubwayStationResponse::code).containsExactly("ST0147");
        return found.get(0);
    }

    private List<String> linesOf(String stationCode) {
        return jdbc.queryForList("SELECT l.code FROM subway_station_lines link JOIN subway_stations s ON s.id = link.station_id "
                + "JOIN subway_lines l ON l.id = link.line_id WHERE s.code = ? ORDER BY link.sort_order", String.class, stationCode);
    }

    private Long id(String table, String code) {
        return jdbc.queryForObject("SELECT id FROM " + table + " WHERE code = ?", Long.class, code);
    }

    private List<Long> totals() {
        return Stream.of("universities", "subway_lines", "subway_stations", "subway_station_lines")
                .map(t -> count("SELECT COUNT(*) FROM " + t)).toList();
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    // 변환기(gen_universities.py · gen_subway.py 의 meters)와 같은 등장방형 근사 — 경계를 같은 식으로 잰다.
    private static double meters(double lat1, double lng1, double lat2, double lng2) {
        double la1 = Math.toRadians(lat1);
        double la2 = Math.toRadians(lat2);
        double x = Math.toRadians(lng2 - lng1) * Math.cos((la1 + la2) / 2);
        return 6_371_000 * Math.hypot(x, la2 - la1);
    }

    private static List<Path> dmlFiles() throws IOException {
        try (Stream<Path> s = Files.list(DML)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".sql")).sorted().toList();
        }
    }

    /*
     * CD 는 파일마다 mysql 을 새로 띄우고 끝나면 끊는다 — 파일이 트랜잭션을 연 채(COMMIT 빠짐) 끝나면 서버가 그 일을 되돌린다.
     * 풀 연결 하나로 돌리면 같은 세션이 커밋 안 된 일을 자기 것으로 보고, 다음 파일의 START TRANSACTION 이 대신 커밋해 버려 못 본다.
     * 그래서 파일마다 풀 밖의 새 연결을 쓰고, 끝에 열린 트랜잭션이 없는지 본다.
     * 주석뿐인 파일(01_banks — 배포 정리용으로 일부러 비워 둠)은 mysql CLI 처럼 그냥 지나간다. ScriptUtils 는 빈 스크립트를 거절한다.
     */
    private void applyAll(List<Path> files) throws Exception {
        for (Path file : files) {
            if (!hasStatements(file)) {
                continue;
            }
            try (Connection connection = DriverManager.getConnection(url, username, password)) {
                ScriptUtils.executeSqlScript(connection, new EncodedResource(new FileSystemResource(file), StandardCharsets.UTF_8));
                try (Statement st = connection.createStatement();
                     ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM information_schema.INNODB_TRX "
                             + "WHERE trx_mysql_thread_id = CONNECTION_ID()")) {
                    rs.next();
                    assertThat(rs.getLong(1)).as("%s 가 트랜잭션을 연 채 끝난다(COMMIT 빠짐) — CD 에서는 되돌려진다", file).isZero();
                }
            }
        }
    }

    private static boolean hasStatements(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                .map(String::strip)
                .anyMatch(line -> !line.isEmpty() && !line.startsWith("--"));
    }

    private static long universityHeaderRows() throws IOException {
        Matcher m = header(UNIVERSITIES, "^-- 행 수: (\\d+) = ");
        return Long.parseLong(m.group(1));
    }

    private static long adminDongHeaderRows() throws IOException {
        return Long.parseLong(header(DML.resolve("08_admin_dongs.sql"), "^-- dml/08_admin_dongs\\.sql — 행정동\\(읍면동\\) (\\d+)개").group(1));
    }

    private static long subwayPlacedStations() throws IOException {
        return Long.parseLong(header(SUBWAY, "^-- 시군구가 붙은 역 (\\d+) · 못 정한 역 \\d+").group(1));
    }

    private static long universityUnplacedRows() throws IOException {
        String listed = header(UNIVERSITIES, "^-- 시군구를 못 정한 행: (.+)$").group(1);
        return "없음".equals(listed) ? 0 : listed.split(" · ").length;
    }

    private record SubwayHeader(long lines, long stations, long seoul, long transfers, long links) {
    }

    private static SubwayHeader subwayHeader() throws IOException {
        // 시도 목록은 역이 있는 시도만(서울이 맨 앞) — 변환기 테스트(dml/tools/test_gen_subway.py)도 같은 식으로 읽는다.
        Matcher m = header(SUBWAY, "^-- 행 수: 노선 (\\d+) · 역 (\\d+)\\(서울 (\\d+)(?: · \\S+ \\d+)*, 환승역 (\\d+)\\) · 역-노선 (\\d+)$");
        return new SubwayHeader(Long.parseLong(m.group(1)), Long.parseLong(m.group(2)), Long.parseLong(m.group(3)),
                Long.parseLong(m.group(4)), Long.parseLong(m.group(5)));
    }

    private static Matcher header(Path file, String regex) throws IOException {
        Matcher m = Pattern.compile(regex, Pattern.MULTILINE).matcher(Files.readString(file));
        assertThat(m.find()).as("%s 머리말에 행 수가 있다(%s)", file, regex).isTrue();
        return m;
    }
}
