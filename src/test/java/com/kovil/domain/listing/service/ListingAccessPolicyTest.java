package com.kovil.domain.listing.service;

import com.kovil.domain.listing.entity.BrokerProfile;
import com.kovil.domain.listing.entity.BrokerageOffice;
import com.kovil.domain.listing.entity.Listing;
import com.kovil.domain.listing.exception.ListingException;
import com.kovil.domain.listing.repository.BrokerProfileRepository;
import com.kovil.domain.listing.repository.BrokerageOfficeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/*
 * 중개사 전 화면의 인가 경계. BrokerListingServiceTest 는 이 정책을 통째로 목으로 바꾸므로
 * 사무소 비교나 광고 가능 게이트를 지워도 그쪽은 초록이다 — 그래서 여기서 직접 박는다.
 *
 * ★ 범위 축은 사무소다. 올린 사람이 아니다. 아래 격자는 두 축이 갈리는 칸을 전부 채운다:
 *   (같은 사무소 · 다른 사무소) × (내가 올림 · 남이 올림).
 */
class ListingAccessPolicyTest {

    // userId 와 officeId 를 서로 다른 값으로 둔다 — findById(userId) 처럼 축을 섞으면 바로 드러난다.
    private static final long CEO_USER_ID = 10L;
    private static final long COLLEAGUE_USER_ID = 11L;
    private static final long NEW_CEO_USER_ID = 20L;

    // Long 캐시(-128~127) 밖의 값이다. 사무소 비교가 equals 에서 == 로 바뀌면
    // 캐시 안의 값으로는 우연히 같은 객체가 되어 통과해 버린다.
    private static final long OFFICE_ID = 7_001L;
    private static final long OTHER_OFFICE_ID = 7_002L;

    private BrokerProfileRepository profileRepository;
    private BrokerageOfficeRepository officeRepository;
    private ListingAccessPolicy policy;

    @BeforeEach
    void setUp() {
        profileRepository = mock(BrokerProfileRepository.class);
        officeRepository = mock(BrokerageOfficeRepository.class);
        policy = new ListingAccessPolicy(profileRepository, officeRepository);
    }

    @Nested
    @DisplayName("requireProfile — 로그인한 중개사의 사무소 연결")
    class RequireProfile {

        @Test
        @DisplayName("프로필이 있으면 그 프로필을 돌려준다")
        void returns_profile() {
            BrokerProfile mine = profile(CEO_USER_ID, OFFICE_ID);
            given(profileRepository.findByUserId(CEO_USER_ID)).willReturn(Optional.of(mine));

            assertThat(policy.requireProfile(CEO_USER_ID)).isSameAs(mine);
        }

        // 승인 흐름이 덜 끝난 계정이 중개사 화면에 들어오면 어느 사무소 매물에도 닿으면 안 된다.
        @Test
        @DisplayName("프로필이 없으면 막는다")
        void rejects_without_profile() {
            given(profileRepository.findByUserId(CEO_USER_ID)).willReturn(Optional.empty());

            assertCode(() -> policy.requireProfile(CEO_USER_ID), "BROKER_PROFILE_NOT_FOUND");
        }

        // 조회 경로는 광고 가능 여부를 묻지 않는다 — 그 판정은 등록 · 게시 · 연장에만 둔다.
        // (부문이 승인 상태가 아니면 토큰에 LISTING_MANAGE 가 없어 매물 화면 자체가 403 이다)
        @Test
        @DisplayName("광고 가능 여부를 보지 않는다 — 조회 경로는 사무소 저장소를 건드리지 않는다")
        void does_not_gate_on_advertisability() {
            given(profileRepository.findByUserId(CEO_USER_ID))
                    .willReturn(Optional.of(profile(CEO_USER_ID, OFFICE_ID)));

            assertThatCode(() -> policy.requireProfile(CEO_USER_ID)).doesNotThrowAnyException();
            verifyNoInteractions(officeRepository);
        }
    }

    @Nested
    @DisplayName("requireAdvertisableOffice — 등록·게시 경로의 관문")
    class RequireAdvertisableOffice {

        @Test
        @DisplayName("대표자의 부문이 승인된 사무소면 그 사무소를 돌려준다 — 프로필이 가리키는 사무소로 묻는다")
        void returns_advertisable_office_of_profile() {
            BrokerageOffice mine = office(OFFICE_ID);
            given(profileRepository.findByUserId(CEO_USER_ID))
                    .willReturn(Optional.of(profile(CEO_USER_ID, OFFICE_ID)));
            given(officeRepository.findById(OFFICE_ID)).willReturn(Optional.of(mine));
            given(officeRepository.isAdvertisable(OFFICE_ID)).willReturn(true);

            assertThat(policy.requireAdvertisableOffice(CEO_USER_ID)).isSameAs(mine);
        }

        // 판정은 사무소 상태가 아니라 **대표자의 공인중개사 부문**이다(쿼리 자체는 ListingPublicQueryTest 가 본다).
        // 이 게이트가 빠지면 심사 중 · 반려 · 정지된 중개사가 새 광고를 등록하고 게시한다.
        @Test
        @DisplayName("대표자의 부문이 승인 상태가 아니면 막는다")
        void rejects_office_that_cannot_advertise() {
            given(profileRepository.findByUserId(CEO_USER_ID))
                    .willReturn(Optional.of(profile(CEO_USER_ID, OFFICE_ID)));
            given(officeRepository.findById(OFFICE_ID)).willReturn(Optional.of(office(OFFICE_ID)));
            given(officeRepository.isAdvertisable(OFFICE_ID)).willReturn(false);

            assertCode(() -> policy.requireAdvertisableOffice(CEO_USER_ID), "OFFICE_NOT_ACTIVE");
        }

