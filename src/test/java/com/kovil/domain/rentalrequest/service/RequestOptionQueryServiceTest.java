package com.kovil.domain.rentalrequest.service;

import com.kovil.domain.rentalrequest.dto.response.RequestOptionGroupResponse;
import com.kovil.domain.rentalrequest.entity.RequestOption;
import com.kovil.domain.rentalrequest.entity.RequestOptionTranslation;
import com.kovil.domain.rentalrequest.repository.RequestOptionRepository;
import com.kovil.domain.rentalrequest.repository.RequestOptionTranslationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class RequestOptionQueryServiceTest {

    @Mock RequestOptionRepository requestOptionRepository;
    @Mock RequestOptionTranslationRepository translationRepository;

    @InjectMocks RequestOptionQueryService service;

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

    private static RequestOptionTranslation translation(Long optionId, String lang, String name) {
        return RequestOptionTranslation.builder()
                .requestOptionId(optionId).languageCode(lang).name(name).build();
    }

    private void givenTree() {
        given(requestOptionRepository.findByActiveTrueOrderBySortOrderAscIdAsc()).willReturn(List.of(
                withId(RequestOption.builder().code("ROOM_TYPE").selectionType("SINGLE").iconKey("home").build(), 1L),
                withId(RequestOption.builder().code("FURNISHING").selectionType("MULTI").iconKey("sofa").build(), 2L),
                withId(RequestOption.builder().parentId(1L).code("ONE_ROOM").iconKey("square").build(), 11L),
                withId(RequestOption.builder().parentId(2L).code("BED").iconKey("bed").build(), 21L)));
    }

    @Test
    @DisplayName("2단 트리로 묶이고 selectionType이 그대로 내려간다 — FE 위젯이 이 값으로 갈린다")
    void builds_two_level_tree() {
        givenTree();
        given(translationRepository.findByLanguageCodeIn(any())).willReturn(List.of(
                translation(1L, "ko", "방 구조"), translation(11L, "ko", "원룸"),
                translation(2L, "ko", "옵션"), translation(21L, "ko", "침대")));

        List<RequestOptionGroupResponse> tree = service.findActiveTree("ko");

        assertThat(tree).hasSize(2);
        assertThat(tree.get(0).code()).isEqualTo("ROOM_TYPE");
        assertThat(tree.get(0).name()).isEqualTo("방 구조");
        assertThat(tree.get(0).selectionType()).isEqualTo("SINGLE");
        assertThat(tree.get(0).items()).extracting(RequestOptionGroupResponse.Item::name)
                .containsExactly("원룸");
        assertThat(tree.get(1).selectionType()).isEqualTo("MULTI");
    }

    // 요청 언어 행이 없으면 en으로 내려간다. 감추면 그 옵션을 아예 고를 수 없게 된다.
    @Test
    @DisplayName("요청 언어 번역이 없으면 en으로 폴백한다")
    void falls_back_to_english() {
        givenTree();
        given(translationRepository.findByLanguageCodeIn(any())).willReturn(List.of(
                translation(1L, "en", "Room type"), translation(11L, "en", "Studio"),
                translation(2L, "en", "Furnishings"), translation(21L, "en", "Bed")));

        List<RequestOptionGroupResponse> tree = service.findActiveTree("vi");

        assertThat(tree.get(0).name()).isEqualTo("Room type");
        assertThat(tree.get(0).items().get(0).name()).isEqualTo("Studio");
    }

    // 번역 자체가 없어도 화면은 떠야 한다 — 빈 이름이 나가면 선택이 불가능하다.
    @Test
    @DisplayName("번역이 아예 없으면 code라도 내려간다 — 항목을 감추지 않는다")
    void falls_back_to_code() {
        givenTree();
        given(translationRepository.findByLanguageCodeIn(any())).willReturn(List.of());

        List<RequestOptionGroupResponse> tree = service.findActiveTree("ko");

        assertThat(tree.get(0).name()).isEqualTo("ROOM_TYPE");
        assertThat(tree.get(0).items().get(0).name()).isEqualTo("ONE_ROOM");
        assertThat(tree).allSatisfy(group -> assertThat(group.name()).isNotBlank());
    }

    @Test
    @DisplayName("selectionType이 비어 있으면 MULTI로 읽는다 — 화면이 뜨는 쪽으로 기운다")
    void null_selection_type_reads_as_multi() {
        given(requestOptionRepository.findByActiveTrueOrderBySortOrderAscIdAsc()).willReturn(List.of(
                withId(RequestOption.builder().code("BROKEN").build(), 9L)));
        given(translationRepository.findByLanguageCodeIn(any())).willReturn(List.of());

        assertThat(service.findActiveTree("ko").get(0).selectionType()).isEqualTo("MULTI");
    }

    @Test
    @DisplayName("항목이 없는 그룹도 빈 배열로 내려간다 — null이 아니다")
    void group_without_items_has_empty_list() {
        given(requestOptionRepository.findByActiveTrueOrderBySortOrderAscIdAsc()).willReturn(List.of(
                withId(RequestOption.builder().code("EMPTY_GROUP").selectionType("MULTI").build(), 5L)));
        given(translationRepository.findByLanguageCodeIn(any())).willReturn(List.of());

        assertThat(service.findActiveTree("ko").get(0).items()).isNotNull().isEmpty();
    }

    // ★ 실측에서 잡은 결함: 기본 언어(en) 요청이 Set.of 중복으로 500이 됐다.
    @Test
    @DisplayName("기본 언어(en)로 요청해도 터지지 않는다")
    void default_language_request_does_not_throw() {
        givenTree();
        given(translationRepository.findByLanguageCodeIn(any())).willReturn(List.of());

        assertThatNoException().isThrownBy(() -> service.findActiveTree("en"));
        assertThatNoException().isThrownBy(() -> service.findActiveTree(null));
        assertThatNoException().isThrownBy(() -> service.findActiveTree(" "));
    }
}
