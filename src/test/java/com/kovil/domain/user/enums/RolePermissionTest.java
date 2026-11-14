package com.kovil.domain.user.enums;

import com.kovil.domain.partner.enums.PartnerDivisionType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 권한 표는 인가의 정본이다. 여기가 조용히 바뀌면 화면이 아니라 **접근 범위**가 바뀐다 —
// 그런 변경이 리뷰 없이 지나가지 않도록 못을 박는다.
class RolePermissionTest {

    /*
     * ★ ADMIN = 전체 − 부문이 여는 권한. 기대값도 부문 표에서 계산한다 — 새 Permission 은 관리자에게 자동으로 따라오고,
     *   새 부문의 권한은 자동으로 빠진다. 관리자는 플랫폼을 관리할 뿐 매물을 올리는 사업자가 아니다.
     */
    @Test
    @DisplayName("ADMIN 은 부문이 여는 권한을 뺀 모든 권한을 가진다")
    void admin_has_every_permission_but_division_ones() {
        Set<Permission> divisionPermissions = Arrays.stream(
                        PartnerDivisionType.values())
                .flatMap(division -> division.getPermissions().stream())
                .collect(Collectors.toSet());

        Arrays.stream(Permission.values()).forEach(permission ->
                assertThat(UserRole.ADMIN.can(permission))
                        .as("%s", permission)
                        .isEqualTo(!divisionPermissions.contains(permission)));
    }

    // 지금의 표를 그대로 못 박는다 — 위 계산이 통째로 틀려도(예: 전부 빼기) 여기서 드러난다.
    @Test
    @DisplayName("ADMIN 은 매물 관리 · 임대 요청 처리를 갖지 않고, 임대 요청 열람 · 매물 신고 처리와 플랫폼 관리 권한은 전부 갖는다")
    void admin_permission_table() {
        assertThat(UserRole.ADMIN.getPermissions()).containsExactlyInAnyOrder(
                Permission.RENTAL_REQUEST_VIEW,
                Permission.VERIFICATION_REVIEW, Permission.COMMUNITY_MODERATE, Permission.USER_MANAGE,
                Permission.PARTNER_APPROVE, Permission.ROLE_ASSIGN, Permission.MASTER_DATA_MANAGE,
                Permission.NOTIFICATION_MANAGE, Permission.APP_POLICY_MANAGE, Permission.LISTING_MODERATE);
        assertThat(UserRole.ADMIN.can(Permission.LISTING_MANAGE)).isFalse();
        assertThat(UserRole.ADMIN.can(Permission.RENTAL_REQUEST_HANDLE)).isFalse();
    }

    @Test
    @DisplayName("USER 는 관리자 웹의 어떤 권한도 갖지 않는다")
    void plain_user_has_none() {
        assertThat(UserRole.USER.getPermissions()).isEmpty();
        Arrays.stream(Permission.values())
                .forEach(permission -> assertThat(UserRole.USER.can(permission)).isFalse());
    }

    /*
     * ★ 파트너 계정 자체에는 권한이 하나도 없다. 할 수 있는 일은 승인된 부문이 연다 —
     *   심사 중인 파트너가 관리자 웹이나 매물 화면에 닿으면 심사가 없는 것과 같다.
     */
    @Test
    @DisplayName("PARTNER 역할 자체는 권한이 없다")
    void partner_role_has_none() {
        assertThat(UserRole.PARTNER.getPermissions()).isEmpty();
        assertThat(UserRole.PARTNER.permissionsWith(List.of())).isEmpty();
    }

    @Test
    @DisplayName("공인중개사 부문이 승인되면 매물 관리와 임대 요청 처리만 열린다")
    void approved_broker_division_opens_listing_and_rental_request_handling_only() {
        assertThat(UserRole.PARTNER.permissionsWith(List.of(
                PartnerDivisionType.REAL_ESTATE_BROKER)))
                .containsExactlyInAnyOrder(Permission.LISTING_MANAGE, Permission.RENTAL_REQUEST_HANDLE);
    }

