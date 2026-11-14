package com.kovil.domain.user.enums;

import com.kovil.domain.partner.enums.PartnerDivisionType;
import com.kovil.domain.user.exception.InvalidUserRoleException;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import static com.kovil.domain.user.exception.UserErrorCode.INVALID_USER_ROLE;
import static com.kovil.domain.user.exception.UserErrorCode.USER_ROLE_NULL;

// 역할은 **권한 집합의 이름**이다. 인가 판정은 역할이 아니라 Permission 으로 한다 —
// 호출부는 자기가 무엇을 하는지만 말하고, 누가 그것을 할 수 있는지는 이 표가 안다.
//
// ★ 역할은 셋뿐이다 — 유학생(USER) · 파트너(PARTNER) · 관리자(ADMIN). 내부 담당자는 개념째 사라졌다.
//   외부 사업자가 늘어날 때(행정사 등) 역할을 늘리지 않는다. **파트너 안에 부문을 더한다**
//   (PartnerDivisionType 한 줄) — 한 사람이 여러 부문을 가질 수 있어 역할 하나로는 표현이 안 된다.
//
// ★ ADMIN 은 **열거하지 않고 계산한다** — 전체(EnumSet.allOf) − 부문이 여는 권한. 새 Permission 을 추가했을 때
//   관리자에게 얹는 것을 잊어 관리자만 못 쓰는 기능이 생기는 사고를 구조적으로 막는다.
//   부문이 여는 권한(매물 관리 등)을 빼는 이유: 관리자는 플랫폼을 관리할 뿐, 매물을 올리거나 임대 요청을 맡는
//   사업자가 아니다. 빼는 집합도 부문 표(PartnerDivisionType)에서 계산한다 — 부문이 늘어도 그 권한이
//   관리자에게 새지 않는다.
@Getter
@RequiredArgsConstructor
public enum UserRole {

    // 유학생 — 관리자 웹의 어떤 것도 못 한다. 자기 데이터 접근은 인가가 아니라 소유권 판정이다.
    USER("USER", SignUpPolicy.OPEN, EnumSet.noneOf(Permission.class)),

    // ★ 계정 자체에는 권한이 **하나도 없다.** 로그인 · 부문 신청 · 내 정보만 된다.
    //   할 수 있는 일은 관리자가 승인한 부문이 연다(permissionsWith) — 승인 전에는 "심사 중"만 본다.
    PARTNER("PARTNER", SignUpPolicy.PARTNER_SIGNUP, EnumSet.noneOf(Permission.class)),

    // ★ ADMIN_ONLY — 가입 화면에 나오지 않는다. 관리자가 기존 회원을 승급시키는 길뿐이다.
    ADMIN("ADMIN", SignUpPolicy.ADMIN_ONLY, allButDivisionPermissions());

    private final String role;
    private final SignUpPolicy signUpPolicy;
    private final Set<Permission> permissions;

    // 역할 **자체의** 권한만 본다. 파트너의 부문 권한은 permissionsWith 로 묻는다.
    public boolean can(Permission permission) {
        return permissions.contains(permission);
    }

    /*
     * 이 사람이 실제로 가진 권한 = 역할의 권한 ∪ (파트너라면) 승인된 부문들의 권한.
     *
     * ★ 부문은 파트너에게만 의미가 있다. 다른 역할에 부문 목록이 따라와도 무시한다 —
     *   토큰이나 DB 가 어긋나도 유학생에게 매물 관리가 열리는 일은 없어야 한다.
     */
    public Set<Permission> permissionsWith(Collection<PartnerDivisionType> approvedDivisions) {
        if (this != PARTNER || approvedDivisions == null || approvedDivisions.isEmpty()) {
            return getPermissions();
        }
        EnumSet<Permission> union = permissions.isEmpty()
                ? EnumSet.noneOf(Permission.class) : EnumSet.copyOf(permissions);
        approvedDivisions.forEach(division -> union.addAll(division.getPermissions()));
        return Collections.unmodifiableSet(union);
    }

    // 관리자의 역할 변경으로 드나들 수 있는 역할인가. 파트너는 가입 · 부문 심사 · 탈퇴로만 바뀐다.
    public boolean isAssignableByAdmin() {
        return signUpPolicy != SignUpPolicy.PARTNER_SIGNUP;
    }

    // 방어적 복사가 아니라 **불변 뷰**다 — 호출부가 실수로 집합을 바꾸면 그 역할 전체가 오염된다.
    public Set<Permission> getPermissions() {
        return Collections.unmodifiableSet(permissions);
    }

    private static EnumSet<Permission> allButDivisionPermissions() {
        EnumSet<Permission> permissions = EnumSet.allOf(Permission.class);
        for (PartnerDivisionType division : PartnerDivisionType.values()) {
            permissions.removeAll(division.getPermissions());
        }
        return permissions;
    }

    public static UserRole from(String value) {
        if (value == null) {
            throw new InvalidUserRoleException(USER_ROLE_NULL);
        }
        return Arrays.stream(values())
                .filter(v -> v.getRole().equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new InvalidUserRoleException(INVALID_USER_ROLE));
    }
}
