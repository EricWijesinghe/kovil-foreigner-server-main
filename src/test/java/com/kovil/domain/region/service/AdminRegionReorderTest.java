package com.kovil.domain.region.service;

import com.kovil.common.dto.request.MasterTreeOrderRequest;
import com.kovil.common.exception.MasterException;
import com.kovil.domain.region.entity.Region;
import com.kovil.domain.region.repository.RegionRepository;
import com.kovil.domain.region.dto.AdminRegionRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.assertj.core.api.Assertions;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

// 트리 축 재정렬의 급소는 부모 검증이다 — create가 "존재만" 검사해 리프 부모의 3단 행이
// 생길 수 있는 상태에서(스키마 주석은 깊이 2 고정), /order까지 리프 parentId를 받으면
// 어느 트리에도 안 그려지는 "유령 스코프"의 재부여가 200으로 성공한다.
@ExtendWith(MockitoExtension.class)
class AdminRegionReorderTest {

    @Mock RegionRepository regionRepository;
    @InjectMocks AdminRegionService service;

    private static Region region(Long id, Long parentId, int sortOrder) {
        Region entity = Region.builder().parentId(parentId).code("C" + id)
                .nameKo("이름").nameEn("Name").sortOrder(sortOrder).isActive(true).build();
        try {
            var field = Region.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return entity;
    }

    @Test
    @DisplayName("최상위 부모의 자식 형제들을 배열 순서대로 1..N으로 재부여한다")
    void reorders_children_of_top_level_parent() {
        Region parent = region(7L, null, 1);
        Region a = region(12L, 7L, 1);
        Region b = region(9L, 7L, 2);
        given(regionRepository.findById(7L)).willReturn(Optional.of(parent));
        given(regionRepository.findSiblingsForReorder(7L)).willReturn(List.of(a, b));

        service.reorder(new MasterTreeOrderRequest(7L, List.of(9L, 12L)));

        assertThat(b.getSortOrder()).isEqualTo(1);
        assertThat(a.getSortOrder()).isEqualTo(2);
    }

    @Test
    @DisplayName("parentId가 리프(비최상위)면 유령 스코프 재부여를 거부한다")
    void leaf_parent_is_rejected() {
        Region leaf = region(12L, 7L, 1);
        given(regionRepository.findById(12L)).willReturn(Optional.of(leaf));

        assertThatThrownBy(() -> service.reorder(new MasterTreeOrderRequest(12L, List.of(1L))))
                .isInstanceOf(MasterException.class)
                .extracting(e -> ((MasterException) e).getErrorCode().getCode())
                .isEqualTo("MASTER_REFERENCE_NOT_FOUND");
        then(regionRepository).should(Mockito.never()).findSiblingsForReorder(ArgumentMatchers.any());
    }

    @Test
    @DisplayName("없는 parentId는 400이다")
    void missing_parent_is_rejected() {
        given(regionRepository.findById(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.reorder(new MasterTreeOrderRequest(99L, List.of(1L))))
                .isInstanceOf(MasterException.class)
                .extracting(e -> ((MasterException) e).getErrorCode().getCode())
                .isEqualTo("MASTER_REFERENCE_NOT_FOUND");
    }

    // BE_REQUEST_08_20_03 — 스키마 주석("3단은 서비스가 거부")이 선언한 계약을 create도 지킨다.
    // 존재-만-검사 시절에는 리프 parentId로 3단 유령 행이 생성됐다(API 직접 호출 한정).
    @Test
    @DisplayName("create도 리프 parentId를 거부한다 — 3단 유령 행 봉쇄")
    void create_rejects_leaf_parent() {
        Region leaf = region(12L, 7L, 1);
        given(regionRepository.existsByCode("11680")).willReturn(false);
        given(regionRepository.findById(12L)).willReturn(Optional.of(leaf));

        assertThatThrownBy(() -> service.create(
                new AdminRegionRequest(
                        12L, "11680", "강남구", "Gangnam-gu", null, null)))
                .isInstanceOf(MasterException.class)
                .extracting(e -> ((MasterException) e).getErrorCode().getCode())
                .isEqualTo("MASTER_REFERENCE_NOT_FOUND");
        then(regionRepository).should(Mockito.never())
                .save(ArgumentMatchers.any());
    }

    @Test
    @DisplayName("create는 최상위 parentId를 그대로 받는다 — 기존 동작 보존")
    void create_accepts_root_parent() {
        Region root = region(7L, null, 1);
        given(regionRepository.existsByCode("11680")).willReturn(false);
        given(regionRepository.findById(7L)).willReturn(Optional.of(root));
        given(regionRepository.maxSortOrderInScope(7L)).willReturn(3);
        given(regionRepository.save(ArgumentMatchers.any(Region.class)))
                .willAnswer(inv -> inv.getArgument(0));

        var response = service.create(
                new AdminRegionRequest(
                        7L, "11680", "강남구", "Gangnam-gu", null, null));

        Assertions.assertThat(response.parentId()).isEqualTo(7L);
        Assertions.assertThat(response.sortOrder()).isEqualTo(4);
    }

    // 자식 0 스코프에 비어있지 않은 ids — 순열 검증이 잡는다(빈 ids 자체는 400 검증이 먼저 막는다).
    @Test
    @DisplayName("자식 없는 최상위 parentId에 ids를 보내면 409다")
    void empty_scope_with_ids_is_conflict() {
        Region parent = region(7L, null, 1);
        given(regionRepository.findById(7L)).willReturn(Optional.of(parent));
        given(regionRepository.findSiblingsForReorder(7L)).willReturn(List.of());

        assertThatThrownBy(() -> service.reorder(new MasterTreeOrderRequest(7L, List.of(1L))))
                .isInstanceOf(MasterException.class)
                .extracting(e -> ((MasterException) e).getErrorCode().getCode())
                .isEqualTo("MASTER_ORDER_SCOPE_MISMATCH");
    }
}
