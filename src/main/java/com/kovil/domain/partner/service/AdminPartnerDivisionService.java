package com.kovil.domain.partner.service;

import com.kovil.common.dto.response.PageResponse;
import com.kovil.common.security.AuthenticatedUser;
import com.kovil.domain.file.dto.response.FileReadUrlResponse;
import com.kovil.domain.file.entity.StoredFile;
import com.kovil.domain.file.repository.StoredFileRepository;
import com.kovil.domain.file.service.FileQueryService;
import com.kovil.domain.listing.entity.BrokerProfile;
import com.kovil.domain.listing.entity.BrokerageOffice;
import com.kovil.domain.listing.enums.ListingStatus;
import com.kovil.domain.listing.exception.ListingException;
import com.kovil.domain.listing.repository.BrokerProfileRepository;
import com.kovil.domain.listing.repository.BrokerageOfficeRepository;
import com.kovil.domain.listing.repository.ListingRepository;
import com.kovil.domain.notification.enums.NotiType;
import com.kovil.domain.notification.service.NotificationPublisher;
import com.kovil.domain.partner.dto.response.AdminPartnerDivisionDetailResponse;
import com.kovil.domain.partner.dto.response.AdminPartnerDivisionSummaryResponse;
import com.kovil.domain.partner.dto.response.PartnerMeResponse;
import com.kovil.domain.partner.entity.PartnerDivision;
import com.kovil.domain.partner.enums.PartnerDivisionStatus;
import com.kovil.domain.partner.enums.PartnerDivisionType;
import com.kovil.domain.partner.exception.PartnerException;
import com.kovil.domain.partner.repository.PartnerDivisionRepository;
import com.kovil.domain.refreshtoken.service.RefreshTokenService;
import com.kovil.domain.rentalrequest.enums.RentalRequestReturnCause;
import com.kovil.domain.rentalrequest.service.RentalRequestPoolReturner;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.enums.UserStatus;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.kovil.domain.listing.exception.ListingErrorCode.OFFICE_REG_NO_DUPLICATED;
import static com.kovil.domain.partner.exception.PartnerErrorCode.PARTNER_DIVISION_NOT_FOUND;
import static com.kovil.domain.partner.exception.PartnerErrorCode.PARTNER_NOT_ACTIVE;

