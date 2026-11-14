package com.kovil.domain.partner.service;

import com.kovil.domain.file.service.FileDeletionService;
import com.kovil.domain.listing.entity.BrokerProfile;
import com.kovil.domain.listing.entity.BrokerageOffice;
import com.kovil.domain.listing.repository.BrokerProfileRepository;
import com.kovil.domain.listing.repository.BrokerageOfficeRepository;
import com.kovil.domain.partner.entity.BrokerageOfficeChangeRequest;
import com.kovil.domain.partner.entity.PartnerDivision;
import com.kovil.domain.partner.enums.OfficeChangeRequestStatus;
import com.kovil.domain.partner.enums.PartnerDivisionStatus;
import com.kovil.domain.partner.enums.PartnerDivisionType;
import com.kovil.domain.partner.exception.PartnerErrorCode;
import com.kovil.domain.partner.exception.PartnerException;
import com.kovil.domain.partner.repository.BrokerageOfficeChangeRequestRepository;
import com.kovil.domain.partner.repository.PartnerDivisionRepository;
import com.kovil.domain.rentalrequest.service.RentalRequestPoolReturner;
import com.kovil.domain.rentalrequest.enums.RentalRequestReturnCause;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.InOrder;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

/*
 * 파트너 탈퇴 정리. 하나라도 빠지면:
 *   · 대기 부문을 안 닫으면 — 대기열에 승인할 수 없는 익명 행이 남는다.
 *   · 번호를 안 놓으면 — 같은 번호로 새 대표자가 영영 가입 · 승인받지 못한다.
 *   · 등록증을 안 버리면 — 관계가 끝난 사람의 서류가 남는다.
 *   · 대기 중인 변경 신청을 안 닫으면 — 변경 심사 대기열에 처리할 수 없는 익명 행이 남고 그 등록증도 남는다.
 */
