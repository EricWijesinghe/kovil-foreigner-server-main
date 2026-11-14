package com.kovil.domain.verification.service;

import com.kovil.domain.country.repository.CountryRepository;
import com.kovil.domain.file.entity.StoredFile;
import com.kovil.domain.file.enums.FilePurpose;
import com.kovil.domain.file.repository.StoredFileRepository;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.service.UserQueryService;
import com.kovil.domain.verification.dto.request.VerificationSubmitRequest;
import com.kovil.domain.verification.dto.response.RequiredDocumentResponse;
import com.kovil.domain.verification.dto.response.VerificationResponse;
import com.kovil.domain.verification.entity.DocumentType;
import com.kovil.domain.verification.entity.ForeignerVerification;
import com.kovil.domain.verification.entity.ForeignerVerificationDocument;
import com.kovil.domain.verification.entity.ResidentStatus;
import com.kovil.domain.verification.exception.VerificationException;
import com.kovil.domain.verification.repository.DocumentTypeRepository;
import com.kovil.domain.verification.repository.ForeignerVerificationDocumentRepository;
import com.kovil.domain.verification.repository.ForeignerVerificationRepository;
import com.kovil.domain.verification.repository.ResidentStatusRepository;
import com.kovil.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Comparator;
import java.util.HashMap;
import java.util.stream.Collectors;

import static com.kovil.common.exception.CommonErrorCode.INVALID_REQUEST;
import static com.kovil.domain.verification.exception.VerificationErrorCode.RESIDENT_STATUS_NOT_FOUND;
import static com.kovil.domain.verification.exception.VerificationErrorCode.VERIFICATION_REQUIREMENTS_NOT_READY;
import static com.kovil.domain.verification.exception.VerificationErrorCode.VERIFICATION_DOCS_MISSING;
import static com.kovil.domain.verification.exception.VerificationErrorCode.VERIFICATION_DOC_NOT_REQUIRED;
import static com.kovil.domain.verification.exception.VerificationErrorCode.VERIFICATION_DOC_TOO_MANY;
import static com.kovil.domain.verification.exception.VerificationErrorCode.VERIFICATION_FILE_INVALID;
import static com.kovil.domain.verification.exception.VerificationErrorCode.VERIFICATION_PENDING;

@Service
@RequiredArgsConstructor
public class VerificationSubmitService {

