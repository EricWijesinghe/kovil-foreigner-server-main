package com.kovil.domain.file.service;

import com.kovil.domain.file.entity.StoredFile;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.enums.Permission;
import org.springframework.stereotype.Component;

// ★ 민감 파일 열람의 문지기.
//
// 파일 원장은 "누가 볼 수 있는지"를 모른다. 그래서 용도별 규칙을 여기 모으고,
// **소유 도메인이 아직 없는 용도는 기본 거부**로 둔다 — 열어 두고 잊는 것보다 낫다.
//
// 규칙: 민감 파일은 **올린 본인 + 그 서류를 업무로 심사하는 사람**만 본다.
//   인증 서류 → 인증 심사자(VERIFICATION_REVIEW) · 중개사무소 등록증 → 파트너 심사자(PARTNER_APPROVE).
//   ★ 심사 권한 하나가 모든 민감 파일을 여는 구조로 두지 않는다 — 인증 심사만 맡는 사람이 생기면
//     그 사람에게 중개사 서류까지 열리고, 그 반대도 같다.
@Component
public class FileAccessPolicy {

    // viewer는 **DB에서 방금 읽은 활성 유저**다(getActiveUser 통과) — 토큰 클레임이 아니다.
    // 정지·강등이 토큰 수명과 무관하게 즉시 이 판정에 반영된다.
    public boolean canRead(StoredFile file, User viewer) {
        // 은퇴한 용도(계약서·입주 사진·은행 아이콘)는 누구에게도 열지 않는다. 소유 도메인이
        // 사라져 "누구의 파일인가"를 판정할 근거가 없다 — 관리자도 예외가 아니다.
        if (file.getPurpose().isRetired()) {
            return false;
        }
        if (!file.isSensitive()) {
            // 비민감 파일은 공개 URL이 따로 있다. 이 통로도 막을 이유가 없다.
            return true;
        }
        boolean uploader = viewer.getId().equals(file.getUploaderId());
        return switch (file.getPurpose()) {
            // 인증 문서의 업로더는 신청자 본인이다.
            case VERIFICATION_DOC -> uploader || can(viewer, Permission.VERIFICATION_REVIEW);
            // 등록증의 업로더는 가입한 대표자 본인이다.
            case BROKER_LICENSE -> uploader || can(viewer, Permission.PARTNER_APPROVE);
            default -> false;
        };
    }

    // 역할 이름으로 묻지 않는다 — 이 권한이 없는 역할이 생겨도 이 통로가 자동으로 열리지 않아야 한다.
    // 역할 자체의 권한만 본다: 심사 권한은 파트너 부문으로 열리는 능력이 아니다.
    private boolean can(User viewer, Permission permission) {
        return viewer.getRole().can(permission);
    }
}
