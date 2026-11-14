package com.kovil.domain.verification.service;

import com.kovil.domain.country.repository.CountryRepository;
import com.kovil.domain.file.repository.StoredFileRepository;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.service.UserQueryService;
import com.kovil.domain.verification.dto.response.VerificationResponse;
import com.kovil.domain.verification.entity.ForeignerVerification;
import com.kovil.domain.verification.entity.ForeignerVerificationDocument;
import com.kovil.domain.verification.exception.VerificationException;
import com.kovil.domain.verification.repository.ForeignerVerificationDocumentRepository;
import com.kovil.domain.verification.repository.ForeignerVerificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static com.kovil.domain.verification.exception.VerificationErrorCode.VERIFICATION_NOT_FOUND;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VerificationQueryService {

    private final UserQueryService userQueryService;
    private final CountryRepository countryRepository;
    private final StoredFileRepository storedFileRepository;
    private final ForeignerVerificationRepository verificationRepository;
    private final ForeignerVerificationDocumentRepository documentRepository;
    private final Clock clock;

    // ★ INV-02 — 판정은 언제나 최신 행 1건이다. 과거 신청을 합산하지 않는다.
    public VerificationResponse getMyLatest(String userUuid) {
        User user = userQueryService.getActiveUser(userUuid);

        ForeignerVerification verification = verificationRepository
                .findFirstByUserIdOrderByIdDesc(user.getId())
                .orElseThrow(() -> new VerificationException(VERIFICATION_NOT_FOUND));

        return toResponse(verification);
    }

    // 임대 요청 게이트가 쓰는 판정(FR-208). 신청이 없으면 당연히 미인증이다.
    public boolean isVerified(Long userId) {
        return findEffective(userId).isPresent();
    }

    // FR-302 — 요청은 "통과시킨 인증 건"을 FK로 고정한다. 그래서 boolean이 아니라 행이 필요하다.
    public Optional<ForeignerVerification> findEffective(Long userId) {
        return verificationRepository.findFirstByUserIdOrderByIdDesc(userId)
                .filter(verification -> verification.isEffectiveOn(LocalDate.now(clock)));
    }

    private VerificationResponse toResponse(ForeignerVerification verification) {
        List<ForeignerVerificationDocument> documents =
                documentRepository.findByVerificationIdOrderBySortOrderAscIdAsc(verification.getId());

        String countryCode = countryRepository.findById(verification.getNationalityCountryId())
                .map(country -> country.getCode()).orElse(null);

        return new VerificationResponse(
                verification.getUuid(),
                verification.getStatus().name(),
                verification.getResidentStatusCode(),
                verification.getFullName(),
                verification.getBirthDate(),
                countryCode,
                verification.getRejectReason(),
                verification.getExpiresAt(),
                verification.isEffectiveOn(LocalDate.now(clock)),
                verification.getCreatedAt(),
                verification.getReviewedAt(),
                documents.stream()
                        .map(doc -> new VerificationResponse.VerificationDocumentResponse(
                                doc.getDocumentTypeCode(),
                                storedFileRepository.findById(doc.getFileId())
                                        .map(file -> file.getUuid()).orElse(null)))
                        .toList());
    }
}
