package com.kovil.domain.rentalrequest;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/*
 * ★ V28(임대 요청을 파트너스로)의 **기존 행 이관**을 진짜 MySQL 에 고정한다. 테스트 프로파일은 H2 + Flyway 꺼짐이라
 *   이관 UPDATE · ENUM 축소 · 칼럼 삭제 순서가 틀려도 게이트는 그린이다 — 운영에 처음 적용할 때 터진다.
 *     · 담당자가 잡고 있던 진행 중 → 대기(담당자 칸 비움), 계약됨 → 종료 + 방을 구함(종료 시각 = 그 행의 갱신 시각).
 *     · 대기 · 취소 · 옛 종료는 그대로(옛 종료의 사유도 남는다). 이관이 갱신 시각을 마이그레이션 시각으로 덮지 않는다.
 *     · 담당자 칸 · 담당자 메모 테이블이 사라지고, 맡은 사무소 칸(FK RESTRICT) · 잡은 시각 · 결과 · (사무소, 상태, id) 인덱스가 생긴다.
 *     · 상태 ENUM 이 넷으로 줄어 계약됨을 더는 넣을 수 없다.
 *     · 옛 "처리 시작" 알림은 옛 문구 키로 옮긴다(새 문구가 사무소 상호 {1} 을 싣는다). 다른 알림은 그대로.
 *     · 아직 안 나간 옛 "처리 시작" 푸시(PENDING · FAILED)는 닫는다 — 요청이 대기로 돌아간 뒤 "처리 중"이 도착하면 안 된다.
 *       포기(GIVEN_UP) · 보냄(SENT) · 다른 알림은 그대로.
 *     · 맡은 사무소 FK 는 (office_id) 단독 인덱스에 얹힌다 — 사무소 행이 잠겨 있어도 상태만 바꾸는 UPDATE 는 기다리지 않는다.
 *     · 잡기 이력 테이블이 빈 채로 생긴다 — 요청 · 사무소 FK(RESTRICT)만 있고 행위자(users)에는 FK 가 없다. 잠금으로 확인한다:
 *       행위자의 users 행이 잠겨 있어도 이력 INSERT 는 기다리지 않고, 요청 · 사무소 행이 잠겨 있으면 FK 검사로 기다린다(대조).
 *
 * 스프링을 띄우지 않는다 — Flyway 자바 API 로 V27 까지 올리고, 담당자 시절의 행을 심고, V28 을 올려 이관을 본다.
 * 그 뒤 최신까지 올려 잠금을 본다 — 뒤 버전이 FK 전용 인덱스를 지우는 회귀도 여기서 잡힌다(CI 의 마이그레이션 검증 단계).
 * RR_MIGRATION_DB_URL 이 없으면 통째로 건너뛴다(FlywayMigrationTest 와 같은 방식). 그 이름의 빈 스키마만 쓴다 — 지우는 코드는 두지 않는다.
 */
@EnabledIfEnvironmentVariable(named = "RR_MIGRATION_DB_URL", matches = ".+")
class RentalRequestV28MigrationMySqlTest {

    private static final String REQUIRED_SCHEMA = "wjf_rr_migration";

    private static String url() {
        return System.getenv("RR_MIGRATION_DB_URL");
    }

    private static String env(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value;
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(url(), env("RR_MIGRATION_DB_USER"), env("RR_MIGRATION_DB_PASSWORD"));
    }

    private static Flyway flyway(String target) {
        var config = Flyway.configure()
                .dataSource(url(), env("RR_MIGRATION_DB_USER"), env("RR_MIGRATION_DB_PASSWORD"))
                .locations("classpath:db/migration");
        return (target == null ? config : config.target(target)).load();
    }

