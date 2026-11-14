package com.kovil.domain.rentalrequest.service;

import com.kovil.domain.rentalrequest.dto.response.PartnerRentalRequestResponse;
import com.kovil.domain.rentalrequest.dto.response.PartnerRentalRequestSummaryResponse;
import com.kovil.domain.rentalrequest.dto.response.RentalRequestResponse;
import com.kovil.domain.rentalrequest.entity.RentalRequest;
import com.kovil.domain.rentalrequest.enums.RentalRequestOutcome;
import com.kovil.domain.rentalrequest.enums.RentalRequestStatus;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.user.repository.UserRepository;
import com.kovil.domain.verification.entity.ForeignerVerification;
import com.kovil.domain.verification.enums.VerificationStatus;
import com.kovil.domain.verification.repository.ForeignerVerificationRepository;
import com.kovil.infra.crypto.Encryptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;

/*
 * 파트너 응답의 두 모양.
 *   · 요약(잡기 전 — 모든 승인 사무소에 나간다): 이름 · 전화 · 자유 요청사항이 **직렬화된 응답 어디에도** 없다. 연락처는 복호화조차 하지 않는다.
 *   · 전체(내 사무소 건): 이름 · 자유 요청사항 · 결과 — 휴대폰은 **진행 중일 때만**(종료 · 취소면 복호화하지 않는다).
 *   · "인증됨"은 요청을 통과시킨 인증 건이 지금 유효한가(승인 · 만료 전). 무효화 · 만료되면 거짓.
 */
