package com.kovil.domain.partner.service;

import com.kovil.common.dto.response.PageResponse;
import com.kovil.common.security.AuthenticatedUser;
import com.kovil.domain.file.dto.response.FileReadUrlResponse;
import com.kovil.domain.file.entity.StoredFile;
import com.kovil.domain.file.repository.StoredFileRepository;
import com.kovil.domain.file.service.FileDeletionService;
import com.kovil.domain.file.service.FileQueryService;
import com.kovil.domain.listing.entity.BrokerageOffice;
import com.kovil.domain.listing.exception.ListingException;
import com.kovil.domain.listing.repository.BrokerProfileRepository;
import com.kovil.domain.listing.repository.BrokerageOfficeRepository;
import com.kovil.domain.notification.enums.NotiType;
import com.kovil.domain.notification.service.NotificationPublisher;
import com.kovil.domain.partner.dto.response.AdminOfficeChangeRequestDetailResponse;
import com.kovil.domain.partner.dto.response.AdminOfficeChangeRequestSummaryResponse;
import com.kovil.domain.partner.dto.response.AdminPartnerDivisionSummaryResponse;
import com.kovil.domain.partner.dto.response.OfficeChangeValues;
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
import com.kovil.domain.user.repository.UserRepository;
import com.kovil.domain.user.service.UserQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.kovil.domain.listing.exception.ListingErrorCode.OFFICE_REG_NO_DUPLICATED;
import static com.kovil.domain.partner.exception.PartnerErrorCode.OFFICE_CHANGE_DIVISION_NOT_APPROVED;
import static com.kovil.domain.partner.exception.PartnerErrorCode.OFFICE_CHANGE_REQUEST_NOT_FOUND;