    @Test
    @DisplayName("V28 이 담당자 시절의 요청 · 알림 행을 계약대로 옮기고 스키마를 바꾼다")
    void v28_migrates_existing_rows() throws Exception {
        assertThat(url()).describedAs("RR_MIGRATION_DB_URL 은 %s 스키마만 가리킬 수 있다", REQUIRED_SCHEMA)
                .contains("/" + REQUIRED_SCHEMA);
        assertThat(count("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = '" + REQUIRED_SCHEMA + "'"))
                .describedAs("빈 스키마에서만 한다").isZero();

        MigrateResult upTo27 = flyway("27").migrate();
        assertThat(upTo27.targetSchemaVersion).isEqualTo("27");
        seedManagerEraRows();

        MigrateResult v28 = flyway("28").migrate();
        assertThat(v28.success).isTrue();
        assertThat(v28.targetSchemaVersion).isEqualTo("28");

        // 대기 — 그대로.
        assertThat(row(9201)).containsExactly("SUBMITTED", null, null, null, null, null, "2026-09-01 11:00:00", "0");
        // 담당자가 잡고 있던 진행 중 → 대기. 맡은 사무소 · 잡은 시각은 비어 있고, 갱신 시각 · 버전은 그대로.
        assertThat(row(9202)).containsExactly("SUBMITTED", null, null, null, null, null, "2026-09-02 09:30:00", "3");
        // 계약됨 → 종료 + 방을 구함. 종료 시각은 그 행의 마지막 갱신 시각이다.
        assertThat(row(9203)).containsExactly("CLOSED", null, null, "FOUND", null, "2026-09-04 15:45:00",
                "2026-09-04 15:45:00", "5");
        // 취소 · 옛 종료(담당자가 사유로 접은 것) — 그대로, 사유도 남는다. 옛 종료에는 결과가 없다.
        assertThat(row(9204)).containsExactly("CANCELED", null, null, null, "직접 구했습니다", "2026-09-05 10:00:00",
                "2026-09-05 10:00:00", "1");
        assertThat(row(9205)).containsExactly("CLOSED", null, null, null, "조건에 맞는 매물이 없습니다",
                "2026-09-07 10:00:00", "2026-09-07 10:00:00", "2");

        assertThat(columns()).contains("office_id", "claimed_at", "outcome")
                .doesNotContain("assigned_manager_id", "assigned_at", "started_at");
        assertThat(text("SELECT COLUMN_TYPE FROM information_schema.columns WHERE table_schema = DATABASE() "
                + "AND table_name = 'rental_requests' AND column_name = 'status'"))
                .isEqualTo("enum('SUBMITTED','IN_PROGRESS','CANCELED','CLOSED')");
        assertThat(text("SELECT COLUMN_TYPE FROM information_schema.columns WHERE table_schema = DATABASE() "
                + "AND table_name = 'rental_requests' AND column_name = 'outcome'"))
                .isEqualTo("enum('FOUND','NOT_PROCEEDED')");
        assertThat(text("SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics "
                + "WHERE table_schema = DATABASE() AND table_name = 'rental_requests' AND index_name = 'idx_rr_office'"))
                .isEqualTo("office_id,status,id");
        assertThat(text("SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics "
                + "WHERE table_schema = DATABASE() AND table_name = 'rental_requests' AND index_name = 'idx_rr_office_fk'"))
                .isEqualTo("office_id");
        assertThat(text("SELECT CONCAT(r.REFERENCED_TABLE_NAME, ':', r.DELETE_RULE) "
                + "FROM information_schema.referential_constraints r WHERE r.constraint_schema = DATABASE() "
                + "AND r.constraint_name = 'fk_rr_office'")).isEqualTo("brokerage_offices:RESTRICT");
        assertThat(count("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() "
                + "AND table_name = 'rental_request_memos'")).isZero();
        assertThat(count("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() "
                + "AND table_name = 'rental_requests' AND index_name = 'idx_rr_manager'")).isZero();

        // 옛 "처리 시작" 알림만 옛 문구 키로 — 종료 알림은 그대로. 발송 큐의 갱신 시각도 덮지 않는다.
        assertThat(text("SELECT GROUP_CONCAT(template_code ORDER BY uuid) FROM user_notifications "
                + "WHERE uuid LIKE 'p3-unt-%'"))
                .isEqualTo("rental_request_in_progress_legacy,rental_request_closed");
        assertThat(text("SELECT GROUP_CONCAT(CONCAT(template_code, '@', DATE_FORMAT(updated_at, '%Y-%m-%d %H:%i:%s')) "
                + "ORDER BY id) FROM notification_outbox WHERE id IN (9301, 9302)"))
                .isEqualTo("rental_request_in_progress_legacy@2026-09-02 09:31:00,rental_request_closed@2026-09-07 10:01:00");
        // 아직 안 나간 옛 "처리 시작" 푸시는 닫힌다(시도 횟수는 그대로). 포기 · 보냄 · 다른 알림의 대기 행은 그대로다.
        assertThat(text("SELECT GROUP_CONCAT(CONCAT(id, ':', status, ':', attempt_count, ':', template_code) ORDER BY id) "
                + "FROM notification_outbox WHERE id BETWEEN 9301 AND 9306"))
                .isEqualTo("9301:SENT:0:rental_request_in_progress_legacy,"
                        + "9302:SENT:0:rental_request_closed,"
                        + "9303:SKIPPED:0:rental_request_in_progress_legacy,"
                        + "9304:SKIPPED:2:rental_request_in_progress_legacy,"
                        + "9305:GIVEN_UP:5:rental_request_in_progress_legacy,"
                        + "9306:PENDING:0:rental_request_closed");
        assertThat(count("SELECT COUNT(*) FROM notification_outbox WHERE id IN (9303, 9304) "
                + "AND last_error LIKE '임대 요청 이관%'")).isEqualTo(2);

        claimEventsTableShape();

        // 상태 ENUM 이 줄었다 — 계약됨은 더는 들어가지 않는다(엄격 모드).
        assertThatThrownBy(() -> execute("UPDATE rental_requests SET status = 'CONTRACTED' WHERE id = 9201"))
                .isInstanceOf(SQLException.class);

        // ★ 잠금은 **최신까지 올린** 스키마에서 본다. V28 뒤의 마이그레이션이 FK 전용 인덱스를 지우면(복합 인덱스와 겹쳐 "중복"으로
        //   보인다) MySQL 은 말없이 FK 를 복합 인덱스로 옮긴다 — V28 에서만 보면 그 회귀를 못 잡는다. CI 의 마이그레이션 검증이 이 탐침을 돈다.
        MigrateResult latest = flyway(null).migrate();
        assertThat(latest.success).isTrue();
        assertThat(text("SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics "
                + "WHERE table_schema = DATABASE() AND table_name = 'rental_requests' AND index_name = 'idx_rr_office_fk'"))
                .as("최신 스키마에도 FK 전용 인덱스가 남아 있다").isEqualTo("office_id");
        statusOnlyUpdateDoesNotWaitForLockedOffice();
        claimEventInsertLocks();
    }