        @Test
        @DisplayName("프로필이 없으면 사무소를 찾기 전에 막는다")
        void rejects_without_profile_before_office_lookup() {
            given(profileRepository.findByUserId(CEO_USER_ID)).willReturn(Optional.empty());

            assertCode(() -> policy.requireAdvertisableOffice(CEO_USER_ID), "BROKER_PROFILE_NOT_FOUND");
            verify(officeRepository, never()).findById(any());
        }

        // 프로필이 가리키는 사무소가 없으면 "연결이 덜 끝난 계정"과 같게 다룬다 —
        // OFFICE_NOT_FOUND(404)가 아니라 BROKER_PROFILE_NOT_FOUND(403)를 던지는 현재 선택을 고정한다.
        @Test
        @DisplayName("프로필이 가리키는 사무소가 없으면 프로필 미설정으로 막는다")
        void rejects_when_office_is_missing() {
            given(profileRepository.findByUserId(CEO_USER_ID))
                    .willReturn(Optional.of(profile(CEO_USER_ID, OFFICE_ID)));
            given(officeRepository.findById(OFFICE_ID)).willReturn(Optional.empty());

            assertCode(() -> policy.requireAdvertisableOffice(CEO_USER_ID), "BROKER_PROFILE_NOT_FOUND");
        }
    }

    @Nested
    @DisplayName("requireOwnership — 이 매물이 내 사무소 것인가")
    class RequireOwnership {

        @Test
        @DisplayName("내 사무소에서 내가 올린 매물은 통과한다")
        void own_office_own_listing_passes() {
            assertThatCode(() -> policy.requireOwnership(
                    profile(CEO_USER_ID, OFFICE_ID), listing(OFFICE_ID, CEO_USER_ID)))
                    .doesNotThrowAnyException();
        }

        // ★ 축이 "올린 사람"으로 바뀌면 이 칸이 먼저 죽는다 — 같은 사무소 동료가 서로의 매물을 못 본다.
        @Test
        @DisplayName("같은 사무소의 다른 계정이 올린 매물도 통과한다")
        void same_office_other_account_passes() {
            assertThatCode(() -> policy.requireOwnership(
                    profile(COLLEAGUE_USER_ID, OFFICE_ID),
                    listing(OFFICE_ID, CEO_USER_ID)))
                    .doesNotThrowAnyException();
        }

        // 대표자가 바뀌면 새 계정이 같은 사무소 프로필을 받는다. 옛 대표가 올린 매물이
        // 여기서 막히면 대표 교체 순간 사무소 광고가 통째로 관리 불능이 된다.
        @Test
        @DisplayName("대표자가 바뀐 뒤에도 옛 대표가 올린 사무소 매물은 통과한다")
        void new_ceo_inherits_office_listings() {
            assertThatCode(() -> policy.requireOwnership(
                    profile(NEW_CEO_USER_ID, OFFICE_ID), listing(OFFICE_ID, CEO_USER_ID)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("다른 사무소 매물은 막는다")
        void other_office_is_denied() {
            assertCode(() -> policy.requireOwnership(
                    profile(CEO_USER_ID, OFFICE_ID), listing(OTHER_OFFICE_ID, COLLEAGUE_USER_ID)),
                    "LISTING_ACCESS_DENIED");
        }

        // ★ 내가 올렸어도 지금 다른 사무소 것이면 막는다(사무소를 옮긴 계정).
        // "사무소가 같거나 올린 사람이 같으면 통과"처럼 축을 섞는 순간 이 칸이 뚫린다.
        @Test
        @DisplayName("내가 올렸어도 다른 사무소 매물이면 막는다")
        void own_upload_in_other_office_is_denied() {
            assertCode(() -> policy.requireOwnership(
                    profile(CEO_USER_ID, OFFICE_ID), listing(OTHER_OFFICE_ID, CEO_USER_ID)),
                    "LISTING_ACCESS_DENIED");
        }
    }

    // Long.valueOf 를 매번 새로 거치게 해 같은 값이라도 다른 객체가 되게 한다.
    private static BrokerProfile profile(long userId, long officeId) {
        return BrokerProfile.builder()
                .userId(Long.valueOf(userId)).officeId(Long.valueOf(officeId))
                .build();
    }

    private static BrokerageOffice office(long id) {
        return BrokerageOffice.builder()
                .id(Long.valueOf(id)).uuid("o-" + id).regNo("99999-2026-00001")
                .name("측정공인중개사사무소").ceoName("홍길동")
                .address("서울특별시 관악구").tel("02-000-0000")
                .build();
    }

    private static Listing listing(long officeId, long createdByUserId) {
        return Listing.builder()
                .id(1L).uuid("l-1").listingNo("LS-261006-ABC123")
                .officeId(Long.valueOf(officeId)).createdByUserId(Long.valueOf(createdByUserId))
                .build();
    }

    private static void assertCode(ThrowingCallable call, String expected) {
        assertThatThrownBy(call)
                .isInstanceOf(ListingException.class)
                .satisfies(e -> assertThat(((ListingException) e).getErrorCode().getCode())
                        .isEqualTo(expected));
    }
}
