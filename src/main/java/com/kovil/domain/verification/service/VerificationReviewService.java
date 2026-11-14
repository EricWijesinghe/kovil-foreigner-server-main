package com.kovil.domain.verification.service;

import com.kovil.common.dto.response.PageResponse;
import com.kovil.domain.country.repository.CountryRepository;
import com.kovil.domain.file.repository.StoredFileRepository;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.exception.UserException;
import com.kovil.domain.user.repository.UserRepository;
import com.kovil.domain.user.service.UserQueryService;
import com.kovil.domain.verification.dto.response.AdminVerificationResponse;
import com.kovil.domain.verification.dto.response.VerificationResponse;
import com.kovil.domain.verification.entity.ForeignerVerification;
import com.kovil.domain.verification.entity.ForeignerVerificationDocument;
import com.kovil.domain.verification.enums.VerificationStatus;
import com.kovil.domain.verification.exception.VerificationException;
import com.kovil.domain.verification.repository.ForeignerVerificationDocumentRepository;
import com.kovil.domain.verification.repository.ForeignerVerificationRepository;
import com.kovil.domain.notification.enums.NotiType;
import com.kovil.domain.notification.service.NotificationPublisher;
import static com.kovil.domain.user.exception.UserErrorCode.USER_NOT_FOUND;

import lombok.RequiredArgsConstructor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static com.kovil.domain.verification.exception.VerificationErrorCode.VERIFICATION_EXPIRY_IN_PAST;
import static com.kovil.domain.verification.exception.VerificationErrorCode.VERIFICATION_NOT_FOUND;

// FR-205 — 심사는 사람이 전량 수행한다. OCR·자동 판독이 개입하지 않는다.
// 이 서비스에는 판정 로직이 없고, 사람이 내린 결론을 기록할 뿐이다.
@Slf4j
@Service
@RequiredArgsConstructor
public class VerificationReviewService {

    private final UserRepository userRepository;
    private final UserQueryService userQueryService;
    private final CountryRepository countryRepository;
    private final StoredFileRepository storedFileRepository;
    private final ForeignerVerificationRepository verificationRepository;
    private final ForeignerVerificationDocumentRepository documentRepository;
    private final JsonMapper jsonMapper;
    private final NotificationPublisher notificationPublisher;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PageResponse<AdminVerificationResponse> findByStatus(
            VerificationStatus status, String residentStatusCode, Pageable pageable) {
        String code = residentStatusCode == null || residentStatusCode.isBlank()
                ? null : residentStatusCode.strip();
        return PageResponse.from(
                verificationRepository.findQueue(status, code, pageable)
                        .map(this::toAdminResponse));
    }

    // 재신청 이력. **이전 반려 사유를 함께 보여주기 위한 조회다** —
    // 같은 사람이 같은 이유로 세 번째 반려되는 상황을 심사자가 알아야 한다.
    @Transactional(readOnly = true)
    public List<AdminVerificationResponse> findByUser(String userUuid) {
        // 읽기 경로다 — 탈퇴자도 찾는다. 회원 목록이 status=WITHDRAWN 을 보여주므로 같은 상세
        // 화면에서 이 조회만 404 면 화면이 반쪽으로 깨진다(적대적 리뷰 2026-09-02).
        // 유저 부재를 VERIFICATION_NOT_FOUND 로 답하면 관리자가 "신청 이력 없음"으로 오판한다.
        User applicant = userRepository.findByUuid(userUuid)
                .orElseThrow(() -> new UserException(USER_NOT_FOUND));
        return verificationRepository.findByUserIdOrderByIdDesc(applicant.getId()).stream()
                .map(this::toAdminResponse)
                .toList();
    }

    @Transactional
    public AdminVerificationResponse approve(
            String reviewerUuid, String verificationUuid, LocalDate expiresAt) {

        LocalDate today = LocalDate.now(clock);
        if (expiresAt != null && expiresAt.isBefore(today)) {
            // 이미 지난 날짜로 승인하면 승인 즉시 만료된 인증이 된다.
            throw new VerificationException(VERIFICATION_EXPIRY_IN_PAST);
        }

        User reviewer = userQueryService.getActiveUser(reviewerUuid);
        ForeignerVerification verification = getVerification(verificationUuid);

        verification.approve(reviewer.getId(), expiresAt, LocalDateTime.now(clock));

        // FR-210 — 국적은 자기 신고값이 아니라 심사값으로 확정된다.
        // 서류 판단의 근거가 되므로 사람이 확인한 값이 이긴다.
        userRepository.findById(verification.getUserId())
                .ifPresent(applicant -> applicant.changeNationality(verification.getNationalityCountryId()));

        notificationPublisher.publish(verification.getUserId(), NotiType.VERIFICATION_APPROVED,
                "VERIFICATION_APPROVED:verification:" + verification.getUuid(),
                Map.of(), "/verifications/me");

        log.info("[verification] 승인 uuid={} reviewer={} expiresAt={}",
                verificationUuid, reviewer.getId(), expiresAt);
        return toAdminResponse(verification);
    }