    private final UserQueryService userQueryService;
    private final UserRepository userRepository;
    private final CountryRepository countryRepository;
    private final ResidentStatusRepository residentStatusRepository;
    private final DocumentTypeRepository documentTypeRepository;
    private final VerificationMasterQueryService masterQueryService;
    private final ForeignerVerificationRepository verificationRepository;
    private final ForeignerVerificationDocumentRepository documentRepository;
    private final StoredFileRepository storedFileRepository;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    @Transactional
    public VerificationResponse submit(String userUuid, VerificationSubmitRequest request) {
        // ★ 유저 행 잠금이 첫 문장이다(FR-309 선례 미러링). 아래 "심사 중 재제출 금지" 검사가
        //   check-then-insert 라, 연타 두 트랜잭션이 모두 검사를 통과해 PENDING 2행이 생기고
        //   INV-02(최신 행 판정)가 승인된 첫 행 대신 유령 두 번째 행을 집었다
        //   (적대적 리뷰 2026-09-02). 잠금 읽기는 읽기 뷰를 만들지 않아 후발의 검사가
        //   선발 커밋 이후를 본다. 이 앞에 일반 조회를 추가하면 다시 뚫린다.
        userRepository.findIdByUuidForUpdate(userUuid);

        User user = userQueryService.getActiveUser(userUuid);

        // FR-207 — 심사 중에는 새로 내지 못한다. 반려·승인 뒤에는 새 행으로 낼 수 있다.
        verificationRepository.findFirstByUserIdOrderByIdDesc(user.getId())
                .filter(ForeignerVerification::isPending)
                .ifPresent(pending -> {
                    throw new VerificationException(VERIFICATION_PENDING);
                });

        ResidentStatus status = residentStatusRepository.findByCodeAndActiveTrue(request.statusCode())
                .orElseThrow(() -> new VerificationException(RESIDENT_STATUS_NOT_FOUND));

        Long countryId = countryRepository
                .findByCodeAndIsActiveTrue(request.nationalityCountryCode().toUpperCase())
                .orElseThrow(() -> new VerificationException(INVALID_REQUEST))
                .getId();

        // ★ INV-03 — 요건은 코드가 아니라 매트릭스가 정한다.
        List<RequiredDocumentResponse> requirements =
                masterQueryService.findRequirements(request.statusCode(), "en");

        // ★ 요건이 비어 있으면(매트릭스 미구성·전 요건 삭제) 제출 자체를 거절한다 — 빈 요건 대비
        //   빈 제출이 통과하면 서류 0장이 PENDING 을 거쳐 승인까지 갈 수 있다. "계약 당사자의
        //   신원은 반드시 사람이 검증한 문서로 확인된다"는 제1 불변식이다(적대적 리뷰 2026-09-02).
        if (requirements.isEmpty()) {
            throw new VerificationException(VERIFICATION_REQUIREMENTS_NOT_READY);
        }

        List<VerificationSubmitRequest.SubmittedDocument> submitted =
                request.documents() == null ? List.of() : request.documents();

        validateAgainstRequirements(requirements, submitted);

        Map<String, StoredFile> filesByUuid = loadOwnedFiles(submitted, user.getId());
        Map<String, DocumentType> typesByCode = loadDocumentTypes(requirements);

        ForeignerVerification verification = verificationRepository.save(ForeignerVerification.builder()
                .userId(user.getId())
                .residentStatusId(status.getId())
                .residentStatusCode(status.getCode())
                // FR-203 — "그때 무엇을 요구했나"의 감사 축. 마스터가 바뀌어도 이 값은 안 바뀐다.
                .requiredDocumentCodes(toJson(requiredCodes(requirements)))
                .nationalityCountryId(countryId)
                .fullName(request.fullName().trim())
                .birthDate(request.birthDate())
                .build());

        // 스냅샷 순서는 업로드 순이 아니라 **요건 화면 순서**다(PO 확정 2026-08-20) —
        // 심사 화면·제출 내역의 서류 순서 = 요건 화면 순서 = 문서 유형 축 순서.
        // 같은 코드의 여러 장은 업로드 순서를 유지한다(안정 정렬). 클라이언트 정렬에 기대지 않는다.
        Map<String, Integer> requirementIndex = new HashMap<>();
        for (int i = 0; i < requirements.size(); i++) {
            requirementIndex.put(requirements.get(i).documentCode(), i);
        }
        List<VerificationSubmitRequest.SubmittedDocument> ordered = submitted.stream()
                .sorted(Comparator.comparingInt(doc ->
                        requirementIndex.getOrDefault(doc.documentCode(), Integer.MAX_VALUE)))
                .toList();

        int order = 0;
        List<ForeignerVerificationDocument> documents = new ArrayList<>();
        for (VerificationSubmitRequest.SubmittedDocument doc : ordered) {
            DocumentType type = typesByCode.get(doc.documentCode());
            documents.add(documentRepository.save(ForeignerVerificationDocument.builder()
                    .verificationId(verification.getId())
                    .documentTypeId(type.getId())
                    .documentTypeCode(type.getCode())
                    .fileId(filesByUuid.get(doc.fileUuid()).getId())
                    .sortOrder(order++)
                    .build()));
        }

        return toResponse(verification, request.nationalityCountryCode().toUpperCase(),
                documents, filesByUuid);
    }

    // FR-204 — 무엇이 빠졌는지 알려주지 않으면 사용자가 화면을 처음부터 다시 훑어야 한다.
    private void validateAgainstRequirements(
            List<RequiredDocumentResponse> requirements,
            List<VerificationSubmitRequest.SubmittedDocument> submitted) {

        Map<String, RequiredDocumentResponse> byCode = new LinkedHashMap<>();
        requirements.forEach(req -> byCode.put(req.documentCode(), req));

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (VerificationSubmitRequest.SubmittedDocument doc : submitted) {
            if (!byCode.containsKey(doc.documentCode())) {
                // 요건에 없는 코드를 조용히 무시하면 사용자는 낸 줄 알고 기다린다.
                throw new VerificationException(VERIFICATION_DOC_NOT_REQUIRED, doc.documentCode());
            }
            counts.merge(doc.documentCode(), 1, Integer::sum);
        }

        for (RequiredDocumentResponse req : requirements) {
            int count = counts.getOrDefault(req.documentCode(), 0);
            if (count > req.maxFiles()) {
                throw new VerificationException(VERIFICATION_DOC_TOO_MANY, req.documentCode());
            }
        }

        List<String> missing = requirements.stream()
                .filter(RequiredDocumentResponse::required)
                .map(RequiredDocumentResponse::documentCode)
                .filter(code -> counts.getOrDefault(code, 0) == 0)
                .toList();
        if (!missing.isEmpty()) {
            throw new MissingDocumentsException(missing);
        }
    }

