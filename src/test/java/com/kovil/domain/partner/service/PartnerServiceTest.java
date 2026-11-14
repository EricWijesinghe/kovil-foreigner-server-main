package com.kovil.domain.partner.service;

import com.kovil.domain.auth.dto.TokenPair;
import com.kovil.domain.auth.dto.response.SignInResponse;
import com.kovil.domain.auth.exception.AuthErrorCode;
import com.kovil.domain.auth.exception.SignUpException;
import com.kovil.domain.auth.service.LocalAuthService;
import com.kovil.domain.auth.service.support.SignUpProcessor;
import com.kovil.domain.auth.service.support.TokenIssuer;
import com.kovil.domain.file.entity.StoredFile;
import com.kovil.domain.file.enums.FilePurpose;
import com.kovil.domain.file.repository.StoredFileRepository;
import com.kovil.domain.file.service.FileDeletionService;
import com.kovil.domain.listing.entity.BrokerProfile;
import com.kovil.domain.listing.entity.BrokerageOffice;
import com.kovil.domain.listing.exception.ListingException;
import com.kovil.domain.listing.repository.BrokerProfileRepository;
import com.kovil.domain.listing.repository.BrokerageOfficeRepository;
import com.kovil.domain.partner.dto.request.BrokerageOfficeForm;
import com.kovil.domain.partner.dto.request.PartnerSignUpRequest;
import com.kovil.domain.partner.dto.request.RealEstateBrokerSubmitRequest;
import com.kovil.domain.partner.dto.response.PartnerMeResponse;
import com.kovil.domain.partner.entity.BrokerageOfficeChangeRequest;
import com.kovil.domain.partner.entity.PartnerDivision;
import com.kovil.domain.partner.enums.OfficeChangeRequestStatus;
import com.kovil.domain.partner.enums.PartnerDivisionStatus;
import com.kovil.domain.partner.enums.PartnerDivisionType;
import com.kovil.domain.partner.exception.PartnerErrorCode;
import com.kovil.domain.partner.exception.PartnerException;
import com.kovil.domain.partner.repository.BrokerageOfficeChangeRequestRepository;
import com.kovil.domain.partner.repository.PartnerDivisionRepository;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.user.exception.UserErrorCode;
import com.kovil.domain.user.exception.UserException;
import com.kovil.domain.user.repository.UserRepository;
import com.kovil.domain.user.service.UserQueryService;
import com.kovil.infra.crypto.Encryptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class PartnerServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-06T01:00:00Z");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final long PARTNER_ID = 7L;
    private static final long OFFICE_ID = 7_001L;
    private static final long LICENSE_ID = 55L;
    private static final long OLD_LICENSE_ID = 44L;
    private static final LocalDateTime APPROVED_AT = LocalDateTime.of(2026, 10, 1, 9, 0);

    @Mock SignUpProcessor signUpProcessor;
    @Mock LocalAuthService localAuthService;
    @Mock TokenIssuer tokenIssuer;
    @Mock UserQueryService userQueryService;
    @Mock UserRepository userRepository;
    @Mock PartnerDivisionRepository divisionRepository;
    @Mock BrokerageOfficeRepository officeRepository;
    @Mock BrokerProfileRepository profileRepository;
    @Mock StoredFileRepository storedFileRepository;
    @Mock FileDeletionService fileDeletionService;
    @Mock BrokerageOfficeChangeRequestRepository changeRequestRepository;
    @Mock Encryptor encryptor;

    private PartnerService service;

    @BeforeEach
    void setUp() {
        service = new PartnerService(signUpProcessor, localAuthService, tokenIssuer, userQueryService,
                userRepository, divisionRepository, officeRepository, profileRepository, storedFileRepository,
                fileDeletionService, changeRequestRepository, Clock.fixed(NOW, SEOUL), encryptor);
    }

    private static User partner() {
        return User.builder().id(PARTNER_ID).uuid("p-1").name("홍길동").email("partner@example.test")
                .role(UserRole.PARTNER).status(UserStatus.ACTIVE).preferredLanguage("ko").build();
    }

    private static User partnerWithPhone(String cipherText) {
        return User.builder().id(PARTNER_ID).uuid("p-1").name("홍길동").email("partner@example.test").phone(cipherText)
                .role(UserRole.PARTNER).status(UserStatus.ACTIVE).preferredLanguage("ko").build();
    }

    private static BrokerageOfficeForm form(String regNo) {
        return new BrokerageOfficeForm(regNo, "테스트공인중개사사무소", "홍길동", "서울특별시 관악구", "02-000-0000", null);
    }

    private static PartnerErrorCode codeOf(Throwable e) {
        return (PartnerErrorCode) ((PartnerException) e).getErrorCode();
    }

    // users 행의 잠금 읽기가 돌려주는 지금 커밋된 계정 상태.
    private static UserRepository.AuthSnapshot snapshot(UserStatus status) {
        return new UserRepository.AuthSnapshot() {
            @Override public String getUuid() { return "p-1"; }
            @Override public String getRole() { return UserRole.PARTNER.name(); }
            @Override public String getStatus() { return status.name(); }
        };
    }

    private static String listingCodeOf(Throwable e) {
        return ((ListingException) e).getErrorCode().getCode();
    }

    @Nested
    @DisplayName("파트너 가입")
    class SignUp {

        private PartnerSignUpRequest request() {
            return new PartnerSignUpRequest("tok", "partner@example.test", "passw0rd!", "홍길동", "ko",
                    PartnerDivisionType.REAL_ESTATE_BROKER, form("99999-2026-00002"));
        }

        /*
         * ★ 계정 · 부문 · 사무소 · 대표자 연결이 한 번에 생긴다. 하나라도 빠지면 반쪽 상태가 되고,
         *   역할 변경으로는 파트너를 고치지 못하므로 되돌릴 화면이 없다.
         */
        @Test
        @DisplayName("PARTNER 계정 · 미제출 PENDING 부문 · 사무소 · 대표자 연결을 함께 만들고 토큰을 준다")
        void creates_account_division_office_and_link() {
            User partner = partner();
            given(officeRepository.existsByClaimedRegNo("99999-2026-00002")).willReturn(false);
            given(localAuthService.registerLocal("tok", "partner@example.test", "passw0rd!", "홍길동", "ko",
                    UserRole.PARTNER)).willReturn(partner);
            given(officeRepository.save(any(BrokerageOffice.class))).willAnswer(inv -> {
                BrokerageOffice o = inv.getArgument(0);
                ReflectionTestUtils.setField(o, "id", OFFICE_ID);
                return o;
            });
            given(tokenIssuer.issue(partner)).willReturn(new TokenPair("at", "rt", 1800));

            SignInResponse response = service.signUp(request());

            ArgumentCaptor<BrokerageOffice> office = ArgumentCaptor.forClass(BrokerageOffice.class);
            then(officeRepository).should().save(office.capture());
            assertThat(office.getValue().getRegNo()).isEqualTo("99999-2026-00002");
            assertThat(office.getValue().getLicenseFileId()).isNull();
            // 가입은 번호를 잡지 않는다 — 잡는 것은 승인이다.
            assertThat(office.getValue().getClaimedRegNo()).isNull();

            ArgumentCaptor<BrokerProfile> profile = ArgumentCaptor.forClass(BrokerProfile.class);
            then(profileRepository).should().save(profile.capture());
            assertThat(profile.getValue().getUserId()).isEqualTo(PARTNER_ID);
            assertThat(profile.getValue().getOfficeId()).isEqualTo(OFFICE_ID);

            ArgumentCaptor<PartnerDivision> division = ArgumentCaptor.forClass(PartnerDivision.class);
            then(divisionRepository).should().save(division.capture());
            assertThat(division.getValue().getUserId()).isEqualTo(PARTNER_ID);
            assertThat(division.getValue().getDivision()).isEqualTo(PartnerDivisionType.REAL_ESTATE_BROKER);
            assertThat(division.getValue().getStatus()).isEqualTo(PartnerDivisionStatus.PENDING);
            // 부문 행에 사무소를 남긴다 — 탈퇴가 대표자 연결을 지워도 정지 이력이 이 사무소의 번호를 찾는다.
            assertThat(division.getValue().getOfficeId()).isEqualTo(OFFICE_ID);
            // 등록증은 가입 뒤에 올린다 — 아직 심사 대기열에 오르지 않는다.
            assertThat(division.getValue().isSubmitted()).isFalse();

            assertThat(response.role()).isEqualTo("PARTNER");
            assertThat(response.permissions()).isEmpty();
        }

        // 그 번호를 잡은 사무소(마지막으로 승인받은 번호)가 있는 동안만 막는다 — 미승인 신청끼리는 겹쳐도 된다(아래 검사가 claimed 만 본다).
        @Test
        @DisplayName("그 번호를 승인받아 쓰는 사무소가 있으면 409 이고 계정을 만들지 않는다(인증도 소비하지 않는다)")
        void claimed_reg_no_is_rejected_before_account() {
            given(officeRepository.existsByClaimedRegNo("99999-2026-00002")).willReturn(true);

            assertThatThrownBy(() -> service.signUp(request()))
                    .isInstanceOf(ListingException.class)
                    .extracting(PartnerServiceTest::listingCodeOf)
                    .isEqualTo("OFFICE_REG_NO_DUPLICATED");
            then(localAuthService).should(never()).registerLocal(any(), any(), any(), any(), any(), any());
            then(officeRepository).should(never()).save(any());
        }

        // ★ 순서가 계약이다 — 토큰 없는 요청이 409 차이로 등록번호 · 이메일의 가입 여부를 캐내지 못한다.
        @Test
        @DisplayName("인증 토큰이 무효면 등록번호 중복 여부를 묻지도 않는다")
        void token_is_checked_before_reg_no() {
            willThrow(new SignUpException(AuthErrorCode.VERIFICATION_TOKEN_INVALID))
                    .given(signUpProcessor).assertUsableSignupToken("tok");

            assertThatThrownBy(() -> service.signUp(request()))
                    .isInstanceOf(SignUpException.class);
            then(officeRepository).should(never()).existsByClaimedRegNo(anyString());
        }
    }

    @Nested
    @DisplayName("공인중개사 부문 제출 · 재제출 · 승인 뒤 변경 신청")
    class Submit {

        private BrokerageOffice office;
        private PartnerDivision division;

        @BeforeEach
        void givenPartnerWithOffice() {
            given(userQueryService.getActiveUser("p-1")).willReturn(partner());
            office = BrokerageOffice.builder().id(OFFICE_ID).uuid("o-1").regNo("99999-2026-00002")
                    .name("테스트공인중개사사무소").ceoName("홍길동").address("서울특별시 관악구")
                    .tel("02-000-0000").build();
            division = PartnerDivision.builder().uuid("pd-1").userId(PARTNER_ID)
                    .division(PartnerDivisionType.REAL_ESTATE_BROKER).status(PartnerDivisionStatus.PENDING).build();
            lenient().when(divisionRepository.findByUserIdAndDivision(PARTNER_ID, PartnerDivisionType.REAL_ESTATE_BROKER))
                    .thenAnswer(inv -> Optional.of(division));
            lenient().when(profileRepository.findByUserId(PARTNER_ID)).thenReturn(Optional.of(
                    BrokerProfile.builder().userId(PARTNER_ID).officeId(OFFICE_ID).build()));
            lenient().when(officeRepository.findById(OFFICE_ID)).thenAnswer(inv -> Optional.of(office));
            lenient().when(divisionRepository.findByUserIdOrderByIdAsc(PARTNER_ID)).thenAnswer(inv -> List.of(division));
            lenient().when(storedFileRepository.findById(LICENSE_ID))
                    .thenReturn(Optional.of(license(FilePurpose.BROKER_LICENSE, PARTNER_ID)));
            // 잠금 읽기 — 계정 · 대표자 연결 · 부문 상태의 지금 커밋된 값. 기본은 "그사이 아무 일도 없었다"다.
            lenient().when(userRepository.findAuthSnapshotForUpdate(PARTNER_ID))
                    .thenReturn(Optional.of(snapshot(UserStatus.ACTIVE)));
            lenient().when(profileRepository.findOfficeIdForUpdate(PARTNER_ID)).thenReturn(Optional.of(OFFICE_ID));
            lenient().when(divisionRepository.findStatusForUpdate(PARTNER_ID,
                            PartnerDivisionType.REAL_ESTATE_BROKER.name()))
                    .thenAnswer(inv -> Optional.of(division.getStatus().name()));
        }

        private StoredFile license(FilePurpose purpose, Long uploaderId) {
            return StoredFile.builder().id(LICENSE_ID).uuid("f-1").storageKey("k").contentType("application/pdf")
                    .byteSize(1L).purpose(purpose).uploaderId(uploaderId).build();
        }

        private void givenOwnLicense() {
            given(storedFileRepository.findByUuidAndDeletedAtIsNull("f-1"))
                    .willReturn(Optional.of(license(FilePurpose.BROKER_LICENSE, PARTNER_ID)));
        }

        // 승인된 사무소 — 지금 서류(등록증 = LICENSE_ID)가 그대로 붙어 있고 번호를 잡고 있다.
        private void givenApproved() {
            office = BrokerageOffice.builder().id(OFFICE_ID).uuid("o-1").regNo("99999-2026-00002")
                    .claimedRegNo("99999-2026-00002")
                    .name("테스트공인중개사사무소").ceoName("홍길동").address("서울특별시 관악구")
                    .tel("02-000-0000").licenseFileId(LICENSE_ID).build();
            division = PartnerDivision.builder().uuid("pd-1").userId(PARTNER_ID)
                    .division(PartnerDivisionType.REAL_ESTATE_BROKER).status(PartnerDivisionStatus.APPROVED)
                    .submittedAt(APPROVED_AT.minusDays(1)).lastApprovedAt(APPROVED_AT).version(5L).build();
        }

        private RealEstateBrokerSubmitRequest request(BrokerageOfficeForm form) {
            return new RealEstateBrokerSubmitRequest(form, "f-1");
        }

        @Test
        @DisplayName("사무소 정보와 등록증을 한 벌로 덮고 제출 시각을 찍는다")
        void submit_attaches_license_and_stamps_submission() {
            givenOwnLicense();

            PartnerMeResponse response = service.submitRealEstateBroker("p-1", request(
                    new BrokerageOfficeForm("99999-2026-00002", "새상호공인중개사사무소", "홍길동",
                            "서울특별시 관악구", "02-000-0001", " ")));

            assertThat(office.getName()).isEqualTo("새상호공인중개사사무소");
            assertThat(office.getTel()).isEqualTo("02-000-0001");
            assertThat(office.getBizNo()).isNull();
            assertThat(office.getLicenseFileId()).isEqualTo(LICENSE_ID);
            assertThat(division.getSubmittedAt()).isEqualTo(LocalDateTime.now(Clock.fixed(NOW, SEOUL)));
            assertThat(response.divisions()).singleElement()
                    .satisfies(d -> assertThat(d.submitted()).isTrue());
            assertThat(response.brokerageOffice().licenseFileUuid()).isEqualTo("f-1");
            // 처음 붙이는 등록증이라 버릴 옛 파일이 없다.
            then(fileDeletionService).shouldHaveNoInteractions();
        }

        /*
         * ★ 남이 올린 등록증 · 다른 용도의 파일을 붙이지 못한다. 셋을 하나의 코드로 합친다 —
         *   어느 쪽인지 알려 주면 남의 파일 uuid 를 찾는 탐침이 된다.
         */
        @Test
        @DisplayName("남의 파일 · 다른 용도 · 없는 파일은 모두 PARTNER_LICENSE_FILE_INVALID 다")
        void foreign_or_wrong_files_are_rejected() {
            RealEstateBrokerSubmitRequest request = request(form("99999-2026-00002"));

            given(storedFileRepository.findByUuidAndDeletedAtIsNull("f-1"))
                    .willReturn(Optional.of(license(FilePurpose.BROKER_LICENSE, 999L)))
                    .willReturn(Optional.of(license(FilePurpose.VERIFICATION_DOC, PARTNER_ID)))
                    .willReturn(Optional.empty());

            for (int i = 0; i < 3; i++) {
                assertThatThrownBy(() -> service.submitRealEstateBroker("p-1", request))
                        .extracting(PartnerServiceTest::codeOf)
                        .isEqualTo(PartnerErrorCode.PARTNER_LICENSE_FILE_INVALID);
            }
            assertThat(division.isSubmitted()).isFalse();
            assertThat(office.getLicenseFileId()).isNull();
        }

        // 정지된 부문은 서류를 바꿔 스스로 정지를 풀 수 없다 — 파일도 사무소도 건드리지 않는다.
        @Test
        @DisplayName("정지된 부문은 서류를 바꿀 수 없다 — 사무소 정보도 그대로다")
        void suspended_cannot_resubmit() {
            division = PartnerDivision.builder().uuid("pd-1").userId(PARTNER_ID)
                    .division(PartnerDivisionType.REAL_ESTATE_BROKER).status(PartnerDivisionStatus.SUSPENDED)
                    .submittedAt(LocalDateTime.of(2026, 10, 1, 0, 0)).build();

            assertThatThrownBy(() -> service.submitRealEstateBroker("p-1", request(form("99999-2026-00002"))))
                    .extracting(PartnerServiceTest::codeOf)
                    .isEqualTo(PartnerErrorCode.PARTNER_DIVISION_NOT_EDITABLE);
            then(storedFileRepository).should(never()).findByUuidAndDeletedAtIsNull(anyString());
            assertThat(office.getName()).isEqualTo("테스트공인중개사사무소");
            then(fileDeletionService).shouldHaveNoInteractions();
            // 정지된 부문은 변경 신청도 낼 수 없다 — 서류를 바꿔 정지를 우회하지 못한다.
            then(changeRequestRepository).shouldHaveNoInteractions();
        }

        /*
         * ★ 계정 상태는 users 행의 잠금 읽기로 다시 본다 — 앞의 읽기(getActiveUser)와 이 잠금 사이에 탈퇴 · 정지가 커밋될 수
         *   있다. 잠근 뒤로는 커밋까지 바뀌지 않는다. 답은 그 직후에 들어온 요청이 받는 것과 같다. 부문 · 사무소는 읽지도 않는다.
         */
        @ParameterizedTest(name = "잠금 뒤 계정 = {0}")
        @EnumSource(value = UserStatus.class, names = {"WITHDRAWN", "SUSPENDED"})
        @DisplayName("users 를 잠근 뒤 계정이 활성이 아니면 탈퇴는 USER_NOT_FOUND · 정지는 USER_SUSPENDED 이고 아무것도 바꾸지 않는다")
        void account_rechecked_under_lock(UserStatus statusAfterLock) {
            given(userRepository.findAuthSnapshotForUpdate(PARTNER_ID)).willReturn(Optional.of(snapshot(statusAfterLock)));

            assertThatThrownBy(() -> service.submitRealEstateBroker("p-1", request(form("99999-2026-00002"))))
                    .isInstanceOf(UserException.class)
                    .extracting(e -> ((UserException) e).getErrorCode())
                    .isEqualTo(statusAfterLock == UserStatus.WITHDRAWN
                            ? UserErrorCode.USER_NOT_FOUND : UserErrorCode.USER_SUSPENDED);
            then(divisionRepository).should(never()).findByUserIdAndDivision(any(), any());
            then(officeRepository).shouldHaveNoInteractions();
            then(changeRequestRepository).shouldHaveNoInteractions();
            assertThat(division.isSubmitted()).isFalse();
        }

        // users 행이 사라졌으면(읽을 행이 없다) 탈퇴와 같게 답한다.
        @Test
        @DisplayName("users 잠금 읽기가 빈 결과면 USER_NOT_FOUND 다")
        void missing_user_row_is_not_found() {
            given(userRepository.findAuthSnapshotForUpdate(PARTNER_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.submitRealEstateBroker("p-1", request(form("99999-2026-00002"))))
                    .extracting(e -> ((UserException) e).getErrorCode())
                    .isEqualTo(UserErrorCode.USER_NOT_FOUND);
            then(officeRepository).shouldHaveNoInteractions();
        }

        // 첫 심사 경로도 users 를 먼저 잡는다 — 부문을 읽고 사무소 · 부문을 고치는 것은 그 뒤다.
        @Test
        @DisplayName("첫 제출도 users 를 잠근 뒤에 부문을 읽는다")
        void first_submission_also_locks_account_first() {
            givenOwnLicense();

            service.submitRealEstateBroker("p-1", request(form("99999-2026-00002")));

            InOrder order = Mockito.inOrder(userRepository, divisionRepository);
            order.verify(userRepository).findAuthSnapshotForUpdate(PARTNER_ID);
            order.verify(divisionRepository).findByUserIdAndDivision(PARTNER_ID, PartnerDivisionType.REAL_ESTATE_BROKER);
        }


        // 가입 때 잘못 적은 등록번호로 반려되면 고칠 길이 이것뿐이다 — 다만 남이 승인받아 쓰는 번호는 가져오지 못한다.
        @Test
        @DisplayName("고친 등록번호를 다른 사무소가 잡고 있으면 409 이고 아무것도 바꾸지 않는다")
        void changed_reg_no_must_be_unclaimed() {
            givenOwnLicense();
            given(officeRepository.existsByClaimedRegNoAndIdNot("99999-2026-00003", OFFICE_ID)).willReturn(true);

            assertThatThrownBy(() -> service.submitRealEstateBroker("p-1", request(form("99999-2026-00003"))))
                    .isInstanceOf(ListingException.class)
                    .extracting(PartnerServiceTest::listingCodeOf)
                    .isEqualTo("OFFICE_REG_NO_DUPLICATED");
            assertThat(office.getRegNo()).isEqualTo("99999-2026-00002");
            assertThat(division.isSubmitted()).isFalse();
        }

        /*
         * ★ 번호를 고치지 않은 재제출도 본다 — 가입 뒤에 같은 번호로 다른 사람이 승인받았으면 이 신청은 승인될 수
         *   없다. 그대로 대기열에 올리면 심사자가 승인을 눌러도 409 만 난다.
         */
        @Test
        @DisplayName("번호를 고치지 않았어도 그사이 다른 사무소가 그 번호를 잡았으면 409 다")
        void unchanged_reg_no_claimed_by_other_is_rejected() {
            givenOwnLicense();
            given(officeRepository.existsByClaimedRegNoAndIdNot("99999-2026-00002", OFFICE_ID)).willReturn(true);

            assertThatThrownBy(() -> service.submitRealEstateBroker("p-1", request(form("99999-2026-00002"))))
                    .extracting(PartnerServiceTest::listingCodeOf)
                    .isEqualTo("OFFICE_REG_NO_DUPLICATED");
            assertThat(division.isSubmitted()).isFalse();
        }

        // 대기 · 반려 건은 바꾼 것이 없어도 다시 낼 수 있다 — "변경 없음" 검사는 승인된 부문이 내는 변경 신청에만 있다.
        @ParameterizedTest(name = "{0}")
        @EnumSource(value = PartnerDivisionStatus.class, names = {"PENDING", "REJECTED"})
        @DisplayName("대기 · 반려 건은 같은 서류로 다시 내도 받는다")
        void pending_or_rejected_may_resubmit_unchanged(PartnerDivisionStatus status) {
            givenOwnLicense();
            office = BrokerageOffice.builder().id(OFFICE_ID).uuid("o-1").regNo("99999-2026-00002")
                    .name("테스트공인중개사사무소").ceoName("홍길동").address("서울특별시 관악구")
                    .tel("02-000-0000").licenseFileId(LICENSE_ID).build();
            division = PartnerDivision.builder().uuid("pd-1").userId(PARTNER_ID)
                    .division(PartnerDivisionType.REAL_ESTATE_BROKER).status(status)
                    .submittedAt(LocalDateTime.of(2026, 10, 1, 0, 0)).build();

            service.submitRealEstateBroker("p-1", request(form("99999-2026-00002")));

            assertThat(division.getStatus()).isEqualTo(PartnerDivisionStatus.PENDING);
            assertThat(division.getSubmittedAt()).isEqualTo(LocalDateTime.now(Clock.fixed(NOW, SEOUL)));
            then(fileDeletionService).shouldHaveNoInteractions();
            // 첫 심사는 사무소를 덮는 길이다 — 변경 신청을 만들지 않는다.
            then(changeRequestRepository).should(never()).save(any());
        }

        /*
         * ★ 승인된 부문이 내면 **변경 신청**이다(PO 확정 2026-10-07). 부문도 사무소도 그대로 두고 바꿀 값만 따로 보관한다 —
         *   심사 동안 매물 공개 · 기능이 멈추지 않는다. (의도된 행동 변경 — 이전에는 사무소를 덮고 부문을 대기로 돌렸다.)
         */
        @Nested
        @DisplayName("승인 뒤 변경 신청")
        class OfficeChange {

            private static final long NEW_LICENSE_ID = 99L;
            private static final long OLD_REQUEST_LICENSE_ID = 88L;

            static Stream<Arguments> reviewedFieldChanges() {
                return Stream.of(
                        Arguments.of("등록번호", new BrokerageOfficeForm("99999-2026-00009", "테스트공인중개사사무소",
                                "홍길동", "서울특별시 관악구", "02-000-0000", null), LICENSE_ID),
                        Arguments.of("상호", new BrokerageOfficeForm("99999-2026-00002", "새상호공인중개사사무소",
                                "홍길동", "서울특별시 관악구", "02-000-0000", null), LICENSE_ID),
                        Arguments.of("대표자", new BrokerageOfficeForm("99999-2026-00002", "테스트공인중개사사무소",
                                "김철수", "서울특별시 관악구", "02-000-0000", null), LICENSE_ID),
                        Arguments.of("소재지", new BrokerageOfficeForm("99999-2026-00002", "테스트공인중개사사무소",
                                "홍길동", "서울특별시 동작구", "02-000-0000", null), LICENSE_ID),
                        Arguments.of("사업자번호", new BrokerageOfficeForm("99999-2026-00002", "테스트공인중개사사무소",
                                "홍길동", "서울특별시 관악구", "02-000-0000", "000-00-00000"), LICENSE_ID),
                        Arguments.of("등록증", new BrokerageOfficeForm("99999-2026-00002", "테스트공인중개사사무소",
                                "홍길동", "서울특별시 관악구", "02-000-0000", null), OLD_LICENSE_ID));
            }

            private BrokerageOfficeChangeRequest pending(long licenseFileId) {
                return BrokerageOfficeChangeRequest.builder().id(500L).uuid("cr-1").officeId(OFFICE_ID)
                        .requestedBy(PARTNER_ID).regNo("99999-2026-00008").name("옛신청공인중개사사무소")
                        .ceoName("홍길동").address("서울특별시 관악구").licenseFileId(licenseFileId)
                        .status(OfficeChangeRequestStatus.PENDING).submittedAt(APPROVED_AT.plusDays(1))
                        .version(2L).build();
            }

            private void givenOwnLicenseFile(long fileId) {
                given(storedFileRepository.findByUuidAndDeletedAtIsNull("f-" + fileId))
                        .willReturn(Optional.of(StoredFile.builder().id(fileId).uuid("f-" + fileId).storageKey("k")
                                .contentType("application/pdf").byteSize(1L).purpose(FilePurpose.BROKER_LICENSE)
                                .uploaderId(PARTNER_ID).build()));
            }

            private void assertOfficeAndDivisionUntouched(long attachedLicense) {
                assertThat(office.getRegNo()).isEqualTo("99999-2026-00002");
                assertThat(office.getClaimedRegNo()).isEqualTo("99999-2026-00002");
                assertThat(office.getName()).isEqualTo("테스트공인중개사사무소");
                assertThat(office.getCeoName()).isEqualTo("홍길동");
                assertThat(office.getAddress()).isEqualTo("서울특별시 관악구");
                assertThat(office.getBizNo()).isNull();
                assertThat(office.getTel()).isEqualTo("02-000-0000");
                assertThat(office.getLicenseFileId()).isEqualTo(attachedLicense);
                assertThat(division.getStatus()).isEqualTo(PartnerDivisionStatus.APPROVED);
                assertThat(division.getSubmittedAt()).isEqualTo(APPROVED_AT.minusDays(1));
                assertThat(division.getLastApprovedAt()).isEqualTo(APPROVED_AT);
                assertThat(division.getVersion()).isEqualTo(5L);
            }

            /*
             * ★ 심사 대상 여섯 칸 중 하나라도 바뀌면 변경 신청이 생긴다 — 바꿀 값 한 벌 · 신청자 · 제출 시각을 담고 대기다.
             *   사무소 · 부문은 그대로다(매물 공개 · 기능 유지). "등록증" 줄은 사무소에 옛 등록증이 붙어 있는데 새 파일을 낸 경우다.
             */
            @ParameterizedTest(name = "{0}")
            @MethodSource("reviewedFieldChanges")
            @DisplayName("심사 대상이 하나라도 바뀌면 변경 신청을 만들고 사무소 · 부문은 건드리지 않는다")
            void any_reviewed_field_change_creates_change_request(String label, BrokerageOfficeForm form,
                                                                  long attachedLicense) {
                givenApproved();
                ReflectionTestUtils.setField(office, "licenseFileId", attachedLicense);
                givenOwnLicense();

                service.submitRealEstateBroker("p-1", request(form));

                ArgumentCaptor<BrokerageOfficeChangeRequest> saved =
                        ArgumentCaptor.forClass(BrokerageOfficeChangeRequest.class);
                then(changeRequestRepository).should().save(saved.capture());
                BrokerageOfficeChangeRequest change = saved.getValue();
                assertThat(change.getOfficeId()).isEqualTo(OFFICE_ID);
                assertThat(change.getRequestedBy()).isEqualTo(PARTNER_ID);
                assertThat(change.getRegNo()).isEqualTo(form.regNo());
                assertThat(change.getName()).isEqualTo(form.name());
                assertThat(change.getCeoName()).isEqualTo(form.ceoName());
                assertThat(change.getAddress()).isEqualTo(form.address());
                assertThat(change.getBizNo()).isEqualTo(form.bizNo());
                assertThat(change.getLicenseFileId()).isEqualTo(LICENSE_ID);
                assertThat(change.getStatus()).isEqualTo(OfficeChangeRequestStatus.PENDING);
                assertThat(change.getSubmittedAt()).isEqualTo(LocalDateTime.now(Clock.fixed(NOW, SEOUL)));
                assertThat(change.getUuid()).isNotBlank();

                assertOfficeAndDivisionUntouched(attachedLicense);
                // 새 신청은 아무 파일도 버리지 않는다 — 옛 등록증은 승인될 때 버린다.
                then(fileDeletionService).shouldHaveNoInteractions();
            }

            /*
             * ★★ 사무소당 대기 건은 하나 — 사무소 행을 먼저 잠그고 그 뒤에 대기 건을 잠금 읽기로 찾는다. 순서가 바뀌면
             *    같은 순간의 두 제출이 둘 다 "대기 건 없음"을 보고 둘을 만든다.
             */
            @Test
            @DisplayName("사무소 행을 잠근 뒤에 대기 건을 잠금 읽기로 찾는다")
            void locks_office_before_looking_up_pending() {
                givenApproved();
                givenOwnLicense();

                service.submitRealEstateBroker("p-1", request(new BrokerageOfficeForm("99999-2026-00002",
                        "새상호공인중개사사무소", "홍길동", "서울특별시 관악구", "02-000-0000", null)));

                InOrder order = Mockito.inOrder(officeRepository, changeRequestRepository);
                order.verify(officeRepository).lockById(OFFICE_ID);
                order.verify(changeRequestRepository).findPendingForUpdate(OFFICE_ID);
                order.verify(changeRequestRepository).save(any(BrokerageOfficeChangeRequest.class));
            }

            /*
             * ★★ 잠금 순서는 토큰 발급과 같은 방향이다 — users → 사무소 → (대표자 연결) → 변경 신청 → 부문.
             *    users 를 사무소보다 먼저 잡지 않으면, 변경 신청 INSERT 의 FK 검사가 사무소 · 부문을 쥔 채 users 를 기다리고,
             *    users → 부문 순으로 잡는 토큰 발급과 교착한다(MySQL 재현). 재판정(연결 · 부문 상태)은 사무소를 잠근 **뒤**다.
             */
            @Test
            @DisplayName("users 를 먼저 잡고, 사무소를 잠근 뒤에 연결 · 대기 건 · 부문 상태를 잠금 읽기로 다시 본다")
            void locks_in_global_order_and_rechecks_after_office_lock() {
                givenApproved();
                givenOwnLicense();

                service.submitRealEstateBroker("p-1", request(new BrokerageOfficeForm("99999-2026-00002",
                        "새상호공인중개사사무소", "홍길동", "서울특별시 관악구", "02-000-0000", null)));

                InOrder order = Mockito.inOrder(userRepository, officeRepository,
                        profileRepository, changeRequestRepository, divisionRepository);
                order.verify(userRepository).findAuthSnapshotForUpdate(PARTNER_ID);
                order.verify(officeRepository).lockById(OFFICE_ID);
                order.verify(profileRepository).findOfficeIdForUpdate(PARTNER_ID);
                order.verify(changeRequestRepository).findPendingForUpdate(OFFICE_ID);
                order.verify(divisionRepository).findStatusForUpdate(PARTNER_ID,
                        PartnerDivisionType.REAL_ESTATE_BROKER.name());
                order.verify(changeRequestRepository).save(any(BrokerageOfficeChangeRequest.class));
            }

            /*
             * ★ 사무소 잠금을 기다리는 동안 탈퇴가 커밋됐다 — 대표자 연결이 지워졌거나 다른 사무소를 가리킨다.
             *   떠난 사무소에 대기 건을 남기면 아무도 처리할 수 없는 행이 된다. 연결이 없는 것은 부문 상세가 없는 것과 같게 답한다.
             */
            @ParameterizedTest(name = "잠금 뒤 연결 = {0}")
            @NullSource
            @ValueSource(longs = 9_999L)
            @DisplayName("사무소를 잠근 뒤 대표자 연결이 이 사무소가 아니면 PARTNER_DIVISION_NOT_FOUND 이고 아무것도 만들지 않는다")
            void representative_gone_after_lock(Long linkedOfficeAfterLock) {
                givenApproved();
                givenOwnLicense();
                given(profileRepository.findOfficeIdForUpdate(PARTNER_ID))
                        .willReturn(Optional.ofNullable(linkedOfficeAfterLock));

                assertThatThrownBy(() -> service.submitRealEstateBroker("p-1", request(form("99999-2026-00009"))))
                        .extracting(PartnerServiceTest::codeOf)
                        .isEqualTo(PartnerErrorCode.PARTNER_DIVISION_NOT_FOUND);

                InOrder order = Mockito.inOrder(officeRepository, profileRepository);
                order.verify(officeRepository).lockById(OFFICE_ID);
                order.verify(profileRepository).findOfficeIdForUpdate(PARTNER_ID);
                then(changeRequestRepository).should(never()).save(any());
                then(changeRequestRepository).should(never()).findPendingForUpdate(any());
                then(fileDeletionService).shouldHaveNoInteractions();
                assertOfficeAndDivisionUntouched(LICENSE_ID);
            }

            /*
             * ★ 사무소 잠금을 기다리는 동안 정지가 커밋됐다 — 첫 읽기는 승인이었어도 잠근 뒤에 본 부문이 승인이 아니면 거절한다.
             *   정지된 부문의 변경 신청은 서류를 바꿔 정지를 우회하는 길이다. 이미 있던 대기 건도 덮지 않는다.
             */
            @ParameterizedTest(name = "잠금 뒤 부문 = {0}")
            @EnumSource(value = PartnerDivisionStatus.class, names = {"SUSPENDED", "PENDING", "REJECTED"})
            @DisplayName("사무소를 잠근 뒤 부문이 승인 상태가 아니면 PARTNER_DIVISION_NOT_EDITABLE 이고 대기 건도 덮지 않는다")
            void division_no_longer_approved_after_lock(PartnerDivisionStatus statusAfterLock) {
                givenApproved();
                givenOwnLicenseFile(NEW_LICENSE_ID);
                BrokerageOfficeChangeRequest existing = pending(OLD_REQUEST_LICENSE_ID);
                given(changeRequestRepository.findPendingForUpdate(OFFICE_ID)).willReturn(Optional.of(existing));
                given(divisionRepository.findStatusForUpdate(PARTNER_ID, PartnerDivisionType.REAL_ESTATE_BROKER.name()))
                        .willReturn(Optional.of(statusAfterLock.name()));

                assertThatThrownBy(() -> service.submitRealEstateBroker("p-1", new RealEstateBrokerSubmitRequest(
                        form("99999-2026-00009"), "f-" + NEW_LICENSE_ID)))
                        .extracting(PartnerServiceTest::codeOf)
                        .isEqualTo(PartnerErrorCode.PARTNER_DIVISION_NOT_EDITABLE);

                InOrder order = Mockito.inOrder(officeRepository, divisionRepository);
                order.verify(officeRepository).lockById(OFFICE_ID);
                order.verify(divisionRepository).findStatusForUpdate(PARTNER_ID,
                        PartnerDivisionType.REAL_ESTATE_BROKER.name());
                assertThat(existing.getRegNo()).isEqualTo("99999-2026-00008");
                assertThat(existing.getLicenseFileId()).isEqualTo(OLD_REQUEST_LICENSE_ID);
                assertThat(existing.getSubmittedAt()).isEqualTo(APPROVED_AT.plusDays(1));
                then(changeRequestRepository).should(never()).save(any());
                then(fileDeletionService).shouldHaveNoInteractions();
            }

            /*
             * ★ 대기 건이 있으면 새로 만들지 않고 덮는다 — 바꿀 값 · 등록증 · 제출 시각이 새것이 된다.
             *   덮여서 버려지는 옛 신청의 등록증은 폐기 표시한다(사무소가 쓰는 파일이 아니므로).
             */
            @Test
            @DisplayName("대기 건이 있으면 덮어쓰고, 덮여 버려진 등록증을 폐기한다")
            void pending_change_is_overwritten() {
                givenApproved();
                givenOwnLicenseFile(NEW_LICENSE_ID);
                BrokerageOfficeChangeRequest existing = pending(OLD_REQUEST_LICENSE_ID);
                given(changeRequestRepository.findPendingForUpdate(OFFICE_ID)).willReturn(Optional.of(existing));

                service.submitRealEstateBroker("p-1", new RealEstateBrokerSubmitRequest(new BrokerageOfficeForm(
                        "99999-2026-00009", "새상호공인중개사사무소", "김철수", "서울특별시 동작구", "02-000-0000",
                        "000-00-00000"), "f-" + NEW_LICENSE_ID));

                then(changeRequestRepository).should(never()).save(any());
                assertThat(existing.getRegNo()).isEqualTo("99999-2026-00009");
                assertThat(existing.getName()).isEqualTo("새상호공인중개사사무소");
                assertThat(existing.getCeoName()).isEqualTo("김철수");
                assertThat(existing.getAddress()).isEqualTo("서울특별시 동작구");
                assertThat(existing.getBizNo()).isEqualTo("000-00-00000");
                assertThat(existing.getLicenseFileId()).isEqualTo(NEW_LICENSE_ID);
                assertThat(existing.getSubmittedAt()).isEqualTo(LocalDateTime.now(Clock.fixed(NOW, SEOUL)));
                then(fileDeletionService).should().discardByIds(List.of(OLD_REQUEST_LICENSE_ID));
                assertOfficeAndDivisionUntouched(LICENSE_ID);
            }

            // 지금 등록증을 그대로 냈던 신청을 새 파일로 덮어도, 그 파일은 사무소의 서류라 버리지 않는다.
            @Test
            @DisplayName("덮여 버려지는 등록증이 사무소가 쓰는 파일이면 폐기하지 않는다")
            void overwrite_keeps_license_used_by_office() {
                givenApproved();
                givenOwnLicenseFile(NEW_LICENSE_ID);
                BrokerageOfficeChangeRequest existing = pending(LICENSE_ID);
                given(changeRequestRepository.findPendingForUpdate(OFFICE_ID)).willReturn(Optional.of(existing));

                service.submitRealEstateBroker("p-1", new RealEstateBrokerSubmitRequest(new BrokerageOfficeForm(
                        "99999-2026-00002", "새상호공인중개사사무소", "홍길동", "서울특별시 관악구", "02-000-0000", null),
                        "f-" + NEW_LICENSE_ID));

                assertThat(existing.getLicenseFileId()).isEqualTo(NEW_LICENSE_ID);
                then(fileDeletionService).shouldHaveNoInteractions();
            }

            @Test
            @DisplayName("같은 등록증으로 덮으면 아무것도 버리지 않는다")
            void overwrite_with_same_license_discards_nothing() {
                givenApproved();
                givenOwnLicenseFile(NEW_LICENSE_ID);
                BrokerageOfficeChangeRequest existing = pending(NEW_LICENSE_ID);
                given(changeRequestRepository.findPendingForUpdate(OFFICE_ID)).willReturn(Optional.of(existing));

                service.submitRealEstateBroker("p-1", new RealEstateBrokerSubmitRequest(new BrokerageOfficeForm(
                        "99999-2026-00002", "새상호공인중개사사무소", "홍길동", "서울특별시 관악구", "02-000-0000", null),
                        "f-" + NEW_LICENSE_ID));

                then(fileDeletionService).shouldHaveNoInteractions();
            }

            /*
             * ★ 지금 사무소 값과 심사 대상이 하나도 다르지 않으면 400 — 심사할 것이 없는 빈 신청이다.
             *   대표번호만 바뀐 것도 같다(대표번호는 심사 대상이 아니다 — 따로 연 길로 바꾼다).
             */
            @ParameterizedTest(name = "대표번호 {0}")
            @ValueSource(strings = {"02-000-0000", "02-000-0009"})
            @DisplayName("심사 대상이 하나도 안 바뀐 변경 신청은 PARTNER_REVIEW_NO_CHANGE 이고 아무것도 만들지 않는다")
            void unchanged_change_request_is_rejected(String tel) {
                givenApproved();
                givenOwnLicense();

                assertThatThrownBy(() -> service.submitRealEstateBroker("p-1", request(new BrokerageOfficeForm(
                        "99999-2026-00002", "테스트공인중개사사무소", "홍길동", "서울특별시 관악구", tel, " "))))
                        .extracting(PartnerServiceTest::codeOf)
                        .isEqualTo(PartnerErrorCode.PARTNER_REVIEW_NO_CHANGE);
                then(changeRequestRepository).shouldHaveNoInteractions();
                then(officeRepository).should(never()).lockById(any());
                then(fileDeletionService).shouldHaveNoInteractions();
                assertOfficeAndDivisionUntouched(LICENSE_ID);
            }

            // 승인될 수 없는 신청을 대기열에 올리지 않는다 — 새 번호를 다른 사무소가 승인받아 쓰고 있다.
            @Test
            @DisplayName("바꿀 번호를 다른 사무소가 잡고 있으면 409 이고 아무것도 만들지 않는다")
            void change_to_claimed_number_is_rejected() {
                givenApproved();
                givenOwnLicense();
                given(officeRepository.existsByClaimedRegNoAndIdNot("99999-2026-00009", OFFICE_ID)).willReturn(true);

                assertThatThrownBy(() -> service.submitRealEstateBroker("p-1", request(form("99999-2026-00009"))))
                        .isInstanceOf(ListingException.class)
                        .extracting(PartnerServiceTest::listingCodeOf)
                        .isEqualTo("OFFICE_REG_NO_DUPLICATED");
                then(changeRequestRepository).shouldHaveNoInteractions();
                assertOfficeAndDivisionUntouched(LICENSE_ID);
            }

            // 대표번호는 변경 신청에 실리지 않는다 — 폼에 다른 번호가 와도 사무소 대표번호는 그대로다(바로 바꾸는 길이 따로 있다).
            @Test
            @DisplayName("변경 신청은 폼의 대표번호를 쓰지 않는다")
            void change_request_ignores_form_tel() {
                givenApproved();
                givenOwnLicense();

                service.submitRealEstateBroker("p-1", request(new BrokerageOfficeForm("99999-2026-00002",
                        "새상호공인중개사사무소", "홍길동", "서울특별시 관악구", "02-000-0009", null)));

                then(changeRequestRepository).should().save(any(BrokerageOfficeChangeRequest.class));
                assertThat(office.getTel()).isEqualTo("02-000-0000");
            }

            // 앱이 "변경 심사 중"을 그린다 — 사무소는 지금 값, 바꿀 값은 officeChange 에.
            @Test
            @DisplayName("응답은 사무소의 지금 값과 방금 낸 변경 신청을 함께 싣는다")
            void response_carries_current_office_and_pending_change() {
                givenApproved();
                givenOwnLicense();
                given(changeRequestRepository.save(any(BrokerageOfficeChangeRequest.class))).willAnswer(inv -> {
                    BrokerageOfficeChangeRequest saved = inv.getArgument(0);
                    given(changeRequestRepository.findFirstByOfficeIdOrderByIdDesc(OFFICE_ID))
                            .willReturn(Optional.of(saved));
                    return saved;
                });

                PartnerMeResponse response = service.submitRealEstateBroker("p-1", request(form("99999-2026-00009")));

                assertThat(response.brokerageOffice().regNo()).isEqualTo("99999-2026-00002");
                assertThat(response.officeChange().status()).isEqualTo(OfficeChangeRequestStatus.PENDING);
                assertThat(response.officeChange().requested().regNo()).isEqualTo("99999-2026-00009");
                assertThat(response.officeChange().requested().licenseFileUuid()).isEqualTo("f-1");
                assertThat(response.officeChange().rejectReason()).isNull();
                assertThat(response.divisions()).singleElement()
                        .satisfies(d -> assertThat(d.status()).isEqualTo(PartnerDivisionStatus.APPROVED));
            }
        }

        @Nested
        @DisplayName("등록증 교체 시 옛 파일 폐기")
        class LicenseDiscard {

            // ★ 바뀐 등록증은 바로 폐기 표시한다(PO 확정 2026-10-06) — 원장 표시만, 실제 파기는 정리 배치가 한다.
            @Test
            @DisplayName("등록증이 바뀌면 옛 파일을 폐기 표시한다")
            void replaced_license_is_discarded() {
                givenOwnLicense();
                ReflectionTestUtils.setField(office, "licenseFileId", OLD_LICENSE_ID);

                service.submitRealEstateBroker("p-1", request(form("99999-2026-00002")));

                then(fileDeletionService).should().discardByIds(List.of(OLD_LICENSE_ID));
                assertThat(office.getLicenseFileId()).isEqualTo(LICENSE_ID);
            }

            // 같은 파일을 다시 낸 것이면 지금 붙어 있는 서류다 — 버리면 심사할 등록증이 사라진다.
            @Test
            @DisplayName("같은 등록증을 다시 내면 아무것도 버리지 않는다")
            void same_license_is_kept() {
                givenOwnLicense();
                ReflectionTestUtils.setField(office, "licenseFileId", LICENSE_ID);

                service.submitRealEstateBroker("p-1", request(form("99999-2026-00002")));

                then(fileDeletionService).shouldHaveNoInteractions();
            }
        }
    }

    @Nested
    @DisplayName("대표번호 변경")
    class ChangeTel {

        /*
         * ★ 대표번호는 심사 대상이 아니다 — 부문 상태와 관계없이 즉시 바뀌고, 부문은 건드리지 않는다.
         *   부문이 바뀌면(version) 상세를 열어 둔 관리자의 심사가 무관한 변경 때문에 409 로 돌아간다.
         */
        @ParameterizedTest(name = "{0}")
        @EnumSource(PartnerDivisionStatus.class)
        @DisplayName("부문 상태와 관계없이 대표번호만 바꾸고 부문은 건드리지 않는다")
        void changes_only_tel(PartnerDivisionStatus status) {
            given(userQueryService.getActiveUser("p-1")).willReturn(partner());
            BrokerageOffice office = BrokerageOffice.builder().id(OFFICE_ID).uuid("o-1").regNo("99999-2026-00002")
                    .name("테스트공인중개사사무소").ceoName("홍길동").address("서울특별시 관악구")
                    .tel("02-000-0000").build();
            PartnerDivision division = PartnerDivision.builder().uuid("pd-1").userId(PARTNER_ID)
                    .division(PartnerDivisionType.REAL_ESTATE_BROKER).status(status)
                    .submittedAt(APPROVED_AT).version(3L).build();
            given(profileRepository.findByUserId(PARTNER_ID)).willReturn(Optional.of(
                    BrokerProfile.builder().userId(PARTNER_ID).officeId(OFFICE_ID).build()));
            given(officeRepository.findById(OFFICE_ID)).willReturn(Optional.of(office));
            given(divisionRepository.findByUserIdOrderByIdAsc(PARTNER_ID)).willReturn(List.of(division));

            PartnerMeResponse response = service.changeOfficeTel("p-1", "02-000-0009");

            assertThat(office.getTel()).isEqualTo("02-000-0009");
            assertThat(office.getName()).isEqualTo("테스트공인중개사사무소");
            assertThat(response.brokerageOffice().tel()).isEqualTo("02-000-0009");
            assertThat(division.getStatus()).isEqualTo(status);
            assertThat(division.getSubmittedAt()).isEqualTo(APPROVED_AT);
            assertThat(division.getVersion()).isEqualTo(3L);
            then(divisionRepository).should(never()).findByUserIdAndDivision(any(), any());
        }

        @Test
        @DisplayName("사무소가 없으면 PARTNER_DIVISION_NOT_FOUND 다")
        void missing_office() {
            given(userQueryService.getActiveUser("p-1")).willReturn(partner());
            given(profileRepository.findByUserId(PARTNER_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.changeOfficeTel("p-1", "02-000-0009"))
                    .extracting(PartnerServiceTest::codeOf)
                    .isEqualTo(PartnerErrorCode.PARTNER_DIVISION_NOT_FOUND);
        }
    }

    /*
     * ★★ 제출은 READ COMMITTED 다. 기본(REPEATABLE READ)이면 대기 건의 잠금 읽기가 빈 인덱스 구간에 갭 잠금을 걸어,
     *    서로 다른 두 사무소의 첫 변경 신청이 서로의 INSERT 를 막아 교착한다(MySQL 실측). 사무소당 직렬화는 사무소 행
     *    잠금이 한다. 이 선언이 빠지면 단위 테스트로는 아무것도 깨지지 않으므로 선언 자체를 고정한다.
     */
    @Test
    @DisplayName("제출 트랜잭션의 격리 수준은 READ COMMITTED 다")
    void submit_runs_in_read_committed() throws Exception {
        Transactional tx = PartnerService.class
                .getMethod("submitRealEstateBroker", String.class, RealEstateBrokerSubmitRequest.class)
                .getAnnotation(Transactional.class);

        assertThat(tx).isNotNull();
        assertThat(tx.isolation()).isEqualTo(Isolation.READ_COMMITTED);
    }

    // 파트너 화면의 API 를 유학생 · 관리자가 부르면 막는다 — 권한이 아니라 역할로 거른다.
    @Test
    @DisplayName("파트너가 아니면 제출 · 대표번호 변경 · 내 상태 모두 NOT_A_PARTNER 다")
    void non_partner_is_rejected() {
        given(userQueryService.getActiveUser("u-1")).willReturn(User.builder().id(1L).uuid("u-1")
                .role(UserRole.USER).status(UserStatus.ACTIVE).build());

        assertThatThrownBy(() -> service.getMe("u-1"))
                .extracting(PartnerServiceTest::codeOf)
                .isEqualTo(PartnerErrorCode.NOT_A_PARTNER);
        assertThatThrownBy(() -> service.submitRealEstateBroker("u-1",
                new RealEstateBrokerSubmitRequest(form("99999-2026-00002"), "f-1")))
                .extracting(PartnerServiceTest::codeOf)
                .isEqualTo(PartnerErrorCode.NOT_A_PARTNER);
        assertThatThrownBy(() -> service.changeOfficeTel("u-1", "02-000-0009"))
                .extracting(PartnerServiceTest::codeOf)
                .isEqualTo(PartnerErrorCode.NOT_A_PARTNER);
        then(divisionRepository).shouldHaveNoInteractions();
        then(officeRepository).shouldHaveNoInteractions();
    }

    // 다시 내면 대기로 돌아가는데 옛 반려 사유가 실리면 앱은 "아직 반려 상태"로 그린다.
    @Test
    @DisplayName("내 상태는 반려일 때만 반려 사유를 싣는다")
    void me_shows_reject_reason_only_while_rejected() {
        given(userQueryService.getActiveUser("p-1")).willReturn(partner());
        PartnerDivision resubmitted = PartnerDivision.builder().uuid("pd-1").userId(PARTNER_ID)
                .division(PartnerDivisionType.REAL_ESTATE_BROKER).status(PartnerDivisionStatus.PENDING)
                .submittedAt(LocalDateTime.of(2026, 10, 6, 9, 0)).rejectReason("지난번 사유").build();
        given(divisionRepository.findByUserIdOrderByIdAsc(PARTNER_ID)).willReturn(List.of(resubmitted));
        given(profileRepository.findByUserId(PARTNER_ID)).willReturn(Optional.empty());

        PartnerMeResponse me = service.getMe("p-1");

        assertThat(me.divisions()).singleElement().satisfies(d -> {
            assertThat(d.status()).isEqualTo(PartnerDivisionStatus.PENDING);
            assertThat(d.rejectReason()).isNull();
        });
        assertThat(me.brokerageOffice()).isNull();
    }

    @Test
    @DisplayName("내 상태는 마지막 승인 시각을 싣는다")
    void me_carries_last_approved_at() {
        given(userQueryService.getActiveUser("p-1")).willReturn(partner());
        PartnerDivision approved = PartnerDivision.builder().uuid("pd-1").userId(PARTNER_ID)
                .division(PartnerDivisionType.REAL_ESTATE_BROKER).status(PartnerDivisionStatus.APPROVED)
                .submittedAt(LocalDateTime.of(2026, 9, 30, 9, 0)).lastApprovedAt(APPROVED_AT).build();
        given(divisionRepository.findByUserIdOrderByIdAsc(PARTNER_ID)).willReturn(List.of(approved));
        given(profileRepository.findByUserId(PARTNER_ID)).willReturn(Optional.empty());

        PartnerMeResponse me = service.getMe("p-1");

        assertThat(me.divisions()).singleElement()
                .satisfies(d -> assertThat(d.lastApprovedAt()).isEqualTo(APPROVED_AT));
        // 사무소가 없으면 변경 신청도 찾지 않는다.
        assertThat(me.officeChange()).isNull();
        then(changeRequestRepository).shouldHaveNoInteractions();
    }

    /*
     * ★ 앱이 "변경 반려(사유)"를 그리는 근거 — 사무소의 최근 변경 신청 한 건. 반려 사유는 반려일 때만 싣는다.
     *   사무소는 지금 값 그대로다(반려는 사무소를 건드리지 않는다).
     */
    @Test
    @DisplayName("내 상태는 사무소의 최근 변경 신청을 싣고, 반려면 사유를 함께 싣는다")
    void me_carries_latest_office_change() {
        given(userQueryService.getActiveUser("p-1")).willReturn(partner());
        given(divisionRepository.findByUserIdOrderByIdAsc(PARTNER_ID)).willReturn(List.of());
        given(profileRepository.findByUserId(PARTNER_ID)).willReturn(Optional.of(
                BrokerProfile.builder().userId(PARTNER_ID).officeId(OFFICE_ID).build()));
        given(officeRepository.findById(OFFICE_ID)).willReturn(Optional.of(BrokerageOffice.builder().id(OFFICE_ID)
                .uuid("o-1").regNo("99999-2026-00002").claimedRegNo("99999-2026-00002")
                .name("테스트공인중개사사무소").ceoName("홍길동").address("서울특별시 관악구").tel("02-000-0000").build()));
        BrokerageOfficeChangeRequest rejected = BrokerageOfficeChangeRequest.builder().uuid("cr-1").officeId(OFFICE_ID)
                .requestedBy(PARTNER_ID).regNo("99999-2026-00009").name("새상호공인중개사사무소").ceoName("홍길동")
                .address("서울특별시 관악구").licenseFileId(66L).status(OfficeChangeRequestStatus.PENDING)
                .submittedAt(APPROVED_AT.plusDays(1)).build();
        rejected.reject(1L, "새 등록증의 상호가 다릅니다", APPROVED_AT.plusDays(2));
        given(changeRequestRepository.findFirstByOfficeIdOrderByIdDesc(OFFICE_ID)).willReturn(Optional.of(rejected));

        PartnerMeResponse me = service.getMe("p-1");

        assertThat(me.brokerageOffice().regNo()).isEqualTo("99999-2026-00002");
        assertThat(me.officeChange().requestUuid()).isEqualTo("cr-1");
        assertThat(me.officeChange().status()).isEqualTo(OfficeChangeRequestStatus.REJECTED);
        assertThat(me.officeChange().rejectReason()).isEqualTo("새 등록증의 상호가 다릅니다");
        assertThat(me.officeChange().reviewedAt()).isEqualTo(APPROVED_AT.plusDays(2));
        assertThat(me.officeChange().requested().regNo()).isEqualTo("99999-2026-00009");
        assertThat(me.officeChange().requested().name()).isEqualTo("새상호공인중개사사무소");
    }

    // 유학생에게 가는 대표자 휴대폰(ceoPhone)과 같은 값을 본인이 확인한다 — 저장된 암호문을 그대로 내보내면 안 된다.
    @Test
    @DisplayName("내 상태는 본인 휴대폰을 복호화해 싣는다")
    void me_carries_own_phone_decrypted() {
        given(userQueryService.getActiveUser("p-1")).willReturn(partnerWithPhone("enc-phone"));
        given(encryptor.decrypt("enc-phone")).willReturn("01000000000");
        given(divisionRepository.findByUserIdOrderByIdAsc(PARTNER_ID)).willReturn(List.of());
        given(profileRepository.findByUserId(PARTNER_ID)).willReturn(Optional.empty());

        assertThat(service.getMe("p-1").phone()).isEqualTo("01000000000");
    }

    @Test
    @DisplayName("저장된 휴대폰이 없으면 null 이고 복호화를 부르지 않는다")
    void me_without_phone_is_null() {
        given(userQueryService.getActiveUser("p-1")).willReturn(partner());
        given(divisionRepository.findByUserIdOrderByIdAsc(PARTNER_ID)).willReturn(List.of());
        given(profileRepository.findByUserId(PARTNER_ID)).willReturn(Optional.empty());

        assertThat(service.getMe("p-1").phone()).isNull();
        then(encryptor).shouldHaveNoInteractions();
    }

    // 키가 바뀌었거나 칸이 깨져도 내 정보 화면 전체가 500 이 되면 안 된다 — 그 칸만 비운다.
    @Test
    @DisplayName("복호화가 실패하면 휴대폰만 null 이고 나머지는 그대로 싣는다")
    void me_survives_decrypt_failure() {
        given(userQueryService.getActiveUser("p-1")).willReturn(partnerWithPhone("broken"));
        given(encryptor.decrypt("broken")).willThrow(new IllegalStateException("bad tag"));
        given(divisionRepository.findByUserIdOrderByIdAsc(PARTNER_ID)).willReturn(List.of());
        given(profileRepository.findByUserId(PARTNER_ID)).willReturn(Optional.empty());

        PartnerMeResponse me = service.getMe("p-1");

        assertThat(me.phone()).isNull();
        assertThat(me.name()).isEqualTo("홍길동");
        assertThat(me.email()).isEqualTo("partner@example.test");
    }
}