@ExtendWith(MockitoExtension.class)
class PartnerWithdrawalServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-06T01:00:00Z");
    private static final LocalDateTime NOW_LOCAL = LocalDateTime.ofInstant(NOW, ZoneId.of("Asia/Seoul"));
    private static final long PARTNER_ID = 7L;
    private static final long OFFICE_ID = 70L;
    private static final String REG_NO = "99999-2026-00002";

    @Mock PartnerDivisionRepository divisionRepository;
    @Mock BrokerProfileRepository profileRepository;
    @Mock BrokerageOfficeRepository officeRepository;
    @Mock FileDeletionService fileDeletionService;
    @Mock BrokerageOfficeChangeRequestRepository changeRequestRepository;
    @Mock RentalRequestPoolReturner poolReturner;

    private PartnerWithdrawalService service;

    @BeforeEach
    void setUp() {
        service = new PartnerWithdrawalService(divisionRepository, profileRepository, officeRepository,
                changeRequestRepository, fileDeletionService, poolReturner, Clock.fixed(NOW, ZoneId.of("Asia/Seoul")));
    }

    private static PartnerDivision division(PartnerDivisionStatus status, LocalDateTime submittedAt) {
        return PartnerDivision.builder().uuid("pd-1").userId(PARTNER_ID)
                .division(PartnerDivisionType.REAL_ESTATE_BROKER).status(status).submittedAt(submittedAt).build();
    }

    private BrokerageOffice givenOffice(String claimedRegNo, Long licenseFileId) {
        BrokerageOffice office = BrokerageOffice.builder().id(OFFICE_ID).uuid("o-1").regNo(REG_NO)
                .claimedRegNo(claimedRegNo).name("테스트공인중개사사무소").ceoName("홍길동")
                .address("서울특별시 관악구").tel("02-000-0000").licenseFileId(licenseFileId).build();
        given(profileRepository.findByUserId(PARTNER_ID)).willReturn(Optional.of(
                BrokerProfile.builder().userId(PARTNER_ID).officeId(OFFICE_ID).build()));
        given(officeRepository.findById(OFFICE_ID)).willReturn(Optional.of(office));
        return office;
    }

    // 제출 전(가입만 한 것)도 닫는다 — 다시 로그인해 제출할 사람이 없다.
    @Test
    @DisplayName("대기 중인 부문은 제출 여부와 관계없이 반려로 닫고 심사자는 비운다")
    void closes_pending_divisions() {
        PartnerDivision submitted = division(PartnerDivisionStatus.PENDING, NOW_LOCAL.minusDays(1));
        given(divisionRepository.findByUserIdOrderByIdAsc(PARTNER_ID)).willReturn(List.of(submitted));
        given(profileRepository.findByUserId(PARTNER_ID)).willReturn(Optional.empty());

        service.purgeOnWithdrawal(PARTNER_ID);

        assertThat(submitted.getStatus()).isEqualTo(PartnerDivisionStatus.REJECTED);
        assertThat(submitted.getRejectReason()).isEqualTo("회원이 탈퇴했습니다.");
        assertThat(submitted.getReviewedBy()).isNull();
        assertThat(submitted.getReviewedAt()).isEqualTo(NOW_LOCAL);

        PartnerDivision draft = division(PartnerDivisionStatus.PENDING, null);
        given(divisionRepository.findByUserIdOrderByIdAsc(PARTNER_ID)).willReturn(List.of(draft));

        service.purgeOnWithdrawal(PARTNER_ID);

        assertThat(draft.getStatus()).isEqualTo(PartnerDivisionStatus.REJECTED);
    }

    /*
     * ★ 승인 · 정지된 사무소도 대표자가 떠나면 번호를 놓는다 — 번호는 "대표자가 남아 있는 동안만" 잡힌다
     *   (PO 확정 2026-10-06). 부문 판정(승인 · 정지)은 이력이라 그대로 둔다.
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(value = PartnerDivisionStatus.class, names = {"APPROVED", "SUSPENDED", "REJECTED"})
    @DisplayName("판정이 난 부문은 그대로 두고, 사무소가 잡은 번호는 놓고, 등록증은 폐기 표시한다")
    void releases_claim_and_discards_license(PartnerDivisionStatus status) {
        PartnerDivision decided = division(status, NOW_LOCAL.minusDays(10));
        given(divisionRepository.findByUserIdOrderByIdAsc(PARTNER_ID)).willReturn(List.of(decided));
        BrokerageOffice office = givenOffice(status == PartnerDivisionStatus.REJECTED ? null : REG_NO, 55L);

        service.purgeOnWithdrawal(PARTNER_ID);

        assertThat(decided.getStatus()).isEqualTo(status);
        assertThat(office.getClaimedRegNo()).isNull();
        // 사무소 행 · 등록증 칸은 남는다 — 정리 배치는 파일 행을 지우지 않아 FK 와 부딪히지 않는다.
        assertThat(office.getLicenseFileId()).isEqualTo(55L);
        then(fileDeletionService).should().discardByIds(List.of(55L));
    }

    private static BrokerageOfficeChangeRequest pendingChange(long licenseFileId) {
        return BrokerageOfficeChangeRequest.builder().uuid("cr-1").officeId(OFFICE_ID).requestedBy(PARTNER_ID)
                .regNo("99999-2026-00009").name("새상호공인중개사사무소").ceoName("홍길동").address("서울특별시 관악구")
                .licenseFileId(licenseFileId).status(OfficeChangeRequestStatus.PENDING)
                .submittedAt(NOW_LOCAL.minusDays(1)).build();
    }

    /*
     * ★ 대기 중인 변경 신청은 반려로 닫고(심사자 없음) 그 등록증도 사무소 등록증과 함께 폐기 표시한다.
     *   사무소 행을 잠근 뒤에 대기 건을 잠금 읽기로 찾는다 — 같은 순간의 변경 신청 제출과 줄을 세운다.
     */
    @Test
    @DisplayName("대기 중인 변경 신청을 반려로 닫고 그 등록증을 사무소 등록증과 함께 폐기한다")
    void closes_pending_office_change_and_discards_its_license() {
        given(divisionRepository.findByUserIdOrderByIdAsc(PARTNER_ID))
                .willReturn(List.of(division(PartnerDivisionStatus.APPROVED, NOW_LOCAL.minusDays(10))));
        BrokerageOffice office = givenOffice(REG_NO, 55L);
        BrokerageOfficeChangeRequest change = pendingChange(66L);
        given(changeRequestRepository.findPendingForUpdate(OFFICE_ID)).willReturn(Optional.of(change));

        service.purgeOnWithdrawal(PARTNER_ID);

        assertThat(change.getStatus()).isEqualTo(OfficeChangeRequestStatus.REJECTED);
        assertThat(change.getRejectReason()).isEqualTo("회원이 탈퇴했습니다.");
        assertThat(change.getReviewedBy()).isNull();
        assertThat(change.getReviewedAt()).isEqualTo(NOW_LOCAL);
        then(fileDeletionService).should().discardByIds(List.of(55L, 66L));
        assertThat(office.getClaimedRegNo()).isNull();

        InOrder order = Mockito.inOrder(officeRepository, changeRequestRepository);
        order.verify(officeRepository).lockById(OFFICE_ID);
        order.verify(changeRequestRepository).findPendingForUpdate(OFFICE_ID);
    }

    /*
     * ★ 대표자가 떠나면 그 사무소가 맡은 진행 중 임대 요청은 대기로 돌아간다(PO 확정 2026-10-06 — 맡을 사람이 사라졌다).
     *   사무소 행을 잠근 **뒤에** 한다 — 같은 사무소의 잡기가 그 행에서 줄을 서므로 그사이의 잡기를 놓치지 않는다.
     *   사무소는 대표자 연결로 찾으므로 이 정리는 이탈(연결 삭제)보다 먼저다(UserService.withdraw 가 순서를 지킨다).
     */
    @Test
    @DisplayName("사무소가 맡은 진행 중 임대 요청을 사무소 행을 잠근 뒤에 대기로 되돌린다")
    void returns_held_rental_requests_after_locking_office() {
        given(divisionRepository.findByUserIdOrderByIdAsc(PARTNER_ID))
                .willReturn(List.of(division(PartnerDivisionStatus.APPROVED, NOW_LOCAL.minusDays(10))));
        givenOffice(REG_NO, 55L);

        service.purgeOnWithdrawal(PARTNER_ID);

        InOrder order = Mockito.inOrder(officeRepository, poolReturner);
        order.verify(officeRepository).lockById(OFFICE_ID);
        order.verify(poolReturner).returnHeldBy(OFFICE_ID,
                RentalRequestReturnCause.REPRESENTATIVE_WITHDRAWN,
                NOW_LOCAL);
    }

    @Test
    @DisplayName("사무소가 없는(파트너가 아닌) 탈퇴는 임대 요청을 건드리지 않는다")
    void non_partner_returns_nothing() {
        given(divisionRepository.findByUserIdOrderByIdAsc(PARTNER_ID)).willReturn(List.of());
        given(profileRepository.findByUserId(PARTNER_ID)).willReturn(Optional.empty());

        service.purgeOnWithdrawal(PARTNER_ID);

        then(poolReturner).shouldHaveNoInteractions();
    }

    // 지금 등록증을 그대로 낸 변경 신청이면 같은 파일이다 — 한 번만 버린다.
    @Test
    @DisplayName("변경 신청이 사무소와 같은 등록증이면 한 번만 폐기한다")
    void same_license_is_discarded_once() {
        given(divisionRepository.findByUserIdOrderByIdAsc(PARTNER_ID))
                .willReturn(List.of(division(PartnerDivisionStatus.APPROVED, NOW_LOCAL.minusDays(10))));
        givenOffice(REG_NO, 55L);
        given(changeRequestRepository.findPendingForUpdate(OFFICE_ID)).willReturn(Optional.of(pendingChange(55L)));

        service.purgeOnWithdrawal(PARTNER_ID);

        then(fileDeletionService).should().discardByIds(List.of(55L));
    }

    @Test
    @DisplayName("등록증을 내기 전이면 버릴 파일이 없다")
    void no_license_nothing_to_discard() {
        given(divisionRepository.findByUserIdOrderByIdAsc(PARTNER_ID))
                .willReturn(List.of(division(PartnerDivisionStatus.PENDING, null)));
        givenOffice(null, null);

        service.purgeOnWithdrawal(PARTNER_ID);

        then(fileDeletionService).shouldHaveNoInteractions();
    }

    // 일반 회원 탈퇴 경로에서도 부른다 — 부문도 사무소도 없으면 아무 일도 하지 않는다.
    @Test
    @DisplayName("파트너가 아니면 아무것도 하지 않는다")
    void non_partner_is_a_no_op() {
        given(divisionRepository.findByUserIdOrderByIdAsc(PARTNER_ID)).willReturn(List.of());
        given(profileRepository.findByUserId(PARTNER_ID)).willReturn(Optional.empty());

        service.purgeOnWithdrawal(PARTNER_ID);

        then(officeRepository).shouldHaveNoInteractions();
        then(fileDeletionService).shouldHaveNoInteractions();
    }

    // 정지가 탈퇴로 씻기지 않게 — 탈퇴는 등록번호를 놓으므로 정지된 동안은 탈퇴 자체를 막는다.
    @Test
    @DisplayName("부문이 정지돼 있으면 탈퇴를 거절한다")
    void suspended_division_blocks_withdrawal() {
        given(divisionRepository.findStatusesForUpdate(PARTNER_ID)).willReturn(List.of("APPROVED", "SUSPENDED"));

        assertThatThrownBy(() -> service.assertWithdrawable(PARTNER_ID))
                .isInstanceOf(PartnerException.class)
                .satisfies(e -> assertThat(((PartnerException) e).getErrorCode())
                        .isEqualTo(PartnerErrorCode.PARTNER_SUSPENDED_CANNOT_WITHDRAW));
    }

    @ParameterizedTest
    @EnumSource(value = PartnerDivisionStatus.class, names = "SUSPENDED", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("정지가 아니면 탈퇴할 수 있다")
    void other_statuses_may_withdraw(PartnerDivisionStatus status) {
        given(divisionRepository.findStatusesForUpdate(PARTNER_ID)).willReturn(List.of(status.name()));

        assertThatCode(() -> service.assertWithdrawable(PARTNER_ID)).doesNotThrowAnyException();
    }

    // 일반 읽기는 users 잠금 앞 첫 읽기의 스냅숏이라, 그사이 커밋된 정지를 못 본다 — 잠금 읽기만 쓴다.
    @Test
    @DisplayName("정지 여부는 잠금 읽기로만 본다")
    void withdrawable_check_reads_current_commit() {
        given(divisionRepository.findStatusesForUpdate(PARTNER_ID)).willReturn(List.of("APPROVED"));

        service.assertWithdrawable(PARTNER_ID);

        then(divisionRepository).should().findStatusesForUpdate(PARTNER_ID);
        then(divisionRepository).should(Mockito.never()).findByUserIdOrderByIdAsc(PARTNER_ID);
    }

    @Test
    @DisplayName("파트너가 아니면 탈퇴를 막지 않는다")
    void non_partner_may_withdraw() {
        given(divisionRepository.findStatusesForUpdate(PARTNER_ID)).willReturn(List.of());

        assertThatCode(() -> service.assertWithdrawable(PARTNER_ID)).doesNotThrowAnyException();
    }
}
