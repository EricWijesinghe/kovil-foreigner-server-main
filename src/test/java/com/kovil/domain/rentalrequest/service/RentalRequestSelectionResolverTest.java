package com.kovil.domain.rentalrequest.service;

import com.kovil.domain.region.entity.Region;
import com.kovil.domain.region.repository.RegionRepository;
import com.kovil.domain.rentalrequest.entity.RequestOption;
import com.kovil.domain.rentalrequest.exception.RentalRequestException;
import com.kovil.domain.rentalrequest.repository.RequestOptionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

// FR-304·FR-305를 강제하는 유일한 지점이다. 등록·수정이 같은 규칙을 타는지도 여기서 결정된다.
@ExtendWith(MockitoExtension.class)
class RentalRequestSelectionResolverTest {

    @Mock RegionRepository regionRepository;
    @Mock RequestOptionRepository requestOptionRepository;

    @InjectMocks RentalRequestSelectionResolver resolver;

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

    private static Region province(Long id, String code) {
        return withId(Region.builder().code(code).nameKo("서울특별시").nameEn("Seoul").build(), id);
    }

    private static Region district(Long id, String code, String nameKo) {
        return withId(Region.builder().parentId(1L).code(code).nameKo(nameKo).nameEn(nameKo).build(), id);
    }

    private static RequestOption group(Long id, String code, String selectionType) {
        return withId(RequestOption.builder().code(code).selectionType(selectionType).build(), id);
    }

    private static RequestOption item(Long id, Long groupId, String code) {
        return withId(RequestOption.builder().parentId(groupId).code(code).build(), id);
    }

    private static String codeOf(Throwable e) {
        return ((RentalRequestException) e).getErrorCode().getCode();
    }

    @Nested
    @DisplayName("지역 (FR-305)")
    class Regions {

        @Test
        @DisplayName("시군구는 통과한다")
        void district_passes() {
            given(regionRepository.findByCodeInAndIsActiveTrue(any()))
                    .willReturn(List.of(district(10L, "11215", "광진구")));
            givenActiveParent();

            assertThat(resolver.resolveRegions(List.of("11215")))
                    .extracting(Region::getCode).containsExactly("11215");
        }

        /*
         * ★ 부모 시도의 활성까지 본다 — 시도만 비활성화되면 유저 트리에서는 통째로 사라지는데,
         *   코드를 직접 보내면 행 단위 검사만 통과해 닫힌 시도에 신규 수요가 쌓였다
         *   (적대적 리뷰 2026-09-02). 부모 검증을 빼면 죽는다.
         */
        @Test
        @DisplayName("비활성 시도 아래의 활성 시군구는 거부한다 — 닫힌 시도에 수요가 쌓이면 안 된다")
        void district_under_inactive_sido_is_rejected() {
            given(regionRepository.findByCodeInAndIsActiveTrue(any()))
                    .willReturn(List.of(district(10L, "11215", "광진구")));
            given(regionRepository.findByIdInAndIsActiveTrue(any())).willReturn(List.of());

            assertThatThrownBy(() -> resolver.resolveRegions(List.of("11215")))
                    .extracting(RentalRequestSelectionResolverTest::codeOf)
                    .isEqualTo("REGION_NOT_FOUND");
        }

        private void givenActiveParent() {
            Mockito.lenient().when(regionRepository.findByIdInAndIsActiveTrue(any()))
                    .thenReturn(List.of(Region.builder().code("11").nameKo("서울").build()));
        }

        // 시도를 고르면 "서울 전체"가 되어 매칭이 무의미해진다.
        @Test
        @DisplayName("시도를 고르면 REGION_INVALID_LEVEL이다")
        void province_is_rejected() {
            given(regionRepository.findByCodeInAndIsActiveTrue(any()))
                    .willReturn(List.of(province(1L, "11")));

            assertThatThrownBy(() -> resolver.resolveRegions(List.of("11")))
                    .isInstanceOf(RentalRequestException.class)
                    .extracting(RentalRequestSelectionResolverTest::codeOf)
                    .isEqualTo("REGION_INVALID_LEVEL");
        }

        // 비활성 지역도 "없는 지역"과 같은 답이다 — 서비스 미개시를 존재 여부로 흘리지 않는다.
        @Test
        @DisplayName("없거나 비활성인 지역은 REGION_NOT_FOUND다")
        void unknown_region_is_rejected() {
            given(regionRepository.findByCodeInAndIsActiveTrue(any())).willReturn(List.of());

            assertThatThrownBy(() -> resolver.resolveRegions(List.of("99999")))
                    .extracting(RentalRequestSelectionResolverTest::codeOf)
                    .isEqualTo("REGION_NOT_FOUND");
        }

