package com.kovil.domain.rentalrequest.entity;

import com.kovil.common.entity.BaseEntity;
import com.kovil.domain.rentalrequest.enums.ExtraInputType;
import com.kovil.domain.rentalrequest.enums.OptionSelectionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;



// 항목 추가 = 행 추가가 설계 전제다. 코드에 옵션 코드를 하드코딩하지 않는다.
@Getter
@Entity
@SuperBuilder
@Table(name = "request_options")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RequestOption extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    // NULL = 그룹(1단), 값 있음 = 항목(2단). 깊이 2 고정이다.
    @Column(name = "parent_id")
    private Long parentId;

    @Column(name = "code", nullable = false, length = 40)
    private String code;

    // 그룹 행에만 값이 있다.
    @Column(name = "selection_type", length = 10)
    private String selectionType;

    @Column(name = "icon_key", length = 40)
    private String iconKey;

    @Builder.Default
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Builder.Default
    @Column(name = "active", nullable = false)
    private Boolean active = true;

    // ── 질문(그룹) 축 ────────────────────────────────────────────────────────
    @Builder.Default
    @Column(name = "required", nullable = false)
    private Boolean required = false;

    // 이 항목이 선택됐을 때만 이 질문을 보인다. NULL = 항상.
    @Column(name = "show_when_option_id")
    private Long showWhenOptionId;

    // ── 항목 축 — 고르면 열리는 추가 입력 ─────────────────────────────────────
    @Builder.Default
    @Column(name = "extra_input_type", nullable = false, length = 10)
    private String extraInputType = ExtraInputType.NONE.name();

    @Column(name = "extra_max_length")
    private Integer extraMaxLength;

    @Builder.Default
    @Column(name = "extra_required", nullable = false)
    private Boolean extraRequired = false;

    // ── 비선택형 질문(TEXT/NUMBER/DATE/ADDRESS)의 입력 제약 ────────────────────
    @Column(name = "input_max_length")
    private Integer inputMaxLength;

    @Column(name = "input_min_number")
    private Long inputMinNumber;

    @Column(name = "input_max_number")
    private Long inputMaxNumber;

    @Builder.Default
    @Column(name = "input_multiple", nullable = false)
    private Boolean inputMultiple = false;

    // 표시 단위(원·년·회) — 값 검증에는 쓰지 않는다. 유형을 늘리지 않고 흡수하는 축이다.
    @Column(name = "input_unit", length = 20)
    private String inputUnit;

    @Builder.Default
    @Column(name = "input_multiline", nullable = false)
    private Boolean inputMultiline = false;

    // DATE 질문만: false = 오늘 이전 날짜 거부(OPTION_DATE_IN_PAST). "입주 희망일"은 미래,
    // "입국일"은 과거가 답이라 코드가 아니라 관리자가 질문마다 정한다(V16).
    @Builder.Default
    @Column(name = "input_allow_past", nullable = false)
    private Boolean inputAllowPast = true;

    public boolean isGroup() {
        return parentId == null;
    }

    /**
     * 유형을 읽는다. **모르는 값이면 MULTI로 떨어진다.**
     *
     * <p>서버가 자기 enum에 없는 값을 만나는 경우는 하나다 — 롤백. 새 유형을 쓰는 행이
     * 남은 채 이전 버전으로 되돌아가면 이 자리로 온다. 그때 예외를 던지면 요청 화면 전체가
     * 500이 된다. 선택형(MULTI)으로 떨어뜨리면 최소한 목록은 뜨고, 검증은
     * "SINGLE 강제"만 풀리므로 잘못 저장될 여지도 없다(선택지가 없으면 답도 없다).
     */
    public OptionSelectionType selectionTypeOrMulti() {
        try {
            return selectionType == null
                    ? OptionSelectionType.MULTI
                    : OptionSelectionType.valueOf(selectionType);
        } catch (IllegalArgumentException e) {
            return OptionSelectionType.MULTI;
        }
    }

    // 값이 깨져 있으면 NONE으로 읽는다 — selectionTypeOrMulti와 같은 이유로 화면이 뜨는 쪽에 기운다.
    public ExtraInputType extraInputTypeOrNone() {
        try {
            return extraInputType == null ? ExtraInputType.NONE : ExtraInputType.valueOf(extraInputType);
        } catch (IllegalArgumentException e) {
            return ExtraInputType.NONE;
        }
    }

    /**
     * 수정 — **아무것도 잠그지 않는다** (PO 확정 2026-08-26, V15).
     *
     * <p>답변이 달린 질문의 구조(selectionType 등)를 바꿔도 지난 요청은 스냅샷(V13)이
     * 지킨다. 유일한 대가는 진행 중 요청의 주인이 수정할 때 바뀐 질문을 다시 답하는 것 —
     * 앱이 현재 질문지로 다시 그리므로 화면이 깨지지 않는다. 관리자를 막는 것보다 낫다는
     * 것이 PO 의 결정이다.
     */
    /**
     * 수정 대상 값 묶음. 인자가 열넷을 넘어가면 호출부에서 순서를 셀 수 없다 —
     * 이름으로 읽히게 레코드로 받는다.
     */
    public record Edit(
            String selectionType, String iconKey, Integer sortOrder, Boolean active,
            Boolean required, Long showWhenOptionId,
            String extraInputType, Integer extraMaxLength, Boolean extraRequired,
            Integer inputMaxLength, Long inputMinNumber, Long inputMaxNumber, Boolean inputMultiple,
            String inputUnit, Boolean inputMultiline, Boolean inputAllowPast
    ) {
    }

    public void update(Edit edit) {
        this.selectionType = edit.selectionType();
        this.iconKey = edit.iconKey();
        this.sortOrder = edit.sortOrder();
        this.active = edit.active();
        this.required = edit.required() != null ? edit.required() : this.required;
        this.showWhenOptionId = edit.showWhenOptionId();
        this.extraInputType = edit.extraInputType() != null ? edit.extraInputType() : this.extraInputType;
        this.extraMaxLength = edit.extraMaxLength();
        this.extraRequired = edit.extraRequired() != null ? edit.extraRequired() : this.extraRequired;
        // 입력 제약은 구조가 아니라 **범위**다 — 답변이 있어도 좁히거나 넓힐 수 있다.
        // 과거 답변이 새 범위를 벗어나더라도 소급 무효화하지 않는다(required 와 같은 취급).
        this.inputMaxLength = edit.inputMaxLength();
        this.inputMinNumber = edit.inputMinNumber();
        this.inputMaxNumber = edit.inputMaxNumber();
        this.inputMultiple = edit.inputMultiple() != null ? edit.inputMultiple() : this.inputMultiple;
        this.inputUnit = edit.inputUnit();
        this.inputMultiline = edit.inputMultiline() != null ? edit.inputMultiline() : this.inputMultiline;
        this.inputAllowPast = edit.inputAllowPast() != null ? edit.inputAllowPast() : this.inputAllowPast;
    }

    /** 과거 날짜를 허용하는가 — 미지정(null)은 허용이다(기존 질문의 동작 보존). */
    public boolean allowsPastDate() {
        return inputAllowPast == null || inputAllowPast;
    }


    // /order 재정렬 전용 — 이름·활성·부모는 건드리지 않는다.
    public void changeSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }
}
