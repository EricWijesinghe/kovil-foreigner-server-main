package com.kovil.domain.rentalrequest.service;

import com.kovil.domain.region.entity.Region;
import com.kovil.domain.region.repository.RegionRepository;
import com.kovil.domain.rentalrequest.dto.request.RequestAnswerEntry;
import com.kovil.domain.rentalrequest.entity.RequestOption;
import com.kovil.domain.rentalrequest.enums.ExtraInputType;
import com.kovil.domain.rentalrequest.exception.RentalRequestException;
import com.kovil.domain.rentalrequest.repository.RequestOptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * ADDRESS 질문의 답은 **지역 마스터**를 가리킨다 — 그 마스터도 관리자가 고친다.
 *
 * <p>지역에 대해서도 옵션과 같은 세 층이 성립해야 한다:
 * <ul>
 *   <li>이름 수정 — 허용하되 답변이 제출 당시 지역명을 박제한다(여기서 고정).</li>
 *   <li>비활성(is_active=0) — 신규 선택에서만 빠지고, 과거 요청의 표시는 유지된다.
 *       조회가 {@code findByIdIn}(활성 여부를 보지 않음)이라 이미 성립한다.</li>
 *   <li>삭제 — 스키마의 ON DELETE RESTRICT 둘이 막는다
 *       ({@code fk_rrr_region} · {@code fk_rro_region}).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class AddressAnswerSnapshotTest {

    @Mock
    private RequestOptionRepository requestOptionRepository;

    @Mock
    private RegionRepository regionRepository;

    @InjectMocks
    private RequestAnswerResolver resolver;

    private RequestOption addressQuestion;

    private static Region region(long id, String code, String nameKo, Long parentId) {
        Region row = Region.builder()
                .parentId(parentId).code(code).nameKo(nameKo).nameEn(nameKo)
                .sortOrder(1).isActive(true).build();
        ReflectionTestUtils.setField(row, "id", id);
        return row;
    }

    @BeforeEach
    void setUp() {
        // 부모 시도 활성 검증(적대적 리뷰 2026-09-02) — 픽스처의 부모는 활성이다.
        Mockito.lenient().when(regionRepository.findByIdInAndIsActiveTrue(ArgumentMatchers.any()))
                .thenReturn(List.of(Region.builder()
                        .code("11").nameKo("서울").build()));
        addressQuestion = RequestOption.builder()
                .code("WORKPLACE").selectionType("ADDRESS").sortOrder(1).active(true)
                .required(false).extraInputType(ExtraInputType.NONE.name()).extraRequired(false)
                .build();
        ReflectionTestUtils.setField(addressQuestion, "id", 40L);
    }

    private void treeIs(RequestOption... rows) {
        given(requestOptionRepository.findByActiveTrueOrderBySortOrderAscIdAsc()).willReturn(List.of(rows));
    }

    @Test
    @DisplayName("고른 지역이 답으로 남는다 — 지역 하나당 한 건")
    void picked_region_becomes_answer() {
        treeIs(addressQuestion);
        given(regionRepository.findByCodeInAndIsActiveTrue(List.of("11215")))
                .willReturn(List.of(region(7L, "11215", "광진구", 1L)));

        List<RequestAnswerResolver.ResolvedAnswer> answers = resolver.resolve(
                List.of(addressQuestion),
                List.of(new RequestAnswerEntry("WORKPLACE", null, null, null, List.of("11215"))));

        assertThat(answers).singleElement()
                .satisfies(answer -> assertThat(answer.regionId()).isEqualTo(7L));
    }

    @Test
    @DisplayName("시도를 고르면 거절한다 — '서울 전체'는 사무소가 좁힐 수 없다(고정 지역 질문과 같은 규칙)")
    void root_region_is_rejected() {
        treeIs(addressQuestion);
        given(regionRepository.findByCodeInAndIsActiveTrue(List.of("11")))
                .willReturn(List.of(region(1L, "11", "서울특별시", null)));

        assertThatThrownBy(() -> resolver.resolve(List.of(addressQuestion),
                List.of(new RequestAnswerEntry("WORKPLACE", null, null, null, List.of("11")))))
                .isInstanceOfSatisfying(RentalRequestException.class, e ->
                        assertThat(e.getErrorCode().getCode()).isEqualTo("REGION_INVALID_LEVEL"));
    }

    @Test
    @DisplayName("없는 지역 코드는 거절한다")
    void unknown_region_is_rejected() {
        treeIs(addressQuestion);
        given(regionRepository.findByCodeInAndIsActiveTrue(List.of("99999"))).willReturn(List.of());

        assertThatThrownBy(() -> resolver.resolve(List.of(addressQuestion),
                List.of(new RequestAnswerEntry("WORKPLACE", null, null, null, List.of("99999")))))
                .isInstanceOfSatisfying(RentalRequestException.class, e ->
                        assertThat(e.getErrorCode().getCode()).isEqualTo("REGION_NOT_FOUND"));
    }

    @Test
    @DisplayName("다중 허용이면 여러 지역이 각각 한 건씩 남는다")
    void multiple_regions_when_allowed() {
        ReflectionTestUtils.setField(addressQuestion, "inputMultiple", true);
        treeIs(addressQuestion);
        given(regionRepository.findByCodeInAndIsActiveTrue(List.of("11215", "11230")))
                .willReturn(List.of(region(7L, "11215", "광진구", 1L), region(8L, "11230", "동대문구", 1L)));

        List<RequestAnswerResolver.ResolvedAnswer> answers = resolver.resolve(
                List.of(addressQuestion),
                List.of(new RequestAnswerEntry("WORKPLACE", null, null, null, List.of("11215", "11230"))));

        assertThat(answers).extracting(RequestAnswerResolver.ResolvedAnswer::regionId)
                .containsExactly(7L, 8L);
    }
}