    /*
     * ★ 맡은 사무소 FK 가 어느 인덱스에 얹혔는지는 information_schema 로 보이지 않는다 — 잠금으로 직접 확인한다.
     *   사무소 행을 배타로 쥔 채(놓기 · 종료 · 대기 복귀가 하는 일) 그 사무소가 맡은 요청의 상태만 바꾸면(유학생 취소 · 탈퇴)
     *   기다리지 않아야 한다. 대조로, office_id 를 채우는 UPDATE(잡기)는 FK 검사로 그 행을 기다려야 한다 — 잠금이 정말 걸려 있다는 증거다.
     */
    private static void statusOnlyUpdateDoesNotWaitForLockedOffice() throws SQLException {
        execute("""
                INSERT INTO brokerage_offices (id, uuid, reg_no, name, ceo_name, address, tel)
                VALUES (8001, 'p3-office-000-0000-0000-000000000001', '99999-2026-00001', '테스트공인중개사사무소', '홍길동',
                        '서울특별시 광진구 능동로 000', '02-000-0000')""");
        execute("UPDATE rental_requests SET status = 'IN_PROGRESS', office_id = 8001, claimed_at = NOW(6) WHERE id = 9202");

        try (Connection holder = connect(); Connection student = connect(); Connection claimer = connect()) {
            holder.setAutoCommit(false);
            student.setAutoCommit(false);
            claimer.setAutoCommit(false);
            try (Statement h = holder.createStatement(); Statement s = student.createStatement();
                 Statement c = claimer.createStatement()) {
                h.executeQuery("SELECT id FROM brokerage_offices WHERE id = 8001 FOR UPDATE").close();
                s.execute("SET SESSION innodb_lock_wait_timeout = 1");
                c.execute("SET SESSION innodb_lock_wait_timeout = 1");

                assertThat(s.executeUpdate("UPDATE rental_requests SET status = 'CANCELED' WHERE id = 9202"))
                        .as("상태만 바꾸는 UPDATE 는 잠긴 사무소 행을 기다리지 않는다").isEqualTo(1);
                assertThatThrownBy(() -> c.executeUpdate(
                        "UPDATE rental_requests SET status = 'IN_PROGRESS', office_id = 8001 WHERE id = 9201"))
                        .as("office_id 를 채우는 UPDATE 는 FK 검사로 잠긴 사무소 행을 기다린다(대조)")
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("Lock wait timeout");
            } finally {
                student.rollback();
                claimer.rollback();
                holder.rollback();
            }
        }
    }

