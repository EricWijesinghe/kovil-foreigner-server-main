package com.kovil.domain.file.enums;

import com.kovil.domain.file.exception.FileException;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Map;

import static com.kovil.domain.file.exception.FileErrorCode.FILE_PURPOSE_NULL;
import static com.kovil.domain.file.exception.FileErrorCode.INVALID_FILE_PURPOSE;

// ★ INV-08이 여기 산다. `sensitive`가 true면 공개 URL을 만들지 않고(file_url = NULL)
// 열람은 매번 단기 presigned GET으로만 한다. 여권 사진 URL 하나가 유출되면 회복이 안 된다.
//
// 용도를 추가할 때 sensitive를 판단하지 않고 복붙하면 그 순간 불변식이 깨진다 —
// 그래서 기본값 없이 생성자에서 반드시 명시하게 했다.
//
// ★ `retention`도 같은 이유로 기본값이 없다. 삭제된 파일이 **어느 대기실로 가는지**를 정한다:
//   법정 보존 대상이면 `retained/{연수}y/`, 아니면 `deleted/`다. 둘을 섞으면 안 된다 —
//   `deleted/`는 "실수를 되돌리려고" 두는 짧은 방이고 `retained/`는 "법이 요구해서" 두는 방이라,
//   섞는 순간 짧은 규칙이 5년 보존 의무를 지운다.
@Getter
@RequiredArgsConstructor
public enum FilePurpose {

    // 신분 증빙 — **임대 중 사고가 나면 "이 사람이 누구였는지"를 증명할 유일한 근거다**
    // (PO 확정 2026-09-23). 계약 종료 직후 탈퇴해도 퇴거 정산 분쟁은 그 뒤에 터지므로,
    // 소비자 분쟁 처리 기록과 같은 3년을 격리 보관한다. 판정(승인·반려·심사자·시각)은
    // DB 에 남고 사라지는 것은 이미지뿐이다.
    VERIFICATION_DOC("verification-docs", true, FileContentTypes.DOCUMENT, 10 * 1024 * 1024L,
            Retention.LEGAL_3Y),
    // 은퇴 — 계약서 원본 스캔. 민감 문서 — 공개 URL 금지.
    // 계약 기록은 전자상거래법상 5년 보존이라 삭제해도 그 기간은 격리 보관한다.
    CONTRACT_DOC("contract-docs", true, FileContentTypes.DOCUMENT, 20 * 1024 * 1024L,
            Retention.LEGAL_5Y),
    // 은퇴 — 입주 상태 확인서 사진. 퇴거 정산 분쟁의 증거 — 소비자 분쟁 처리 기록 3년.
    MOVE_IN_PHOTO("move-in-photos", true, FileContentTypes.IMAGE, 10 * 1024 * 1024L,
            Retention.LEGAL_3Y),

    COMMUNITY_IMAGE("community-images", false, FileContentTypes.IMAGE, 10 * 1024 * 1024L,
            Retention.NONE),
    PROFILE_IMAGE("profile-images", false, FileContentTypes.IMAGE, 5 * 1024 * 1024L,
            Retention.NONE),
    // 은퇴 — 은행 마스터의 아이콘.
    BANK_ICON("bank-icons", false, FileContentTypes.ICON, 512 * 1024L,
            Retention.NONE),
    // 매물 사진. 광고 이미지라 **민감하지 않다** — 공개 URL 로 서빙해야 목록이 빠르다.
    // 사진을 빼거나 초안을 지워 폐기 표시되면 법정 보존 의무가 없어 복구 대기실로 간다. 내린(HIDDEN) 매물은
    // 다시 게시하므로 사진을 지우지 않는다.
    LISTING_IMAGE("listing-images", false, FileContentTypes.IMAGE, 10 * 1024 * 1024L,
            Retention.NONE),
    // 중개사무소 등록증 사본 — 공인중개사 부문 심사의 근거 서류. 대표자 성명 · 사무소 소재지가 박힌
    // 공문서라 인증 서류와 같은 급으로 다룬다: 공개 URL 금지, 열람은 올린 본인과 부문 심사자뿐이다.
    // 법정 보존 의무가 있는 기록이 아니라(우리는 중개 거래의 당사자가 아니다) 폐기되면 복구 대기실로 간다.
    BROKER_LICENSE("broker-licenses", true, FileContentTypes.DOCUMENT, 10 * 1024 * 1024L,
            Retention.NONE);

    private final String pathPrefix;
    private final boolean sensitive;
    private final Map<String, String> allowedContentTypes;
    private final long maxSizeBytes;
    private final Retention retention;

    /**
     * 삭제된 파일이 가는 대기실. <b>접두사에 기간을 박는다</b> — R2 수명 규칙이 아는 것은
     * "접두사 + 업로드 후 며칠"뿐이라, 이름이 곧 규칙이 된다.
     */
    @Getter
    @RequiredArgsConstructor
    public enum Retention {
        /** 법정 보존 의무 없음 — 복구 대기실로 간다. */
        NONE("deleted/"),
        /** 소비자 분쟁 처리 기록 3년(전자상거래법). */
        LEGAL_3Y("retained/3y/"),
        /** 계약·청약철회 기록 5년(전자상거래법). */
        LEGAL_5Y("retained/5y/");

        private final String pathPrefix;

        public boolean isLegal() {
            return this != NONE;
        }
    }

    /*
     * 계약 도메인(계약서 · 입주 확인서 · 수납 계좌의 은행)을 걷어내며 소유자를 잃은 용도다.
     *
     * ★ 상수를 지우지 않는 이유: files.purpose 가 VARCHAR 라 상수가 사라지면 이미 저장된 행을
     *   읽는 순간 매핑이 터진다 — 파일 원장 전체를 훑는 정리 배치까지 같이 멈춘다.
     * ★ 새 업로드(presign · confirm)와 열람은 막는다. 이미 있는 행과 R2 객체는 건드리지 않는다 —
     *   계약서는 민감 문서이고 보존 기간 판단이 남아 있어, 지울지는 사람이 정한다.
     */
    public boolean isRetired() {
        return switch (this) {
            case CONTRACT_DOC, MOVE_IN_PHOTO, BANK_ICON -> true;
            default -> false;
        };
    }

    public static FilePurpose from(String value) {
        if (value == null) {
            throw new FileException(FILE_PURPOSE_NULL);
        }
        return Arrays.stream(values())
                .filter(purpose -> purpose.name().equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new FileException(INVALID_FILE_PURPOSE));
    }

    public String extensionOf(String contentType) {
        return allowedContentTypes.get(contentType);
    }

    public boolean allows(String contentType) {
        return contentType != null && allowedContentTypes.containsKey(contentType);
    }
}
