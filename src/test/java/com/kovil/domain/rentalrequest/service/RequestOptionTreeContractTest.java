package com.kovil.domain.rentalrequest.service;

import com.kovil.domain.rentalrequest.dto.response.RequestOptionGroupResponse;
import com.kovil.domain.rentalrequest.entity.RequestOption;
import com.kovil.domain.rentalrequest.entity.RequestOptionTranslation;
import com.kovil.domain.rentalrequest.enums.ExtraInputType;
import com.kovil.domain.rentalrequest.repository.RequestOptionRepository;
import com.kovil.domain.rentalrequest.repository.RequestOptionTranslationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * 앱이 질문지를 그릴 수 있는 만큼 트리가 실려 나가는지 고정한다.
 *
 * <p>★ 조건부 대상은 **code로** 나간다. 앱이 들고 있는 것도 code라 id를 주면 앱이 다시
 * 매핑해야 하고, 그 매핑이 어긋나면 조건이 조용히 틀어진다.
 */
@ExtendWith(MockitoExtension.class)
class RequestOptionTreeContractTest {

    @Mock
    private RequestOptionRepository optionRepository;

    @Mock
    private RequestOptionTranslationRepository translationRepository;

    @InjectMocks
    private RequestOptionQueryService service;

    private RequestOption roomType;
    private RequestOption visitDetail;
    private RequestOption oneRoom;
    private RequestOption etc;

    private static RequestOption row(long id, Long parentId, String code, String selectionType) {
        RequestOption option = RequestOption.builder()
                .parentId(parentId).code(code).selectionType(selectionType)
                .sortOrder(1).active(true).required(false)
                .extraInputType(ExtraInputType.NONE.name()).extraRequired(false)
                .build();
        ReflectionTestUtils.setField(option, "id", id);
        return option;
    }

    private static RequestOptionTranslation name(long optionId, String text, String description) {
        RequestOptionTranslation translation = RequestOptionTranslation.builder()
                .requestOptionId(optionId).languageCode("ko").name(text).description(description).build();
        ReflectionTestUtils.setField(translation, "id", optionId);
        return translation;
    }

    @BeforeEach
    void setUp() {
        roomType = row(10L, null, "ROOM_TYPE", "SINGLE");
        ReflectionTestUtils.setField(roomType, "required", true);

        visitDetail = row(20L, null, "VISIT_DETAIL", "SINGLE");
        ReflectionTestUtils.setField(visitDetail, "showWhenOptionId", 101L);

        oneRoom = row(101L, 10L, "ONE_ROOM", null);
        etc = row(102L, 10L, "ETC", null);
        ReflectionTestUtils.setField(etc, "extraInputType", ExtraInputType.TEXT.name());
        ReflectionTestUtils.setField(etc, "extraRequired", true);
        ReflectionTestUtils.setField(etc, "extraMaxLength", 30);
    }

    @Test
    @DisplayName("질문에 required·설명이, 항목에 추가 입력 정보가 실려 나간다")
    void tree_carries_question_and_extra_axes() {
        given(optionRepository.findByActiveTrueOrderBySortOrderAscIdAsc())
                .willReturn(List.of(roomType, visitDetail, oneRoom, etc));
        given(translationRepository.findByLanguageCodeIn(Set.of("ko", "en")))
                .willReturn(List.of(
                        name(10L, "어떤 집을 찾으세요?", null),
                        name(20L, "방문 상세", "해당 없다면 넘어가세요"),
                        name(101L, "원룸", null),
                        name(102L, "기타", null)));

        List<RequestOptionGroupResponse> tree = service.findActiveTree("ko");

        RequestOptionGroupResponse first = tree.stream()
                .filter(group -> "ROOM_TYPE".equals(group.code())).findFirst().orElseThrow();
        assertThat(first.required()).isTrue();
        assertThat(first.showWhenOptionCode()).isNull();

        RequestOptionGroupResponse.Item etcItem = first.items().stream()
                .filter(item -> "ETC".equals(item.code())).findFirst().orElseThrow();
        assertThat(etcItem.extraInputType()).isEqualTo("TEXT");
        assertThat(etcItem.extraRequired()).isTrue();
        assertThat(etcItem.extraMaxLength()).isEqualTo(30);
    }

    @Test
    @DisplayName("조건부 질문은 대상 항목을 **code로** 알려 준다 — 앱이 id를 다시 매핑하지 않게")
    void conditional_target_is_exposed_as_code() {
        given(optionRepository.findByActiveTrueOrderBySortOrderAscIdAsc())
                .willReturn(List.of(roomType, visitDetail, oneRoom, etc));
        given(translationRepository.findByLanguageCodeIn(Set.of("ko", "en")))
                .willReturn(List.of(name(20L, "방문 상세", "해당 없다면 넘어가세요")));

        RequestOptionGroupResponse conditional = service.findActiveTree("ko").stream()
                .filter(group -> "VISIT_DETAIL".equals(group.code())).findFirst().orElseThrow();

        assertThat(conditional.showWhenOptionCode()).isEqualTo("ONE_ROOM");
        assertThat(conditional.description()).isEqualTo("해당 없다면 넘어가세요");
    }

    @Test
    @DisplayName("설명이 없으면 null이다 — 빈 문자열을 만들지 않는다(화면이 빈 줄을 그리지 않게)")
    void missing_description_is_null() {
        given(optionRepository.findByActiveTrueOrderBySortOrderAscIdAsc())
                .willReturn(List.of(roomType, oneRoom));
        given(translationRepository.findByLanguageCodeIn(Set.of("ko", "en")))
                .willReturn(List.of(name(10L, "어떤 집을 찾으세요?", "   ")));

        RequestOptionGroupResponse group = service.findActiveTree("ko").get(0);

        assertThat(group.description()).isNull();
    }
}
