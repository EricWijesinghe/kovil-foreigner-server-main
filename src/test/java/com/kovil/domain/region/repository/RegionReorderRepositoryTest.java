package com.kovil.domain.region.repository;

import com.kovil.common.config.JpaAuditingConfig;
import com.kovil.domain.region.entity.Region;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

// 트리 스코프 쿼리는 JPQL의 null 분기가 급소다 — `:parentId IS NULL` 갈래가 틀리면
// "최상위 형제들"이 빈 스코프가 되어 재정렬이 영원히 409다. 실DB로 고정한다.
@DataJpaTest
@Import(JpaAuditingConfig.class)
class RegionReorderRepositoryTest {

    @Autowired
    RegionRepository regionRepository;

    private Long seoulId;
    private Long busanId;

    @BeforeEach
    void seed() {
        Region seoul = regionRepository.save(region(null, "11", 1));
        Region busan = regionRepository.save(region(null, "26", 2));
        seoulId = seoul.getId();
        busanId = busan.getId();
        regionRepository.save(region(seoulId, "11680", 1)); // 강남구
        regionRepository.save(region(seoulId, "11710", 2)); // 송파구 (비활성)
        regionRepository.save(region(busanId, "26440", 1));
    }

    private static Region region(Long parentId, String code, int sortOrder) {
        return Region.builder().parentId(parentId).code(code)
                .nameKo("이름-" + code).nameEn("Name-" + code)
                .sortOrder(sortOrder).isActive(!code.equals("11710")).build();
    }

    @Test
    @DisplayName("parentId null은 최상위 형제들만 잡는다")
    void null_parent_scopes_roots() {
        assertThat(regionRepository.findSiblingsForReorder(null))
                .extracting(Region::getCode).containsExactlyInAnyOrder("11", "26");
    }

    // 스코프는 활성·비활성을 가리지 않는다 — 관리자 목록이 비활성을 섞어 보여주므로
    // 순서도 함께 재부여돼야 한다.
    @Test
    @DisplayName("자식 스코프는 비활성 행을 포함한다")
    void child_scope_includes_inactive() {
        assertThat(regionRepository.findSiblingsForReorder(seoulId))
                .extracting(Region::getCode).containsExactlyInAnyOrder("11680", "11710");
    }

    @Test
    @DisplayName("다른 부모의 자식은 스코프에 섞이지 않는다")
    void scope_excludes_other_parents() {
        assertThat(regionRepository.findSiblingsForReorder(busanId))
                .extracting(Region::getCode).containsExactly("26440");
    }

    @Test
    @DisplayName("생성 기본 순서는 같은 부모 안의 max+1이다 — 빈 스코프면 1")
    void max_sort_order_is_scoped() {
        assertThat(regionRepository.maxSortOrderInScope(seoulId)).isEqualTo(2);
        assertThat(regionRepository.maxSortOrderInScope(null)).isEqualTo(2);
        assertThat(regionRepository.maxSortOrderInScope(busanId) + 1).isEqualTo(2);
    }
}
