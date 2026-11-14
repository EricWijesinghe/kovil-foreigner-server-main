package com.kovil.domain.rentalrequest.service;

import com.kovil.common.dto.response.PageResponse;
import com.kovil.domain.listing.entity.BrokerProfile;
import com.kovil.domain.listing.exception.ListingException;
import com.kovil.domain.listing.repository.BrokerProfileRepository;
import com.kovil.domain.listing.repository.BrokerageOfficeRepository;
import com.kovil.domain.partner.enums.PartnerDivisionStatus;
import com.kovil.domain.partner.enums.PartnerDivisionType;
import com.kovil.domain.partner.repository.PartnerDivisionRepository;
import com.kovil.domain.region.entity.Region;
import com.kovil.domain.rentalrequest.config.RentalRequestProperties;
import com.kovil.domain.rentalrequest.dto.response.PartnerRentalRequestResponse;
import com.kovil.domain.rentalrequest.dto.response.PartnerRentalRequestSummaryResponse;
import com.kovil.domain.rentalrequest.dto.response.PartnerRentalRequestView;
import com.kovil.domain.rentalrequest.entity.RentalRequest;
import com.kovil.domain.rentalrequest.enums.RentalRequestOutcome;
import com.kovil.domain.rentalrequest.enums.RentalRequestStatus;
import com.kovil.domain.rentalrequest.exception.RentalRequestException;
import com.kovil.domain.rentalrequest.repository.RentalRequestRepository;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.user.exception.UserException;
import com.kovil.domain.user.repository.UserRepository;
import com.kovil.domain.user.service.UserQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import static com.kovil.domain.listing.exception.ListingErrorCode.BROKER_PROFILE_NOT_FOUND;
import static com.kovil.domain.rentalrequest.exception.RentalRequestErrorCode.RENTAL_REQUEST_ALREADY_CLAIMED;
import static com.kovil.domain.rentalrequest.exception.RentalRequestErrorCode.RENTAL_REQUEST_CLAIM_LIMIT;
import static com.kovil.domain.rentalrequest.exception.RentalRequestErrorCode.RENTAL_REQUEST_NOT_FOUND;
import static com.kovil.domain.rentalrequest.exception.RentalRequestErrorCode.RENTAL_REQUEST_OFFICE_NOT_ACTIVE;
import static com.kovil.domain.user.exception.UserErrorCode.USER_NOT_FOUND;
import static com.kovil.domain.user.exception.UserErrorCode.USER_SUSPENDED;

