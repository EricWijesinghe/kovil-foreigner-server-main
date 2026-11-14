package com.kovil.domain.partner.service;

import com.kovil.domain.listing.entity.BrokerProfile;
import com.kovil.domain.listing.entity.BrokerageOffice;
import com.kovil.domain.listing.repository.BrokerProfileRepository;
import com.kovil.domain.listing.repository.BrokerageOfficeRepository;
import com.kovil.domain.partner.dto.response.AdminPartnerDivisionDetailResponse.SameRegNoApplication;
import com.kovil.domain.partner.entity.BrokerageOfficeChangeRequest;
import com.kovil.domain.partner.entity.PartnerDivision;
import com.kovil.domain.partner.enums.OfficeChangeRequestStatus;
import com.kovil.domain.partner.enums.PartnerDivisionStatus;
import com.kovil.domain.partner.enums.PartnerDivisionType;
import com.kovil.domain.partner.enums.SameRegNoApplicationKind;
import com.kovil.domain.partner.repository.BrokerageOfficeChangeRequestRepository;
import com.kovil.domain.partner.repository.PartnerDivisionRepository;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.assertj.core.groups.Tuple;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

/*
 * "같은 등록번호의 다른 신청"의 경계. 부문 심사 · 변경 심사 상세 테스트가 줄의 모양과 순서를 고정하고, 여기는 무엇을 싣지 않는지와
 * 쿼리를 아끼는 길을 고정한다.
 */
@ExtendWith(MockitoExtension.class)
class SameRegNoApplicationFinderTest {

