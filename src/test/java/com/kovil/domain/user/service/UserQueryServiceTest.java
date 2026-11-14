package com.kovil.domain.user.service;

import com.kovil.domain.country.repository.CountryRepository;
import com.kovil.domain.user.dto.response.ConsentResponse;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.entity.UserConsent;
import com.kovil.domain.user.enums.ConsentType;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.user.repository.UserConsentRepository;
import com.kovil.domain.user.repository.UserRepository;
import com.kovil.domain.partner.enums.PartnerDivisionType;
import com.kovil.domain.partner.repository.PartnerDivisionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.BDDMockito;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class UserQueryServiceTest {

    private static final String UUID = "user-uuid";

    @Mock UserRepository userRepository;
    @Mock CountryRepository countryRepository;
    @Mock UserConsentRepository userConsentRepository;
    @Mock PartnerDivisionRepository partnerDivisionRepository;

    @InjectMocks UserQueryService service;

    private static <T> T withId(T entity, Long id) {
        try {
            var field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return entity;
    }

    private void givenUser() {
        given(userRepository.findByUuidAndDeletedAtIsNull(UUID)).willReturn(Optional.of(
                withId(User.builder().uuid(UUID).nickname("CalmRiver0042")
                        .phone("cipher").phoneHash("phone-hash")
                        .phoneVerifiedAt(LocalDateTime.now())
                        .preferredLanguage("ko").status(UserStatus.ACTIVE)
                        .role(UserRole.USER).build(), 7L)));
    }

    // 암호문뿐인 값을 복호화해서 실어 보낼 이유가 없다. 화면은 인증 여부만 알면 된다.
    @Test
    @DisplayName("내 정보 응답에 휴대폰 번호가 없다 — 인증 여부만 준다")
    void me_never_carries_phone() {
        givenUser();

        var me = service.getMe(UUID);

        assertThat(me.phoneVerified()).isTrue();
        assertThat(me.toString()).doesNotContain("cipher").doesNotContain("phone-hash");
    }

    /*
     * 관리자 웹 · 파트너스가 /me 의 permissions 로 메뉴를 그린다. 파트너는 **승인된 부문의 권한**만 담긴다 —
     * 심사 중이면 빈 배열이라 매물 화면이 열리지 않는다.
     */
    @Test
    @DisplayName("파트너의 /me 권한은 승인된 부문에서 나온다")
    void partner_me_permissions_come_from_approved_divisions() {
        given(userRepository.findByUuidAndDeletedAtIsNull(UUID)).willReturn(Optional.of(
                withId(User.builder().uuid(UUID).preferredLanguage("ko").status(UserStatus.ACTIVE)
                        .role(UserRole.PARTNER).build(), 7L)));
        given(partnerDivisionRepository.findApprovedTypes(7L)).willReturn(List.of(
                PartnerDivisionType.REAL_ESTATE_BROKER));

        assertThat(service.getMe(UUID).permissions())
                .containsExactlyInAnyOrder("LISTING_MANAGE", "RENTAL_REQUEST_HANDLE");
    }

    // 유학생에게는 부문을 묻지 않는다 — 부문이 섞일 길도, 쿼리가 하나 느는 일도 없다.
    @Test
    @DisplayName("파트너가 아니면 부문을 읽지 않는다")
    void non_partner_me_skips_divisions() {
        givenUser();

        assertThat(service.getMe(UUID).permissions()).isEmpty();
        BDDMockito.then(partnerDivisionRepository).shouldHaveNoInteractions();
    }

    // FR-111 — 이력은 쌓이지만 현재 상태는 타입별 최신 행 하나다.
    @Test
    @DisplayName("동의 이력에서 타입별 최신 행만 현재 상태로 추린다")
    void current_consent_is_latest_per_type() {
        givenUser();
        given(userConsentRepository.findByUserIdOrderByIdDesc(7L)).willReturn(List.of(
                withId(UserConsent.builder().userId(7L).consentType(ConsentType.MARKETING)
                        .agreed(false).version("v2").build(), 30L),
                withId(UserConsent.builder().userId(7L).consentType(ConsentType.MARKETING)
                        .agreed(true).version("v1").build(), 20L),
                withId(UserConsent.builder().userId(7L).consentType(ConsentType.TERMS_OF_SERVICE)
                        .agreed(true).version("v1").build(), 10L)));

        List<ConsentResponse> current = service.getCurrentConsents(UUID);

        assertThat(current).hasSize(2);
        assertThat(current.get(0).consentType()).isEqualTo("MARKETING");
        assertThat(current.get(0).agreed()).isFalse();
        assertThat(current.get(0).version()).isEqualTo("v2");
        assertThat(current.get(1).consentType()).isEqualTo("TERMS_OF_SERVICE");
    }
}
