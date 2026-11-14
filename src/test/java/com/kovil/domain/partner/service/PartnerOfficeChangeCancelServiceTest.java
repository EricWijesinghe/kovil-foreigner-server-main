package com.kovil.domain.partner.service;

import com.kovil.domain.auth.service.LocalAuthService;
import com.kovil.domain.auth.service.support.SignUpProcessor;
import com.kovil.domain.auth.service.support.TokenIssuer;
import com.kovil.domain.file.repository.StoredFileRepository;
import com.kovil.domain.file.service.FileDeletionService;
import com.kovil.domain.listing.entity.BrokerProfile;
import com.kovil.domain.listing.entity.BrokerageOffice;
import com.kovil.domain.listing.repository.BrokerProfileRepository;
import com.kovil.domain.listing.repository.BrokerageOfficeRepository;
import com.kovil.domain.partner.entity.BrokerageOfficeChangeRequest;
import com.kovil.domain.partner.enums.OfficeChangeRequestStatus;
import com.kovil.domain.partner.exception.PartnerException;
import com.kovil.domain.partner.repository.BrokerageOfficeChangeRequestRepository;
import com.kovil.domain.partner.repository.PartnerDivisionRepository;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.user.exception.UserException;
import com.kovil.domain.user.repository.UserRepository;
import com.kovil.domain.user.service.UserQueryService;
import com.kovil.infra.crypto.Encryptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/*
 * 변경 신청 취소의 분기 격자.
 * ★ 잠금 순서는 제출과 같아야 한다 — users → 사무소 → (대표자 연결) → 변경 신청. 관리자 승인 · 반려도 users 를 먼저 잡으므로
 *   여기가 어긋나면 같은 순간의 처리와 교착한다. 대기 건은 사무소를 잠근 **뒤에** 잠금 읽기로 찾아야 앞사람의 처리를 본다.
 * ★ 등록증은 반려와 같은 규칙 — 신청에만 붙은 새 파일은 버리고, 사무소가 지금 쓰는 파일이면 둔다.
 * ★ 부문 상태는 묻지 않는다(닫는 방향) — 정지 중에도 거둬들일 수 있다.
 */
class PartnerOfficeChangeCancelServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-13T01:00:00Z");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final long PARTNER_ID = 7L;
    private static final long OFFICE_ID = 7_001L;
    // ★ Long 캐시(-128 ~ 127) 밖의 값이다 — 등록증 id 를 == 로 비교하는 실수는 작은 값에서는 우연히 통과한다.
    private static final long OFFICE_LICENSE_ID = 5_500L;
    private static final long NEW_LICENSE_ID = 9_900L;

    private final UserQueryService userQueryService = mock(UserQueryService.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final PartnerDivisionRepository divisionRepository = mock(PartnerDivisionRepository.class);
    private final BrokerageOfficeRepository officeRepository = mock(BrokerageOfficeRepository.class);
    private final BrokerProfileRepository profileRepository = mock(BrokerProfileRepository.class);
    private final StoredFileRepository storedFileRepository = mock(StoredFileRepository.class);
    private final FileDeletionService fileDeletionService = mock(FileDeletionService.class);
    private final BrokerageOfficeChangeRequestRepository changeRequestRepository =
            mock(BrokerageOfficeChangeRequestRepository.class);
    private PartnerService service;

    @BeforeEach
    void setUp() {
        service = new PartnerService(mock(SignUpProcessor.class), mock(LocalAuthService.class), mock(TokenIssuer.class),
                userQueryService, userRepository, divisionRepository, officeRepository, profileRepository,
                storedFileRepository, fileDeletionService, changeRequestRepository, Clock.fixed(NOW, SEOUL),
                mock(Encryptor.class));
        when(userQueryService.getActiveUser("p-1")).thenReturn(User.builder().id(PARTNER_ID).uuid("p-1")
                .role(UserRole.PARTNER).status(UserStatus.ACTIVE).preferredLanguage("ko").build());
        BrokerageOffice office = BrokerageOffice.builder().id(OFFICE_ID).uuid("o-1").regNo("99999-2026-00002")
                .claimedRegNo("99999-2026-00002").name("테스트공인중개사사무소").ceoName("홍길동")
                .address("서울특별시 관악구").tel("02-000-0000").licenseFileId(OFFICE_LICENSE_ID).build();
        lenient().when(profileRepository.findByUserId(PARTNER_ID)).thenReturn(Optional.of(
                BrokerProfile.builder().userId(PARTNER_ID).officeId(OFFICE_ID).build()));
        lenient().when(officeRepository.findById(OFFICE_ID)).thenReturn(Optional.of(office));
        lenient().when(userRepository.findAuthSnapshotForUpdate(PARTNER_ID)).thenReturn(Optional.of(snapshot(UserStatus.ACTIVE)));
        lenient().when(profileRepository.findOfficeIdForUpdate(PARTNER_ID)).thenReturn(Optional.of(OFFICE_ID));
        lenient().when(divisionRepository.findByUserIdOrderByIdAsc(PARTNER_ID)).thenReturn(List.of());
    }

    private static UserRepository.AuthSnapshot snapshot(UserStatus status) {
        return new UserRepository.AuthSnapshot() {
            @Override public String getUuid() { return "p-1"; }
            @Override public String getRole() { return UserRole.PARTNER.name(); }
            @Override public String getStatus() { return status.name(); }
        };
    }

    private BrokerageOfficeChangeRequest pending(long licenseFileId) {
        BrokerageOfficeChangeRequest request = BrokerageOfficeChangeRequest.builder().id(500L).uuid("cr-1")
                .officeId(OFFICE_ID).requestedBy(PARTNER_ID).regNo("99999-2026-00009").name("새상호공인중개사사무소")
                .ceoName("홍길동").address("서울특별시 관악구").licenseFileId(licenseFileId)
                .status(OfficeChangeRequestStatus.PENDING).submittedAt(LocalDateTime.of(2026, 10, 12, 9, 0))
                .version(2L).build();
        when(changeRequestRepository.findPendingForUpdate(OFFICE_ID)).thenReturn(Optional.of(request));
        return request;
    }

    private static String codeOf(Throwable e) {
        return ((PartnerException) e).getErrorCode().getCode();
    }

    @Test
    @DisplayName("대기 건을 CANCELED 로 닫고, 신청에만 붙은 새 등록증은 폐기 표시한다 — 심사자 · 심사 시각은 비운다")
    void cancels_pending_and_discards_new_license() {
        BrokerageOfficeChangeRequest request = pending(NEW_LICENSE_ID);

        service.cancelOfficeChange("p-1");

        assertThat(request.getStatus()).isEqualTo(OfficeChangeRequestStatus.CANCELED);
        assertThat(request.getReviewedBy()).isNull();
        assertThat(request.getReviewedAt()).isNull();
        verify(fileDeletionService).discardByIds(List.of(NEW_LICENSE_ID));
    }

    // 지금 등록증을 그대로 내고 상호만 바꾼 신청 — 그 파일은 사무소의 서류다. 버리면 사무소 등록증이 사라진다.
    @Test
    @DisplayName("신청의 등록증이 사무소가 지금 쓰는 파일이면 버리지 않는다")
    void keeps_license_shared_with_office() {
        BrokerageOfficeChangeRequest request = pending(OFFICE_LICENSE_ID);

        service.cancelOfficeChange("p-1");

        assertThat(request.getStatus()).isEqualTo(OfficeChangeRequestStatus.CANCELED);
        verifyNoInteractions(fileDeletionService);
    }

    @Test
    @DisplayName("대기 건이 없으면 409 OFFICE_CHANGE_NOT_PENDING 이고 아무것도 버리지 않는다")
    void nothing_pending_is_conflict() {
        when(changeRequestRepository.findPendingForUpdate(OFFICE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancelOfficeChange("p-1"))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("OFFICE_CHANGE_NOT_PENDING"));
        verifyNoInteractions(fileDeletionService);
    }

    @Test
    @DisplayName("users → 사무소 → 대표자 연결 → 대기 건 순서로 잠근다 — 부문 상태는 묻지 않는다")
    void locks_in_global_order_without_division_check() {
        pending(NEW_LICENSE_ID);

        service.cancelOfficeChange("p-1");

        InOrder order = inOrder(userRepository, officeRepository, profileRepository, changeRequestRepository);
        order.verify(userRepository).findAuthSnapshotForUpdate(PARTNER_ID);
        order.verify(officeRepository).lockById(OFFICE_ID);
        order.verify(profileRepository).findOfficeIdForUpdate(PARTNER_ID);
        order.verify(changeRequestRepository).findPendingForUpdate(OFFICE_ID);
        verify(divisionRepository, never()).findStatusForUpdate(any(), anyString());
    }

    // 사무소 잠금을 기다리는 동안 탈퇴가 커밋됐다 — 떠난 사무소의 신청은 탈퇴 정리가 이미 닫았다.
    @Test
    @DisplayName("잠근 뒤 대표자 연결이 없으면 PARTNER_DIVISION_NOT_FOUND 이고 대기 건을 찾지 않는다")
    void representative_left_while_waiting() {
        when(profileRepository.findOfficeIdForUpdate(PARTNER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancelOfficeChange("p-1"))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("PARTNER_DIVISION_NOT_FOUND"));
        verify(changeRequestRepository, never()).findPendingForUpdate(any());
    }

    @Test
    @DisplayName("users 를 잠근 뒤 정지된 계정이면 USER_SUSPENDED 이고 사무소를 잠그지 않는다")
    void suspended_account() {
        when(userRepository.findAuthSnapshotForUpdate(PARTNER_ID)).thenReturn(Optional.of(snapshot(UserStatus.SUSPENDED)));

        assertThatThrownBy(() -> service.cancelOfficeChange("p-1")).isInstanceOf(UserException.class);
        verify(officeRepository, never()).lockById(any());
    }

    // 격리 수준 · 쓰기 트랜잭션은 단위 · H2 테스트로는 깨지지 않는다(제출과 같은 이유) — 선언 자체를 고정한다.
    @Test
    @DisplayName("취소는 READ COMMITTED 쓰기 트랜잭션이다")
    void cancel_runs_in_read_committed_writable_tx() throws Exception {
        Transactional tx = PartnerService.class
                .getMethod("cancelOfficeChange", String.class)
                .getAnnotation(Transactional.class);

        assertThat(tx).isNotNull();
        assertThat(tx.readOnly()).isFalse();
        assertThat(tx.isolation()).isEqualTo(Isolation.READ_COMMITTED);
    }

    // 대기 건을 잠금 없이 읽으면 관리자 승인을 기다린 뒤에도 옛 스냅숏의 PENDING 을 보고 닫는다.
    @Test
    @DisplayName("대기 건 조회는 잠금 읽기(PESSIMISTIC_WRITE)다")
    void pending_lookup_is_a_locking_read() throws Exception {
        Lock lock = BrokerageOfficeChangeRequestRepository.class
                .getMethod("findPendingForUpdate", Long.class)
                .getAnnotation(Lock.class);

        assertThat(lock).isNotNull();
        assertThat(lock.value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
    }

    @Test
    @DisplayName("파트너가 아니면 403 NOT_A_PARTNER")
    void not_a_partner() {
        when(userQueryService.getActiveUser("s-1")).thenReturn(User.builder().id(8L).uuid("s-1")
                .role(UserRole.USER).status(UserStatus.ACTIVE).preferredLanguage("ko").build());

        assertThatThrownBy(() -> service.cancelOfficeChange("s-1"))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("NOT_A_PARTNER"));
    }
}