    // 잡기 이력 테이블(V28) — 칼럼 · 인덱스 · FK 모양. 행위자에는 FK 가 없고, 옮길 이력이 없어 빈 채로 시작한다.
    private static void claimEventsTableShape() throws SQLException {
        assertThat(text("SELECT GROUP_CONCAT(CONCAT(column_name, ':', column_type, ':', is_nullable) ORDER BY ordinal_position) "
                + "FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'rental_request_claim_events'"))
                .isEqualTo("id:bigint:NO,rental_request_id:bigint:NO,office_id:bigint:NO,actor_id:bigint:YES,"
                        + "event_type:enum('CLAIMED','RELEASED','RETURNED','CLOSED'):NO,"
                        + "return_cause:enum('REPRESENTATIVE_WITHDRAWN','DIVISION_SUSPENDED','ACCOUNT_SUSPENDED'):YES,"
                        + "outcome:enum('FOUND','NOT_PROCEEDED'):YES,occurred_at:datetime(6):NO");
        assertThat(text("SELECT GROUP_CONCAT(CONCAT(constraint_name, ':', referenced_table_name, ':', delete_rule) "
                + "ORDER BY constraint_name) FROM information_schema.referential_constraints "
                + "WHERE constraint_schema = DATABASE() AND table_name = 'rental_request_claim_events'"))
                .isEqualTo("fk_rrce_office:brokerage_offices:RESTRICT,fk_rrce_request:rental_requests:RESTRICT");
        assertThat(count("SELECT COUNT(*) FROM information_schema.key_column_usage WHERE table_schema = DATABASE() "
                + "AND table_name = 'rental_request_claim_events' AND column_name = 'actor_id' "
                + "AND referenced_table_name IS NOT NULL")).as("행위자에는 FK 가 없다").isZero();
        assertThat(text("SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics "
                + "WHERE table_schema = DATABASE() AND table_name = 'rental_request_claim_events' "
                + "AND index_name = 'idx_rrce_request'")).isEqualTo("rental_request_id,id");
        assertThat(count("SELECT COUNT(*) FROM rental_request_claim_events")).as("옮길 이력이 없다 — 빈 채로 시작").isZero();
    }

