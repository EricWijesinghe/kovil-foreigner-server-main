package com.kovil.domain.rentalrequest.service;

import com.kovil.domain.rentalrequest.dto.request.RequestAnswerEntry;
import com.kovil.domain.rentalrequest.entity.RequestOption;
import com.kovil.domain.rentalrequest.enums.ExtraInputType;
import com.kovil.domain.rentalrequest.exception.RentalRequestException;
import com.kovil.domain.rentalrequest.repository.RequestOptionRepository;
import com.kovil.domain.region.repository.RegionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * 질문지 답변 규칙의 결과 격자.
 *
 * <p>고정하는 것 셋: 조건부 숨김 시 답 폐기 · 필수(노출된 질문만) · 추가 입력 검증.
 */
@ExtendWith(MockitoExtension.class)
class RequestAnswerResolverTest {

    @Mock
    private RequestOptionRepository requestOptionRepository;

    @Mock
    private RegionRepository regionRepository;

    @InjectMocks
    private RequestAnswerResolver resolver;

    // 질문 10(항상 노출·필수) / 항목 101,102
    // 질문 20(101 을 골랐을 때만 노출) / 항목 201
    // 항목 102 는 고르면 TEXT 를 요구한다
    private RequestOption q10;
    private RequestOption q20;
    private RequestOption item101;
    private RequestOption item102;
    private RequestOption item201;

    private static RequestOption row(long id, Long parentId, String code) {
        RequestOption option = RequestOption.builder()
                .parentId(parentId).code(code).sortOrder(1).active(true)
                .required(false).extraInputType(ExtraInputType.NONE.name()).extraRequired(false)
                .build();
        ReflectionTestUtils.setField(option, "id", id);
        return option;
    }

    @BeforeEach
    void setUp() {
        q10 = row(10L, null, "ROOM_TYPE");
        ReflectionTestUtils.setField(q10, "required", true);

        q20 = row(20L, null, "VISIT_DETAIL");
        ReflectionTestUtils.setField(q20, "showWhenOptionId", 101L);
        ReflectionTestUtils.setField(q20, "required", true);

        item101 = row(101L, 10L, "ONE_ROOM");
        item102 = row(102L, 10L, "ETC");
        ReflectionTestUtils.setField(item102, "extraInputType", ExtraInputType.TEXT.name());
        ReflectionTestUtils.setField(item102, "extraRequired", true);
        ReflectionTestUtils.setField(item102, "extraMaxLength", 10);

        item201 = row(201L, 20L, "HAS_PIANO");
    }

    private void treeIs(RequestOption... rows) {
        given(requestOptionRepository.findByActiveTrueOrderBySortOrderAscIdAsc()).willReturn(List.of(rows));
    }

    @Nested
    @DisplayName("조건부 질문")
    class Conditional {

        @Test
        @DisplayName("조건이 충족되면 그 질문의 답이 살아남는다")
        void kept_when_condition_met() {
            treeIs(q10, q20, item101, item102, item201);

            List<RequestAnswerResolver.ResolvedAnswer> answers =
                    resolver.resolve(List.of(item101, item201), null);

            assertThat(answers).extracting(a -> a.option().getCode())
                    .containsExactly("ONE_ROOM", "HAS_PIANO");
        }

        @Test
        @DisplayName("조건 대상이 비활성화되면 질문은 **항상 노출**이다 — 트리 API가 그리는 대로 답을 받는다")
        void inactive_condition_target_means_always_visible() {
            // 관리자가 조건 대상 항목(101)을 비활성화 → 활성 트리에 101이 없다.
            // 트리 API는 이 조건을 null(항상 노출)로 내보내 앱이 질문 20을 그린다 —
            // 여기서 "선택 불가 조건 = 영구 숨김"으로 판정하면 유저의 답이 소리 없이 증발한다.
            treeIs(q10, q20, item102, item201); // 101 없음 = 비활성

            List<RequestAnswerResolver.ResolvedAnswer> answers =
                    resolver.resolve(List.of(item102, item201),
                            List.of(new RequestAnswerEntry("ETC", "친구랑 같이", null, null, null)));

            assertThat(answers).extracting(a -> a.option().getCode())
                    .containsExactly("ETC", "HAS_PIANO");
        }

