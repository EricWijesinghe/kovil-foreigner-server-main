package com.kovil.domain.rentalrequest.service;

import com.kovil.domain.region.repository.RegionRepository;
import com.kovil.domain.rentalrequest.dto.request.RequestAnswerEntry;
import com.kovil.domain.rentalrequest.entity.RequestOption;
import com.kovil.domain.rentalrequest.enums.ExtraInputType;
import com.kovil.domain.rentalrequest.exception.RentalRequestException;
import com.kovil.domain.rentalrequest.repository.RequestOptionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * 값이 빈 직접 답(TEXT·NUMBER·DATE·ADDRESS)의 결과 격자.
 *
 * <p>★ 필수 판정을 코드 포함 여부로만 하면 **코드만 넣고 값을 비운 본문**이 통과해
 * 전부 null 인 답변 행이 저장된다 — 사무소에 "무언가 답했다"는 거짓 신호다.
 * 그래서 required 는 값 단계에서 다시 물고, 필수가 아니면 빈 답은 행 자체를 만들지 않는다.
 */
@ExtendWith(MockitoExtension.class)
class DirectAnswerRequiredTest {

    @Mock RequestOptionRepository requestOptionRepository;
    @Mock RegionRepository regionRepository;
    @InjectMocks RequestAnswerResolver resolver;

    private static RequestOption textQuestion(boolean required) {
        RequestOption question = RequestOption.builder()
                .code("MOVE_REASON").selectionType("TEXT").sortOrder(1).active(true)
                .required(required).extraInputType(ExtraInputType.NONE.name()).extraRequired(false)
                .build();
        ReflectionTestUtils.setField(question, "id", 50L);
        return question;
    }

    private void treeIs(RequestOption... rows) {
        given(requestOptionRepository.findByActiveTrueOrderBySortOrderAscIdAsc()).willReturn(List.of(rows));
    }

    private static RequestAnswerEntry entry(String text) {
        return new RequestAnswerEntry("MOVE_REASON", text, null, null, null);
    }

    @Test
    @DisplayName("필수 질문에 코드만 보내고 값을 비우면 OPTION_ANSWER_REQUIRED다")
    void required_empty_value_is_rejected() {
        RequestOption question = textQuestion(true);
        treeIs(question);

        assertThatThrownBy(() -> resolver.resolve(List.of(question), List.of(entry("   "))))
                .isInstanceOf(RentalRequestException.class)
                .satisfies(e -> assertThat(((RentalRequestException) e).getErrorCode().getCode())
                        .isEqualTo("OPTION_ANSWER_REQUIRED"));
    }

    @Test
    @DisplayName("선택 질문의 빈 값은 행을 만들지 않는다 — 전부 null인 답변은 저장되지 않는다")
    void optional_empty_value_leaves_no_row() {
        RequestOption question = textQuestion(false);
        treeIs(question);

        List<RequestAnswerResolver.ResolvedAnswer> answers =
                resolver.resolve(List.of(question), List.of(entry(null)));

        assertThat(answers).isEmpty();
    }

    @Test
    @DisplayName("값이 있으면 그대로 답이 된다")
    void present_value_passes() {
        RequestOption question = textQuestion(true);
        treeIs(question);

        List<RequestAnswerResolver.ResolvedAnswer> answers =
                resolver.resolve(List.of(question), List.of(entry("직장 이직")));

        assertThat(answers).singleElement()
                .satisfies(answer -> assertThat(answer.textValue()).isEqualTo("직장 이직"));
    }
}