    @Transactional
    public AdminVerificationResponse reject(
            String reviewerUuid, String verificationUuid, String reason) {

        User reviewer = userQueryService.getActiveUser(reviewerUuid);
        ForeignerVerification verification = getVerification(verificationUuid);

        verification.reject(reviewer.getId(), reason.trim(), LocalDateTime.now(clock));

        notificationPublisher.publish(verification.getUserId(), NotiType.VERIFICATION_REJECTED,
                "VERIFICATION_REJECTED:verification:" + verification.getUuid(),
                Map.of(), "/verifications/me");

        log.info("[verification] 반려 uuid={} reviewer={}", verificationUuid, reviewer.getId());
        return toAdminResponse(verification);
    }

    // 오승인 무효화. 무효화하면 그 즉시 미인증으로 취급되어 임대 요청 게이트가 다시 닫힌다.
    @Transactional
    public AdminVerificationResponse revoke(String adminUuid, String verificationUuid, String reason) {
        User admin = userQueryService.getActiveUser(adminUuid);
        ForeignerVerification verification = getVerification(verificationUuid);

        verification.revoke(admin.getId(), reason.trim(), LocalDateTime.now(clock));

        // 조용히 미인증으로 돌아가면 유저는 임대 요청이 막히는 이유를 알 수 없다.
        notificationPublisher.publish(verification.getUserId(), NotiType.VERIFICATION_REVOKED,
                "VERIFICATION_REVOKED:verification:" + verification.getUuid(),
                Map.of(), "/verifications/me");

        log.info("[verification] 승인 무효화 uuid={} admin={}", verificationUuid, admin.getId());
        return toAdminResponse(verification);
    }

    private ForeignerVerification getVerification(String uuid) {
        return verificationRepository.findByUuid(uuid)
                .orElseThrow(() -> new VerificationException(VERIFICATION_NOT_FOUND));
    }

    private AdminVerificationResponse toAdminResponse(ForeignerVerification verification) {
        List<ForeignerVerificationDocument> documents =
                documentRepository.findByVerificationIdOrderBySortOrderAscIdAsc(verification.getId());

        return new AdminVerificationResponse(
                verification.getUuid(),
                verification.getStatus().name(),
                userRepository.findById(verification.getUserId())
                        .map(User::getUuid).orElse(null),
                verification.getFullName(),
                verification.getBirthDate(),
                countryRepository.findById(verification.getNationalityCountryId())
                        .map(country -> country.getCode()).orElse(null),
                verification.getResidentStatusCode(),
                parseCodes(verification.getRequiredDocumentCodes()),
                documents.stream()
                        .map(doc -> new VerificationResponse.VerificationDocumentResponse(
                                doc.getDocumentTypeCode(),
                                storedFileRepository.findById(doc.getFileId())
                                        .map(file -> file.getUuid()).orElse(null)))
                        .toList(),
                verification.getRejectReason(),
                verification.getExpiresAt(),
                verification.getCreatedAt(),
                verification.getReviewedAt(),
                verification.getReviewedBy() == null ? null
                        : userRepository.findById(verification.getReviewedBy())
                                .map(User::getName).orElse(null),
                (int) verificationRepository.countByUserIdAndIdLessThan(
                        verification.getUserId(), verification.getId()),
                verification.getRevokedAt(),
                verification.getRevokeReason());
    }

    // 스냅샷이 깨져도 심사 화면 전체가 죽으면 안 된다 — 그 항목만 비운다.
    private List<String> parseCodes(String json) {
        try {
            return jsonMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (RuntimeException e) {
            log.warn("[verification] required_document_codes 파싱 실패 — 빈 목록으로 응답한다");
            return List.of();
        }
    }
}
