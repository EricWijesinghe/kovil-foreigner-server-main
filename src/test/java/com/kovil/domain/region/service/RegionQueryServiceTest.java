package com.kovil.domain.region.service;

import com.kovil.domain.region.dto.response.RegionResponse;
import com.kovil.domain.region.entity.Region;
import com.kovil.domain.region.repository.RegionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class RegionQueryServiceTest {

    @Mock
    private RegionRepository regionRepository;

    @InjectMocks
    private RegionQueryService regionQueryService;

    private static Region region(long id, Long parentId, String code, String ko, String en) {
        Region r = Region.builder().parentId(parentId).code(code).nameKo(ko).nameEn(en).build();
        try {
            var field = Region.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(r, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return r;
    }

    private void givenSeoulTree() {
        given(regionRepository.findByIsActiveTrueOrderBySortOrderAscCodeAsc()).willReturn(List.of(
                region(1L, null, "11", "서울특별시", "Seoul"),
                region(2L, 1L, "11110", "종로구", "Jongno-gu"),
                region(3L, 1L, "11140", "중구", "Jung-gu")));
    }

    @Test
    @DisplayName("시도 아래에 시군구가 붙은 2단 트리로 나온다")
    void builds_two_level_tree() {
        givenSeoulTree();

        List<RegionResponse> tree = regionQueryService.findActiveRegionTree("ko");

        assertThat(tree).hasSize(1);
        assertThat(tree.get(0).code()).isEqualTo("11");
        assertThat(tree.get(0).children()).extracting(RegionResponse::code)
                .containsExactly("11110", "11140");
    }

    @Test
    @DisplayName("한국어 요청은 국문, 그 외 언어는 전부 영문이다")
    void name_by_language() {
        givenSeoulTree();

        assertThat(regionQueryService.findActiveRegionTree("ko").get(0).name()).isEqualTo("서울특별시");
        assertThat(regionQueryService.findActiveRegionTree("en").get(0).name()).isEqualTo("Seoul");
        assertThat(regionQueryService.findActiveRegionTree("vi").get(0).name()).isEqualTo("Seoul");
    }

    // 서비스 미개시 시도를 비활성으로 꺼도 그 아래 시군구가 루트로 올라오면 열린 셈이 된다.
    @Test
    @DisplayName("부모가 비활성이라 목록에 없으면 그 자식은 트리에서 통째로 빠진다")
    void orphaned_children_are_dropped() {
        given(regionRepository.findByIsActiveTrueOrderBySortOrderAscCodeAsc()).willReturn(List.of(
                region(1L, null, "11", "서울특별시", "Seoul"),
                region(2L, 1L, "11110", "종로구", "Jongno-gu"),
                region(9L, 99L, "26110", "중구", "Jung-gu")));

        List<RegionResponse> tree = regionQueryService.findActiveRegionTree("ko");

        assertThat(tree).extracting(RegionResponse::code).containsExactly("11");
        assertThat(tree.get(0).children()).extracting(RegionResponse::code).containsExactly("11110");
    }

    @Test
    @DisplayName("자식이 없는 시도도 빈 배열을 달고 나온다 — null이 아니다")
    void root_without_children_has_empty_list() {
        given(regionRepository.findByIsActiveTrueOrderBySortOrderAscCodeAsc())
                .willReturn(List.of(region(1L, null, "11", "서울특별시", "Seoul")));

        assertThat(regionQueryService.findActiveRegionTree("ko").get(0).children()).isEmpty();
    }

    @Test
    @DisplayName("활성 지역이 없으면 빈 트리다")
    void empty_when_nothing_active() {
        given(regionRepository.findByIsActiveTrueOrderBySortOrderAscCodeAsc()).willReturn(List.of());

        assertThat(regionQueryService.findActiveRegionTree("ko")).isEmpty();
    }
}
