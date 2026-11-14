package com.kovil.domain.listing.service;

import com.kovil.domain.listing.entity.BrokerProfile;
import com.kovil.domain.listing.entity.Listing;
import com.kovil.domain.listing.enums.*;
import com.kovil.domain.listing.repository.BrokerProfileRepository;
import com.kovil.domain.listing.repository.BrokerageOfficeRepository;
import com.kovil.domain.listing.repository.ListingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/*
 * 중개사가 사무소를 떠날 때 — PO 확정 2026-10-06:
 * **사무소에 남은 중개사가 없을 때만 그 사무소의 공개 매물을 내린다.**
 * 매물의 법적 표시 주체는 사무소라, 동료가 남아 있으면 그 사람이 이어서 관리한다.
 */
class BrokerOffboardingServiceTest {

    private BrokerProfileRepository profileRepository;
    private BrokerageOfficeRepository officeRepository;
    private ListingRepository listingRepository;
    private BrokerOffboardingService service;

    @BeforeEach
    void setUp() {
        profileRepository = mock(BrokerProfileRepository.class);
        officeRepository = mock(BrokerageOfficeRepository.class);
        listingRepository = mock(ListingRepository.class);
        service = new BrokerOffboardingService(profileRepository, officeRepository, listingRepository);
    }

    // 일반 회원 탈퇴 경로에서도 부른다 — 중개사가 아니면 아무것도 건드리지 않아야 한다.
    @Test
    @DisplayName("중개사가 아니면 아무 일도 하지 않는다")
    void non_broker_is_a_no_op() {
        given(profileRepository.findByUserId(10L)).willReturn(Optional.empty());

        service.offboard(10L);

        verify(profileRepository, never()).delete(any());
        verify(listingRepository, never()).findAllByOfficeIdAndStatusIn(anyLong(), any());
    }

    @Test
    @DisplayName("마지막 중개사가 떠나면 사무소의 공개 매물을 내린다(HIDDEN)")
    void last_broker_leaving_hides_public_listings() {
        BrokerProfile profile = profile(10L, 1L);
        given(profileRepository.findByUserId(10L)).willReturn(Optional.of(profile));
        given(profileRepository.lockIdsByOfficeId(1L)).willReturn(List.of());
        Listing active = listing(ListingStatus.ACTIVE);
        Listing reserved = listing(ListingStatus.RESERVED);
        given(listingRepository.findAllByOfficeIdAndStatusIn(eq(1L), any()))
                .willReturn(List.of(active, reserved));

        service.offboard(10L);

        verify(profileRepository).delete(profile);
        // ★ HIDDEN 이다(ARCHIVED 가 아니다) — 사무소에 중개사가 다시 붙으면 되살릴 수 있어야 한다.
        assertThat(active.getStatus()).isEqualTo(ListingStatus.HIDDEN);
        assertThat(reserved.getStatus()).isEqualTo(ListingStatus.HIDDEN);
    }

    // 동료가 남아 있으면 그 사람이 관리한다 — 사무소 입장에선 광고가 갑자기 사라지면 안 된다.
    @Test
    @DisplayName("동료가 남아 있으면 매물을 내리지 않는다")
    void colleague_remaining_keeps_listings() {
        given(profileRepository.findByUserId(10L)).willReturn(Optional.of(profile(10L, 1L)));
        given(profileRepository.lockIdsByOfficeId(1L)).willReturn(List.of(20L));

        service.offboard(10L);

        verify(listingRepository, never()).findAllByOfficeIdAndStatusIn(anyLong(), any());
    }

    /*
     * ★★ **지운 뒤에** 센다. 먼저 세면 떠나는 사람이 포함돼 "남은 사람 1명"으로 읽히고,
     *    마지막 사람이 떠나도 매물이 내려가지 않는다.
     */
    @Test
    @DisplayName("프로필을 지우고 flush 한 뒤에야 남은 사람을 센다")
    void counts_remaining_brokers_after_deleting() {
        BrokerProfile profile = profile(10L, 1L);
        given(profileRepository.findByUserId(10L)).willReturn(Optional.of(profile));
        given(profileRepository.lockIdsByOfficeId(1L)).willReturn(List.of());
        given(listingRepository.findAllByOfficeIdAndStatusIn(eq(1L), any())).willReturn(List.of());

        service.offboard(10L);

        InOrder order = inOrder(profileRepository);
        order.verify(profileRepository).delete(profile);
        order.verify(profileRepository).flush();
        order.verify(profileRepository).lockIdsByOfficeId(1L);
    }