        // V14 (PO 번복 2026-08-26) — 고정 지역 축이 질문지(ADDRESS 답변)로 이관됐다.
        @Test
        @DisplayName("지역이 비어도 통과다 — 필수는 질문지의 required 가 강제한다(V14)")
        void empty_region_is_allowed_since_v14() {
            assertThat(resolver.resolveRegions(List.of())).isEmpty();
            assertThat(resolver.resolveRegions(null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("옵션 (FR-304)")
    class Options {

        @Test
        @DisplayName("아무것도 안 고르면 빈 목록이다 — 옵션은 선택 사항이다")
        void empty_is_allowed() {
            assertThat(resolver.resolveOptions(null)).isEmpty();
            assertThat(resolver.resolveOptions(List.of())).isEmpty();
        }

        // 그룹은 화면의 묶음이지 선택지가 아니다.
        @Test
        @DisplayName("그룹 코드를 보내면 OPTION_SELECTION_INVALID다")
        void group_code_is_rejected() {
            given(requestOptionRepository.findByCodeInAndActiveTrue(any()))
                    .willReturn(List.of(group(1L, "ROOM_TYPE", "SINGLE")));

            assertThatThrownBy(() -> resolver.resolveOptions(List.of("ROOM_TYPE")))
                    .extracting(RentalRequestSelectionResolverTest::codeOf)
                    .isEqualTo("OPTION_SELECTION_INVALID");
        }

        @Test
        @DisplayName("SINGLE 그룹에서 두 개를 고르면 거절된다")
        void single_group_rejects_two() {
            given(requestOptionRepository.findByCodeInAndActiveTrue(any()))
                    .willReturn(List.of(item(11L, 1L, "ONE_ROOM"), item(12L, 1L, "TWO_ROOM")));
            given(requestOptionRepository.findByIdIn(List.of(1L)))
                    .willReturn(List.of(group(1L, "ROOM_TYPE", "SINGLE")));

            assertThatThrownBy(() -> resolver.resolveOptions(List.of("ONE_ROOM", "TWO_ROOM")))
                    .extracting(RentalRequestSelectionResolverTest::codeOf)
                    .isEqualTo("OPTION_SELECTION_INVALID");
        }

        @Test
        @DisplayName("MULTI 그룹은 여러 개를 골라도 통과한다")
        void multi_group_allows_many() {
            given(requestOptionRepository.findByCodeInAndActiveTrue(any()))
                    .willReturn(List.of(item(21L, 2L, "AIR_CONDITIONER"), item(22L, 2L, "BED")));
            given(requestOptionRepository.findByIdIn(List.of(2L)))
                    .willReturn(List.of(group(2L, "FURNISHING", "MULTI")));

            assertThat(resolver.resolveOptions(List.of("AIR_CONDITIONER", "BED")))
                    .extracting(RequestOption::getCode)
                    .containsExactly("AIR_CONDITIONER", "BED");
        }

        // 같은 코드를 두 번 보내는 것은 실수지 SINGLE 위반이 아니다.
        @Test
        @DisplayName("같은 코드를 두 번 보내면 한 번으로 접는다 — SINGLE 위반이 아니다")
        void duplicate_code_is_folded() {
            given(requestOptionRepository.findByCodeInAndActiveTrue(any()))
                    .willReturn(List.of(item(11L, 1L, "ONE_ROOM")));

            assertThat(resolver.resolveOptions(List.of("ONE_ROOM", "ONE_ROOM")))
                    .extracting(RequestOption::getCode).containsExactly("ONE_ROOM");
        }

        @Test
        @DisplayName("없거나 비활성인 옵션은 OPTION_NOT_FOUND다")
        void inactive_option_is_rejected() {
            given(requestOptionRepository.findByCodeInAndActiveTrue(any())).willReturn(List.of());

            assertThatThrownBy(() -> resolver.resolveOptions(List.of("GONE")))
                    .extracting(RentalRequestSelectionResolverTest::codeOf)
                    .isEqualTo("OPTION_NOT_FOUND");
        }

        // selectionType이 깨져 있으면 판정 근거가 없다 — 통과시키지 않는다.
        @Test
        @DisplayName("그룹 행이 사라졌으면 복수 선택을 통과시키지 않는다")
        void missing_group_rejects_multi_pick() {
            given(requestOptionRepository.findByCodeInAndActiveTrue(any()))
                    .willReturn(List.of(item(11L, 1L, "ONE_ROOM"), item(12L, 1L, "TWO_ROOM")));
            given(requestOptionRepository.findByIdIn(List.of(1L))).willReturn(List.of());

            assertThatThrownBy(() -> resolver.resolveOptions(List.of("ONE_ROOM", "TWO_ROOM")))
                    .extracting(RentalRequestSelectionResolverTest::codeOf)
                    .isEqualTo("OPTION_SELECTION_INVALID");
        }
    }
}
