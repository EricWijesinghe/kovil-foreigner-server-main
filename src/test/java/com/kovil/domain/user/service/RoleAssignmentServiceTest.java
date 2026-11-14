package com.kovil.domain.user.service;

import com.kovil.domain.refreshtoken.service.RefreshTokenService;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.user.exception.UserErrorCode;
import com.kovil.domain.user.exception.UserException;
import com.kovil.domain.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

/*
 * 관리자의 역할 변경 — ADMIN 을 만드는 유일한 문. 직원 신청 흐름이 사라지면서 이 경로만 남았다.
 * 오가는 것은 USER ↔ ADMIN 뿐이고, 파트너는 이 문으로 드나들지 않는다.
 */
@ExtendWith(MockitoExtension.class)
class RoleAssignmentServiceTest {

    @Mock UserRepository userRepository;
    @Mock UserQueryService userQueryService;
    @Mock RefreshTokenService refreshTokenService;

    @InjectMocks RoleAssignmentService service;

    private User user(long id, UserRole role) {
        User u = User.builder().uuid("u-" + id).role(role).status(UserStatus.ACTIVE).build();
        ReflectionTestUtils.setField(u, "id", id);
        return u;
    }

    private static UserErrorCode codeOf(Throwable e) {
        return (UserErrorCode) ((UserException) e).getErrorCode();
    }

    // 마지막 관리자가 스스로를 강등하면 되돌릴 화면이 이 제품에 없다 — DB 를 직접 고쳐야 한다.
    @Test
    @DisplayName("자기 역할은 바꿀 수 없다")
    void cannot_change_own_role() {
        given(userQueryService.getActiveUser("u-1")).willReturn(user(1L, UserRole.ADMIN));

        assertThatThrownBy(() -> service.assignRole("u-1", "u-1", UserRole.USER))
                .isInstanceOf(UserException.class)
                .extracting(RoleAssignmentServiceTest::codeOf)
                .isEqualTo(UserErrorCode.ROLE_ASSIGN_SELF_FORBIDDEN);
    }

    @Test
    @DisplayName("마지막 관리자는 강등되지 않는다 — 아무도 못 들어가는 상태를 막는다")
    void last_admin_is_protected() {
        User target = user(2L, UserRole.ADMIN);
        given(userQueryService.getActiveUser("u-1")).willReturn(user(1L, UserRole.ADMIN));
        given(userQueryService.getActiveUser("u-2")).willReturn(target);
        given(userRepository.findIdsByRoleForUpdate(UserRole.ADMIN)).willReturn(List.of(2L));

        assertThatThrownBy(() -> service.assignRole("u-1", "u-2", UserRole.USER))
                .extracting(RoleAssignmentServiceTest::codeOf)
                .isEqualTo(UserErrorCode.LAST_ADMIN_CANNOT_BE_DEMOTED);
        assertThat(target.getRole()).isEqualTo(UserRole.ADMIN);
        then(refreshTokenService).should(never()).revokeAllFor(any());
    }

    /*
     * ★ 강등하면 액세스 토큰도 죽어야 한다. 리프레시만 끊으면 발급 시점의 role 을 든 토큰이 최대 30분
     *   옛 권한으로 통과해, 강등된 관리자가 공범 계정을 ADMIN 으로 올릴 수 있다(적대적 리뷰 2026-09-17).
     *   액세스 토큰까지 끊는 것은 revokeAllFor 안에서 일어나고 그 계약은 RefreshTokenServiceTest 가 지킨다.
     */
    @Test
    @DisplayName("관리자가 둘 이상이면 강등되고 그 사람의 세션이 끊긴다")
    void demotion_allowed_when_another_admin_remains() {
        User target = user(2L, UserRole.ADMIN);
        given(userQueryService.getActiveUser("u-1")).willReturn(user(1L, UserRole.ADMIN));
        given(userQueryService.getActiveUser("u-2")).willReturn(target);
        given(userRepository.findIdsByRoleForUpdate(UserRole.ADMIN)).willReturn(List.of(1L, 2L));

        service.assignRole("u-1", "u-2", UserRole.USER);

        assertThat(target.getRole()).isEqualTo(UserRole.USER);
        then(refreshTokenService).should().revokeAllFor(2L);
    }

    // 승급은 세는 대상이 아니다 — 관리자를 늘리는 변경까지 막으면 첫 승급이 불가능해진다.
    @Test
    @DisplayName("관리자로 승급은 인원수와 무관하게 되고 세션이 끊긴다")
    void promotion_is_not_blocked() {
        User target = user(2L, UserRole.USER);
        given(userQueryService.getActiveUser("u-1")).willReturn(user(1L, UserRole.ADMIN));
        given(userQueryService.getActiveUser("u-2")).willReturn(target);

        service.assignRole("u-1", "u-2", UserRole.ADMIN);

        assertThat(target.getRole()).isEqualTo(UserRole.ADMIN);
        then(userRepository).should(never()).findIdsByRoleForUpdate(any());
        then(refreshTokenService).should().revokeAllFor(2L);
    }

    /*
     * ★ 파트너로 만들면 사무소 · 서류 · 부문이 없는 파트너가 생긴다 — 그 상태를 고칠 화면이 없다.
     *   파트너는 파트너 가입으로만 생긴다.
     */
    @Test
    @DisplayName("PARTNER 로는 바꿀 수 없다")
    void cannot_grant_partner() {
        User target = user(2L, UserRole.USER);
        given(userQueryService.getActiveUser("u-1")).willReturn(user(1L, UserRole.ADMIN));
        given(userQueryService.getActiveUser("u-2")).willReturn(target);

        assertThatThrownBy(() -> service.assignRole("u-1", "u-2", UserRole.PARTNER))
                .extracting(RoleAssignmentServiceTest::codeOf)
                .isEqualTo(UserErrorCode.PARTNER_ROLE_NOT_ASSIGNABLE);
        assertThat(target.getRole()).isEqualTo(UserRole.USER);
        then(refreshTokenService).should(never()).revokeAllFor(any());
    }

    /*
     * ★ 파트너를 다른 역할로 바꾸면 승인된 부문 · 사무소 연결이 유학생 · 관리자 계정에 남는다.
     *   특히 ADMIN 으로 올리면 외부 사업자가 관리자 웹 전체를 갖는다. 파트너를 내보내는 길은 탈퇴다.
     */
    @Test
    @DisplayName("PARTNER 는 USER · ADMIN 어느 쪽으로도 바꿀 수 없다")
    void cannot_change_partner() {
        User partner = user(2L, UserRole.PARTNER);
        given(userQueryService.getActiveUser("u-1")).willReturn(user(1L, UserRole.ADMIN));
        given(userQueryService.getActiveUser("u-2")).willReturn(partner);

        for (UserRole next : new UserRole[]{UserRole.USER, UserRole.ADMIN}) {
            assertThatThrownBy(() -> service.assignRole("u-1", "u-2", next))
                    .as("%s", next)
                    .extracting(RoleAssignmentServiceTest::codeOf)
                    .isEqualTo(UserErrorCode.PARTNER_ROLE_NOT_ASSIGNABLE);
        }
        assertThat(partner.getRole()).isEqualTo(UserRole.PARTNER);
        then(refreshTokenService).should(never()).revokeAllFor(any());
    }
}