    /*
     * 잡기 이력 INSERT 의 잠금 — 행위자에 FK 가 없다는 것을 잠금으로 확인한다.
     * ★ 이력 INSERT 의 FK 검사가 users 행을 S 로 잡으면, 사무소 행을 쥔 잡기 · 놓기 · 종료 · 대기 복귀가 그 줄에서 users 를 사무소
     *   **뒤에** 잡게 된다(전역 순서는 users → 사무소). 그래서 행위자에는 FK 를 걸지 않았다 — 다른 트랜잭션이 그 사람의 users 행을
     *   배타로 쥐고 있어도 INSERT 는 기다리지 않아야 한다. 대조로 요청 · 사무소 FK 는 살아 있어 잠긴 부모 행을 기다린다.
     */
    private static void claimEventInsertLocks() throws SQLException {
        execute("""
                INSERT INTO brokerage_offices (id, uuid, reg_no, name, ceo_name, address, tel)
                VALUES (8002, 'p3-office-000-0000-0000-000000000002', '99999-2026-00002', '테스트공인중개사사무소', '홍길동',
                        '서울특별시 광진구 능동로 000', '02-000-0000')""");
        try (Connection holder = connect(); Connection writer = connect()) {
            holder.setAutoCommit(false);
            writer.setAutoCommit(false);
            try (Statement h = holder.createStatement(); Statement w = writer.createStatement()) {
                h.executeQuery("SELECT id FROM users WHERE id = 9002 FOR UPDATE").close();
                h.executeQuery("SELECT id FROM brokerage_offices WHERE id = 8002 FOR UPDATE").close();
                h.executeQuery("SELECT id FROM rental_requests WHERE id = 9201 FOR UPDATE").close();
                w.execute("SET SESSION innodb_lock_wait_timeout = 1");

                assertThat(w.executeUpdate("INSERT INTO rental_request_claim_events "
                        + "(rental_request_id, office_id, actor_id, event_type, occurred_at) "
                        + "VALUES (9202, 8001, 9002, 'CLAIMED', NOW(6))"))
                        .as("행위자의 users 행이 잠겨 있어도 이력 INSERT 는 기다리지 않는다(FK 없음)").isEqualTo(1);
                assertThatThrownBy(() -> w.executeUpdate("INSERT INTO rental_request_claim_events "
                        + "(rental_request_id, office_id, actor_id, event_type, occurred_at) "
                        + "VALUES (9202, 8002, NULL, 'CLAIMED', NOW(6))"))
                        .as("사무소 FK 는 잠긴 사무소 행을 기다린다(대조)")
                        .isInstanceOf(SQLException.class).hasMessageContaining("Lock wait timeout");
                assertThatThrownBy(() -> w.executeUpdate("INSERT INTO rental_request_claim_events "
                        + "(rental_request_id, office_id, actor_id, event_type, occurred_at) "
                        + "VALUES (9201, 8001, NULL, 'CLAIMED', NOW(6))"))
                        .as("요청 FK 는 잠긴 요청 행을 기다린다(대조)")
                        .isInstanceOf(SQLException.class).hasMessageContaining("Lock wait timeout");
            } finally {
                writer.rollback();
                holder.rollback();
            }
        }
        assertThatThrownBy(() -> execute("INSERT INTO rental_request_claim_events "
                + "(rental_request_id, office_id, actor_id, event_type, occurred_at) VALUES (9202, 999999, NULL, 'CLAIMED', NOW(6))"))
                .as("없는 사무소는 FK 가 막는다").isInstanceOf(SQLException.class);
    }