/*
 * 관리자의 사무소 정보 변경 심사 — 승인 · 반려(PO 확정 2026-10-07).
 *
 * ★ 승인 = 바꿀 값 한 벌을 사무소에 적용한다. 번호가 바뀌면 새 번호를 잡고 옛 번호를 놓는다(같은 칸을 덮는다).
 *   반려 = 사무소 그대로 — 옛 번호도 계속 잡는다. 그 사무소는 옛 번호로 승인받아 영업 중이다. 번호가 풀리는 것은
 *   대표자 탈퇴와 변경 승인(새 번호로 옮김)뿐이다.
 * ★ 승인 · 반려에는 관리자가 화면에서 본 판(version)을 싣는다 — 상세를 본 뒤 신청자가 덮어썼으면 본 적 없는 값이 승인된다.
 * ★ 승인은 그 사무소의 공인중개사 부문이 승인 상태일 때만 한다. 정지된 사무소의 번호를 옮기면 정지가 다른 번호로 샌다.
 *   반려는 부문 상태와 관계없이 된다(닫는 방향이다).
 * ★★ 잠금 순서는 refresh_tokens → users → 사무소 → 변경 신청 → 부문이다(토큰 발급과 같은 방향). 승인 · 반려는 신청자의
 *    users 행부터 잡는다(lockRequester) — 변경 신청 제출 · 탈퇴도 users 를 먼저 잡고 사무소를 잠그며, 정지는 세션을 끊으며
 *    users 를 잡은 뒤 사무소 번호를 잠금 읽기로 읽고 부문을 고친다. 순서가 어긋나면 서로의 행을 쥔 채 기다려 교착한다.
 *    ⚠ INSERT 의 FK 검사도 이 순서에 든다 — InnoDB 는 인덱스 순서대로 부모 행을 S 로 잡는다. 매물 INSERT 는 사무소가
 *      작성자보다 앞이라, 매물 등록은 작성자 users 행을 먼저 공유 잠금으로 잡는다(BrokerListingService.create). 사무소와
 *      users 를 함께 참조하는 INSERT 를 새로 두면 그 인덱스 순서를 같은 기준으로 본다.
 * ★ 세션은 끊지 않는다 — 부문이 바뀌지 않아 토큰에 실린 것이 그대로다.
 * ★ 사무소 행에는 @Version 이 있다 — 같은 순간의 대표번호 변경과 겹치면 늦은 쪽이 409 CONFLICT 로 돌아간다
 *   (어느 쪽도 상대가 쓴 칸을 낡은 값으로 덮지 않는다). 승인이 그 충돌로 지면 알림도 나가지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminOfficeChangeRequestService {

    private final BrokerageOfficeChangeRequestRepository changeRequestRepository;
    private final BrokerageOfficeRepository officeRepository;
    private final BrokerProfileRepository profileRepository;
    private final PartnerDivisionRepository divisionRepository;
    private final UserRepository userRepository;
    private final UserQueryService userQueryService;
    private final StoredFileRepository storedFileRepository;
    private final FileQueryService fileQueryService;
    private final FileDeletionService fileDeletionService;
    private final RegNoSuspensionService regNoSuspensionService;
    private final SameRegNoApplicationFinder sameRegNoApplicationFinder;
    private final NotificationPublisher notificationPublisher;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PageResponse<AdminOfficeChangeRequestSummaryResponse> findQueue(OfficeChangeRequestStatus status,
                                                                           Pageable pageable) {
        Page<BrokerageOfficeChangeRequest> page = changeRequestRepository.findQueue(status, pageable);
        List<BrokerageOfficeChangeRequest> rows = page.getContent();

        // ★ 한 페이지의 사람 · 사무소 · 경고를 한 번에 읽는다 — 행마다 찾으면 페이지 크기만큼 쿼리가 는다.
        Map<Long, User> users = userRepository
                .findAllById(rows.stream().map(BrokerageOfficeChangeRequest::getRequestedBy).distinct().toList())
                .stream().collect(Collectors.toMap(User::getId, Function.identity()));
        Map<Long, BrokerageOffice> offices = officeRepository
                .findAllById(rows.stream().map(BrokerageOfficeChangeRequest::getOfficeId).distinct().toList())
                .stream().collect(Collectors.toMap(BrokerageOffice::getId, Function.identity()));
        Set<String> numbers = new LinkedHashSet<>();
        rows.forEach(r -> numbers.add(r.getRegNo()));
        offices.values().forEach(office -> numbers.addAll(currentNumbersOf(office)));
        RegNoSuspensionService.SuspensionMarks marks =
                regNoSuspensionService.marksAmong(List.copyOf(numbers), List.copyOf(offices.keySet()));

        return PageResponse.from(page.map(r -> {
            BrokerageOffice office = offices.get(r.getOfficeId());
            return AdminOfficeChangeRequestSummaryResponse.of(r, users.get(r.getRequestedBy()), office,
                    suspendedBefore(marks, r, office));
        }));
    }

    /*
     * 지금 값과 바꿀 값을 나란히 준다. 새 등록증 URL 은 파일 열람 규칙(FileAccessPolicy)을 그대로 지난다.
     * 정지 이력은 **바꿀 번호 · 지금 번호 · 이 사무소** 모두로 찾는다 — 승인되면 바꿀 번호를 이 사무소가 쓰고, 지금 번호와
     * 이 사무소 자신의 정지는 번호를 바꿔도 따라가야 할 이력이다. 화면은 officeUuid 로 "이 사무소 자신의 정지"를 가른다.
     * 같은 번호의 다른 신청은 **바꿀 번호**로 찾는다 — 승인되면 이 사무소가 잡을 번호라, 그 번호를 잡고 있거나 노리는 신청이 판단 근거다.
     */
    @Transactional(readOnly = true)
    public AdminOfficeChangeRequestDetailResponse getDetail(String requestUuid, AuthenticatedUser viewer) {
        BrokerageOfficeChangeRequest request = require(requestUuid);
        User partner = userRepository.findById(request.getRequestedBy()).orElse(null);
        PartnerDivisionStatus divisionStatus = divisionRepository
                .findByUserIdAndDivision(request.getRequestedBy(), PartnerDivisionType.REAL_ESTATE_BROKER)
                .map(PartnerDivision::getStatus)
                .orElse(null);
        BrokerageOffice office = officeRepository.findById(request.getOfficeId()).orElse(null);
        StoredFile newLicense = storedFileRepository.findById(request.getLicenseFileId()).orElse(null);

        return AdminOfficeChangeRequestDetailResponse.of(request,
                AdminPartnerDivisionSummaryResponse.PartnerView.of(partner),
                divisionStatus,
                office == null ? null : PartnerMeResponse.OfficeView.of(office, fileUuidOf(office.getLicenseFileId())),
                OfficeChangeValues.of(request, newLicense == null ? null : newLicense.getUuid()),
                licenseUrl(newLicense, viewer),
                sameRegNoApplicationFinder.find(request.getRegNo(), request.getOfficeId()),
                regNoSuspensionService.historyOf(reviewNumbersOf(request, office), request.getOfficeId()));
    }

    /*
     * 승인 — 바꿀 값을 사무소에 적용하고, 번호가 바뀌면 새 번호를 잡는다(옛 번호는 같은 칸을 덮으며 놓인다).
     * 다른 사무소가 새 번호를 잡고 있으면 409 — 사전 검사는 순차 실행의 답이고, 같은 순간 그 번호를 잡는 다른 승인과의
     * 경합은 uq_bo_claimed_reg_no 가 잡는다(flushClaim). 옛 등록증은 폐기 표시한다(새 등록증이 같은 파일이면 둔다).
     */
    @Transactional
    public AdminOfficeChangeRequestSummaryResponse approve(String adminUuid, String requestUuid, Long seenVersion) {
        User admin = userQueryService.getActiveUser(adminUuid);
        BrokerageOfficeChangeRequest request = require(requestUuid);
        request.assertVersion(seenVersion);
        request.assertReviewable();

        BrokerageOffice office = requireOffice(request);
        lockRequester(request);
        officeRepository.lockById(office.getId());
        requireApprovedDivision(request, office);
        if (officeRepository.existsByClaimedRegNoAndIdNot(request.getRegNo(), office.getId())) {
            throw new ListingException(OFFICE_REG_NO_DUPLICATED);
        }

        Long previousLicenseId = office.getLicenseFileId();
        office.applyApprovedChange(request.getRegNo(), request.getName(), request.getCeoName(),
                request.getAddress(), request.getBizNo(), request.getLicenseFileId());
        request.approve(admin.getId(), LocalDateTime.now(clock));
        flushClaim();
        if (previousLicenseId != null && !previousLicenseId.equals(request.getLicenseFileId())) {
            fileDeletionService.discardByIds(List.of(previousLicenseId));
        }
        notifyPartner(request, NotiType.OFFICE_CHANGE_APPROVED, Map.of());
        log.info("[partner] 사무소 변경 승인 request={} office={} by={}", requestUuid, office.getId(), admin.getId());
        return summaryOf(request, office);
    }

    /*
     * 반려 — 변경 신청만 닫는다. 사무소는 그대로(옛 번호도 그대로 잡는다). 변경 신청의 등록증은 폐기 표시하되, 사무소가
     * 지금 쓰는 파일이면 둔다 — 지금 등록증을 그대로 내고 상호만 바꾼 신청이 반려되면 그 파일이 사무소의 서류다.
     * 사유는 신청자에게 그대로 보인다 — 앞뒤 공백은 다듬는다(부문 심사와 같다).
     */
    @Transactional
    public AdminOfficeChangeRequestSummaryResponse reject(String adminUuid, String requestUuid, String reason,
                                                          Long seenVersion) {
        User admin = userQueryService.getActiveUser(adminUuid);
        BrokerageOfficeChangeRequest request = require(requestUuid);
        request.assertVersion(seenVersion);
        lockRequester(request);

        String trimmed = reason.trim();
        request.reject(admin.getId(), trimmed, LocalDateTime.now(clock));
        BrokerageOffice office = requireOffice(request);
        if (!request.getLicenseFileId().equals(office.getLicenseFileId())) {
            fileDeletionService.discardByIds(List.of(request.getLicenseFileId()));
        }
        notifyPartner(request, NotiType.OFFICE_CHANGE_REJECTED, Map.of("0", trimmed));
        log.info("[partner] 사무소 변경 반려 request={} office={} by={}", requestUuid, office.getId(), admin.getId());
        return summaryOf(request, office);
    }

    /*
     * 신청자의 users 행을 **사무소보다 먼저** 잠근다(잠금 읽기). 그 뒤의 알림 INSERT 가 users 를 FK 검사로(S) 잡는데,
     * 사무소 · 부문을 쥔 채 그때 처음 users 를 기다리면 users → 부문 순으로 잡는 토큰 발급과 교착한다(MySQL 재현).
     * 먼저 잡으면 같은 파트너의 발급 · 정지 · 탈퇴 · 변경 신청 제출 · 매물 등록과 이 행 하나에서 줄을 선다.
     * 상태는 묻지 않는다 — 승인의 조건은 부문 상태(requireApprovedDivision)이고, 반려는 닫는 방향이라 상태와 관계없다.
     */
    private void lockRequester(BrokerageOfficeChangeRequest request) {
        userRepository.findAuthSnapshotForUpdate(request.getRequestedBy());
    }

    /*
     * 승인할 수 있는 사무소인가 = 신청자가 아직 그 사무소의 대표자이고, 그 사람의 공인중개사 부문이 **지금** 승인 상태다.
     * 둘 다 잠금 읽기로 본다(사무소를 잠근 뒤) — 일반 읽기는 스냅숏이라 같은 순간 커밋된 탈퇴(연결 삭제) · 정지를 못 본다.
     * 대표자가 떠났으면(탈퇴 정리가 대기 건을 닫으므로 정상 경로로는 없다) 승인할 사무소가 아니다.
     */
    private void requireApprovedDivision(BrokerageOfficeChangeRequest request, BrokerageOffice office) {
        boolean representative = profileRepository.findOfficeIdForUpdate(request.getRequestedBy())
                .filter(office.getId()::equals)
                .isPresent();
        boolean approved = representative && divisionRepository
                .findStatusForUpdate(request.getRequestedBy(), PartnerDivisionType.REAL_ESTATE_BROKER.name())
                .filter(PartnerDivisionStatus.APPROVED.name()::equals)
                .isPresent();
        if (!approved) {
            throw new PartnerException(OFFICE_CHANGE_DIVISION_NOT_APPROVED);
        }
    }

    /*
     * 사전 검사와 UPDATE 사이에 같은 번호가 먼저 잡힐 수 있다(같은 번호의 다른 승인 · 변경 승인이 동시에). 그때는
     * uq_bo_claimed_reg_no 가 잡는데 그대로 두면 500 이 나간다 — 순차 실행의 답(409)과 같게 번역한다.
     * 직접 flush 해야 한다 — 두면 위반이 커밋 시점에 터져 이 try 를 빠져나간다. 사무소 · 변경 신청의 낙관적 락 충돌은
     * 번역하지 않는다(그대로 올라가 409 CONFLICT — 상세를 다시 연다).
     */
    private void flushClaim() {
        try {
            officeRepository.flush();
        } catch (DataIntegrityViolationException e) {
            if (e.getCause() instanceof ConstraintViolationException violation
                    && violation.getConstraintName() != null
                    && violation.getConstraintName().toLowerCase().contains("uq_bo_claimed_reg_no")) {
                throw new ListingException(OFFICE_REG_NO_DUPLICATED);
            }
            throw e;
        }
    }

    // 한 변경 신청은 한 번만 판정되지만, 부문 심사 알림과 같은 모양으로 처리 시각을 중복 키에 넣는다.
    private void notifyPartner(BrokerageOfficeChangeRequest request, NotiType type, Map<String, Object> params) {
        notificationPublisher.publish(request.getRequestedBy(), type,
                type.name() + ":office-change:" + request.getUuid() + ":" + request.getReviewedAt(),
                params, "/partner/me");
    }

    private BrokerageOfficeChangeRequest require(String requestUuid) {
        return changeRequestRepository.findByUuid(requestUuid)
                .orElseThrow(() -> new PartnerException(OFFICE_CHANGE_REQUEST_NOT_FOUND));
    }

    // 사무소 행은 지워지지 않는다(FK RESTRICT) — 없으면 변경 신청이 없는 것과 같게 답한다.
    private BrokerageOffice requireOffice(BrokerageOfficeChangeRequest request) {
        return officeRepository.findById(request.getOfficeId())
                .orElseThrow(() -> new PartnerException(OFFICE_CHANGE_REQUEST_NOT_FOUND));
    }

    private AdminOfficeChangeRequestSummaryResponse summaryOf(BrokerageOfficeChangeRequest request,
                                                              BrokerageOffice office) {
        List<String> numbers = reviewNumbersOf(request, office);
        return AdminOfficeChangeRequestSummaryResponse.of(request,
                userRepository.findById(request.getRequestedBy()).orElse(null), office,
                suspendedBefore(regNoSuspensionService.marksAmong(numbers, List.of(request.getOfficeId())),
                        request, office));
    }

    // 심사 대상 번호 — 바꿀 번호와 그 사무소의 지금 번호(신청 번호 · 잡고 있는 번호). 겹치는 값은 한 번만 준다.
    private static List<String> reviewNumbersOf(BrokerageOfficeChangeRequest request, BrokerageOffice office) {
        Set<String> numbers = new LinkedHashSet<>();
        numbers.add(request.getRegNo());
        if (office != null) {
            numbers.addAll(currentNumbersOf(office));
        }
        return List.copyOf(numbers);
    }

    /*
     * 사무소의 지금 번호 — 신청 번호와 잡고 있는 번호. 승인된 사무소는 둘이 같지만, 변경 신청이 생기기 전에 승인 뒤 번호를
     * 고쳐 낸 사무소는 다를 수 있다(옛 번호를 잡은 채 영업 중이다). 비어 있는 값은 빼고 준다.
     */
    private static List<String> currentNumbersOf(BrokerageOffice office) {
        List<String> numbers = new ArrayList<>();
        if (office.getRegNo() != null) {
            numbers.add(office.getRegNo());
        }
        if (office.getClaimedRegNo() != null) {
            numbers.add(office.getClaimedRegNo());
        }
        return numbers;
    }

    private static boolean suspendedBefore(RegNoSuspensionService.SuspensionMarks marks,
                                           BrokerageOfficeChangeRequest request, BrokerageOffice office) {
        List<String> numbers = reviewNumbersOf(request, office);
        return marks.any(request.getOfficeId(), numbers.toArray(String[]::new));
    }

    private String fileUuidOf(Long fileId) {
        if (fileId == null) {
            return null;
        }
        return storedFileRepository.findById(fileId).map(StoredFile::getUuid).orElse(null);
    }

    /*
     * 폐기된 파일이면 등록증 칸만 비운다(반려 · 탈퇴로 닫힌 건).
     * ★ 예외를 잡아 넘기는 방식으로 짜지 않는다 — 열람 서비스는 같은 트랜잭션에 참여하므로 그 안에서 던진 예외가
     *   트랜잭션을 롤백 전용으로 표시해, 잡아도 커밋 때 UnexpectedRollbackException 이 난다(부문 심사 상세와 같다).
     */
    private FileReadUrlResponse licenseUrl(StoredFile license, AuthenticatedUser viewer) {
        if (license == null || license.getDeletedAt() != null) {
            return null;
        }
        return fileQueryService.issueReadUrl(license.getUuid(), viewer);
    }
}
