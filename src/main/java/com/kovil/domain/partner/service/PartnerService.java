package com.kovil.domain.partner.service;

import com.kovil.domain.auth.dto.response.SignInResponse;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.kovil.domain.listing.exception.ListingErrorCode.OFFICE_REG_NO_DUPLICATED;
import static com.kovil.domain.partner.exception.PartnerErrorCode.*;
import static com.kovil.domain.user.exception.UserErrorCode.USER_NOT_FOUND;
import static com.kovil.domain.user.exception.UserErrorCode.USER_SUSPENDED;

@Slf4j
@Service
@RequiredArgsConstructor
public class PartnerService {

    private final SignUpProcessor signUpProcessor;
    private final LocalAuthService localAuthService;
    private final TokenIssuer tokenIssuer;
    private final UserQueryService userQueryService;
    private final UserRepository userRepository;
    private final PartnerDivisionRepository divisionRepository;
    private final BrokerageOfficeRepository officeRepository;
    private final BrokerProfileRepository profileRepository;
    private final StoredFileRepository storedFileRepository;
    private final FileDeletionService fileDeletionService;
    private final BrokerageOfficeChangeRequestRepository changeRequestRepository;
    private final Clock clock;
    private final Encryptor encryptor;

    @Transactional
    public SignInResponse signUp(PartnerSignUpRequest request) {
        BrokerageOfficeForm form = request.office();

        signUpProcessor.assertUsableSignupToken(request.verificationToken());
        if (officeRepository.existsByClaimedRegNo(form.regNo())) {
            throw new ListingException(OFFICE_REG_NO_DUPLICATED);
        }

        User partner = localAuthService.registerLocal(request.verificationToken(), request.email(),
                request.password(), request.name(), request.preferredLanguage(), UserRole.PARTNER);

        BrokerageOffice office = officeRepository.save(BrokerageOffice.builder()
                .uuid(UUID.randomUUID().toString())
                .regNo(form.regNo())
                .name(form.name()).ceoName(form.ceoName())
                .address(form.address()).tel(form.tel())
                .bizNo(blankToNull(form.bizNo()))
                .build());
        profileRepository.save(BrokerProfile.builder()
                .userId(partner.getId()).officeId(office.getId())
                .build());
        divisionRepository.save(PartnerDivision.builder()
                .uuid(UUID.randomUUID().toString())
                .userId(partner.getId())
                .division(request.division())
                .officeId(office.getId())
                .status(PartnerDivisionStatus.PENDING)
                .build());

        return SignInResponse.of(tokenIssuer.issue(partner), partner);
    }

