package com.kovil.domain.rentalrequest.entity;

import com.kovil.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Getter
@Entity
@SuperBuilder
@Table(name = "rental_request_options")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RentalRequestOption extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "rental_request_id", nullable = false)
    private Long rentalRequestId;

    // V15 — NULL 허용: 마스터가 삭제되면 DB(SET NULL)가 링크만 끊는다. 답의 표시는
    // 스냅샷이, 값은 값 컬럼이 정본이라 행은 그대로 남는다.
    @Column(name = "request_option_id")
    private Long requestOptionId;

    // 항목이 연 입력칸의 값. 종류(extra_input_type)에 따라 셋 중 하나만 찬다.
    @Column(name = "text_value", length = 255)
    private String textValue;

    @Column(name = "number_value")
    private Long numberValue;

    @Column(name = "date_value")
    private LocalDate dateValue;

    // ADDRESS 질문의 답 — 지역 마스터(시군구 행)를 가리킨다. 다른 유형에서는 null.
    @Column(name = "region_id")
    private Long regionId;

    // ★ 제출 당시 화면 문구의 박제.
    //
    // 관리자가 "원룸"을 "투룸"으로 고치면 마스터를 따라 그리던 과거 답변이 조용히 거짓말이 된다.
    // 맡은 사무소는 이 답을 근거로 방을 찾는다 — 무엇을 물었고 무엇을 골랐는지가
    // 근거다. 표시는 이 스냅샷으로, 집계·필터는 request_option_id 로 한다.
    //
    // 운영 언어(ko)로 박는다. 사용자가 본 언어가 아닌 이유는 이 값의 독자가
    // 한국의 중개사무소이기 때문이다 — 네팔어 스냅샷은 근거로 쓰이지 못한다.
    @Column(name = "question_text_snapshot", length = 255)
    private String questionTextSnapshot;

    @Column(name = "answer_text_snapshot", length = 255)
    private String answerTextSnapshot;

    // ★ 제출자가 본 언어의 박제(V13) — 유저 화면 표시의 정본.
    //
    // ko 스냅샷만으로는 유저 화면이 살아있는 마스터(name)에 기대야 했고, 그러면 관리자의
    // 문구 수정이 제출된 요청의 표시를 바꿨다. 제출된 요청은 그 시점의 박제본이어야 한다
    // (PO 확정 2026-08-26). 유저가 나중에 앱 언어를 바꿔도 이 문구가 그대로 나온다 —
    // "제출할 때 본 그 화면"이 불변의 기준이다.
    @Column(name = "question_text_snapshot_user", length = 255)
    private String questionTextSnapshotUser;

    // 선택지 라벨·지역명. 직접 입력 답(TEXT·NUMBER·DATE)은 값 컬럼이 정본이라 null.
    @Column(name = "answer_text_snapshot_user", length = 255)
    private String answerTextSnapshotUser;
}
