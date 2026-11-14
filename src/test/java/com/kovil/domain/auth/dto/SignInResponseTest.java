package com.kovil.domain.auth.dto;

import com.kovil.domain.auth.dto.response.SignInResponse;
import com.kovil.domain.partner.enums.PartnerDivisionType;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.enums.UserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * 로그인 응답의 permissions 는 **발급한 토큰과 같은 출처**(TokenPair 의 승인 부문)에서 나온다.
 * 응답과 토큰이 다른 권한을 말하면 화면은 열렸다고 그리는데 API 는 403 을 낸다.
 */
class SignInResponseTest {

    private static User partner() {
        return User.builder().uuid("p-1").role(UserRole.PARTNER).preferredLanguage("ko").build();
    }

    @Test
    @DisplayName("승인된 공인중개사 부문이 실린 토큰이면 LISTING_MANAGE · RENTAL_REQUEST_HANDLE 이 응답에 실린다")
    void partner_with_approved_division() {
        SignInResponse response = SignInResponse.of(
                new TokenPair("at", "rt", 1800, Set.of(PartnerDivisionType.REAL_ESTATE_BROKER)), partner());

        assertThat(response.role()).isEqualTo("PARTNER");
        assertThat(response.permissions()).containsExactlyInAnyOrder("LISTING_MANAGE", "RENTAL_REQUEST_HANDLE");
    }

    @Test
    @DisplayName("심사 중인 파트너(승인 부문 없음)는 빈 권한이다")
    void partner_under_review() {
        assertThat(SignInResponse.of(new TokenPair("at", "rt", 1800), partner()).permissions()).isEmpty();
    }
}