    // V27 상태의 담당자 시절 행 — 센티널 값만.
    private static void seedManagerEraRows() throws SQLException {
        execute("""
                INSERT INTO users (id, uuid, name, email, role, status, preferred_language, created_at, updated_at)
                VALUES (9001, 'p3-student-0000-0000-000000000001', '홍길동', 'student1@example.test', 'USER', 'ACTIVE', 'ko',
                        '2026-09-01 10:00:00', '2026-09-01 10:00:00'),
                       (9002, 'p3-manager-0000-0000-000000000002', '홍길동', 'manager2@example.test', 'USER', 'ACTIVE', 'ko',
                        '2026-09-01 10:00:00', '2026-09-01 10:00:00')""");
        execute("""
                INSERT INTO foreigner_verifications (id, uuid, user_id, resident_status_id, resident_status_code,
                                                     required_document_codes, nationality_country_id, full_name, birth_date)
                VALUES (9101, 'p3-verif-0000-0000-0000-000000000001', 9001,
                        (SELECT MIN(id) FROM resident_statuses), 'STUDENT', '[]', (SELECT MIN(id) FROM countries),
                        'HONG GILDONG', '1998-03-15')""");
        execute("""
                INSERT INTO rental_requests (id, uuid, request_number, user_id, verification_id, assigned_manager_id,
                                             assigned_at, started_at, status, move_in_date, closed_reason, closed_at,
                                             version, created_at, updated_at)
                VALUES (9201, 'p3-rr-submitted-0000-000000000001', 'RQ-260901-SUB001', 9001, 9101, NULL, NULL, NULL,
                        'SUBMITTED', '2026-11-01', NULL, NULL, 0, '2026-09-01 11:00:00', '2026-09-01 11:00:00'),
                       (9202, 'p3-rr-inprogres-0000-000000000002', 'RQ-260901-PRG002', 9001, 9101, 9002,
                        '2026-09-02 09:00:00', '2026-09-02 09:30:00', 'IN_PROGRESS', '2026-11-01', NULL, NULL, 3,
                        '2026-09-01 12:00:00', '2026-09-02 09:30:00'),
                       (9203, 'p3-rr-contracted-000-000000000003', 'RQ-260901-CON003', 9001, 9101, 9002,
                        '2026-09-03 09:00:00', '2026-09-03 09:30:00', 'CONTRACTED', '2026-11-01', NULL, NULL, 5,
                        '2026-09-01 13:00:00', '2026-09-04 15:45:00'),
                       (9204, 'p3-rr-canceled-00000-000000000004', 'RQ-260901-CAN004', 9001, 9101, NULL, NULL, NULL,
                        'CANCELED', '2026-11-01', '직접 구했습니다', '2026-09-05 10:00:00', 1,
                        '2026-09-01 14:00:00', '2026-09-05 10:00:00'),
                       (9205, 'p3-rr-closed-0000000-000000000005', 'RQ-260901-CLS005', 9001, 9101, 9002,
                        '2026-09-06 09:00:00', '2026-09-06 09:30:00', 'CLOSED', '2026-11-01', '조건에 맞는 매물이 없습니다',
                        '2026-09-07 10:00:00', 2, '2026-09-01 15:00:00', '2026-09-07 10:00:00')""");
        execute("""
                INSERT INTO rental_request_memos (uuid, rental_request_id, content, created_by, created_at)
                VALUES ('p3-memo-0000-0000-0000-000000000001', 9202, '통화 메모(센티널)', 9002, '2026-09-02 10:00:00')""");
        execute("""
                INSERT INTO notification_outbox (id, uuid, user_id, noti_type, dedupe_key, template_code, params, deep_link,
                                                 scheduled_at, status, created_at, updated_at)
                VALUES (9301, 'p3-obx-0000-0000-0000-000000000001', 9001, 'RENTAL_REQUEST_IN_PROGRESS',
                        'RENTAL_REQUEST_IN_PROGRESS:request:p3-rr-inprogres-0000-000000000002', 'rental_request_in_progress',
                        '{"0": "RQ-260901-PRG002"}', '/rental-requests/p3-rr-inprogres-0000-000000000002',
                        '2026-09-02 09:30:00', 'SENT', '2026-09-02 09:30:00', '2026-09-02 09:31:00'),
                       (9302, 'p3-obx-0000-0000-0000-000000000002', 9001, 'RENTAL_REQUEST_CLOSED',
                        'RENTAL_REQUEST_CLOSED:request:p3-rr-closed-0000000-000000000005', 'rental_request_closed',
                        '{"0": "RQ-260901-CLS005"}', '/rental-requests/p3-rr-closed-0000000-000000000005',
                        '2026-09-07 10:00:00', 'SENT', '2026-09-07 10:00:00', '2026-09-07 10:01:00')""");
        // 아직 안 나간 옛 "처리 시작" 푸시 — 대기 · 재시도 대기 · 포기. 그리고 대기 중인 다른 알림 하나.
        execute("""
                INSERT INTO notification_outbox (id, uuid, user_id, noti_type, dedupe_key, template_code, params, deep_link,
                                                 scheduled_at, status, attempt_count, last_error, created_at, updated_at)
                VALUES (9303, 'p3-obx-0000-0000-0000-000000000003', 9001, 'RENTAL_REQUEST_IN_PROGRESS',
                        'RENTAL_REQUEST_IN_PROGRESS:request:p3-pending', 'rental_request_in_progress',
                        '{"0": "RQ-260901-PRG002"}', '/rental-requests/p3-rr-inprogres-0000-000000000002',
                        '2026-09-02 09:30:00', 'PENDING', 0, NULL, '2026-09-02 09:30:00', '2026-09-02 09:30:00'),
                       (9304, 'p3-obx-0000-0000-0000-000000000004', 9001, 'RENTAL_REQUEST_IN_PROGRESS',
                        'RENTAL_REQUEST_IN_PROGRESS:request:p3-failed', 'rental_request_in_progress',
                        '{"0": "RQ-260901-PRG002"}', '/rental-requests/p3-rr-inprogres-0000-000000000002',
                        '2026-09-02 09:40:00', 'FAILED', 2, '모든 기기 발송 실패', '2026-09-02 09:30:00', '2026-09-02 09:35:00'),
                       (9305, 'p3-obx-0000-0000-0000-000000000005', 9001, 'RENTAL_REQUEST_IN_PROGRESS',
                        'RENTAL_REQUEST_IN_PROGRESS:request:p3-given-up', 'rental_request_in_progress',
                        '{"0": "RQ-260901-PRG002"}', '/rental-requests/p3-rr-inprogres-0000-000000000002',
                        '2026-09-02 10:00:00', 'GIVEN_UP', 5, '모든 기기 발송 실패', '2026-09-02 09:30:00', '2026-09-02 10:00:00'),
                       (9306, 'p3-obx-0000-0000-0000-000000000006', 9001, 'RENTAL_REQUEST_CLOSED',
                        'RENTAL_REQUEST_CLOSED:request:p3-pending', 'rental_request_closed',
                        '{"0": "RQ-260901-CLS005"}', '/rental-requests/p3-rr-closed-0000000-000000000005',
                        '2026-09-07 10:00:00', 'PENDING', 0, NULL, '2026-09-07 10:00:00', '2026-09-07 10:00:00')""");
        execute("""
                INSERT INTO user_notifications (uuid, user_id, noti_type, template_code, params, deep_link, created_at)
                VALUES ('p3-unt-0000-0000-0000-000000000001', 9001, 'RENTAL_REQUEST_IN_PROGRESS', 'rental_request_in_progress',
                        '{"0": "RQ-260901-PRG002"}', '/rental-requests/p3-rr-inprogres-0000-000000000002', '2026-09-02 09:30:00'),
                       ('p3-unt-0000-0000-0000-000000000002', 9001, 'RENTAL_REQUEST_CLOSED', 'rental_request_closed',
                        '{"0": "RQ-260901-CLS005"}', '/rental-requests/p3-rr-closed-0000000-000000000005', '2026-09-07 10:00:00')""");
    }

