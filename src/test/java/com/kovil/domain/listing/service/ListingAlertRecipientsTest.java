package com.kovil.domain.listing.service;

import com.kovil.domain.listing.entity.BrokerProfile;
import com.kovil.domain.listing.entity.Listing;
import com.kovil.domain.listing.repository.BrokerProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/*
 * 매물 알림의 수신자 — PO 확정 2026-10-06: 올린 중개사가 아직 그 사무소에 있으면 그 사람만,
 * 떠났으면 사무소의 현재 중개사 전원.
 */
class ListingAlertRecipientsTest {

    // 사무소 id 와 사용자 id 를 서로 다른 수로 둔다 — 바꿔 끼우면 스텁이 빗나간다.
    private static final Long OFFICE = 3L;
    private static final Long OTHER_OFFICE = 4L;
    private static final Long UPLOADER = 70L;
    private static final Long COLLEAGUE_A = 80L;
    private static final Long COLLEAGUE_B = 81L;

    private BrokerProfileRepository profileRepository;
    private ListingAlertRecipients recipients;

    @BeforeEach
    void setUp() {
        profileRepository = mock(BrokerProfileRepository.class);
        recipients = new ListingAlertRecipients(profileRepository);
    }

    // 평소 경로 — 알림 양은 올린 사람 한 명분 그대로다.
    @Test
    @DisplayName("올린 중개사가 아직 그 사무소에 있으면 그 사람에게만 보낸다")
    void uploader_still_in_office_gets_it_alone() {
        given(profileRepository.findByUserId(UPLOADER)).willReturn(Optional.of(profile(UPLOADER, OFFICE)));

        assertThat(recipients.of(listing())).containsExactly(UPLOADER);
        verify(profileRepository, never()).findByOfficeId(any());
    }

    /*
     * ★ 다른 사무소로 옮긴 사람은 프로필이 **남아 있어도** 떠난 사람이다. 프로필 존재만 보면
     *   옮겨 간 사람이 옛 사무소 광고의 알림을 계속 받고 실제 관리자는 모른다.
     */
    @Test
    @DisplayName("올린 사람이 다른 사무소로 옮겼으면 원래 사무소의 동료 전원에게 보낸다")
    void moved_uploader_is_treated_as_gone() {
        given(profileRepository.findByUserId(UPLOADER)).willReturn(Optional.of(profile(UPLOADER, OTHER_OFFICE)));
        given(profileRepository.findByOfficeId(OFFICE)).willReturn(List.of(
                profile(COLLEAGUE_A, OFFICE), profile(COLLEAGUE_B, OFFICE)));

        assertThat(recipients.of(listing())).containsExactlyInAnyOrder(COLLEAGUE_A, COLLEAGUE_B);
    }

    @Test
    @DisplayName("올린 사람이 떠났으면(프로필 없음) 사무소의 현재 중개사 전원에게 보낸다")
    void departed_uploader_hands_over_to_colleagues() {
        given(profileRepository.findByUserId(UPLOADER)).willReturn(Optional.empty());
        given(profileRepository.findByOfficeId(OFFICE)).willReturn(List.of(
                profile(COLLEAGUE_A, OFFICE), profile(COLLEAGUE_B, OFFICE)));

        assertThat(recipients.of(listing())).containsExactlyInAnyOrder(COLLEAGUE_A, COLLEAGUE_B);
    }

    // 남은 사람이 없으면 받을 사람도 없다 — 그런 사무소의 공개 매물은 이탈 처리가 이미 내렸다.
    @Test
    @DisplayName("사무소에 아무도 없으면 아무에게도 보내지 않는다")
    void nobody_left_nobody_notified() {
        given(profileRepository.findByUserId(UPLOADER)).willReturn(Optional.empty());
        given(profileRepository.findByOfficeId(OFFICE)).willReturn(List.of());

        assertThat(recipients.of(listing())).isEmpty();
    }

    private static BrokerProfile profile(Long userId, Long officeId) {
        return BrokerProfile.builder().userId(userId).officeId(officeId).build();
    }

    private static Listing listing() {
        return Listing.builder().id(1L).uuid("l-1").officeId(OFFICE).createdByUserId(UPLOADER).build();
    }
}
