package com.kovil.domain.rentalrequest.service;

import com.kovil.domain.region.entity.Region;
import com.kovil.domain.region.repository.RegionRepository;
import com.kovil.domain.rentalrequest.config.RentalRequestProperties;
import com.kovil.domain.rentalrequest.dto.request.RentalRequestSaveRequest;
import com.kovil.domain.rentalrequest.entity.RentalRequestOption;
import com.kovil.domain.rentalrequest.entity.RentalRequestRegion;
import com.kovil.domain.rentalrequest.entity.RequestOption;
import com.kovil.domain.rentalrequest.entity.RequestOptionTranslation;
import com.kovil.domain.rentalrequest.repository.RentalRequestOptionRepository;
import com.kovil.domain.rentalrequest.repository.RentalRequestRegionRepository;
import com.kovil.domain.rentalrequest.repository.RentalRequestRepository;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.user.service.UserQueryService;
import com.kovil.domain.verification.entity.ForeignerVerification;
import com.kovil.domain.verification.service.VerificationQueryService;
import com.kovil.common.util.BusinessNumberGenerator;
import com.kovil.domain.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.i18n.LocaleContextHolder;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

/**
 * 스냅샷 완결(V13)의 결과 격자 — **제출된 요청은 그 시점의 박제본이다**(PO 확정 2026-08-26).
 *
 * <p>저장 시점에 네 값이 박힌다: 질문 문구 ko·제출 언어, 답 문구 ko·제출 언어.
 * 직접 입력 답(TEXT·NUMBER·DATE)은 값 컬럼이 정본이라 답 문구는 null 이고,
 * **질문 행 자체가 답인데도 질문 문구가 박힌다** — V10 구현은 parentId 로만 물어서
 * 비선택형 질문의 질문 문구가 통째로 NULL 이었다(그 버그를 여기서 고정).
 */
@ExtendWith(MockitoExtension.class)
class AnswerSnapshotCompletionTest {

    private static final Instant NOW = Instant.parse("2026-08-11T00:00:00Z");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDate TODAY = LocalDate.ofInstant(NOW, SEOUL);
    private static final String USER_UUID = "user-uuid";

    @Mock UserQueryService userQueryService;
    @Mock VerificationQueryService verificationQueryService;
    @Mock RentalRequestSelectionResolver selectionResolver;
    @Mock RentalRequestAssembler assembler;
    @Mock RentalRequestRepository requestRepository;
    @Mock RentalRequestRegionRepository requestRegionRepository;
    @Mock RentalRequestOptionRepository requestOptionRepository;
    @Mock BusinessNumberGenerator businessNumberGenerator;
    @Mock RequestAnswerResolver answerResolver;
    @Mock RequestOptionQueryService requestOptionQueryService;
    @Mock RegionRepository regionRepository;

    @Mock UserRepository userRepository;

    private RentalRequestService service;

    @BeforeEach
    void setUp() {
        service = new RentalRequestService(userQueryService, userRepository, verificationQueryService, selectionResolver,
                answerResolver, requestOptionQueryService, regionRepository,
                assembler, requestRepository, requestRegionRepository, requestOptionRepository,
                businessNumberGenerator, new RentalRequestProperties(1, 50), Clock.fixed(NOW, SEOUL));
    }

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    private static <T> T withId(T entity, Long id) {
        try {
            var field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return entity;
    }

    private static RequestOption row(Long id, Long parentId, String code, String selectionType) {
        return withId(RequestOption.builder()
                .parentId(parentId).code(code).selectionType(selectionType)
                .active(true).sortOrder(1).build(), id);
    }

    private static RequestOptionTranslation translation(Long optionId, String language, String name) {
        return RequestOptionTranslation.builder()
                .requestOptionId(optionId).languageCode(language).name(name).build();
    }

    private static Region region(Long id, String code, String nameKo, String nameEn) {
        return withId(Region.builder()
                .parentId(1L).code(code).nameKo(nameKo).nameEn(nameEn)
                .sortOrder(1).isActive(true).build(), id);
    }

    private void givenCreatePasses(List<RequestAnswerResolver.ResolvedAnswer> answers) {
        given(userQueryService.getActiveUser(USER_UUID)).willReturn(withId(
                User.builder().uuid(USER_UUID).name("holder")
                        .status(UserStatus.ACTIVE).role(UserRole.USER).build(), 10L));
        given(verificationQueryService.findEffective(10L)).willReturn(Optional.of(withId(
                ForeignerVerification.builder()
                        .uuid("v-uuid").userId(10L).residentStatusId(1L).residentStatusCode("STUDENT")
                        .requiredDocumentCodes("[]").nationalityCountryId(7L).fullName("NGUYEN VAN A")
                        .birthDate(LocalDate.of(1998, 3, 15)).build(), 99L)));
        given(selectionResolver.resolveRegions(any())).willReturn(
                List.of(region(20L, "11215", "광진구", "Gwangjin-gu")));
        given(requestRepository.save(any())).willAnswer(call -> withId(call.getArgument(0), 500L));
        given(businessNumberGenerator.generate(any(), any())).willReturn("RQ-260811-ABC123");
        given(answerResolver.resolve(any(), any())).willReturn(answers);
    }

    private static RentalRequestSaveRequest saveRequest() {
        return new RentalRequestSaveRequest(List.of("11215"), List.of("ONE_ROOM"), null,
                TODAY.plusMonths(1), true, (short) 12,
                5_000_000L, 20_000_000L, 500_000L, 900_000L, (byte) 2, "건대입구역 도보 10분", "note");
    }

    private RentalRequestOption savedAnswer() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RentalRequestOption>> captor = ArgumentCaptor.forClass(List.class);
        then(requestOptionRepository).should().saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        return captor.getValue().get(0);
    }