/*
 * 관리자의 파트너 부문 심사 — 승인 · 반려 · 정지 · 복구.
 *
 * ★★ 세션은 **정지에만** 끊는다(revokeAllFor). 정지는 권한이 줄어드는 처분이라, 끊지 않으면 낡은 토큰이
 *    수명만큼 그 부문의 기능을 열어 둔다. 승인 · 복구 · 반려는 끊지 않는다 — 늘어나는 권한은 낡은 토큰이
 *    들고 있어도 해가 없고(앱이 리프레시로 새 토큰을 받으면 열린다), 반려는 토큰에 실리는 것(승인된 부문)을
 *    바꾸지 않는다. 승인 때 끊으면 파트너가 이유도 모른 채 로그아웃된다.
 *
 * ★★ 잠금 순서는 발급 경로(RefreshTokenService)와 같은 방향이다 — 발급은 refresh_tokens → users → 부문 순으로 잠근다.
 *    · 정지: 세션을 먼저 끊고(refresh_tokens → users) 그 뒤에 부문을 고친다.
 *    · 승인 · 복구: users 행을 잠금 읽기로 보고(requireActive) 그 뒤에 부문 · 사무소를 고친다.
 *    부문을 먼저 고치면 그 행을 쥔 채 users 를 기다리고, 발급은 users 를 쥔 채 그 행을 기다려 교착이 난다.
 *    users 행 하나에서 줄을 서므로 어느 쪽이 먼저든 답이 옳다 — 발급이 먼저면 그 토큰은 심사 전 상태를 싣고,
 *    심사가 먼저면 발급이 새 상태를 읽는다. 부문 갱신은 커밋 때 나가고 @Version 이 겹친 처리를 409 로 돌려보낸다.
 *
 * ★ 승인 · 반려는 관리자가 **화면에서 본 판**(version)을 싣는다. 상세를 본 뒤 신청자가 다시 냈으면 관리자가 본 적
 *   없는 서류가 승인된다 — @Version 은 한 요청 안만 지키고 요청 사이는 지키지 못한다.
 *
 * ★ 네 처분 모두 파트너에게 알린다(푸시 + 알림함). 승인 · 복구는 세션을 끊지 않으므로 앱이 새 토큰을 받으러 갈
 *   신호가 이 알림이다. 탈퇴 자동 반려는 사람이 내린 처분이 아니라 알리지 않는다.
 *
 * ★ 정지할 때마다 그 사무소의 등록번호를 정지 이력에 남긴다(PO 확정 2026-10-07). 심사 상세 · 대기열은 신청 번호 **또는 그 사무소**로
 *   그 이력을 찾아 경고만 한다 — 막지 않는다. 사무소로도 찾는 이유: 정지됐던 사무소가 번호를 바꾼 뒤에도 자기 경고가 남아야 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminPartnerDivisionService {

    private static final List<ListingStatus> PUBLIC = List.of(ListingStatus.ACTIVE, ListingStatus.RESERVED);

    private final PartnerDivisionRepository divisionRepository;
    private final UserRepository userRepository;
    private final UserQueryService userQueryService;
    private final BrokerProfileRepository profileRepository;
    private final BrokerageOfficeRepository officeRepository;
    private final ListingRepository listingRepository;
    private final StoredFileRepository storedFileRepository;
    private final FileQueryService fileQueryService;
    private final RefreshTokenService refreshTokenService;
    private final NotificationPublisher notificationPublisher;
    private final RegNoSuspensionService regNoSuspensionService;
    private final SameRegNoApplicationFinder sameRegNoApplicationFinder;
    private final RentalRequestPoolReturner poolReturner;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PageResponse<AdminPartnerDivisionSummaryResponse> findSubmitted(
            PartnerDivisionStatus status, PartnerDivisionType division, Pageable pageable) {
        Page<PartnerDivision> page = divisionRepository.findSubmitted(status, division, pageable);
        List<Long> userIds = page.getContent().stream().map(PartnerDivision::getUserId).distinct().toList();

        // ★ 한 페이지의 사람 · 사무소를 한 번에 읽는다 — 행마다 찾으면 페이지 크기만큼 쿼리가 는다.
        Map<Long, User> users = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        Map<Long, BrokerageOffice> officesByUser = officesByUser(userIds);
        RegNoSuspensionService.SuspensionMarks marks = regNoSuspensionService.marksAmong(
                officesByUser.values().stream().map(BrokerageOffice::getRegNo).toList(),
                officesByUser.values().stream().map(BrokerageOffice::getId).toList());

        return PageResponse.from(page.map(d -> {
            BrokerageOffice office = officesByUser.get(d.getUserId());
            return AdminPartnerDivisionSummaryResponse.of(d, users.get(d.getUserId()), office,
                    office != null && marks.any(office.getId(), office.getRegNo()));
        }));
    }

    // 등록증 URL 발급은 파일 열람 규칙(FileAccessPolicy)을 그대로 지난다 — 심사 화면이라고 우회하지 않는다.
    @Transactional(readOnly = true)
    public AdminPartnerDivisionDetailResponse getDetail(String divisionUuid, AuthenticatedUser viewer) {
        PartnerDivision division = require(divisionUuid);
        User partner = userRepository.findById(division.getUserId()).orElse(null);
        BrokerageOffice office = officesByUser(List.of(division.getUserId())).get(division.getUserId());

        StoredFile license = office == null || office.getLicenseFileId() == null ? null
                : storedFileRepository.findById(office.getLicenseFileId()).orElse(null);
        return AdminPartnerDivisionDetailResponse.of(division,
                AdminPartnerDivisionSummaryResponse.PartnerView.of(partner),
                office == null ? null : PartnerMeResponse.OfficeView.of(office, license == null ? null : license.getUuid()),
                licenseUrl(license, viewer),
                office == null ? 0 : listingRepository.countByOfficeIdAndStatusIn(office.getId(), PUBLIC),
                office == null ? List.of() : sameRegNoApplicationFinder.find(office.getRegNo(), office.getId()),
                office == null ? List.of()
                        : regNoSuspensionService.historyOf(List.of(office.getRegNo()), office.getId()));
    }

    /*
     * 승인. 능력을 여는 방향이라 활성 계정에만 하고, 사무소가 그 등록번호를 잡는다(claimed_reg_no).
     * 같은 번호를 다른 사무소가 잡고 있으면 409 — 같은 번호로 여러 명이 신청할 수 있지만 승인은 한 명만이다.
     */
    @Transactional
    public AdminPartnerDivisionSummaryResponse approve(String adminUuid, String divisionUuid, Long seenVersion) {
        User admin = userQueryService.getActiveUser(adminUuid);
        PartnerDivision division = require(divisionUuid);
        division.assertVersion(seenVersion);
        division.assertReviewable();

        requireActive(division.getUserId());
        claimRegNo(division);
        division.approve(admin.getId(), LocalDateTime.now(clock));
        flushClaim();
        notifyPartner(division, NotiType.PARTNER_DIVISION_APPROVED, Map.of());
        log.info("[partner] 부문 승인 division={} user={} by={}", divisionUuid, division.getUserId(), admin.getId());
        return summaryOf(division);
    }

    // 반려 — 신청만 닫는다. 계정은 그대로 두고, 고쳐 다시 낼 수 있다. 번호는 잡지도 놓지도 않는다.
    @Transactional
    public AdminPartnerDivisionSummaryResponse reject(String adminUuid, String divisionUuid, String reason,
                                                      Long seenVersion) {
        User admin = userQueryService.getActiveUser(adminUuid);
        PartnerDivision division = require(divisionUuid);
        division.assertVersion(seenVersion);

        // 사유는 신청자에게 그대로 보인다 — 무엇을 고쳐 다시 낼지가 여기 있다. 앞뒤 공백은 다듬는다(인증 심사와 같다).
        String trimmed = reason.trim();
        division.reject(admin.getId(), trimmed, LocalDateTime.now(clock));
        notifyPartner(division, NotiType.PARTNER_DIVISION_REJECTED, Map.of("0", trimmed));
        log.info("[partner] 부문 반려 division={} user={} by={}", divisionUuid, division.getUserId(), admin.getId());
        return summaryOf(division);
    }

    /*
     * 정지. **그 사무소 공개 매물이 즉시 노출에서 빠진다** — 공개 조회가 부문 상태를 보므로 매물을 하나씩
     * 내릴 필요가 없고, 복구하면 그대로 돌아온다. 정지된 계정 · 탈퇴 계정도 정지할 수 있다(닫는 방향이다).
     * 등록번호는 놓지 않는다 — 놓으면 정지가 이어지는 동안 같은 번호의 다른 신청이 승인받을 수 있다.
     * 정지된 동안은 탈퇴도 막힌다(PartnerWithdrawalService.assertWithdrawable) — 탈퇴가 번호를 놓기 때문이다.
     * 관리자가 본 판(version)은 받지 않는다 — 닫는 방향이라 그사이의 변경과 무관하게 막아야 한다.
     * ★ 세션을 먼저 끊고(users 행 잠금이 여기서 잡힌다) 그 뒤에 부문을 고친다 — 클래스 주석의 순서.
     * ★ 그 사무소의 등록번호를 정지 이력에 남긴다 — 번호는 사무소 행을 잠그고 읽는다(suspendedOffice). 부문을 고치기 **전에**
     *   읽어야 한다: 부문을 먼저 고치면 그 갱신이 잠금 읽기 앞에서 flush 되어 부문 → 사무소 순으로 잠그게 되어, 전역 순서
     *   (refresh_tokens → users → 사무소 → 변경 신청 → 부문)를 거스른다.
     * ★ 그 사무소가 맡은 진행 중 임대 요청은 대기로 되돌리고 유학생에게 알린다(PO 확정 2026-10-06 — 맡을 사람이 사라졌다).
     *   번호를 읽으며 잡은 사무소 행 잠금 **뒤**, 부문을 고치기 **전**에 한다(같은 이유). 복구해도 되찾지 않는다.
     */
    @Transactional
    public AdminPartnerDivisionSummaryResponse suspend(String adminUuid, String divisionUuid, String reason) {
        User admin = userQueryService.getActiveUser(adminUuid);
        PartnerDivision division = require(divisionUuid);
        // 세션을 끊기 전에 먼저 거른다 — 두 번 누른 정지가 그 사람의 세션만 괜히 끊지 않게.
        division.assertSuspendable();

        cutSessions(division.getUserId());
        Optional<SuspendedOffice> office = suspendedOffice(division);
        String trimmed = reason.trim();
        LocalDateTime now = LocalDateTime.now(clock);
        office.ifPresent(o -> poolReturner.returnHeldBy(o.officeId(), RentalRequestReturnCause.DIVISION_SUSPENDED, now));
        division.suspend(admin.getId(), trimmed, now);
        office.ifPresent(o -> regNoSuspensionService.record(o.regNo(), o.officeId(), division.getId(),
                admin.getId(), trimmed, now));
        // 사유는 싣지 않는다 — 정지 사유는 관리자 내부 기록이다(파트너 응답에도 없다).
        notifyPartner(division, NotiType.PARTNER_DIVISION_SUSPENDED, Map.of());
        log.info("[partner] 부문 정지 division={} user={} by={}", divisionUuid, division.getUserId(), admin.getId());
        return summaryOf(division);
    }

    // 복구 — 승인과 같은 길(활성 계정만 · 번호 잡기). 정지 전 매물은 그대로 돌아온다.
    @Transactional
    public AdminPartnerDivisionSummaryResponse reinstate(String adminUuid, String divisionUuid) {
        User admin = userQueryService.getActiveUser(adminUuid);
        PartnerDivision division = require(divisionUuid);
        division.assertReinstatable();

        requireActive(division.getUserId());
        claimRegNo(division);
        division.reinstate(admin.getId(), LocalDateTime.now(clock));
        flushClaim();
        notifyPartner(division, NotiType.PARTNER_DIVISION_REINSTATED, Map.of());
        log.info("[partner] 부문 복구 division={} user={} by={}", divisionUuid, division.getUserId(), admin.getId());
        return summaryOf(division);
    }

    /*
     * 같은 부문이 승인 → 정지 → 복구 → 정지로 여러 번 오간다 — 중복 키에 처분 시각을 넣는다. 빼면 두 번째 정지부터
     * "이미 보낸 알림"으로 버려진다. 딥링크는 앱이 상태를 그리는 화면(GET /api/v1/partner/me)이다.
     */
    private void notifyPartner(PartnerDivision division, NotiType type, Map<String, Object> params) {
        notificationPublisher.publish(division.getUserId(), type,
                type.name() + ":division:" + division.getUuid() + ":" + division.getReviewedAt(),
                params, "/partner/me");
    }

    /*
     * 정지 이력에 남길 사무소와 번호 — 잡고 있는 번호, 없으면 신청 번호(정지는 번호를 놓지 않아 보통 둘이 같다).
     * 사무소 행을 잠그고 지금 커밋된 값을 읽는다(같은 순간 변경 승인이 번호를 옮겼으면 옮긴 번호를 남긴다).
     * ★ 사무소는 **부문 행의 연결**(office_id)로 찾는다. 대표자 연결(broker_profiles)은 탈퇴가 지우므로, 그것으로 찾으면
     *   탈퇴한 파트너의 승인 부문을 정지할 때 이력이 남지 않는다. 탈퇴로 번호가 풀렸으면 신청 번호(reg_no)가 남는다.
     * 연결이 비어 있으면(V27 전에 탈퇴해 백필할 근거가 없던 부문) 남길 번호가 없다 — 건너뛰되 로그로 남긴다.
     */
    private Optional<SuspendedOffice> suspendedOffice(PartnerDivision division) {
        if (division.getDivision() != PartnerDivisionType.REAL_ESTATE_BROKER) {
            return Optional.empty();
        }
        Long officeId = division.getOfficeId();
        if (officeId == null) {
            log.warn("[partner] 정지 이력을 남기지 못했다 — 부문에 사무소 연결이 없다 division={} user={}",
                    division.getUuid(), division.getUserId());
            return Optional.empty();
        }
        return officeRepository.findRegNoForSuspension(officeId)
                .map(regNo -> new SuspendedOffice(officeId, regNo));
    }

    private record SuspendedOffice(Long officeId, String regNo) {
    }

    // 리프레시와 살아 있는 액세스 토큰을 함께 끊는다 — users 행 잠금도 여기서 잡힌다(클래스 주석의 순서).
    private void cutSessions(Long userId) {
        refreshTokenService.revokeAllFor(userId);
    }

    /*
     * 능력을 **여는** 방향(승인 · 복구)은 활성 계정에만 한다. 정지 계정에 열어 두면 정지가 풀리는 순간
     * 아무도 의식하지 못한 채 기능이 살아나고, 탈퇴 계정에 여는 것은 의미가 없다.
     * ★ 잠금 읽기로 본다 — 지금 커밋된 상태를 읽고, users 행을 커밋까지 쥐어 발급 경로와 줄을 세운다
     *   (클래스 주석의 순서). 그래서 부문을 고치기 **전에** 불러야 한다.
     */
    private void requireActive(Long userId) {
        boolean active = userRepository.findAuthSnapshotForUpdate(userId)
                .map(snapshot -> UserStatus.from(snapshot.getStatus()) == UserStatus.ACTIVE)
                .orElse(false);
        if (!active) {
            throw new PartnerException(PARTNER_NOT_ACTIVE);
        }
    }

    /*
     * 공인중개사 부문의 승인 · 복구 = 그 사무소가 지금 등록번호를 잡는다(승인된 사무소의 번호 변경은 변경 신청 승인이
     * 옮긴다 — AdminOfficeChangeRequestService). 다른 사무소가 그 번호를 잡고 있으면 409 — 사전 검사는 순차 실행의 답이고,
     * 동시에 두 사무소를 승인하는 경합은 uq_bo_claimed_reg_no 가 잡는다(flushClaim).
     * 사무소는 가입이 함께 만드므로 없을 수 없다 — 없으면 부문이 없는 것과 같게 답한다(제출 쪽과 같다).
     */
    private void claimRegNo(PartnerDivision division) {
        if (division.getDivision() != PartnerDivisionType.REAL_ESTATE_BROKER) {
            return;
        }
        BrokerageOffice office = profileRepository.findByUserId(division.getUserId())
                .flatMap(profile -> officeRepository.findById(profile.getOfficeId()))
                .orElseThrow(() -> new PartnerException(PARTNER_DIVISION_NOT_FOUND));
        if (officeRepository.existsByClaimedRegNoAndIdNot(office.getRegNo(), office.getId())) {
            throw new ListingException(OFFICE_REG_NO_DUPLICATED);
        }
        office.claimRegNo();
    }

    /*
     * 위 사전 검사와 UPDATE 사이에 같은 번호가 먼저 잡힐 수 있다(두 관리자가 같은 번호의 두 신청을 동시에 승인).
     * 그때는 uq_bo_claimed_reg_no 가 잡는데 그대로 두면 500 이 나간다 — 순차 실행의 답(409)과 같게 번역한다.
     * 직접 flush 해야 한다 — 두면 위반이 커밋 시점에 터져 이 try 를 빠져나간다.
     */
    private void flushClaim() {
        try {
            officeRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw translateClaimRace(e);
        }
    }

    private RuntimeException translateClaimRace(DataIntegrityViolationException e) {
        if (e.getCause() instanceof ConstraintViolationException violation
                && violation.getConstraintName() != null
                && violation.getConstraintName().toLowerCase().contains("uq_bo_claimed_reg_no")) {
            return new ListingException(OFFICE_REG_NO_DUPLICATED);
        }
        return e;
    }

    private PartnerDivision require(String divisionUuid) {
        return divisionRepository.findByUuid(divisionUuid)
                .orElseThrow(() -> new PartnerException(PARTNER_DIVISION_NOT_FOUND));
    }

    private AdminPartnerDivisionSummaryResponse summaryOf(PartnerDivision division) {
        BrokerageOffice office = officesByUser(List.of(division.getUserId())).get(division.getUserId());
        return AdminPartnerDivisionSummaryResponse.of(division,
                userRepository.findById(division.getUserId()).orElse(null), office,
                office != null && regNoSuspensionService
                        .marksAmong(List.of(office.getRegNo()), List.of(office.getId()))
                        .any(office.getId(), office.getRegNo()));
    }

    // 사무소는 대표자 연결(broker_profiles)로 찾는다. 대표자가 탈퇴하면 연결이 지워져 찾을 수 없다.
    private Map<Long, BrokerageOffice> officesByUser(List<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> officeIdByUser = profileRepository.findByUserIdIn(userIds).stream()
                .collect(Collectors.toMap(BrokerProfile::getUserId, BrokerProfile::getOfficeId));
        Map<Long, BrokerageOffice> offices = officeRepository.findAllById(officeIdByUser.values()).stream()
                .collect(Collectors.toMap(BrokerageOffice::getId, Function.identity()));
        return officeIdByUser.entrySet().stream()
                .filter(entry -> offices.containsKey(entry.getValue()))
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> offices.get(entry.getValue())));
    }

    /*
     * 파일이 폐기됐으면 상세 화면 전체를 막지 않고 등록증 칸만 비운다.
     * ★ 예외를 잡아 넘기는 방식으로 짜지 않는다 — 열람 서비스는 같은 트랜잭션에 참여하므로 그 안에서
     *   던진 예외가 트랜잭션을 롤백 전용으로 표시해, 잡아도 커밋 때 UnexpectedRollbackException 이 난다.
     *   그래서 폐기 여부를 먼저 보고 살아 있는 파일만 넘긴다.
     */
    private FileReadUrlResponse licenseUrl(StoredFile license, AuthenticatedUser viewer) {
        if (license == null || license.getDeletedAt() != null) {
            return null;
        }
        return fileQueryService.issueReadUrl(license.getUuid(), viewer);
    }
}