        @Test
        @DisplayName("조건 대상이 비활성인 필수 질문은 답이 없으면 거절된다 — 노출인데 필수만 면제되면 앞뒤가 안 맞다")
        void inactive_condition_target_keeps_required() {
            treeIs(q10, q20, item102, item201); // 101 비활성 → 질문 20 항상 노출·필수

            assertThatThrownBy(() -> resolver.resolve(List.of(item102),
                    List.of(new RequestAnswerEntry("ETC", "친구랑 같이", null, null, null))))
                    .isInstanceOf(RentalRequestException.class)
                    .satisfies(e -> assertThat(((RentalRequestException) e).getErrorCode().getCode())
                            .isEqualTo("OPTION_ANSWER_REQUIRED"));
        }

        @Test
        @DisplayName("조건이 깨지면 그 질문의 답은 **조용히 버려진다** — 맡은 사무소가 없는 요구를 읽지 않게")
        void dropped_when_condition_unmet() {
            treeIs(q10, q20, item101, item102, item201);
            ReflectionTestUtils.setField(q20, "required", false);

            // 102(기타)를 골랐으므로 101 조건이 깨졌다 — 201 의 답은 버려진다.
            List<RequestAnswerResolver.ResolvedAnswer> answers = resolver.resolve(
                    List.of(item102, item201),
                    List.of(new RequestAnswerEntry("ETC", "친구랑 같이", null, null, null)));

            assertThat(answers).extracting(a -> a.option().getCode()).containsExactly("ETC");
        }

        @Test
        @DisplayName("숨겨진 질문의 required 는 따지지 않는다 — 보이지도 않은 것을 강요할 수 없다")
        void hidden_required_is_not_enforced() {
            treeIs(q10, q20, item101, item102, item201);

            // q20 은 required 지만 조건(101)이 깨져 숨었다 — 답이 없어도 통과해야 한다.
            List<RequestAnswerResolver.ResolvedAnswer> answers = resolver.resolve(
                    List.of(item102),
                    List.of(new RequestAnswerEntry("ETC", "직접 입력", null, null, null)));

            assertThat(answers).hasSize(1);
        }
    }

    @Nested
    @DisplayName("필수 질문")
    class Required {

        @Test
        @DisplayName("노출된 필수 질문에 답이 없으면 거절한다")
        void missing_answer_is_rejected() {
            treeIs(q10, item101, item102);

            assertThatThrownBy(() -> resolver.resolve(List.of(), null))
                    .isInstanceOfSatisfying(RentalRequestException.class, e ->
                            assertThat(e.getErrorCode().getCode()).isEqualTo("OPTION_ANSWER_REQUIRED"));
        }