    private static final String REG_NO = "99999-2026-00002";
    private static final long OFFICE_ID = 70L;
    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 7, 9, 0);

    @Mock BrokerageOfficeRepository officeRepository;
    @Mock BrokerProfileRepository profileRepository;
    @Mock PartnerDivisionRepository divisionRepository;
    @Mock BrokerageOfficeChangeRequestRepository changeRequestRepository;
    @Mock UserRepository userRepository;

    private SameRegNoApplicationFinder finder;

    @BeforeEach
    void setUp() {
        finder = new SameRegNoApplicationFinder(officeRepository, profileRepository, divisionRepository,
                changeRequestRepository, userRepository);
    }

    private static BrokerageOfficeChangeRequest change(String uuid, long officeId, long requestedBy) {
        return BrokerageOfficeChangeRequest.builder().uuid(uuid).officeId(officeId).requestedBy(requestedBy)
                .regNo(REG_NO).name("바꿀상호-" + uuid).ceoName("박대표").address("서울특별시 강남구")
                .licenseFileId(66L).status(OfficeChangeRequestStatus.PENDING).submittedAt(AT).build();
    }

    private static BrokerageOffice office(long id, String regNo, String claimedRegNo) {
        return BrokerageOffice.builder().id(id).uuid("o-" + id).regNo(regNo).claimedRegNo(claimedRegNo)
                .name("상호-" + id).ceoName("대표-" + id).address("서울특별시 관악구").tel("02-000-0000").build();
    }

    // 같은 번호의 다른 신청이 없으면 연결 · 부문 · 사람을 읽지 않는다 — 대부분의 심사 상세가 이 길이다.
    @Test
    @DisplayName("다른 신청이 없으면 빈 목록이고 연결 · 부문 · 사람을 읽지 않는다")
    void nothing_found_reads_nothing_more() {
        assertThat(finder.find(REG_NO, OFFICE_ID)).isEmpty();

        then(officeRepository).should().findOthersWithRegNo(REG_NO, OFFICE_ID);
        then(officeRepository).shouldHaveNoMoreInteractions();
        then(profileRepository).shouldHaveNoInteractions();
        then(divisionRepository).shouldHaveNoInteractions();
        then(userRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("번호가 없으면 아무것도 묻지 않는다")
    void null_number_asks_nothing() {
        assertThat(finder.find(null, OFFICE_ID)).isEmpty();

        then(officeRepository).shouldHaveNoInteractions();
        then(changeRequestRepository).shouldHaveNoInteractions();
    }

    // 심사 중인 사무소 자신의 변경 신청은 "다른" 신청이 아니다 — 변경 심사 상세는 바로 그 신청을 보고 있다.
    @Test
    @DisplayName("심사 중인 사무소 자신의 변경 신청은 싣지 않는다")
    void own_office_change_is_excluded() {
        given(changeRequestRepository.findByRegNoAndStatusOrderBySubmittedAtAscIdAsc(REG_NO,
                OfficeChangeRequestStatus.PENDING)).willReturn(List.of(change("cr-own", OFFICE_ID, 7L)));

        assertThat(finder.find(REG_NO, OFFICE_ID)).isEmpty();
        then(officeRepository).should(Mockito.never()).findAllById(any());
        then(divisionRepository).shouldHaveNoInteractions();
    }

    /*
     * ★ 번호를 옮기지 않는 변경 신청(이미 이 번호를 잡고 쓰는 사무소의 상호 · 소재지 변경)은 경쟁자가 아니다 — 그 사무소는 부문 신청
     *   줄(잡은 번호)로 이미 나온다. 그것만 있으면 부문 · 사람도 읽지 않는다.
     */
    @Test
    @DisplayName("이미 이 번호를 쓰는 사무소의 변경 신청(번호를 옮기지 않는 것)은 싣지 않는다")
    void change_that_keeps_the_number_is_excluded() {
        given(changeRequestRepository.findByRegNoAndStatusOrderBySubmittedAtAscIdAsc(REG_NO,
                OfficeChangeRequestStatus.PENDING)).willReturn(List.of(change("cr-72", 72L, 9L)));
        given(officeRepository.findAllById(List.of(72L))).willReturn(List.of(office(72L, REG_NO, REG_NO)));

        assertThat(finder.find(REG_NO, OFFICE_ID)).isEmpty();
        then(divisionRepository).shouldHaveNoInteractions();
        then(userRepository).shouldHaveNoInteractions();
    }

    /*
     * 변경 신청 줄은 신청자의 부문이 없어도 싣는다 — 줄의 근거는 변경 신청 자체다(부문 신청 줄은 부문이 없으면 신청이 아니라 뺀다).
     * 부문 칸만 비운다.
     */
    @Test
    @DisplayName("신청자의 부문을 못 찾아도 변경 신청 줄은 싣고 부문 칸만 비운다")
    void change_row_survives_missing_division() {
        given(changeRequestRepository.findByRegNoAndStatusOrderBySubmittedAtAscIdAsc(REG_NO,
                OfficeChangeRequestStatus.PENDING)).willReturn(List.of(change("cr-80", 80L, 12L)));
        given(officeRepository.findAllById(List.of(80L)))
                .willReturn(List.of(office(80L, "99999-2026-00008", "99999-2026-00008")));
        given(userRepository.findAllById(Set.of(12L))).willReturn(List.of(
                User.builder().id(12L).uuid("u-12").status(UserStatus.SUSPENDED).role(UserRole.PARTNER).build()));

        List<SameRegNoApplication> rows = finder.find(REG_NO, OFFICE_ID);

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.kind()).isEqualTo(SameRegNoApplicationKind.OFFICE_CHANGE_REQUEST);
            assertThat(row.changeRequestUuid()).isEqualTo("cr-80");
            assertThat(row.officeUuid()).isEqualTo("o-80");
            assertThat(row.divisionUuid()).isNull();
            assertThat(row.status()).isNull();
            assertThat(row.accountStatus()).isEqualTo(UserStatus.SUSPENDED);
            assertThat(row.claimed()).isFalse();
        });
        then(profileRepository).shouldHaveNoInteractions();
    }

    /*
     * 부문 신청 줄은 대표자 연결 · 공인중개사 부문이 있어야 신청이다 — 떠난 사무소(연결 없음)와 부문 없는 연결은 뺀다.
     * 변경 신청이 함께 있으면 부문 · 사람은 두 쪽 사람을 모아 한 번에 읽는다.
     */
    @Test
    @DisplayName("떠난 사무소의 부문 신청 줄은 빼고, 부문 · 사람은 두 종류의 신청자를 모아 한 번에 읽는다")
    void orphan_office_is_dropped_and_lookups_are_batched() {
        given(officeRepository.findOthersWithRegNo(REG_NO, OFFICE_ID))
                .willReturn(List.of(office(71L, REG_NO, null), office(73L, REG_NO, null)));
        given(changeRequestRepository.findByRegNoAndStatusOrderBySubmittedAtAscIdAsc(REG_NO,
                OfficeChangeRequestStatus.PENDING)).willReturn(List.of(change("cr-80", 80L, 12L)));
        given(officeRepository.findAllById(List.of(80L)))
                .willReturn(List.of(office(80L, "99999-2026-00008", "99999-2026-00008")));
        given(profileRepository.findByOfficeIdIn(List.of(71L, 73L)))
                .willReturn(List.of(BrokerProfile.builder().userId(8L).officeId(71L).build()));
        given(divisionRepository.findByUserIdInAndDivision(Set.of(8L, 12L), PartnerDivisionType.REAL_ESTATE_BROKER))
                .willReturn(List.of(
                        PartnerDivision.builder().uuid("pd-8").userId(8L).division(PartnerDivisionType.REAL_ESTATE_BROKER)
                                .status(PartnerDivisionStatus.REJECTED).submittedAt(AT.minusDays(1)).build(),
                        PartnerDivision.builder().uuid("pd-12").userId(12L).division(PartnerDivisionType.REAL_ESTATE_BROKER)
                                .status(PartnerDivisionStatus.APPROVED).submittedAt(AT.minusDays(9)).build()));
        given(userRepository.findAllById(Set.of(8L, 12L))).willReturn(List.of());

        List<SameRegNoApplication> rows = finder.find(REG_NO, OFFICE_ID);

        assertThat(rows).extracting(SameRegNoApplication::kind, SameRegNoApplication::officeUuid,
                        SameRegNoApplication::status)
                .containsExactly(
                        Tuple.tuple(SameRegNoApplicationKind.DIVISION_APPLICATION, "o-71",
                                PartnerDivisionStatus.REJECTED),
                        Tuple.tuple(SameRegNoApplicationKind.OFFICE_CHANGE_REQUEST, "o-80",
                                PartnerDivisionStatus.APPROVED));
        then(divisionRepository).should().findByUserIdInAndDivision(Set.of(8L, 12L),
                PartnerDivisionType.REAL_ESTATE_BROKER);
        then(divisionRepository).shouldHaveNoMoreInteractions();
        then(userRepository).should().findAllById(Set.of(8L, 12L));
        then(userRepository).shouldHaveNoMoreInteractions();
    }
}