@ExtendWith(MockitoExtension.class)
class PartnerRentalRequestAssemblerTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Instant NOW = Instant.parse("2026-10-07T03:00:00Z");
    private static final LocalDate TODAY = LocalDate.ofInstant(NOW, SEOUL);
    private static final long STUDENT = 10L;
    private static final long VERIFICATION = 99L;
    private static final String NAME = "홍길동";
    private static final String PHONE = "01000000000";
    private static final String NOTE = "연락은 010-0000-0001 로 주세요";
    private static final String AREA_NOTE = "건대입구역 도보 10분";
    private static final String TEXT_ANSWER = "카톡 kovil_test 로 연락 주세요";

    @Mock RentalRequestAssembler requestAssembler;
    @Mock UserRepository userRepository;
    @Mock ForeignerVerificationRepository verificationRepository;
    @Mock Encryptor encryptor;

    private PartnerRentalRequestAssembler assembler;

    @BeforeEach
    void setUp() {
        assembler = new PartnerRentalRequestAssembler(requestAssembler, userRepository, verificationRepository,
                encryptor, Clock.fixed(NOW, SEOUL));
        lenient().when(requestAssembler.contentsOf(any())).thenAnswer(inv -> {
            List<RentalRequest> requests = inv.getArgument(0);
            List<RentalRequestAssembler.Contents> contents = new ArrayList<>();
            for (int i = 0; i < requests.size(); i++) {
                contents.add(new RentalRequestAssembler.Contents(
                        List.of(new RentalRequestResponse.Region("11215", "광진구")),
                        List.of(new RentalRequestResponse.Option("ROOM_TYPE", "ONE_ROOM", "원룸", "어떤 집을 찾으세요?",
                                "원룸", "어떤 집을 찾으세요?", "원룸", null, null, null, null))));
            }
            return contents;
        });
    }

    private static RentalRequest request(RentalRequestStatus status) {
        RentalRequest request = RentalRequest.builder()
                .uuid("r-1").requestNumber("RQ-261007-AAAAAA").userId(STUDENT).verificationId(VERIFICATION)
                .status(RentalRequestStatus.SUBMITTED).moveInDate(LocalDate.of(2026, 11, 1)).moveInFlexible(true)
                .leaseMonths((short) 12).depositMin(5_000_000L).depositMax(20_000_000L)
                .monthlyRentMin(500_000L).monthlyRentMax(900_000L).householdSize((byte) 1)
                .preferredAreaNote(AREA_NOTE).requestNote(NOTE).build();
        ReflectionTestUtils.setField(request, "id", 500L);
        ReflectionTestUtils.setField(request, "createdAt", LocalDateTime.of(2026, 10, 6, 9, 0));
        LocalDateTime claimedAt = LocalDateTime.of(2026, 10, 7, 9, 0);
        switch (status) {
            case SUBMITTED -> { }
            case IN_PROGRESS -> request.claim(70L, claimedAt);
            case CLOSED -> {
                request.claim(70L, claimedAt);
                request.close(70L, RentalRequestOutcome.FOUND, claimedAt.plusHours(2));
            }
            case CANCELED -> {
                request.claim(70L, claimedAt);
                request.cancel("직접 구했습니다", claimedAt.plusHours(2));
            }
        }
        return request;
    }

    // 글 · 숫자 · 날짜 · 지역 답 하나씩. 글 답에는 연락처가 적혀 있다.
    private void givenAnswers() {
        // willReturn 꼴 — given(...) 은 다시 스텁하면서 setUp 의 답변을 null 인자로 한 번 부른다.
        willReturn(List.of(new RentalRequestAssembler.Contents(List.of(), List.of(
                new RentalRequestResponse.Option(null, "NOTE", "하고 싶은 말", "하고 싶은 말", null,
                        "하고 싶은 말", null, TEXT_ANSWER, null, null, null),
                new RentalRequestResponse.Option(null, "ROOMMATES", "같이 살 사람", "같이 살 사람", null,
                        "같이 살 사람", null, null, 2L, null, null),
                new RentalRequestResponse.Option(null, "MOVE_IN", "입주일", "입주일", null,
                        "입주일", null, null, null, LocalDate.of(2026, 11, 1), null),
                new RentalRequestResponse.Option(null, "AREA", "광진구", "어디에 살고 싶으세요?", "광진구",
                        "어디에 살고 싶으세요?", "광진구", null, null, null, "11215")))))
                .given(requestAssembler).contentsOf(any());
    }

    private void givenApplicant(UserStatus status) {
        User student = User.builder().uuid("s-1").name(NAME).phone("cipher").preferredLanguage("vi")
                .role(UserRole.USER).status(status).build();
        ReflectionTestUtils.setField(student, "id", STUDENT);
        given(userRepository.findAllById(any())).willReturn(List.of(student));
    }

    private void givenVerification(VerificationStatus status, LocalDate expiresAt) {
        ForeignerVerification verification = ForeignerVerification.builder().userId(STUDENT)
                .residentStatusId(1L).residentStatusCode("STUDENT").requiredDocumentCodes("[]")
                .nationalityCountryId(7L).fullName("HONG GILDONG").birthDate(LocalDate.of(1998, 3, 15))
                .status(status).expiresAt(expiresAt).build();
        ReflectionTestUtils.setField(verification, "id", VERIFICATION);
        given(verificationRepository.findAllById(any())).willReturn(List.of(verification));
    }

    @Nested
    @DisplayName("요약 — 잡기 전")
    class Summary {

        @Test
        @DisplayName("요청 번호 · 지역 · 답 · 입주 · 기간 · 예산 · 인원 · 지역 메모 · 선호 언어 · 인증됨 · 제출 시각을 싣는다")
        void carries_the_contract_fields() {
            givenApplicant(UserStatus.ACTIVE);
            givenVerification(VerificationStatus.APPROVED, TODAY);

            PartnerRentalRequestSummaryResponse summary = assembler.summary(request(RentalRequestStatus.SUBMITTED));

            assertThat(summary.view()).isEqualTo("SUMMARY");
            assertThat(summary.requestNumber()).isEqualTo("RQ-261007-AAAAAA");
            assertThat(summary.regions()).extracting(RentalRequestResponse.Region::code).containsExactly("11215");
            assertThat(summary.options()).hasSize(1);
            assertThat(summary.moveInDate()).isEqualTo(LocalDate.of(2026, 11, 1));
            assertThat(summary.leaseMonths()).isEqualTo((short) 12);
            assertThat(summary.depositMax()).isEqualTo(20_000_000L);
            assertThat(summary.householdSize()).isEqualTo((byte) 1);
            assertThat(summary.preferredAreaNote()).isEqualTo(AREA_NOTE);
            assertThat(summary.preferredLanguage()).isEqualTo("vi");
            assertThat(summary.verified()).as("만료일 당일까지 유효하다").isTrue();
            assertThat(summary.submittedAt()).isEqualTo(LocalDateTime.of(2026, 10, 6, 9, 0));
        }

        /*
         * ★ 직렬화 수준에서 본다 — 타입에 칸이 없다는 것만으로는 부족하다. 지역 · 답 · 메모 어디에든 연락처가 흘러들면
         *   잡지 않은 사무소 수십 곳에 번호가 나간다. 실제 JSON 의 최상위 키가 정확히 계약의 목록인지, 신청자의 이름 · 번호 ·
         *   자유 요청사항이 문자열 어디에도 없는지, 번호를 복호화조차 하지 않았는지 함께 본다.
         */
        @Test
        @DisplayName("직렬화된 요약에는 이름 · 전화 · 자유 요청사항이 칸으로도 값으로도 없고, 번호는 복호화하지 않는다")
        void serialized_summary_has_no_contact() {
            givenApplicant(UserStatus.ACTIVE);
            givenVerification(VerificationStatus.APPROVED, null);

            JsonMapper mapper = JsonMapper.builder().build();
            String json = mapper.writeValueAsString(assembler.summary(request(RentalRequestStatus.SUBMITTED)));
            JsonNode tree = mapper.readTree(json);

            List<String> keys = new ArrayList<>();
            tree.propertyNames().forEach(keys::add);
            assertThat(keys).containsExactlyInAnyOrder(
                    "view", "requestUuid", "requestNumber", "status", "regions", "options",
                    "moveInDate", "moveInFlexible", "leaseMonths", "depositMin", "depositMax",
                    "monthlyRentMin", "monthlyRentMax", "householdSize", "preferredAreaNote",
                    "preferredLanguage", "verified", "submittedAt");
            assertThat(json).doesNotContain(NAME, PHONE, NOTE, "applicant", "requestNote", "\"phone\"",
                    "HONG GILDONG", "claimedAt", "outcome");
            then(encryptor).should(never()).decrypt(any());
        }

        // 글 답은 자유 글이라 연락처가 들어갈 수 있다 — 값만 비우고, 답 행 · 질문 문구 · 다른 값 칸은 남긴다.
        @Test
        @DisplayName("요약은 질문지 글 답(textValue)만 비우고 답 행 · 질문 문구 · 숫자 · 날짜 · 지역 답은 남긴다")
        void summary_blanks_text_answers_only() {
            givenApplicant(UserStatus.ACTIVE);
            givenVerification(VerificationStatus.APPROVED, null);
            givenAnswers();

            List<RentalRequestResponse.Option> options =
                    assembler.summary(request(RentalRequestStatus.SUBMITTED)).options();

            assertThat(options).hasSize(4);
            assertThat(options).extracting(RentalRequestResponse.Option::textValue).containsOnlyNulls();
            assertThat(options.get(0).questionName()).isEqualTo("하고 싶은 말");
            assertThat(options.get(0).questionText()).isEqualTo("하고 싶은 말");
            assertThat(options.get(1).numberValue()).isEqualTo(2L);
            assertThat(options.get(2).dateValue()).isEqualTo(LocalDate.of(2026, 11, 1));
            assertThat(options.get(3).regionCode()).isEqualTo("11215");
            assertThat(JsonMapper.builder().build().writeValueAsString(options)).doesNotContain(TEXT_ANSWER);
        }

        // 선택지에 입력칸이 붙은 답(기타 + 직접 입력) — 다시 만든 행이 textValue 말고는 모든 칸을 그대로 옮기는지.
        @Test
        @DisplayName("선택지 + 글 답 행은 textValue 만 비고 나머지 칸은 그대로다")
        void choice_with_text_keeps_every_other_field() {
            givenApplicant(UserStatus.ACTIVE);
            givenVerification(VerificationStatus.APPROVED, null);
            RentalRequestResponse.Option original = new RentalRequestResponse.Option("ROOM_TYPE", "ETC", "기타",
                    "어떤 집을 찾으세요?", "기타", "어떤 집을 찾으세요?", "기타", TEXT_ANSWER, 3L,
                    LocalDate.of(2026, 12, 1), "11215");
            willReturn(List.of(new RentalRequestAssembler.Contents(List.of(), List.of(original))))
                    .given(requestAssembler).contentsOf(any());

            RentalRequestResponse.Option blanked = assembler.summary(request(RentalRequestStatus.SUBMITTED)).options().get(0);

            assertThat(blanked.textValue()).isNull();
            assertThat(blanked).usingRecursiveComparison().ignoringFields("textValue").isEqualTo(original);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = VerificationStatus.class, names = {"REVOKED", "PENDING_REVIEW", "REJECTED"})
        @DisplayName("통과시킨 인증이 지금 승인 상태가 아니면 인증됨이 거짓이다")
        void not_verified_when_not_approved(VerificationStatus status) {
            givenApplicant(UserStatus.ACTIVE);
            givenVerification(status, null);

            assertThat(assembler.summary(request(RentalRequestStatus.SUBMITTED)).verified()).isFalse();
        }

        @Test
        @DisplayName("통과시킨 인증이 만료됐으면 인증됨이 거짓이다")
        void not_verified_when_expired() {
            givenApplicant(UserStatus.ACTIVE);
            givenVerification(VerificationStatus.APPROVED, TODAY.minusDays(1));

            assertThat(assembler.summary(request(RentalRequestStatus.SUBMITTED)).verified()).isFalse();
        }
    }

    @Nested
    @DisplayName("전체 — 내 사무소 건")
    class Full {

        @Test
        @DisplayName("잡은 사무소에는 글 답(textValue)을 그대로 싣는다")
        void full_keeps_text_answers() {
            givenApplicant(UserStatus.ACTIVE);
            givenVerification(VerificationStatus.APPROVED, null);
            given(encryptor.decrypt("cipher")).willReturn(PHONE);
            givenAnswers();

            assertThat(assembler.full(request(RentalRequestStatus.IN_PROGRESS)).options().get(0).textValue())
                    .isEqualTo(TEXT_ANSWER);
        }

        @Test
        @DisplayName("진행 중이면 이름 · 휴대폰 · 자유 요청사항 · 잡은 시각을 싣는다")
        void in_progress_carries_contact() {
            givenApplicant(UserStatus.ACTIVE);
            givenVerification(VerificationStatus.APPROVED, null);
            given(encryptor.decrypt("cipher")).willReturn(PHONE);

            PartnerRentalRequestResponse full = assembler.full(request(RentalRequestStatus.IN_PROGRESS));

            assertThat(full.view()).isEqualTo("FULL");
            assertThat(full.status()).isEqualTo("IN_PROGRESS");
            assertThat(full.applicant().name()).isEqualTo(NAME);
            assertThat(full.applicant().phone()).isEqualTo(PHONE);
            assertThat(full.requestNote()).isEqualTo(NOTE);
            assertThat(full.preferredLanguage()).isEqualTo("vi");
            assertThat(full.verified()).isTrue();
            assertThat(full.claimedAt()).isEqualTo(LocalDateTime.of(2026, 10, 7, 9, 0));
            assertThat(full.outcome()).isNull();
        }

        // 종료 · 취소되면 서로의 번호를 가린다(PO 확정 2026-10-07) — 복호화조차 하지 않는다. 이름 · 자유 요청사항은 기록으로 남는다.
        @ParameterizedTest(name = "{0}")
        @EnumSource(value = RentalRequestStatus.class, names = {"CLOSED", "CANCELED"})
        @DisplayName("종료 · 취소면 휴대폰을 싣지 않고 복호화하지도 않는다")
        void phone_only_in_progress(RentalRequestStatus status) {
            givenApplicant(UserStatus.ACTIVE);
            givenVerification(VerificationStatus.APPROVED, null);

            PartnerRentalRequestResponse full = assembler.full(request(status));

            assertThat(full.applicant().phone()).isNull();
            assertThat(full.applicant().name()).isEqualTo(NAME);
            assertThat(full.requestNote()).isEqualTo(NOTE);
            then(encryptor).should(never()).decrypt(any());
        }

        @Test
        @DisplayName("종료면 고른 결과가 실린다")
        void closed_carries_outcome() {
            givenApplicant(UserStatus.ACTIVE);
            givenVerification(VerificationStatus.APPROVED, null);

            assertThat(assembler.full(request(RentalRequestStatus.CLOSED)).outcome()).isEqualTo("FOUND");
        }

        @Test
        @DisplayName("진행 중이어도 신청자 계정이 정지됐으면 휴대폰을 싣지 않는다")
        void suspended_applicant_hides_phone() {
            givenApplicant(UserStatus.SUSPENDED);
            givenVerification(VerificationStatus.APPROVED, null);

            assertThat(assembler.full(request(RentalRequestStatus.IN_PROGRESS)).applicant().phone()).isNull();
            then(encryptor).should(never()).decrypt(any());
        }

        @Test
        @DisplayName("복호화가 실패하면 그 번호만 비우고 응답은 나간다")
        void decrypt_failure_leaves_phone_empty() {
            givenApplicant(UserStatus.ACTIVE);
            givenVerification(VerificationStatus.APPROVED, null);
            given(encryptor.decrypt("cipher")).willThrow(new IllegalStateException("bad key"));

            PartnerRentalRequestResponse full = assembler.full(request(RentalRequestStatus.IN_PROGRESS));

            assertThat(full.applicant().phone()).isNull();
            assertThat(full.applicant().name()).isEqualTo(NAME);
        }

        // 여권 성명 · 인증 서류는 어느 모양에도 없다 — 연락에는 이름 · 전화면 된다.
        @Test
        @DisplayName("전체 모양에도 여권 성명은 없다")
        void full_has_no_passport_name() {
            givenApplicant(UserStatus.ACTIVE);
            givenVerification(VerificationStatus.APPROVED, null);
            given(encryptor.decrypt("cipher")).willReturn(PHONE);

            String json = JsonMapper.builder().build()
                    .writeValueAsString(assembler.full(request(RentalRequestStatus.IN_PROGRESS)));

            assertThat(json).contains(NAME, PHONE, NOTE).doesNotContain("HONG GILDONG");
        }
    }

    @Test
    @DisplayName("빈 목록은 아무것도 읽지 않는다")
    void empty_page_reads_nothing() {
        assertThat(assembler.summaries(List.of())).isEmpty();
        assertThat(assembler.fulls(List.of())).isEmpty();
        then(userRepository).shouldHaveNoInteractions();
        then(verificationRepository).shouldHaveNoInteractions();
    }
}
