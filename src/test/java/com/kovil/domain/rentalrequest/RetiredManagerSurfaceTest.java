package com.kovil.domain.rentalrequest;

import com.kovil.domain.rentalrequest.exception.RentalRequestErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/*
 * ★ 내부 담당자의 임대 요청 처리(대기열 · 시작 · 이관 · 메모 · 종료)는 통째로 사라졌다 — 요청은 파트너스(사무소)가 맡는다.
 *   부활 금지 목록을 못 박는다. 클래스 · 오류 코드 · 문구 · 권한 · 상태 중 하나라도 돌아오면 FE 가 없는 기능을 부르거나,
 *   유학생 연락처가 담당자 응답 모양으로 다시 새는 길이 생긴다.
 */
class RetiredManagerSurfaceTest {

    private static final String BASE = "com.kovil.domain.";
    private static final Pattern TEXT_FILE =
            Pattern.compile("\\.(java|properties|ya?ml|xml|sql|json|html|txt)$");

    @Test
    @DisplayName("담당자 컨트롤러 · 서비스 · 응답 · 이관/메모 요청 · 메모 엔티티/리포지토리 · 직원 드롭다운이 없다")
    void retired_classes_are_gone() {
        List<String> retired = List.of(
                BASE + "rentalrequest.controller.ManagerRentalRequestController",
                BASE + "rentalrequest.service.ManagerRentalRequestService",
                BASE + "rentalrequest.dto.response.ManagerRentalRequestResponse",
                BASE + "rentalrequest.dto.request.RentalRequestAssignRequest",
                BASE + "rentalrequest.dto.request.RentalRequestMemoRequest",
                BASE + "rentalrequest.entity.RentalRequestMemo",
                BASE + "rentalrequest.repository.RentalRequestMemoRepository",
                BASE + "user.controller.ManagerStaffController");
        for (String name : retired) {
            assertThatThrownBy(() -> Class.forName(name)).as(name).isInstanceOf(ClassNotFoundException.class);
        }
    }

    @Test
    @DisplayName("담당자 전용 오류 코드가 enum 에도 문구 번들에도 없다")
    void retired_error_codes_are_gone() throws IOException {
        List<String> retired = List.of("MANAGER_NOT_FOUND", "REQUEST_NOT_ASSIGNEE", "CLOSE_REASON_REQUIRED");
        assertThat(Arrays.stream(RentalRequestErrorCode.values()).map(Enum::name)).doesNotContainAnyElementsOf(retired);
        for (String bundle : List.of("/messages/errors.properties", "/messages/errors_ko.properties")) {
            assertThat(load(bundle).stringPropertyNames()).as(bundle).doesNotContainAnyElementsOf(retired);
        }
    }

    /*
     * 운영 코드 · 설정 · 문구에 옛 표면의 이름이 남아 있지 않다. 마이그레이션은 이력이라 제외한다(V28 이 바로 그것들을 지운다).
     * 주석 · Swagger 설명에 남은 옛 이름도 다음 사람이 그 표면이 아직 있다고 믿게 만든다 — 함께 본다.
     */
    @Test
    @DisplayName("운영 코드 · 설정 · 문구에 옛 담당자 표면의 이름이 남아 있지 않다")
    void no_remnants_in_main_sources() throws IOException {
        List<String> needles = List.of(
                "/api/v1/manager/rental-requests", "/api/v1/manager/staff",
                "assignedManager", "assigned_manager", "RentalRequestMemo", "rental_request_memos",
                "RENTAL_REQUEST_MANAGE", "ASSIGNMENT_OVERRIDE", "CONTRACTED",
                "MANAGER_NOT_FOUND", "REQUEST_NOT_ASSIGNEE", "CLOSE_REASON_REQUIRED",
                "startProgress", "countOpenByAssignedManagerId", "StaffWorkload");
        List<String> hits = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src", "main"))) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String path = file.toString().replace('\\', '/');
                // 글자 파일만 읽는다(글꼴 · 이미지 같은 이진 파일은 UTF-8 로 읽히지 않는다).
                if (path.contains("/db/migration/") || !TEXT_FILE.matcher(path).find()) {
                    continue;
                }
                String text = Files.readString(file, StandardCharsets.UTF_8);
                for (String needle : needles) {
                    if (text.contains(needle)) {
                        hits.add(path + " → " + needle);
                    }
                }
            }
        }
        assertThat(hits).as("옛 담당자 표면의 잔재").isEmpty();
    }

    private static Properties load(String resource) throws IOException {
        Properties properties = new Properties();
        try (InputStream stream = RetiredManagerSurfaceTest.class.getResourceAsStream(resource)) {
            assertThat(stream).as(resource).isNotNull();
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
        return properties;
    }
}
