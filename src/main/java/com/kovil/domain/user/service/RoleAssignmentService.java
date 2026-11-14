package com.kovil.domain.user.service;

import com.kovil.domain.refreshtoken.service.RefreshTokenService;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.exception.UserException;
import com.kovil.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.kovil.domain.user.exception.UserErrorCode.LAST_ADMIN_CANNOT_BE_DEMOTED;
import static com.kovil.domain.user.exception.UserErrorCode.PARTNER_ROLE_NOT_ASSIGNABLE;
import static com.kovil.domain.user.exception.UserErrorCode.ROLE_ASSIGN_SELF_FORBIDDEN;

/*
 * 관리자의 역할 변경(승급 · 강등). **ADMIN 은 여기로만 만들어진다.**
 *
 * ★ 오가는 것은 USER ↔ ADMIN 뿐이다. 파트너는 파트너 가입으로만 생기고 부문 심사 · 탈퇴로만 바뀐다 —
 *   이 문으로 파트너를 만들면 사무소 · 서류 없는 파트너가, 빼면 승인 부문이 남은 유학생 계정이 생긴다.
 */
@Service
@RequiredArgsConstructor
public class RoleAssignmentService {

    private final UserRepository userRepository;
    private final UserQueryService userQueryService;
    private final RefreshTokenService refreshTokenService;

    /*
     * ★ 자기 자신은 못 바꾼다 — 마지막 관리자가 실수로 스스로를 강등하면 아무도 들어갈 수
     *   없는 상태가 되고, 그 상태를 되돌릴 화면이 이 제품에 없다(DB 를 직접 고쳐야 한다).
     *   마지막 관리자를 남이 강등하는 경로도 같은 이유로 막는다.
     */
    @Transactional
    public void assignRole(String adminUuid, String targetUserUuid, UserRole nextRole) {
        User admin = userQueryService.getActiveUser(adminUuid);
        User target = userQueryService.getActiveUser(targetUserUuid);

        if (admin.getId().equals(target.getId())) {
            throw new UserException(ROLE_ASSIGN_SELF_FORBIDDEN);
        }
        if (!nextRole.isAssignableByAdmin() || !target.getRole().isAssignableByAdmin()) {
            throw new UserException(PARTNER_ROLE_NOT_ASSIGNABLE);
        }
        // 관리자를 0명으로 만드는 변경은 거절한다. 되돌릴 사람이 남지 않는다.
        // 잠금판으로 읽는 이유는 UserRepository.findIdsByRoleForUpdate 주석에 있다 —
        // 일반 count 면 동시 강등 둘이 서로를 지나쳐 관리자가 0명이 된다.
        if (target.getRole() == UserRole.ADMIN && nextRole != UserRole.ADMIN
                && userRepository.findIdsByRoleForUpdate(UserRole.ADMIN).size() <= 1) {
            throw new UserException(LAST_ADMIN_CANNOT_BE_DEMOTED);
        }

        target.changeRole(nextRole);
        /*
         * ★ 리프레시와 액세스 토큰을 함께 끊는다(revokeAllFor 안에서 둘 다 한다). 리프레시만 끊으면
         *   강등된 관리자가 액세스 토큰 수명(기본 30분) 동안 옛 권한을 그대로 써서 공범 계정을
         *   ADMIN 으로 올릴 수 있다(적대적 리뷰 2026-09-17).
         */
        refreshTokenService.revokeAllFor(target.getId());
    }
}