    @Test
    @DisplayName("직접 입력 답: 질문 문구가 ko·제출 언어로 박히고, 답 문구는 값 컬럼이 정본이라 null")
    void direct_answer_snapshots_question_text_in_both_languages() {
        LocaleContextHolder.setLocale(Locale.forLanguageTag("vi"));
        RequestOption question = row(40L, null, "MOVE_REASON", "TEXT");
        givenCreatePasses(List.of(new RequestAnswerResolver.ResolvedAnswer(question, "직장 이직", null, null, null)));
        given(requestOptionQueryService.resolveNames("ko")).willReturn(
                Map.of(40L, translation(40L, "ko", "이사 이유가 무엇인가요?")));
        given(requestOptionQueryService.resolveNames("vi")).willReturn(
                Map.of(40L, translation(40L, "vi", "Lý do chuyển nhà?")));

        service.create(USER_UUID, saveRequest());

        RentalRequestOption saved = savedAnswer();
        assertThat(saved.getQuestionTextSnapshot()).isEqualTo("이사 이유가 무엇인가요?");
        assertThat(saved.getQuestionTextSnapshotUser()).isEqualTo("Lý do chuyển nhà?");
        assertThat(saved.getAnswerTextSnapshot()).isNull();
        assertThat(saved.getAnswerTextSnapshotUser()).isNull();
        assertThat(saved.getTextValue()).isEqualTo("직장 이직");
    }

    @Test
    @DisplayName("선택지 답: 부모 질문 문구와 항목 라벨이 박히고, 번역이 없으면 code가 남는다")
    void choice_answer_snapshots_labels_with_code_fallback() {
        LocaleContextHolder.setLocale(Locale.forLanguageTag("vi"));
        RequestOption item = row(11L, 1L, "ONE_ROOM", null);
        givenCreatePasses(List.of(new RequestAnswerResolver.ResolvedAnswer(item, null, null, null, null)));
        given(requestOptionQueryService.findRows(List.of(1L)))
                .willReturn(List.of(row(1L, null, "ROOM_TYPE", "SINGLE")));
        given(requestOptionQueryService.resolveNames("ko")).willReturn(Map.of(
                1L, translation(1L, "ko", "어떤 집을 찾으세요?"),
                11L, translation(11L, "ko", "원룸")));
        // vi 는 질문 번역만 있다 — 항목 라벨은 code 폴백이 박혀야 한다(빈 칸이면 추적 불가).
        given(requestOptionQueryService.resolveNames("vi")).willReturn(Map.of(
                1L, translation(1L, "vi", "Bạn tìm loại nhà nào?")));

        service.create(USER_UUID, saveRequest());

        RentalRequestOption saved = savedAnswer();
        assertThat(saved.getQuestionTextSnapshot()).isEqualTo("어떤 집을 찾으세요?");
        assertThat(saved.getQuestionTextSnapshotUser()).isEqualTo("Bạn tìm loại nhà nào?");
        assertThat(saved.getAnswerTextSnapshot()).isEqualTo("원룸");
        assertThat(saved.getAnswerTextSnapshotUser()).isEqualTo("ONE_ROOM");
    }

    @Test
    @DisplayName("ADDRESS 답: 지역명이 ko·영문으로 박힌다 — 지역 마스터 수정에도 과거 답이 안 흔들린다")
    void address_answer_snapshots_region_names() {
        LocaleContextHolder.setLocale(Locale.forLanguageTag("vi"));
        RequestOption question = row(40L, null, "WORKPLACE", "ADDRESS");
        givenCreatePasses(List.of(new RequestAnswerResolver.ResolvedAnswer(question, null, null, null, 30L)));
        given(regionRepository.findAllById(List.of(30L)))
                .willReturn(List.of(region(30L, "11440", "마포구", "Mapo-gu")));
        given(requestOptionQueryService.resolveNames("ko")).willReturn(
                Map.of(40L, translation(40L, "ko", "직장은 어디인가요?")));
        given(requestOptionQueryService.resolveNames("vi")).willReturn(Map.of());

        service.create(USER_UUID, saveRequest());

        RentalRequestOption saved = savedAnswer();
        assertThat(saved.getAnswerTextSnapshot()).isEqualTo("마포구");
        assertThat(saved.getAnswerTextSnapshotUser()).isEqualTo("Mapo-gu");
        assertThat(saved.getQuestionTextSnapshot()).isEqualTo("직장은 어디인가요?");
        // vi 번역이 없으면 code 라도 남는다.
        assertThat(saved.getQuestionTextSnapshotUser()).isEqualTo("WORKPLACE");
    }

    @Test
    @DisplayName("고정 희망 지역도 지역명이 박힌다 — 100% live 조인이던 마지막 구멍")
    void fixed_region_axis_snapshots_names() {
        LocaleContextHolder.setLocale(Locale.forLanguageTag("vi"));
        givenCreatePasses(List.of());
        given(requestOptionQueryService.resolveNames(any())).willReturn(Map.of());

        service.create(USER_UUID, saveRequest());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RentalRequestRegion>> captor = ArgumentCaptor.forClass(List.class);
        then(requestRegionRepository).should().saveAll(captor.capture());
        assertThat(captor.getValue()).singleElement().satisfies(link -> {
            assertThat(link.getRegionNameSnapshot()).isEqualTo("광진구");
            assertThat(link.getRegionNameSnapshotUser()).isEqualTo("Gwangjin-gu");
        });
    }
}
