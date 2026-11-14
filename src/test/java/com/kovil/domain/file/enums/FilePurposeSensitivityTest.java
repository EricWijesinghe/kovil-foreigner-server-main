package com.kovil.domain.file.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.Arrays;

import com.kovil.domain.file.exception.FileException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// ★ INV-08의 1차 방어선. 용도를 추가하면서 민감 여부를 잘못 정하면
// 여권 사진에 공개 URL이 생긴다 — 그건 되돌릴 수 없다.
class FilePurposeSensitivityTest {

    // 이 목록을 바꾸려면 반드시 SPEC에 근거를 남길 것.
    private static final Set<FilePurpose> MUST_BE_SENSITIVE = Set.of(
            FilePurpose.VERIFICATION_DOC,   // 여권·외국인등록증 사진
            FilePurpose.CONTRACT_DOC,       // 계약서 원본 — 주소·금액·서명이 다 있다 (은퇴했지만 행이 남아 있다)
            FilePurpose.MOVE_IN_PHOTO,      // 주거 내부 사진 (은퇴했지만 행이 남아 있다)
            FilePurpose.BROKER_LICENSE);    // 중개사무소 등록증 — 대표자 성명·소재지가 박힌 공문서

    @Test
    @DisplayName("신분·주거 사진은 반드시 민감으로 분류된다")
    void sensitive_purposes_are_marked() {
        assertThat(MUST_BE_SENSITIVE).allSatisfy(purpose ->
                assertThat(purpose.isSensitive()).as("%s", purpose).isTrue());
    }

    @Test
    @DisplayName("새 용도를 추가하면 민감 여부를 반드시 정해야 한다 — 분류 누락이 없다")
    void every_purpose_is_classified() {
        for (FilePurpose purpose : FilePurpose.values()) {
            boolean expected = MUST_BE_SENSITIVE.contains(purpose);
            assertThat(purpose.isSensitive())
                    .as("%s의 민감 분류가 목록과 어긋난다 — 새 용도라면 목록에 넣거나 비민감임을 확인하라", purpose)
                    .isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("SVG는 어떤 용도에서도 허용되지 않는다 — 스크립트 삽입 벡터다")
    void svg_is_never_allowed() {
        for (FilePurpose purpose : FilePurpose.values()) {
            assertThat(purpose.allows("image/svg+xml")).as("%s", purpose).isFalse();
        }
    }

    // 계약 도메인이 사라졌어도 계약서 행은 files 에 남아 있다(지울지는 사람이 정한다).
    // 상수가 사라지면 그 행을 읽는 순간 매핑이 터지고, 민감 분류가 풀리면 공개 URL 이 생길 수 있다.
    @Test
    @DisplayName("계약서 용도는 은퇴했지만 상수와 민감 분류는 그대로 남는다")
    void contract_doc_is_retired_but_still_sensitive() {
        FilePurpose purpose = FilePurpose.from("CONTRACT_DOC");

        assertThat(purpose.isRetired()).isTrue();
        assertThat(purpose.isSensitive()).isTrue();
    }

    @Test
    @DisplayName("은퇴한 용도는 계약서·입주 사진·은행 아이콘 셋뿐이다")
    void retired_purposes_are_exactly_the_contract_domain_ones() {
        assertThat(Arrays.stream(FilePurpose.values()).filter(FilePurpose::isRetired))
                .containsExactlyInAnyOrder(
                        FilePurpose.CONTRACT_DOC, FilePurpose.MOVE_IN_PHOTO, FilePurpose.BANK_ICON);
    }

    @Test
    @DisplayName("모든 용도가 확장자 매핑을 갖는다")
    void every_allowed_type_maps_to_extension() {
        for (FilePurpose purpose : FilePurpose.values()) {
            for (String contentType : new String[]{"image/png", "image/jpeg", "application/pdf"}) {
                if (purpose.allows(contentType)) {
                    assertThat(purpose.extensionOf(contentType)).as("%s %s", purpose, contentType)
                            .isNotBlank();
                }
            }
        }
    }
}
