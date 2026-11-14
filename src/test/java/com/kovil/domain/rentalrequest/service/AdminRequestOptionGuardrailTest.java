package com.kovil.domain.rentalrequest.service;

import com.kovil.domain.rentalrequest.dto.request.AdminRequestOptionRequest;
import com.kovil.domain.rentalrequest.entity.RequestOption;
import com.kovil.domain.rentalrequest.exception.RentalRequestException;
import com.kovil.domain.rentalrequest.repository.RequestOptionRepository;
import com.kovil.domain.rentalrequest.repository.RequestOptionTranslationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import jakarta.persistence.EntityManager;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

/**
 * 관리자 질문지 설정의 자기모순 가드 격자 (AUDIT_2026_08_26 수정분).
 *
 * <p>★ V15 무잠금은 "답변이 있어도 고칠 수 있다"는 뜻이지 "무엇이든 저장된다"가 아니다 —
 * 자기모순 설정(자기순환 조건·2단 사슬·min>max)은 저장 즉시 유저 제출을 통째로 죽이므로
 * 사람이 보고 있는 저장 시점에 끊는다.
 */
@ExtendWith(MockitoExtension.class)
class AdminRequestOptionGuardrailTest {

    @Mock RequestOptionRepository optionRepository;
    @Mock RequestOptionTranslationRepository translationRepository;
    @Mock EntityManager entityManager;
    @InjectMocks AdminRequestOptionService service;

    private static RequestOption row(Long id, Long parentId, Long showWhen) {
        RequestOption entity = RequestOption.builder()
                .parentId(parentId).code("OPT" + id).active(true).sortOrder(1)
                .showWhenOptionId(showWhen).build();
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }

    private static AdminRequestOptionRequest reqWithCondition(Long showWhenOptionId) {
        return new AdminRequestOptionRequest(null, null, null, null, null, null,
                null, showWhenOptionId, null, null, null,
                null, null, null, null, null, null, null, List.of());
    }

    private static AdminRequestOptionRequest reqWithRange(Long min, Long max) {
        return new AdminRequestOptionRequest(null, null, null, null, null, null,
                null, null, null, null, null,
                null, min, max, null, null, null, null, List.of());
    }

    private void expectConditionInvalid(Long editingId, AdminRequestOptionRequest request) {
        assertThatThrownBy(() -> service.update(editingId, request))
                .isInstanceOf(RentalRequestException.class)
                .satisfies(e -> assertThat(((RentalRequestException) e).getErrorCode().getCode())
                        .isEqualTo("OPTION_CONDITION_INVALID"));
    }

    @Nested
    @DisplayName("조건 깊이 1 — 양방향 검증")
    class ConditionDepth {

        @Test
        @DisplayName("자기 항목을 자기 조건으로 걸 수 없다 — 스스로를 열어야 열리는 질문은 영구 미노출이다")
        void self_cycle_is_rejected() {
            RequestOption question = row(7L, null, null);
            RequestOption ownItem = row(71L, 7L, null);
            given(optionRepository.findById(7L)).willReturn(Optional.of(question));
            given(optionRepository.findById(71L)).willReturn(Optional.of(ownItem));

            expectConditionInvalid(7L, reqWithCondition(71L));
        }

        @Test
        @DisplayName("내 항목이 이미 남의 조건이면 나는 조건부가 될 수 없다 — 역방향 2단 사슬 봉쇄")
        void reverse_chain_is_rejected() {
            // C.showWhen = a1(질문 A의 항목) 이 이미 걸린 상태에서 A 에 조건을 걸면 A→B→? 사슬 완성.
            RequestOption questionA = row(7L, null, null);
            RequestOption itemB1 = row(81L, 8L, null);   // 질문 B(8)의 항목
            RequestOption questionB = row(8L, null, null);
            given(optionRepository.findById(7L)).willReturn(Optional.of(questionA));
            given(optionRepository.findById(81L)).willReturn(Optional.of(itemB1));
            given(optionRepository.findById(8L)).willReturn(Optional.of(questionB));
            given(optionRepository.existsByShowWhenOptionIdParent(7L)).willReturn(true);

            expectConditionInvalid(7L, reqWithCondition(81L));
        }

        @Test
        @DisplayName("아무도 나를 조건으로 안 쓰면 조건 설정은 그냥 된다")
        void clean_condition_passes() {
            RequestOption questionA = row(7L, null, null);
            RequestOption itemB1 = row(81L, 8L, null);
            RequestOption questionB = row(8L, null, null);
            given(optionRepository.findById(7L)).willReturn(Optional.of(questionA));
            given(optionRepository.findById(81L)).willReturn(Optional.of(itemB1));
            given(optionRepository.findById(8L)).willReturn(Optional.of(questionB));
            given(optionRepository.existsByShowWhenOptionIdParent(7L)).willReturn(false);
            lenient().when(translationRepository.findByRequestOptionId(7L)).thenReturn(List.of());

            assertThatCode(() -> service.update(7L, reqWithCondition(81L)))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("NUMBER min > max 는 저장을 거절한다 — 통과시키면 그 질문의 모든 제출이 400으로 봉쇄된다")
    void min_above_max_is_rejected() {
        assertThatThrownBy(() -> service.update(7L, reqWithRange(100L, 10L)))
                .isInstanceOf(RentalRequestException.class)
                .satisfies(e -> assertThat(((RentalRequestException) e).getErrorCode().getCode())
                        .isEqualTo("OPTION_NUMBER_RANGE_INVALID"));
    }

    @Test
    @DisplayName("min == max 는 정상이다 — 고정값 질문")
    void equal_range_passes() {
        RequestOption question = row(7L, null, null);
        given(optionRepository.findById(7L)).willReturn(Optional.of(question));
        lenient().when(translationRepository.findByRequestOptionId(7L)).thenReturn(List.of());

        assertThatCode(() -> service.update(7L, reqWithRange(10L, 10L))).doesNotThrowAnyException();
    }
}
