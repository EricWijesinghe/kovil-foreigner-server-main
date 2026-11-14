package com.kovil.domain.rentalrequest.entity;

import com.kovil.domain.rentalrequest.enums.ExtraInputType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 관리자가 질문지를 고쳤을 때 **과거 답변이 어떻게 되는가**를 고정한다.
 *
 * <p>V15(PO 확정 2026-08-26)부터 층이 둘뿐이다:
 * <ul>
 *   <li>수정 — **무엇이든 바꿀 수 있다.** 문구·순서·활성은 물론 구조(selectionType·
 *       showWhen·extraInputType)도 답변 유무와 무관하게 허용된다. 과거 답변은 제출 당시
 *       문구·값을 스냅샷(V13)으로 들고 있어 흔들리지 않는다.</li>
 *   <li>삭제 — 조건 없이 된다. 답변 행의 마스터 링크는 DB(ON DELETE SET NULL)가 끊는다.</li>
 * </ul>
 *
 * <p>※ 과거의 OPTION_HAS_ANSWERS 잠금(V10~V14)은 폐기됐다 — 부활 금지(SPEC_2026_08_26 §6).
 */
class RequestOptionStructureTest {

    private static RequestOption question() {
        return RequestOption.builder()
                .code("ROOM_TYPE")
                .selectionType("MULTI")
                .sortOrder(1)
                .active(true)
                .required(false)
                .extraInputType(ExtraInputType.NONE.name())
                .extraRequired(false)
                .build();
    }

    /** 현재 값을 그대로 담은 Edit — 바꿀 축만 갈아 끼워 쓴다. */
    private static RequestOption.Edit same(RequestOption option) {
        return new RequestOption.Edit(option.getSelectionType(), option.getIconKey(),
                option.getSortOrder(), option.getActive(), option.getRequired(),
                option.getShowWhenOptionId(), option.getExtraInputType(),
                option.getExtraMaxLength(), option.getExtraRequired(),
                option.getInputMaxLength(), option.getInputMinNumber(),
                option.getInputMaxNumber(), option.getInputMultiple(),
                option.getInputUnit(), option.getInputMultiline(), option.getInputAllowPast());
    }

    @Test
    @DisplayName("유형 변경(MULTI→SINGLE)이 답변 유무와 무관하게 된다 — 잠금은 폐기됐다(V15)")
    void selection_type_change_is_always_allowed() {
        RequestOption option = question();
        RequestOption.Edit base = same(option);

        assertThatCode(() -> option.update(new RequestOption.Edit("SINGLE", base.iconKey(),
                base.sortOrder(), base.active(), base.required(), base.showWhenOptionId(),
                base.extraInputType(), base.extraMaxLength(), base.extraRequired(),
                base.inputMaxLength(), base.inputMinNumber(), base.inputMaxNumber(),
                base.inputMultiple(), base.inputUnit(), base.inputMultiline(), base.inputAllowPast())))
                .doesNotThrowAnyException();
        assertThat(option.getSelectionType()).isEqualTo("SINGLE");
    }

    @Test
    @DisplayName("노출 조건·추가입력 변경도 그냥 된다")
    void condition_and_extra_changes_are_allowed() {
        RequestOption option = question();
        RequestOption.Edit base = same(option);

        option.update(new RequestOption.Edit(base.selectionType(), base.iconKey(),
                base.sortOrder(), base.active(), base.required(), 99L,
                ExtraInputType.TEXT.name(), 50, true,
                base.inputMaxLength(), base.inputMinNumber(), base.inputMaxNumber(),
                base.inputMultiple(), base.inputUnit(), base.inputMultiline(), base.inputAllowPast()));

        assertThat(option.getShowWhenOptionId()).isEqualTo(99L);
        assertThat(option.getExtraInputType()).isEqualTo("TEXT");
        assertThat(option.getExtraRequired()).isTrue();
    }

    @Test
    @DisplayName("문구 축(아이콘·순서·활성)도 당연히 된다 — 과거 표시는 스냅샷이 지킨다")
    void cosmetic_changes_are_allowed() {
        RequestOption option = question();
        RequestOption.Edit base = same(option);

        option.update(new RequestOption.Edit(base.selectionType(), "sofa", 9, false,
                base.required(), base.showWhenOptionId(), base.extraInputType(),
                base.extraMaxLength(), base.extraRequired(),
                base.inputMaxLength(), base.inputMinNumber(), base.inputMaxNumber(),
                base.inputMultiple(), base.inputUnit(), base.inputMultiline(), base.inputAllowPast()));

        assertThat(option.getIconKey()).isEqualTo("sofa");
        assertThat(option.getSortOrder()).isEqualTo(9);
        assertThat(option.getActive()).isFalse();
    }
}
