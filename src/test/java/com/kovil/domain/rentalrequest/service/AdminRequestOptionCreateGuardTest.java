package com.kovil.domain.rentalrequest.service;

import com.kovil.common.exception.MasterException;
import com.kovil.domain.rentalrequest.dto.request.AdminRequestOptionRequest;
import com.kovil.domain.rentalrequest.entity.RequestOption;
import com.kovil.domain.rentalrequest.repository.RequestOptionRepository;
import com.kovil.domain.rentalrequest.repository.RequestOptionTranslationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import jakarta.persistence.EntityManager;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

// BE_REQUEST_08_20_03 — 지역과 같은 게이트를 옵션 축에도 고정한다. 두 서비스가 미러라서
// 한쪽만 테스트하면 다른 쪽 회귀가 조용히 지나간다.
@ExtendWith(MockitoExtension.class)
class AdminRequestOptionCreateGuardTest {

    @Mock RequestOptionRepository optionRepository;
    @Mock RequestOptionTranslationRepository translationRepository;
    @Mock EntityManager entityManager;
    @InjectMocks AdminRequestOptionService service;

    private static RequestOption option(Long id, Long parentId) {
        RequestOption entity = RequestOption.builder()
                .parentId(parentId).code("OPT" + id).active(true).sortOrder(1).build();
        try {
            var field = RequestOption.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return entity;
    }

    /** 이 테스트가 보는 축은 parentId 뿐이다 — 질문지 확장(V10) 필드는 기본값으로 둔다. */
    private static AdminRequestOptionRequest req(Long parentId, String code) {
        return new AdminRequestOptionRequest(parentId, code, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null, null, null, List.of());
    }

    @Test
    @DisplayName("create가 리프(2단) parentId를 거부한다 — 3단 유령 행 봉쇄")
    void create_rejects_leaf_parent() {
        given(optionRepository.findById(12L)).willReturn(Optional.of(option(12L, 7L)));

        assertThatThrownBy(() -> service.create(req(12L, "PARKING")))
                .isInstanceOf(MasterException.class)
                .extracting(e -> ((MasterException) e).getErrorCode().getCode())
                .isEqualTo("MASTER_REFERENCE_NOT_FOUND");
        then(optionRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("최상위(그룹) parentId는 그대로 받는다 — 기존 동작 보존")
    void create_accepts_group_parent() {
        given(optionRepository.existsByCode("PARKING")).willReturn(false);
        given(optionRepository.findById(7L)).willReturn(Optional.of(option(7L, null)));
        given(optionRepository.maxSortOrderInScope(7L)).willReturn(2);
        given(optionRepository.save(any(RequestOption.class))).willAnswer(inv -> inv.getArgument(0));

        service.create(req(7L, "PARKING"));

        then(optionRepository).should().save(any(RequestOption.class));
    }

    // ★ 관리자는 코드를 짓지 않는다 — 이 화면의 취지가 "개발자 없이 질문을 만든다"이고,
    //   코드는 만든 뒤 바꿀 수 없어 오타가 영구적이기 때문이다. 코드로 분기하는 곳은 없다.
    @Test
    @DisplayName("코드를 비우면 서버가 짓는다 — 질문은 Q_ 접두어")
    void blank_code_is_generated_for_question() {
        given(optionRepository.countByCodeStartingWith("Q_")).willReturn(2);
        given(optionRepository.existsByCode("Q_003")).willReturn(false);
        given(optionRepository.maxSortOrderInScope(null)).willReturn(5);
        given(optionRepository.save(any(RequestOption.class))).willAnswer(inv -> inv.getArgument(0));

        service.create(req(null, null));

        ArgumentCaptor<RequestOption> saved = ArgumentCaptor.forClass(RequestOption.class);
        then(optionRepository).should().save(saved.capture());
        assertThat(saved.getValue().getCode()).isEqualTo("Q_003");
    }

    @Test
    @DisplayName("선택지는 부모 코드를 접두어로 물려받는다 — 페이로드에서 읽힌다")
    void blank_code_inherits_parent_prefix() {
        given(optionRepository.findById(7L)).willReturn(Optional.of(option(7L, null)));
        given(optionRepository.countByCodeStartingWith("OPT7_")).willReturn(3);
        given(optionRepository.existsByCode("OPT7_004")).willReturn(false);
        given(optionRepository.maxSortOrderInScope(7L)).willReturn(3);
        given(optionRepository.save(any(RequestOption.class))).willAnswer(inv -> inv.getArgument(0));

        service.create(req(7L, null));

        ArgumentCaptor<RequestOption> saved = ArgumentCaptor.forClass(RequestOption.class);
        then(optionRepository).should().save(saved.capture());
        assertThat(saved.getValue().getCode()).isEqualTo("OPT7_004");
    }

    @Test
    @DisplayName("코드를 보내면 그대로 쓴다 — 개발자가 특정 행을 참조해야 할 때의 문")
    void given_code_is_respected() {
        given(optionRepository.existsByCode("PARKING")).willReturn(false);
        given(optionRepository.maxSortOrderInScope(null)).willReturn(1);
        given(optionRepository.save(any(RequestOption.class))).willAnswer(inv -> inv.getArgument(0));

        service.create(req(null, "PARKING"));

        ArgumentCaptor<RequestOption> saved = ArgumentCaptor.forClass(RequestOption.class);
        then(optionRepository).should().save(saved.capture());
        assertThat(saved.getValue().getCode()).isEqualTo("PARKING");
    }

    @Test
    @DisplayName("없는 parentId는 400 — 회귀 확인")
    void create_rejects_missing_parent() {
        given(optionRepository.findById(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(req(99L, "PARKING")))
                .isInstanceOf(MasterException.class)
                .extracting(e -> ((MasterException) e).getErrorCode().getCode())
                .isEqualTo("MASTER_REFERENCE_NOT_FOUND");
    }
}