    @Transactional(readOnly = true)
    public PartnerMeResponse getMe(String userUuid) {
        return assemble(requirePartner(userUuid));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PartnerMeResponse submitRealEstateBroker(String userUuid, RealEstateBrokerSubmitRequest request) {
        User partner = requirePartner(userUuid);
        lockActiveAccount(partner.getId());
        PartnerDivision division = divisionRepository
                .findByUserIdAndDivision(partner.getId(), PartnerDivisionType.REAL_ESTATE_BROKER)
                .orElseThrow(() -> new PartnerException(PARTNER_DIVISION_NOT_FOUND));
        if (division.isApproved()) {
            return requestOfficeChange(partner, request);
        }
        division.assertEditable();

        BrokerageOffice office = requireOffice(partner.getId());
        StoredFile license = requireOwnLicense(request.licenseFileUuid(), partner.getId());

        BrokerageOfficeForm form = request.office();
        String bizNo = blankToNull(form.bizNo());

        if (officeRepository.existsByClaimedRegNoAndIdNot(form.regNo(), office.getId())) {
            throw new ListingException(OFFICE_REG_NO_DUPLICATED);
        }

        Long previousLicenseId = office.getLicenseFileId();
        office.resubmit(form.regNo(), form.name(), form.ceoName(), form.address(), form.tel(), bizNo,
                license.getId());
        division.submit(LocalDateTime.now(clock));
        if (previousLicenseId != null && !previousLicenseId.equals(license.getId())) {
            fileDeletionService.discardByIds(List.of(previousLicenseId));
        }

        return assemble(partner);
    }

    private PartnerMeResponse requestOfficeChange(User partner, RealEstateBrokerSubmitRequest request) {
        BrokerageOffice office = requireOffice(partner.getId());
        StoredFile license = requireOwnLicense(request.licenseFileUuid(), partner.getId());

        BrokerageOfficeForm form = request.office();
        String bizNo = blankToNull(form.bizNo());
        if (office.isSameForReview(form.regNo(), form.name(), form.ceoName(), form.address(), bizNo,
                license.getId())) {
            throw new PartnerException(PARTNER_REVIEW_NO_CHANGE);
        }
        if (officeRepository.existsByClaimedRegNoAndIdNot(form.regNo(), office.getId())) {
            throw new ListingException(OFFICE_REG_NO_DUPLICATED);
        }

        officeRepository.lockById(office.getId());
        assertStillRepresentative(partner.getId(), office.getId());
        Optional<BrokerageOfficeChangeRequest> pending = changeRequestRepository.findPendingForUpdate(office.getId());
        assertStillApproved(partner.getId());

        LocalDateTime now = LocalDateTime.now(clock);
        if (pending.isPresent()) {
            Long replacedLicenseId = pending.get().getLicenseFileId();
            pending.get().overwrite(form.regNo(), form.name(), form.ceoName(), form.address(), bizNo,
                    license.getId(), now);
            if (!replacedLicenseId.equals(license.getId())
                    && !replacedLicenseId.equals(office.getLicenseFileId())) {
                fileDeletionService.discardByIds(List.of(replacedLicenseId));
            }
        } else {
            changeRequestRepository.save(BrokerageOfficeChangeRequest.builder()
                    .uuid(UUID.randomUUID().toString())
                    .officeId(office.getId())
                    .requestedBy(partner.getId())
                    .regNo(form.regNo()).name(form.name()).ceoName(form.ceoName())
                    .address(form.address()).bizNo(bizNo)
                    .licenseFileId(license.getId())
                    .status(OfficeChangeRequestStatus.PENDING)
                    .submittedAt(now)
                    .build());
        }
        return assemble(partner);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PartnerMeResponse cancelOfficeChange(String userUuid) {
        User partner = requirePartner(userUuid);
        lockActiveAccount(partner.getId());
        BrokerageOffice office = requireOffice(partner.getId());

        officeRepository.lockById(office.getId());
        assertStillRepresentative(partner.getId(), office.getId());
        BrokerageOfficeChangeRequest pending = changeRequestRepository.findPendingForUpdate(office.getId())
                .orElseThrow(() -> new PartnerException(OFFICE_CHANGE_NOT_PENDING));

        pending.cancel();
        if (!pending.getLicenseFileId().equals(office.getLicenseFileId())) {
            fileDeletionService.discardByIds(List.of(pending.getLicenseFileId()));
        }

        return assemble(partner);
    }

    @Transactional
    public PartnerMeResponse changeOfficeTel(String userUuid, String tel) {
        User partner = requirePartner(userUuid);
        requireOffice(partner.getId()).changeTel(tel);
        return assemble(partner);
    }

    /*
     * 파트너 본인의 사무소 · 부문 쓰기는 **users 행부터 잡는다**(잠금 읽기). 토큰 발급(refresh_tokens → users → 부문) · 정지 ·
     * 승인 · 변경 승인 · 탈퇴 · 매물 등록(공유 잠금)이 모두 이 행을 먼저 잡으므로, 같은 파트너의 처리가 여기서 한 줄로 선다.
     * 사무소부터 잡으면 users 는 나중에(변경 신청 INSERT 의 FK 검사가 S 로) 잡히게 되어, users 를 먼저 쥐는 그 경로들과 순서가
     * 어긋난다 — 어긋난 잠금 순서는 교착의 재료다. FK 검사는 인덱스 순서대로 잠그므로, 사무소 인덱스가 앞인 INSERT 는
     * users 를 따로 먼저 잡아야 이 순서가 지켜진다(매물 등록이 그렇다).
     * 계정 상태도 이 잠금 읽기로 본다 — requirePartner 의 읽기와 이 잠금 사이에 정지 · 탈퇴가 커밋될 수 있다. 잠근 뒤로는 커밋까지
     * 바뀌지 않는다. 답은 그 직후에 들어온 요청이 받는 것과 같다(탈퇴 = USER_NOT_FOUND, 정지 = USER_SUSPENDED).
     */
    private void lockActiveAccount(Long partnerId) {
        UserStatus status = userRepository.findAuthSnapshotForUpdate(partnerId)
                .map(snapshot -> UserStatus.from(snapshot.getStatus()))
                .orElse(UserStatus.WITHDRAWN);
        if (status == UserStatus.WITHDRAWN) {
            throw new UserException(USER_NOT_FOUND);
        }
        if (status == UserStatus.SUSPENDED) {
            throw new UserException(USER_SUSPENDED);
        }
    }

    // 사무소를 잠근 뒤의 재판정 ① — 대표자 연결이 아직 이 사무소인가(탈퇴는 연결을 지운다). 연결이 없으면 부문 상세가 없는 것과 같게 답한다.
    private void assertStillRepresentative(Long partnerId, Long officeId) {
        boolean representative = profileRepository.findOfficeIdForUpdate(partnerId)
                .filter(officeId::equals)
                .isPresent();
        if (!representative) {
            throw new PartnerException(PARTNER_DIVISION_NOT_FOUND);
        }
    }

    /*
     * 사무소를 잠근 뒤의 재판정 ② — 공인중개사 부문이 아직 승인 상태인가. 정지됐으면 첫 판정(정지된 부문)과 같은 답이다.
     * 엔티티로 다시 읽지 않는다 — 영속성 컨텍스트에 이미 있는 부문은 같은 행을 다시 읽어도 처음 읽은 값을 돌려준다.
     */
    private void assertStillApproved(Long partnerId) {
        boolean approved = divisionRepository
                .findStatusForUpdate(partnerId, PartnerDivisionType.REAL_ESTATE_BROKER.name())
                .filter(PartnerDivisionStatus.APPROVED.name()::equals)
                .isPresent();
        if (!approved) {
            throw new PartnerException(PARTNER_DIVISION_NOT_EDITABLE);
        }
    }

    // 파트너 화면의 API 는 파트너만 부른다. 권한(PERM_)으로 묻지 않는 이유: 심사 중인 파트너는 권한이
    // 하나도 없는데, 바로 그 사람이 제출 · 상태 조회를 해야 한다. 역할은 DB 에서 방금 읽은 값이다.
    private User requirePartner(String userUuid) {
        User user = userQueryService.getActiveUser(userUuid);
        if (user.getRole() != UserRole.PARTNER) {
            throw new PartnerException(NOT_A_PARTNER);
        }
        return user;
    }

    // 공인중개사 부문의 상세. 가입이 함께 만들므로 없을 수 없다 — 없으면 부문이 없는 것과 같게 답한다.
    private BrokerageOffice requireOffice(Long partnerId) {
        return profileRepository.findByUserId(partnerId)
                .flatMap(profile -> officeRepository.findById(profile.getOfficeId()))
                .orElseThrow(() -> new PartnerException(PARTNER_DIVISION_NOT_FOUND));
    }

    /*
     * ★ 등록증으로 붙일 수 있는 파일 = 살아 있고 · 용도가 BROKER_LICENSE 이고 · **본인이 올린 것**.
     *   셋 중 무엇이 틀렸는지 가르지 않는다 — 가르면 남의 파일 uuid 를 찾는 탐침이 된다.
     *   본인 확인이 없으면 남의 등록증 uuid 를 넣어 남의 사무소 서류로 심사를 받을 수 있다.
     */
    private StoredFile requireOwnLicense(String fileUuid, Long partnerId) {
        return storedFileRepository.findByUuidAndDeletedAtIsNull(fileUuid)
                .filter(file -> file.getPurpose() == FilePurpose.BROKER_LICENSE)
                .filter(file -> partnerId.equals(file.getUploaderId()))
                .orElseThrow(() -> new PartnerException(PARTNER_LICENSE_FILE_INVALID));
    }

    // 사무소는 지금 값, officeChange 는 그 사무소의 최근 변경 신청 — 앱이 "변경 심사 중 · 반려(사유)"를 그린다.
    private PartnerMeResponse assemble(User partner) {
        Optional<BrokerageOffice> office = profileRepository.findByUserId(partner.getId())
                .flatMap(profile -> officeRepository.findById(profile.getOfficeId()));
        PartnerMeResponse.OfficeView officeView = office
                .map(o -> PartnerMeResponse.OfficeView.of(o, licenseUuidOf(o.getLicenseFileId())))
                .orElse(null);
        PartnerMeResponse.OfficeChangeView change = office
                .flatMap(o -> changeRequestRepository.findFirstByOfficeIdOrderByIdDesc(o.getId()))
                .map(r -> PartnerMeResponse.OfficeChangeView.of(r, licenseUuidOf(r.getLicenseFileId())))
                .orElse(null);
        return PartnerMeResponse.of(partner, phoneOf(partner),
                divisionRepository.findByUserIdOrderByIdAsc(partner.getId()), officeView, change);
    }

    private String phoneOf(User partner) {
        if (partner.getPhone() == null) {
            return null;
        }
        try {
            return encryptor.decrypt(partner.getPhone());
        } catch (RuntimeException e) {
            log.warn("[partner] 본인 휴대폰 복호화 실패 user={}", partner.getId());
            return null;
        }
    }

    private String licenseUuidOf(Long fileId) {
        if (fileId == null) {
            return null;
        }
        return storedFileRepository.findById(fileId).map(StoredFile::getUuid).orElse(null);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
