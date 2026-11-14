package com.kovil.common.validator;

import com.kovil.common.validator.annotation.MultipartFilesWithErrorCode;
import com.kovil.common.validator.annotation.NotBlankWithErrorCode;
import com.kovil.common.validator.annotation.NotNullWithErrorCode;
import com.kovil.common.validator.annotation.PatternWithErrorCode;
import com.kovil.common.validator.annotation.RangeWithErrorCode;
import com.kovil.common.validator.annotation.SizeWithErrorCode;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class WithErrorCodeValidatorTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private static List<String> messagesOf(Object target) {
        Set<ConstraintViolation<Object>> violations = validator.validate(target);
        return violations.stream().map(ConstraintViolation::getMessage).toList();
    }

    @Nested
    @DisplayName("NotBlankWithErrorCode")
    class NotBlank {

        record Dto(@NotBlankWithErrorCode("INVALID_REQUEST") String name) {
        }

        @Test
        @DisplayName("값이 있으면 통과한다")
        void present_passes() {
            assertThat(messagesOf(new Dto("hyo"))).isEmpty();
        }

        @Test
        @DisplayName("null·빈 문자열·공백만 있는 문자열은 모두 거부되고 위반 메시지는 에러코드 이름이다")
        void blank_variants_rejected_with_error_code_name() {
            assertThat(messagesOf(new Dto(null))).containsExactly("INVALID_REQUEST");
            assertThat(messagesOf(new Dto(""))).containsExactly("INVALID_REQUEST");
            assertThat(messagesOf(new Dto("   "))).containsExactly("INVALID_REQUEST");
        }
    }

    @Nested
    @DisplayName("NotNullWithErrorCode")
    class NotNull {

        record Dto(@NotNullWithErrorCode("NOT_FOUND") Object value) {
        }

        @Test
        @DisplayName("null만 거부한다 — 빈 문자열은 통과시켜 NotBlank와 역할이 겹치지 않는다")
        void only_null_rejected() {
            assertThat(messagesOf(new Dto(null))).containsExactly("NOT_FOUND");
            assertThat(messagesOf(new Dto(""))).isEmpty();
            assertThat(messagesOf(new Dto(0))).isEmpty();
        }
    }

    @Nested
    @DisplayName("PatternWithErrorCode")
    class Pattern {

        record Dto(
                @PatternWithErrorCode(value = "INVALID_REQUEST", regexp = "^01[016-9]\\d{7,8}$")
                String phone
        ) {
        }

        record CaseInsensitiveDto(
                @PatternWithErrorCode(
                        value = "INVALID_REQUEST",
                        regexp = "^[a-z]+$",
                        flags = PatternWithErrorCode.Flag.CASE_INSENSITIVE)
                String code
        ) {
        }

        @Test
        @DisplayName("전체 일치해야 통과한다 — 부분 일치는 거부된다")
        void matches_entire_value() {
            assertThat(messagesOf(new Dto("01012345678"))).isEmpty();
            assertThat(messagesOf(new Dto("01012345678xyz"))).containsExactly("INVALID_REQUEST");
            assertThat(messagesOf(new Dto("0201234567"))).containsExactly("INVALID_REQUEST");
        }

        @Test
        @DisplayName("null은 통과시킨다 — 필수 여부는 NotNull/NotBlank가 따로 결정한다")
        void null_passes() {
            assertThat(messagesOf(new Dto(null))).isEmpty();
        }

        @Test
        @DisplayName("flags가 컴파일된 패턴에 실제로 반영된다")
        void flags_are_applied() {
            assertThat(messagesOf(new CaseInsensitiveDto("ABC"))).isEmpty();
        }
    }

    @Nested
    @DisplayName("RangeWithErrorCode")
    class Range {

        record Dto(@RangeWithErrorCode(value = "INVALID_REQUEST", min = 1, max = 31) Integer paymentDay) {
        }

        @Test
        @DisplayName("경계값은 포함이다")
        void bounds_are_inclusive() {
            assertThat(messagesOf(new Dto(1))).isEmpty();
            assertThat(messagesOf(new Dto(31))).isEmpty();
        }

        @Test
        @DisplayName("경계 밖은 거부된다")
        void outside_bounds_rejected() {
            assertThat(messagesOf(new Dto(0))).containsExactly("INVALID_REQUEST");
            assertThat(messagesOf(new Dto(32))).containsExactly("INVALID_REQUEST");
        }

        @Test
        @DisplayName("null은 통과시킨다")
        void null_passes() {
            assertThat(messagesOf(new Dto(null))).isEmpty();
        }
    }

    @Nested
    @DisplayName("SizeWithErrorCode")
    class Size {

        record StringDto(@SizeWithErrorCode(value = "INVALID_REQUEST", min = 2, max = 20) String nickname) {
        }

        record CollectionDto(@SizeWithErrorCode(value = "NOT_FOUND", min = 1, max = 3) List<String> tags) {
        }

        @Test
        @DisplayName("문자열은 길이로, 컬렉션은 원소 수로 판정된다 — 대상 타입에 따라 검증기가 갈린다")
        void dispatches_by_target_type() {
            assertThat(messagesOf(new StringDto("ab"))).isEmpty();
            assertThat(messagesOf(new StringDto("a"))).containsExactly("INVALID_REQUEST");

            assertThat(messagesOf(new CollectionDto(List.of("a", "b", "c")))).isEmpty();
            assertThat(messagesOf(new CollectionDto(List.of()))).containsExactly("NOT_FOUND");
            assertThat(messagesOf(new CollectionDto(List.of("a", "b", "c", "d")))).containsExactly("NOT_FOUND");
        }

        @Test
        @DisplayName("null은 양쪽 모두 통과시킨다")
        void null_passes() {
            assertThat(messagesOf(new StringDto(null))).isEmpty();
            assertThat(messagesOf(new CollectionDto(null))).isEmpty();
        }
    }

    @Nested
    @DisplayName("MultipartFilesWithErrorCode")
    class MultipartFiles {

        record Dto(
                @MultipartFilesWithErrorCode(
                        emptyErrorCode = "INVALID_REQUEST",
                        maxCount = 2,
                        maxCountErrorCode = "NOT_FOUND",
                        maxFileSizeBytes = 10,
                        fileTooLargeErrorCode = "CONFLICT",
                        allowedMimeTypes = {"image/jpeg", "image/png"},
                        unsupportedTypeErrorCode = "INTERNAL_SERVER_ERROR")
                List<MultipartFile> files
        ) {
        }

        private static MultipartFile file(String contentType, int sizeBytes) {
            return new MockMultipartFile("files", "f.jpg", contentType, new byte[sizeBytes]);
        }

        @Test
        @DisplayName("허용 타입·개수·용량을 모두 만족하면 통과한다")
        void valid_files_pass() {
            assertThat(messagesOf(new Dto(List.of(file("image/jpeg", 5), file("image/png", 10))))).isEmpty();
        }

        @Test
        @DisplayName("실패 사유마다 서로 다른 에러코드가 나온다")
        void each_failure_reason_emits_its_own_code() {
            assertThat(messagesOf(new Dto(null))).containsExactly("INVALID_REQUEST");
            assertThat(messagesOf(new Dto(List.of()))).containsExactly("INVALID_REQUEST");
            assertThat(messagesOf(new Dto(List.of(file("image/jpeg", 0))))).containsExactly("INVALID_REQUEST");
            assertThat(messagesOf(new Dto(List.of(
                    file("image/jpeg", 1), file("image/jpeg", 1), file("image/jpeg", 1)))))
                    .containsExactly("NOT_FOUND");
            assertThat(messagesOf(new Dto(List.of(file("image/jpeg", 11))))).containsExactly("CONFLICT");
            assertThat(messagesOf(new Dto(List.of(file("application/pdf", 5)))))
                    .containsExactly("INTERNAL_SERVER_ERROR");
        }

        @Test
        @DisplayName("첫 실패에서 멈춘다 — 위반은 항상 1건이다")
        void stops_at_first_failure() {
            List<MultipartFile> bothWrong = List.of(file("application/pdf", 99));
            assertThat(messagesOf(new Dto(bothWrong))).hasSize(1);
        }
    }
}
