package com.kovil.domain.user.dto.response;

import com.kovil.domain.partner.enums.PartnerDivisionType;
import com.kovil.domain.user.entity.User;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Schema(description = """
        내 정보.

        **휴대폰 번호는 내려주지 않는다** — 서버에는 암호문만 있고, 화면은 인증 여부만 알면 된다.
        번호를 다시 보여줘야 하는 화면이 생기면 별도 엔드포인트로 다룬다.
        """)
public record MeResponse(

        @Schema(description = "외부 노출 사용자 식별자")
        String userUuid,

        @Schema(description = "표시용 이름. 계약 명의의 정본이 아니다.", example = "Nguyen Van A")
        String name,

        @Schema(description = "커뮤니티 표시명. **v1 UI에는 노출하지 않는다**(전면 익명).",
                example = "CalmRiver0042")
        String nickname,

        @Schema(description = "연락·알림용 이메일 스냅샷", example = "a@b.com")
        String email,

        @Schema(description = "저장된 UI 언어. 이후 요청의 Accept-Language에 쓴다.", example = "ko")
        String preferredLanguage,

        @Schema(description = "국적 ISO 코드. 미설정이면 null.", example = "VN")
        String nationalityCountryCode,

        @Schema(description = "역할 이름. **화면 분기에 쓰지 말 것** — 역할은 늘어난다.", example = "USER")
        String role,

        @Schema(description = """
                이 사용자가 할 수 있는 일. **관리자 웹 · 파트너스의 메뉴·화면 분기는 role 이 아니라 이 목록으로 한다** —
                파트너 부문이 늘어도(예: 행정사) 화면 코드를 고치지 않기 위해서다.
                유학생은 빈 배열이다. 파트너는 **승인된 부문의 권한**만 담긴다(심사 중이면 빈 배열).
                """, example = "[\"LISTING_MANAGE\", \"RENTAL_REQUEST_HANDLE\"]")
        List<String> permissions,

        @Schema(description = "ACTIVE · SUSPENDED · WITHDRAWN", example = "ACTIVE")
        String status,

        @Schema(description = "휴대폰 인증 완료 여부. **false면 임대 요청 단계로 진입할 수 없다.**",
                example = "true")
        boolean phoneVerified,

        @Schema(description = "가입 시각")
        LocalDateTime createdAt
) {
    // approvedDivisions — 파트너의 승인된 부문. 파트너가 아니면 무시된다(UserRole.permissionsWith).
    public static MeResponse of(User user, String nationalityCountryCode,
                                Collection<PartnerDivisionType> approvedDivisions) {
        return new MeResponse(
                user.getUuid(),
                user.getName(),
                user.getNickname(),
                user.getEmail(),
                user.getPreferredLanguage(),
                nationalityCountryCode,
                user.getRole().name(),
                user.getRole().permissionsWith(approvedDivisions).stream().map(Enum::name).sorted().toList(),
                user.getStatus().name(),
                user.getPhoneVerifiedAt() != null,
                user.getCreatedAt());
    }
}