/*
 * 공인중개사 사무소의 임대 요청 처리 — 대기 목록 · 맡은 요청 · 상세 · 잡기 · 놓기 · 종료.
 *
 * 잡는 단위는 사무소(User 아님). 사무소 : 계정 = 1 : 1.
 * 남의 사무소 요청과 대기 목록에 없는 요청은 404 — uuid 의 존재를 흘리지 않음.
 * 잡기 · 놓기 · 종료는 토큰 권한만 믿지 않고 DB 로 다시 판정(계정 ACTIVE · 대표자 연결 · 부문 APPROVED).
 * 정지는 이미 나간 요청 하나를 막지 못하고, 계정 정지는 부문을 건드리지 않음.
 *
 * 락킹 순서: refresh_tokens → users → 대표자 연결 → 사무소 → 부문 → 신청자 users(공유) → 요청 행.
 * 순서를 바꾸면 교착. 같은 순서를 지키는 곳: 대기 복귀(RentalRequestPoolReturner), 탈퇴 · 정지.
 * 이유와 실측: RentalRequestRepository.findByOfficeIdAndStatus, V28.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PartnerRentalRequestService {

    private final UserQueryService userQueryService;
    private final UserRepository userRepository;
    private final BrokerProfileRepository profileRepository;
    private final BrokerageOfficeRepository officeRepository;
    private final PartnerDivisionRepository divisionRepository;
    private final RentalRequestRepository requestRepository;
    private final RentalRequestSelectionResolver selectionResolver;
    private final PartnerRentalRequestAssembler assembler;
    private final RentalRequestNotifier notifier;
    private final RentalRequestClaimHistory claimHistory;
    private final RentalRequestProperties properties;
    private final Clock clock;

    // ───────────────────────── 조회

    /*
     * 대기 목록 — 대기 중이고 신청자 계정이 살아 있는 요청, **오래 기다린 것부터**. 승인된 모든 사무소가 전국을 본다(PO 확정 2026-10-07).
     * 희망 지역(시군구 코드)을 고르면 그중 하나라도 걸리는 요청만 — 시도 · 없는 코드는 요청 등록과 같은 400 이다.
     */
    @Transactional(readOnly = true)
    public PageResponse<PartnerRentalRequestSummaryResponse> findPool(String userUuid, List<String> regionCodes,
                                                                      Pageable pageable) {
        requireRepresentative(userUuid);
        List<Long> regionIds = selectionResolver.resolveRegions(regionCodes).stream().map(Region::getId).toList();
        Page<RentalRequest> page = regionIds.isEmpty()
                ? requestRepository.findPool(pageable)
                : requestRepository.findPoolInRegions(regionIds, pageable);
        return PageResponse.from(new PageImpl<>(
                assembler.summaries(page.getContent()), pageable, page.getTotalElements()));
    }

    // 내 사무소가 맡은(맡았던) 요청 — 진행 중 · 종료 · 맡은 뒤 취소된 것. 놓은 요청은 더는 내 것이 아니라 빠진다. 최신순.
    @Transactional(readOnly = true)
    public PageResponse<PartnerRentalRequestResponse> findMine(String userUuid, RentalRequestStatus status,
                                                               Pageable pageable) {
        Long officeId = requireRepresentative(userUuid).getOfficeId();
        Page<RentalRequest> page = requestRepository.findHandledByOffice(officeId, status, pageable);
        return PageResponse.from(new PageImpl<>(
                assembler.fulls(page.getContent()), pageable, page.getTotalElements()));
    }

    // 내 사무소 건이면 전체, 대기 목록에 있는 건이면 요약. 나머지는 없음(404).
    @Transactional(readOnly = true)
    public PartnerRentalRequestView get(String userUuid, String requestUuid) {
        Long officeId = requireRepresentative(userUuid).getOfficeId();
        RentalRequest request = requestRepository.findByUuid(requestUuid)
                .orElseThrow(() -> new RentalRequestException(RENTAL_REQUEST_NOT_FOUND));
        if (officeId.equals(request.getOfficeId())) {
            return assembler.full(request);
        }
        if (request.getStatus() == RentalRequestStatus.SUBMITTED && isActiveApplicant(request.getUserId())) {
            return assembler.summary(request);
        }
        throw new RentalRequestException(RENTAL_REQUEST_NOT_FOUND);
    }

    // ───────────────────────── 잡기 · 놓기 · 종료

    /*
     * 잡기(선착순). 사무소 행 잠금 → 신청자 users(공유) → 요청 행 잠금 → [이미 내 것이면 그대로] → 진행 중 개수 < 상한 →
     * 신청자 ACTIVE → 대기 → 전이 → 이력 · 알림.
     * ★ 동시에 누른 두 사무소는 요청 행 잠금에서 줄을 서고 늦은 쪽이 RENTAL_REQUEST_ALREADY_CLAIMED 로 진다.
     * ★ 같은 사무소의 동시 잡기는 사무소 행 잠금에서 줄을 선다 — 개수와 그 앞의 신청자 조회는 모두 그 잠금을 기다린 **뒤의**
     *   일반 읽기라 읽기 뷰가 앞사람의 커밋 뒤에 만들어진다(RentalRequestRepository.countByOfficeIdAndStatus 주석).
     *   사무소 잠금 **앞에** 일반 읽기를 넣으면 상한이 뚫린다.
     * ★ 이미 내 사무소가 잡아 진행 중인 요청을 다시 잡으면 **멱등 성공**이다 — 전체 응답을 그대로 주고, 이력 · 알림 · 개수를
     *   바꾸지 않는다. 두 번 누른 잡기에 "다른 사무소가 맡았다"(409)는 거짓 안내다. 그래서 상한 · 신청자 상태보다 먼저 본다 —
     *   상한에 닿은 사무소의 두 번째 누름도, 그사이 신청자가 정지된 내 요청도 "내 것"이다(상세 조회와 같은 답).
     * ★ 신청자가 정지 · 탈퇴했으면 대기 목록에 없는 요청이다 — 없음(404)으로 답한다.
     */
    @Transactional
    public PartnerRentalRequestResponse claim(String userUuid, String requestUuid) {
        Handler handler = lockHandlingOffice(userUuid);
        Long officeId = handler.officeId();
        Long applicantId = requestRepository.findUserIdByUuid(requestUuid)
                .orElseThrow(() -> new RentalRequestException(RENTAL_REQUEST_NOT_FOUND));
        boolean applicantActive = userRepository.findStatusByIdForShare(applicantId)
                .filter(UserStatus.ACTIVE::equals).isPresent();
        RentalRequest request = lockRequest(requestUuid);

        if (request.getStatus() == RentalRequestStatus.IN_PROGRESS && officeId.equals(request.getOfficeId())) {
            log.info("[rental-request] 잡기(이미 내 것) number={} office={}", request.getRequestNumber(), officeId);
            return assembler.full(request);
        }
        if (requestRepository.countByOfficeIdAndStatus(officeId, RentalRequestStatus.IN_PROGRESS)
                >= properties.maxClaimsPerOffice()) {
            throw new RentalRequestException(RENTAL_REQUEST_CLAIM_LIMIT);
        }
        if (!applicantActive) {
            throw new RentalRequestException(RENTAL_REQUEST_NOT_FOUND);
        }

        request.claim(officeId, LocalDateTime.now(clock));
        flushClaim();
        claimHistory.claimed(request, officeId, handler.partnerId(), request.getClaimedAt());
        notifier.claimed(request, officeRepository.findById(officeId)
                .orElseThrow(() -> new ListingException(BROKER_PROFILE_NOT_FOUND)).getName());
        log.info("[rental-request] 잡기 number={} office={}", request.getRequestNumber(), officeId);
        return assembler.full(request);
    }

    // 놓기 → 다시 대기. 다른 사무소가 잡을 수 있고, 유학생에게 "다시 대기" 알림이 간다. 응답은 이제 대기 건의 요약이다.
    @Transactional
    public PartnerRentalRequestSummaryResponse release(String userUuid, String requestUuid) {
        Handler handler = lockHandlingOffice(userUuid);
        Long officeId = handler.officeId();
        RentalRequest request = lockApplicantThenRequest(requestUuid);

        LocalDateTime now = LocalDateTime.now(clock);
        request.release(officeId);
        claimHistory.released(request, officeId, handler.partnerId(), now);
        notifier.released(request, now);
        log.info("[rental-request] 놓기 number={} office={}", request.getRequestNumber(), officeId);
        return assembler.summary(request);
    }

    // 종료 — 결과를 한 번 고른다. 유학생에게는 결과 없이 "종료" 알림이 간다. 휴대폰은 이제 양쪽 다 가려진다.
    @Transactional
    public PartnerRentalRequestResponse close(String userUuid, String requestUuid, RentalRequestOutcome outcome) {
        Handler handler = lockHandlingOffice(userUuid);
        Long officeId = handler.officeId();
        RentalRequest request = lockApplicantThenRequest(requestUuid);

        request.close(officeId, outcome, LocalDateTime.now(clock));
        claimHistory.closed(request, officeId, handler.partnerId(), outcome, request.getClosedAt());
        notifier.closed(request);
        log.info("[rental-request] 종료 number={} office={} outcome={}", request.getRequestNumber(), officeId, outcome);
        return assembler.full(request);
    }

    // ───────────────────────── 판정

    // 조회용 — 계정 ACTIVE(관문)와 대표자 연결. 부문은 토큰의 권한이 본다.
    private BrokerProfile requireRepresentative(String userUuid) {
        User partner = userQueryService.getActiveUser(userUuid);
        return profileRepository.findByUserId(partner.getId())
                .orElseThrow(() -> new ListingException(BROKER_PROFILE_NOT_FOUND));
    }

    /*
     * 쓰기용 — DB 로 다시 판정하고 사무소 행을 잠근다. 전부 잠금 읽기라 읽기 뷰를 만들지 않는다(클래스 주석의 순서).
     *   ① 계정 ACTIVE — 탈퇴는 USER_NOT_FOUND, 정지는 USER_SUSPENDED(관문 getActiveUser 와 같은 답).
     *   ② 대표자 연결 → 사무소 — 없으면 BROKER_PROFILE_NOT_FOUND(매물 관문과 같은 답).
     *   ③ 사무소 행 잠금 — 같은 사무소의 잡기 · 놓기 · 종료와 대기 복귀 처리가 여기서 줄을 선다.
     *   ④ 공인중개사 부문 APPROVED — 아니면 RENTAL_REQUEST_OFFICE_NOT_ACTIVE.
     * 반환은 파트너 id(잡기 이력의 행위자)와 사무소 id.
     */
    private Handler lockHandlingOffice(String userUuid) {
        Long partnerId = userRepository.findIdByUuidForUpdate(userUuid)
                .orElseThrow(() -> new UserException(USER_NOT_FOUND));
        UserStatus status = userRepository.findAuthSnapshotForUpdate(partnerId)
                .map(snapshot -> UserStatus.from(snapshot.getStatus()))
                .orElse(UserStatus.WITHDRAWN);
        if (status == UserStatus.SUSPENDED) {
            throw new UserException(USER_SUSPENDED);
        }
        if (status != UserStatus.ACTIVE) {
            throw new UserException(USER_NOT_FOUND);
        }
        Long officeId = profileRepository.findOfficeIdForUpdate(partnerId)
                .orElseThrow(() -> new ListingException(BROKER_PROFILE_NOT_FOUND));
        officeRepository.lockById(officeId);
        boolean approved = divisionRepository
                .findStatusForUpdate(partnerId, PartnerDivisionType.REAL_ESTATE_BROKER.name())
                .filter(PartnerDivisionStatus.APPROVED.name()::equals)
                .isPresent();
        if (!approved) {
            throw new RentalRequestException(RENTAL_REQUEST_OFFICE_NOT_ACTIVE);
        }
        return new Handler(partnerId, officeId);
    }

    // 쓰기를 하는 사람과 그 사람의 사무소 — 둘 다 잠금 읽기로 다시 본 값이다.
    private record Handler(Long partnerId, Long officeId) {
    }

    // 놓기 · 종료 — 신청자 users 행(공유)부터 잡고 요청 행을 잠근다(클래스 주석). 신청자 상태는 묻지 않는다 — 맡은 요청을 접는 길이다.
    private RentalRequest lockApplicantThenRequest(String requestUuid) {
        Long applicantId = requestRepository.findUserIdByUuid(requestUuid)
                .orElseThrow(() -> new RentalRequestException(RENTAL_REQUEST_NOT_FOUND));
        userRepository.findStatusByIdForShare(applicantId);
        return lockRequest(requestUuid);
    }

    private RentalRequest lockRequest(String requestUuid) {
        return requestRepository.findByUuidForUpdate(requestUuid)
                .orElseThrow(() -> new RentalRequestException(RENTAL_REQUEST_NOT_FOUND));
    }

    /*
     * 잡기의 UPDATE 를 여기서 내보낸다 — @Version 이 마지막 방어선이다. 요청 행을 잠금 읽기로 쥐고 있어 보통은 실패할 수 없지만,
     * 실패하면 커밋 시점에 일반 409(CONFLICT)로 나가지 않게 선착순의 답으로 번역한다. 알림을 넣기 **전에** 내보낸다.
     */
    private void flushClaim() {
        try {
            requestRepository.flush();
        } catch (OptimisticLockingFailureException e) {
            throw new RentalRequestException(RENTAL_REQUEST_ALREADY_CLAIMED);
        }
    }

    private boolean isActiveApplicant(Long userId) {
        return userRepository.findById(userId).map(User::isActive).orElse(false);
    }
}