        @Test
        @DisplayName("필수가 아니면 답이 없어도 통과한다")
        void optional_question_can_be_skipped() {
            ReflectionTestUtils.setField(q10, "required", false);
            treeIs(q10, item101, item102);

            assertThat(resolver.resolve(List.of(), null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("비선택형 질문 — 선택지 없이 값이 답이다")
    class DirectAnswer {

        private RequestOption typed(String type) {
            RequestOption question = row(30L, null, "NOTE");
            ReflectionTestUtils.setField(question, "selectionType", type);
            return question;
        }

        @Test
        @DisplayName("TEXT — 상한을 넘으면 거절한다")
        void text_respects_max_length() {
            RequestOption q = typed("TEXT");
            ReflectionTestUtils.setField(q, "inputMaxLength", 5);
            treeIs(q);

            assertThatThrownBy(() -> resolver.resolve(List.of(q),
                    List.of(new RequestAnswerEntry("NOTE", "123456", null, null, null))))
                    .isInstanceOfSatisfying(RentalRequestException.class, e ->
                            assertThat(e.getErrorCode().getCode()).isEqualTo("OPTION_EXTRA_INVALID"));
        }

        @Test
        @DisplayName("NUMBER — 범위를 벗어나면 거절한다")
        void number_respects_range() {
            RequestOption q = typed("NUMBER");
            ReflectionTestUtils.setField(q, "inputMinNumber", 1L);
            ReflectionTestUtils.setField(q, "inputMaxNumber", 10L);
            treeIs(q);

            assertThatThrownBy(() -> resolver.resolve(List.of(q),
                    List.of(new RequestAnswerEntry("NOTE", null, 11L, null, null))))
                    .isInstanceOfSatisfying(RentalRequestException.class, e ->
                            assertThat(e.getErrorCode().getCode()).isEqualTo("OPTION_EXTRA_INVALID"));
        }

        @Test
        @DisplayName("NUMBER — 범위 안이면 값이 그대로 남는다")
        void number_in_range_is_kept() {
            RequestOption q = typed("NUMBER");
            treeIs(q);

            List<RequestAnswerResolver.ResolvedAnswer> answers = resolver.resolve(List.of(q),
                    List.of(new RequestAnswerEntry("NOTE", null, 3L, null, null)));

            assertThat(answers).singleElement()
                    .satisfies(answer -> assertThat(answer.numberValue()).isEqualTo(3L));
        }

        @Test
        @DisplayName("ADDRESS — 다중 허용이 아니면 두 곳을 보낼 수 없다")
        void address_single_rejects_many() {
            RequestOption q = typed("ADDRESS");
            treeIs(q);

            assertThatThrownBy(() -> resolver.resolve(List.of(q),
                    List.of(new RequestAnswerEntry("NOTE", null, null, null, List.of("11215", "11230")))))
                    .isInstanceOfSatisfying(RentalRequestException.class, e ->
                            assertThat(e.getErrorCode().getCode()).isEqualTo("OPTION_EXTRA_INVALID"));
        }
    }

    @Nested
    @DisplayName("추가 입력")
    class Extra {

        @Test
        @DisplayName("필수인데 비어 있으면 거절한다")
        void required_extra_must_be_filled() {
            treeIs(q10, item101, item102);

            assertThatThrownBy(() -> resolver.resolve(List.of(item102), null))
                    .isInstanceOfSatisfying(RentalRequestException.class, e ->
                            assertThat(e.getErrorCode().getCode()).isEqualTo("OPTION_EXTRA_REQUIRED"));
        }

        @Test
        @DisplayName("상한을 넘으면 거절한다")
        void too_long_extra_is_rejected() {
            treeIs(q10, item101, item102);

            assertThatThrownBy(() -> resolver.resolve(List.of(item102),
                    List.of(new RequestAnswerEntry("ETC", "12345678901", null, null, null))))
                    .isInstanceOfSatisfying(RentalRequestException.class, e ->
                            assertThat(e.getErrorCode().getCode()).isEqualTo("OPTION_EXTRA_INVALID"));
        }

        @Test
        @DisplayName("입력칸이 없는 항목에 값이 딸려 와도 조용히 버린다 — 앱 버전이 앞서갔을 뿐이다")
        void extra_on_plain_item_is_ignored() {
            treeIs(q10, item101, item102);

            List<RequestAnswerResolver.ResolvedAnswer> answers = resolver.resolve(
                    List.of(item101),
                    List.of(new RequestAnswerEntry("ONE_ROOM", "쓸데없는 값", 5L, LocalDate.now(), null)));

            assertThat(answers).singleElement().satisfies(answer -> {
                assertThat(answer.textValue()).isNull();
                assertThat(answer.numberValue()).isNull();
                assertThat(answer.dateValue()).isNull();
            });
        }
    }
}