    // 남이 올린 파일 uuid를 넣어 남의 여권을 자기 신청에 붙이는 것을 막는다.
    // 용도가 VERIFICATION_DOC이 아닌 파일도 거절한다 — 공개 URL이 있는 파일이 섞이면 INV-08이 깨진다.
    private Map<String, StoredFile> loadOwnedFiles(
            List<VerificationSubmitRequest.SubmittedDocument> submitted, Long userId) {

        Map<String, StoredFile> byUuid = new LinkedHashMap<>();
        Set<String> seen = new LinkedHashSet<>();
        for (VerificationSubmitRequest.SubmittedDocument doc : submitted) {
            if (!seen.add(doc.fileUuid())) {
                // 같은 파일을 여러 칸에 붙이면 uq_fvd_file 위반으로 500이 된다.
                throw new VerificationException(VERIFICATION_FILE_INVALID);
            }
            StoredFile file = storedFileRepository.findByUuidAndDeletedAtIsNull(doc.fileUuid())
                    .filter(stored -> stored.getPurpose() == FilePurpose.VERIFICATION_DOC)
                    .filter(stored -> userId.equals(stored.getUploaderId()))
                    .orElseThrow(() -> new VerificationException(VERIFICATION_FILE_INVALID));
            if (documentRepository.existsByFileId(file.getId())) {
                // 한 파일 = 한 제출물(uq_fvd_file). 반려 후 재제출 화면이 이전 fileUuid를 그대로
                // 되보내면 여기서 걸린다 — DB 유니크 위반 500이 아니라 400으로 말해 준다(AUDIT).
                throw new VerificationException(VERIFICATION_FILE_INVALID);
            }
            byUuid.put(doc.fileUuid(), file);
        }
        return byUuid;
    }

    private Map<String, DocumentType> loadDocumentTypes(List<RequiredDocumentResponse> requirements) {
        Map<String, DocumentType> byCode = new LinkedHashMap<>();
        documentTypeRepository.findAll().stream()
                .filter(type -> requiredCodes(requirements).contains(type.getCode()))
                .forEach(type -> byCode.put(type.getCode(), type));
        return byCode;
    }

    private Set<String> requiredCodes(List<RequiredDocumentResponse> requirements) {
        return requirements.stream()
                .map(RequiredDocumentResponse::documentCode)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private String toJson(Set<String> codes) {
        return jsonMapper.writeValueAsString(codes);
    }

    private VerificationResponse toResponse(
            ForeignerVerification verification, String countryCode,
            List<ForeignerVerificationDocument> documents, Map<String, StoredFile> filesByUuid) {

        Map<Long, String> uuidByFileId = new LinkedHashMap<>();
        filesByUuid.forEach((uuid, file) -> uuidByFileId.put(file.getId(), uuid));

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
                                doc.getDocumentTypeCode(), uuidByFileId.get(doc.getFileId())))
                        .toList());
    }

    // 누락 코드를 응답에 실어야 해서 전용 예외를 둔다 — 일반 예외로는 목록을 전달할 수 없다.
    public static class MissingDocumentsException extends VerificationException {
        private final transient List<String> missingDocumentCodes;

        public MissingDocumentsException(List<String> missingDocumentCodes) {
            super(VERIFICATION_DOCS_MISSING);
            this.missingDocumentCodes = List.copyOf(missingDocumentCodes);
        }

        public List<String> getMissingDocumentCodes() {
            return missingDocumentCodes;
        }
    }
}
