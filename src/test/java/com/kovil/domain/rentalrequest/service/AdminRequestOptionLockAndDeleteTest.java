package com.kovil.domain.rentalrequest.service;

import com.kovil.domain.rentalrequest.dto.request.AdminRequestOptionRequest;
import com.kovil.domain.rentalrequest.dto.response.AdminRequestOptionResponse;
import com.kovil.domain.rentalrequest.entity.RequestOption;
import com.kovil.domain.rentalrequest.repository.RentalRequestOptionRepository;
import com.kovil.domain.rentalrequest.repository.RequestOptionRepository;
import com.kovil.domain.rentalrequest.repository.RequestOptionTranslationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import jakarta.persistence.EntityManager;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

/**
 * 관리자 조작 무잠금(V15)의 결과 격자 — **PO 확정 2026-08-26: 아무것도 막지 않는다.**
 *
 * <p>답변이 달린 질문·선택지도 구조 변경과 삭제가 조건 없이 된다. 지난 요청은
 * 스냅샷(V13)이 지키고, 답변 행의 마스터 링크는 DB(SET NULL)가 끊는다.
 * hasAnswers 는 이제 **정보 값**일 뿐이다 — 화면이 귀띔하는 데만 쓴다.
 *
 * <p>※ 과거에 존재했던 OPTION_HAS_ANSWERS / OPTION_CONDITION_IN_USE 잠금은 V15 에서
 * 폐기됐다(부활 금지 — SPEC_2026_08_26 §6). 이 테스트가 그 폐기를 고정한다.
 */
@ExtendWith(MockitoExtension.class)
class AdminRequestOptionLockAndDeleteTest {

    @Mock RequestOptionRepository optionRepository;
    @Mock RequestOptionTranslationRepository translationRepository;
    @Mock RentalRequestOptionRepository rentalRequestOptionRepository;
    @Mock EntityManager entityManager;
    @InjectMocks AdminRequestOptionService service;

    private static RequestOption option(Long id, Long parentId, String selectionType) {
        RequestOption entity = RequestOption.builder()
                .parentId(parentId).code("OPT" + id).selectionType(selectionType)
                .active(true).sortOrder(1).build();
        try {
            var field = RequestOption.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return entity;
    }

    private static AdminRequestOptionRequest typeChange(String selectionType) {
        return new AdminRequestOptionRequest(null, null, selectionType, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null, null, null, List.of());
    }

    @Nested
    @DisplayName("구조 변경 — 답변이 달려 있어도 잠기지 않는다 (V15)")
    class StructureUnlocked {

        @Test
        @DisplayName("답변 달린 선택형 질문의 유형 변경이 그냥 된다 — 잠금 코드는 폐기됐다")
        void type_change_is_never_blocked() {
            given(optionRepository.findById(7L)).willReturn(Optional.of(option(7L, null, "MULTI")));

            assertThatCode(() -> service.update(7L, typeChange("SINGLE"))).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("삭제 — 조건 없이 지워진다 (V15)")
    class DeleteUnconditional {

        @Test
        @DisplayName("답변이 달린 질문도 선택지·번역과 함께 지워진다 — 답변 행은 DB가 링크만 끊는다")
        void answered_question_is_deleted_with_children() {
            RequestOption question = option(7L, null, "SINGLE");
            List<RequestOption> children = List.of(option(12L, 7L, null));
            given(optionRepository.findById(7L)).willReturn(Optional.of(question));
            given(optionRepository.findByParentId(7L)).willReturn(children);
            given(translationRepository.findByRequestOptionIdIn(any())).willReturn(List.of());

            service.delete(7L);

            then(translationRepository).should().deleteAll(any());
            then(optionRepository).should().deleteAll(children);
            then(optionRepository).should().delete(question);
        }

        @Test
        @DisplayName("선택지 삭제는 형제를 건드리지 않는다")
        void choice_delete_leaves_siblings() {
            RequestOption item = option(12L, 7L, null);
            given(optionRepository.findById(12L)).willReturn(Optional.of(item));
            given(translationRepository.findByRequestOptionIdIn(any())).willReturn(List.of());

            service.delete(12L);

            then(optionRepository).should().delete(item);
        }
    }

    @Test
    @DisplayName("hasAnswers는 정보 값으로 남는다 — 비선택형 질문은 자기 행 답변을 본다")
    void has_answers_stays_informational() {
        given(optionRepository.findAllByOrderBySortOrderAscIdAsc())
                .willReturn(List.of(option(30L, null, "TEXT")));
        given(translationRepository.findByRequestOptionIdIn(any())).willReturn(List.of());
        given(rentalRequestOptionRepository.findAnsweredOptionIdsByStatusIn(anyCollection(), anyCollection()))
                .willReturn(Set.of(30L));

        List<AdminRequestOptionResponse> rows = service.findAll();

        assertThat(rows).singleElement()
                .satisfies(row -> assertThat(row.hasAnswers()).isTrue());
    }
}
