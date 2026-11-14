package com.kovil.domain.user.exception;

import com.kovil.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum UserErrorCode implements ErrorCode {
    /*
     * ★ 오늘은 발동하지 않는다 — 정지에는 PERM_USER_MANAGE 가 필요하고 그건 ADMIN 뿐이라,
     *   행위자와 대상이 모두 ADMIN 이면 활성 관리자가 최소 둘이다(자기 정지는 따로 막힌다).
     *
     *   그래도 남긴다: USER_MANAGE 를 가진 **비-ADMIN 역할**(운영 매니저 같은)이 생기는 순간
     *   그 역할이 마지막 관리자를 정지시켜 아무도 못 들어가는 상태를 만들 수 있다.
     *   역할을 늘리기 쉽게 만든 것이 이 제품의 설계라, 그날이 오면 이 가드가 유일한 방어다.
     */
    LAST_ADMIN_CANNOT_BE_SUSPENDED(
            "LAST_ADMIN_CANNOT_BE_SUSPENDED",
            "마지막 관리자는 정지할 수 없습니다. 다른 관리자를 지정한 뒤 진행해 주세요.",
            HttpStatus.CONFLICT
    ),
    // 관리자를 0명으로 만드는 탈퇴 — 되돌릴 화면이 없어 DB 를 직접 고쳐야 한다.
    LAST_ADMIN_CANNOT_WITHDRAW(
            "LAST_ADMIN_CANNOT_WITHDRAW",
            "마지막 관리자는 탈퇴할 수 없습니다. 다른 관리자를 지정한 뒤 진행해 주세요.",
            HttpStatus.CONFLICT
    ),
    // 자기 역할을 자기가 바꾸면 마지막 관리자가 스스로를 강등시켜 아무도 못 들어가는 상태가 된다.
    ROLE_ASSIGN_SELF_FORBIDDEN(
            "ROLE_ASSIGN_SELF_FORBIDDEN",
            "자신의 역할은 바꿀 수 없습니다.",
            HttpStatus.FORBIDDEN
    ),
    // 관리자를 전부 없애면 되돌릴 사람이 남지 않는다 — 마지막 한 명은 강등하지 못하게 막는다.
    LAST_ADMIN_CANNOT_BE_DEMOTED(
            "LAST_ADMIN_CANNOT_BE_DEMOTED",
            "마지막 관리자는 역할을 바꿀 수 없습니다.",
            HttpStatus.CONFLICT
    ),
    /*
     * 파트너는 역할 변경으로 만들지도 없애지도 못한다.
     * 주면 부문 · 사무소 · 서류가 없는 파트너가 생기고, 빼면 승인된 부문과 사무소가 유학생 계정에 남는다.
     * 파트너는 파트너 가입으로만 생기고, 능력은 부문 심사가, 떠나는 것은 탈퇴가 정한다.
     */
    PARTNER_ROLE_NOT_ASSIGNABLE(
            "PARTNER_ROLE_NOT_ASSIGNABLE",
            "파트너 역할은 역할 변경으로 주거나 뺄 수 없습니다.",
            HttpStatus.BAD_REQUEST
    ),
    INVALID_USER_ROLE(
            "INVALID_USER_ROLE",
            "올바르지 않은 유저 역할 정보입니다.",
            HttpStatus.BAD_REQUEST
    ),
    USER_ROLE_NULL(
            "USER_ROLE_NULL",
            "유저 역할 정보를 찾을 수 없습니다.",
            HttpStatus.BAD_REQUEST
    ),
    INVALID_USER_STATUS(
            "INVALID_USER_STATUS",
            "올바르지 않은 사용자 상태입니다.",
            HttpStatus.BAD_REQUEST
    ),
    USER_STATUS_NULL(
            "USER_STATUS_NULL",
            "사용자 상태값을 찾을 수 없습니다.",
            HttpStatus.BAD_REQUEST
    ),
    INVALID_AUTH_TYPE(
            "INVALID_AUTH_TYPE",
            "올바르지 않은 인증 수단입니다.",
            HttpStatus.BAD_REQUEST
    ),
    AUTH_TYPE_NULL(
            "AUTH_TYPE_NULL",
            "인증 수단값을 찾을 수 없습니다.",
            HttpStatus.BAD_REQUEST
    ),
    USER_NOT_FOUND(
            "USER_NOT_FOUND",
            "유저 정보를 찾을 수 없습니다.",
            HttpStatus.NOT_FOUND
    ),
    USER_SUSPENDED(
            "USER_SUSPENDED",
            "정지된 계정입니다. 고객센터에 문의해 주세요.",
            HttpStatus.FORBIDDEN
    ),
    SELF_SUSPEND(
            "SELF_SUSPEND",
            "자기 자신은 정지할 수 없습니다.",
            HttpStatus.BAD_REQUEST
    ),
    NICKNAME_BLANK(
            "NICKNAME_BLANK",
            "닉네임은 필수입니다.",
            HttpStatus.BAD_REQUEST
    ),
    NICKNAME_INVALID_FORMAT(
            "NICKNAME_INVALID_FORMAT",
            "닉네임은 2~20자의 문자와 숫자만 사용할 수 있습니다.",
            HttpStatus.BAD_REQUEST
    ),
    NICKNAME_BANNED(
            "NICKNAME_BANNED",
            "사용할 수 없는 닉네임입니다.",
            HttpStatus.BAD_REQUEST
    ),
    NICKNAME_TAKEN(
            "NICKNAME_TAKEN",
            "이미 사용 중인 닉네임입니다.",
            HttpStatus.CONFLICT
    ),
    INVALID_CONSENT_TYPE(
            "INVALID_CONSENT_TYPE",
            "올바르지 않은 동의 항목입니다.",
            HttpStatus.BAD_REQUEST
    );

    private final String code;
    private final String defaultMessage;
    private final HttpStatus httpStatus;
}