    // 상태 · 맡은 사무소 · 잡은 시각 · 결과 · 사유 · 종료 시각 · 갱신 시각 · 버전 — 시각은 문자열로(드라이버의 시간대 변환을 피한다).
    private static List<String> row(long id) throws SQLException {
        String sql = "SELECT status, CAST(office_id AS CHAR), DATE_FORMAT(claimed_at, '%Y-%m-%d %H:%i:%s'), outcome, "
                + "closed_reason, DATE_FORMAT(closed_at, '%Y-%m-%d %H:%i:%s'), "
                + "DATE_FORMAT(updated_at, '%Y-%m-%d %H:%i:%s'), CAST(version AS CHAR) "
                + "FROM rental_requests WHERE id = " + id;
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            assertThat(rs.next()).as("요청 %d", id).isTrue();
            List<String> values = new ArrayList<>();
            for (int i = 1; i <= 8; i++) {
                values.add(rs.getString(i));
            }
            return values;
        }
    }

    private static List<String> columns() throws SQLException {
        List<String> names = new ArrayList<>();
        try (Connection c = connect(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT column_name FROM information_schema.columns "
                     + "WHERE table_schema = DATABASE() AND table_name = 'rental_requests'")) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        }
        return names;
    }

    private static String text(String sql) throws SQLException {
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static long count(String sql) throws SQLException {
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void execute(String sql) throws SQLException {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }
}