    /*
     * ★ 공인중개사 부문이 열면 안 되는 것들. 하나씩 이유가 있다:
     *   RENTAL_REQUEST_VIEW — 관리자의 전체 열람이다. 사무소는 대기 요약과 자기가 맡은 요청만 본다.
     *   VERIFICATION_REVIEW — 신분 심사는 이 제품의 첫 불변식이다.
     *   PARTNER_APPROVE · ROLE_ASSIGN — 외부 사업자가 자기 · 남의 심사나 관리자 승급에 닿는다.
     *   LISTING_MODERATE — 중개사가 경쟁 사무소 매물을 차단하거나 자기 매물의 차단을 풀 수 있게 된다.
     */
    @Test
    @DisplayName("공인중개사 부문에 열리면 안 되는 권한은 닫혀 있다")
    void broker_division_must_not_open_admin_powers() {
        Set<Permission> broker = UserRole.PARTNER.permissionsWith(List.of(
                PartnerDivisionType.REAL_ESTATE_BROKER));
        assertThat(broker).doesNotContain(
                Permission.RENTAL_REQUEST_VIEW,
                Permission.VERIFICATION_REVIEW, Permission.PARTNER_APPROVE, Permission.ROLE_ASSIGN,
                Permission.USER_MANAGE, Permission.MASTER_DATA_MANAGE, Permission.COMMUNITY_MODERATE,
                Permission.NOTIFICATION_MANAGE, Permission.APP_POLICY_MANAGE, Permission.LISTING_MODERATE);
    }

    // 부문은 파트너에게만 의미가 있다 — 토큰이나 DB 가 어긋나도 유학생에게 매물 관리가 열리면 안 된다.
    @Test
    @DisplayName("파트너가 아닌 역할은 부문 목록이 따라와도 무시한다")
    void divisions_are_ignored_for_non_partners() {
        List<PartnerDivisionType> broker = List.of(
                PartnerDivisionType.REAL_ESTATE_BROKER);
        assertThat(UserRole.USER.permissionsWith(broker)).isEmpty();
        assertThat(UserRole.ADMIN.permissionsWith(broker)).isEqualTo(UserRole.ADMIN.getPermissions())
                .doesNotContain(Permission.LISTING_MANAGE, Permission.RENTAL_REQUEST_HANDLE);
    }

    // 계약 · 직원 신청 · 내부 담당자의 임대 요청 처리(배정 · 이관)를 위한 권한은 그 흐름과 함께 사라졌다.
    @Test
    @DisplayName("사라진 도메인의 권한은 남아 있지 않다")
    void retired_permissions_are_gone() {
        assertThat(Arrays.stream(Permission.values()).map(Enum::name))
                .doesNotContain("CONTRACT_MANAGE", "CONTRACT_ASSIGN", "INSPECTION_REVISE",
                        "STAFF_APPROVE", "STAFF_LOOKUP", "RENTAL_REQUEST_MANAGE", "ASSIGNMENT_OVERRIDE");
    }

    /*
     * ★ 임대 요청 처리는 부문이 연다 — 관리자 계산(전체 − 부문 권한)이 이 권한을 저절로 빼는지 본다.
     *   관리자에게 새면 관리자 토큰으로 요청을 잡을 수 있게 된다(관리자는 요청을 맡는 사업자가 아니다).
     */
    @Test
    @DisplayName("임대 요청 처리는 공인중개사 부문의 권한이고, 관리자에게서는 계산으로 빠진다")
    void rental_request_handle_belongs_to_broker_division() {
        assertThat(PartnerDivisionType.REAL_ESTATE_BROKER
                .getPermissions()).contains(Permission.RENTAL_REQUEST_HANDLE);
        assertThat(UserRole.ADMIN.can(Permission.RENTAL_REQUEST_HANDLE)).isFalse();
        assertThat(UserRole.ADMIN.can(Permission.RENTAL_REQUEST_VIEW)).isTrue();
        assertThat(UserRole.PARTNER.can(Permission.RENTAL_REQUEST_HANDLE)).isFalse();
        assertThat(UserRole.USER.can(Permission.RENTAL_REQUEST_HANDLE)).isFalse();
    }

    // 역할 변경으로 드나드는 것은 USER ↔ ADMIN 뿐이다. 파트너는 가입 · 부문 심사 · 탈퇴로만 바뀐다.
    @Test
    @DisplayName("역할 변경으로 줄 수 있는 역할은 USER · ADMIN 뿐이다")
    void only_user_and_admin_are_assignable() {
        assertThat(UserRole.USER.isAssignableByAdmin()).isTrue();
        assertThat(UserRole.ADMIN.isAssignableByAdmin()).isTrue();
        assertThat(UserRole.PARTNER.isAssignableByAdmin()).isFalse();
    }

    // 권한 집합을 밖에서 고치면 그 역할을 쓰는 **모든 요청**이 함께 오염된다.
    @Test
    @DisplayName("권한 집합은 밖에서 바꿀 수 없다")
    void permissions_are_immutable() {
        assertThatThrownBy(() -> UserRole.ADMIN.getPermissions().add(Permission.USER_MANAGE))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