    /*
     * ★★ 같은 사무소의 두 사람이 **동시에** 떠나면, 잠금 없이는 둘 다 상대를 "남은 사람"으로 보고 아무도 매물을
     *    내리지 않는다 — 프로필은 0행인데 광고는 공개된 채 남는다(적대적 리뷰 2026-10-06).
     *    지우기 전에 사무소를 잠가 줄 세우고, 세기는 **잠금 읽기**로 한다(REPEATABLE READ 의 일반 SELECT 는
     *    트랜잭션 첫 읽기 시점 스냅숏이라 앞사람의 커밋이 안 보인다).
     */
    @Test
    @DisplayName("지우기 전에 사무소를 잠그고, 남은 사람은 잠금 읽기로 센다")
    void locks_the_office_before_deleting_and_counts_with_a_locking_read() {
        BrokerProfile profile = profile(10L, 1L);
        given(profileRepository.findByUserId(10L)).willReturn(Optional.of(profile));
        given(profileRepository.lockIdsByOfficeId(1L)).willReturn(List.of());
        given(listingRepository.findAllByOfficeIdAndStatusIn(eq(1L), any())).willReturn(List.of());

        service.offboard(10L);

        InOrder order = inOrder(officeRepository, profileRepository);
        order.verify(officeRepository).lockById(1L);
        order.verify(profileRepository).delete(profile);
        order.verify(profileRepository).lockIdsByOfficeId(1L);
        verify(profileRepository, never()).findByOfficeId(any());
    }

    // 초안·만료·종료는 이미 광고가 아니다 — 공개 중인 것만 대상이다.
    @Test
    @DisplayName("공개 중인 매물(ACTIVE·RESERVED)만 대상으로 조회한다")
    void only_public_listings_are_targeted() {
        given(profileRepository.findByUserId(10L)).willReturn(Optional.of(profile(10L, 1L)));
        given(profileRepository.lockIdsByOfficeId(1L)).willReturn(List.of());
        given(listingRepository.findAllByOfficeIdAndStatusIn(eq(1L), any())).willReturn(List.of());

        service.offboard(10L);

        verify(listingRepository).findAllByOfficeIdAndStatusIn(1L,
                List.of(ListingStatus.ACTIVE, ListingStatus.RESERVED));
    }

    /*
     * ★ 위 테스트는 목이라 **잠금 순서**만 본다. 잠금 자체는 리포지토리 메서드의 @Lock 이 건다 — 그걸 지우면 이름만
     *   'lock' 인 일반 SELECT 가 되어 동시 이탈 경쟁이 되돌아오는데 위 테스트는 초록이다. 그래서 애너테이션을 박는다.
     */
    @Test
    @DisplayName("사무소 잠금과 남은 인원 세기는 PESSIMISTIC_WRITE 잠금 읽기다")
    void both_lock_queries_are_pessimistic_writes() throws NoSuchMethodException {
        var officeLock = BrokerageOfficeRepository.class
                .getMethod("lockById", Long.class)
                .getAnnotation(Lock.class);
        var countLock = BrokerProfileRepository.class.getMethod("lockIdsByOfficeId", Long.class)
                .getAnnotation(Lock.class);

        assertThat(officeLock).isNotNull();
        assertThat(officeLock.value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
        assertThat(countLock).isNotNull();
        assertThat(countLock.value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
    }

    private BrokerProfile profile(Long userId, Long officeId) {
        return BrokerProfile.builder().userId(userId).officeId(officeId).build();
    }

    private Listing listing(ListingStatus status) {
        return Listing.builder()
                .id(1L).uuid("l-1").listingNo("LS-261006-ABC123")
                .officeId(1L).createdByUserId(10L)
                .category(ListingCategory.ONE_TWO_ROOM).roomType(RoomType.ONE_ROOM)
                .title("테스트").sellingType(SellingType.MONTHLY_RENT)
                .deposit(3_000_000L).monthlyRent(300_000L)
                .exclusiveArea(new BigDecimal("26.44")).floorType(FloorType.GROUND)
                .residentRegistration(AvailabilityAnswer.POSSIBLE)
                .depositInsurance(AvailabilityAnswer.ASK)
                .realLat(new BigDecimal("37.4862378")).realLng(new BigDecimal("126.9331220"))
                .displayLat(new BigDecimal("37.4862552")).displayLng(new BigDecimal("126.9332423"))
                .addressJibun("서울특별시 관악구 신림동").status(status).build();
    }
}
