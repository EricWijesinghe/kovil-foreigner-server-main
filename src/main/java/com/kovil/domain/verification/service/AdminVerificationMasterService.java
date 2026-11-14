package com.kovil.domain.verification.service;

import com.kovil.common.dto.request.MasterOrderRequest;
import com.kovil.common.exception.MasterException;
import com.kovil.common.master.MasterOrderPolicy;
import com.kovil.domain.verification.dto.request.AdminDocumentTypeRequest;
import com.kovil.domain.verification.dto.request.AdminMatrixRowRequest;
import com.kovil.domain.verification.dto.request.AdminResidentStatusRequest;
import com.kovil.domain.verification.dto.response.AdminVerificationMasterResponse.DocumentRow;
import com.kovil.domain.verification.dto.response.AdminVerificationMasterResponse.MatrixRow;
import com.kovil.domain.verification.dto.response.AdminVerificationMasterResponse.StatusRow;
import com.kovil.domain.verification.entity.DocumentType;
import com.kovil.domain.verification.entity.DocumentTypeTranslation;
import com.kovil.domain.verification.entity.ResidentStatus;
import com.kovil.domain.verification.entity.ResidentStatusDocument;
import com.kovil.domain.verification.entity.ResidentStatusTranslation;
import com.kovil.domain.verification.repository.DocumentTypeRepository;
import com.kovil.domain.verification.repository.DocumentTypeTranslationRepository;
import com.kovil.domain.verification.repository.ResidentStatusDocumentRepository;
import com.kovil.domain.verification.repository.ResidentStatusRepository;
import com.kovil.domain.verification.repository.ResidentStatusTranslationRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.kovil.common.exception.MasterErrorCode.MASTER_DUPLICATE;
import static com.kovil.common.exception.MasterErrorCode.MASTER_NOT_FOUND;
import static com.kovil.common.exception.MasterErrorCode.MASTER_REFERENCE_NOT_FOUND;

// 매트릭스 변경은 새 제출부터다 — 제출된 인증은 required_document_codes 스냅샷을 따른다.
@Service
@RequiredArgsConstructor
public class AdminVerificationMasterService {

    private final ResidentStatusRepository statusRepository;
    private final ResidentStatusTranslationRepository statusTranslationRepository;
    private final DocumentTypeRepository documentRepository;
    private final DocumentTypeTranslationRepository documentTranslationRepository;
    private final ResidentStatusDocumentRepository matrixRepository;
    private final EntityManager entityManager;

    @Transactional(readOnly = true)
    public List<StatusRow> findStatuses() {
        List<ResidentStatus> statuses = statusRepository.findAllByOrderBySortOrderAscIdAsc();
        Map<Long, List<ResidentStatusTranslation>> translations = statusTranslationRepository
                .findByResidentStatusIdIn(statuses.stream().map(ResidentStatus::getId).toList())
                .stream().collect(Collectors.groupingBy(ResidentStatusTranslation::getResidentStatusId));
        return statuses.stream().map(status -> new StatusRow(
                status.getId(), status.getCode(), status.getIconKey(),
                status.getSortOrder(), status.getActive(),
                names(translations.get(status.getId()), ResidentStatusTranslation::getLanguageCode,
                        ResidentStatusTranslation::getName),
                names(translations.get(status.getId()), ResidentStatusTranslation::getLanguageCode,
                        ResidentStatusTranslation::getDescription)))
                .toList();
    }

    @Transactional
    public void createStatus(AdminResidentStatusRequest request) {
        String code = request.code().strip().toUpperCase();
        if (statusRepository.existsByCode(code)) {
            throw new MasterException(MASTER_DUPLICATE);
        }
        ResidentStatus status = statusRepository.save(ResidentStatus.builder()
                .code(code).iconKey(request.iconKey())
                .sortOrder(request.sortOrder() == null ? statusRepository.maxSortOrder() + 1 : request.sortOrder())
                .active(request.active() == null || request.active())
                .build());
        replaceStatusTranslations(status.getId(), request.translations());
    }

    @Transactional
    public void updateStatus(Long id, AdminResidentStatusRequest request) {
        ResidentStatus status = statusRepository.findById(id)
                .orElseThrow(() -> new MasterException(MASTER_NOT_FOUND));
        status.update(request.iconKey(),
                request.sortOrder() == null ? status.getSortOrder() : request.sortOrder(),
                request.active() == null ? status.getActive() : request.active());
        replaceStatusTranslations(id, request.translations());
    }

    @Transactional(readOnly = true)
    public List<DocumentRow> findDocuments() {
        List<DocumentType> documents = documentRepository.findAllByOrderBySortOrderAscIdAsc();
        Map<Long, List<DocumentTypeTranslation>> translations = documentTranslationRepository
                .findByDocumentTypeIdIn(documents.stream().map(DocumentType::getId).toList())
                .stream().collect(Collectors.groupingBy(DocumentTypeTranslation::getDocumentTypeId));
        return documents.stream().map(document -> new DocumentRow(
                document.getId(), document.getCode(), document.getIsSensitive(), document.getMaxFiles(),
                document.getSortOrder(), document.getActive(),
                names(translations.get(document.getId()), DocumentTypeTranslation::getLanguageCode,
                        DocumentTypeTranslation::getName),
                names(translations.get(document.getId()), DocumentTypeTranslation::getLanguageCode,
                        DocumentTypeTranslation::getGuideNote)))
                .toList();
    }

    @Transactional
    public void createDocument(AdminDocumentTypeRequest request) {
        String code = request.code().strip().toUpperCase();
        if (documentRepository.existsByCode(code)) {
            throw new MasterException(MASTER_DUPLICATE);
        }
        DocumentType document = documentRepository.save(DocumentType.builder()
                .code(code)
                .isSensitive(request.isSensitive() == null || request.isSensitive())
                .maxFiles(request.maxFiles() == null ? 1 : request.maxFiles())
                .sortOrder(request.sortOrder() == null ? documentRepository.maxSortOrder() + 1 : request.sortOrder())
                .active(request.active() == null || request.active())
                .build());
        replaceDocumentTranslations(document.getId(), request.translations());
    }

    @Transactional
    public void updateDocument(Long id, AdminDocumentTypeRequest request) {
        DocumentType document = documentRepository.findById(id)
                .orElseThrow(() -> new MasterException(MASTER_NOT_FOUND));
        document.update(
                request.isSensitive() == null ? document.getIsSensitive() : request.isSensitive(),
                request.maxFiles() == null ? document.getMaxFiles() : request.maxFiles(),
                request.sortOrder() == null ? document.getSortOrder() : request.sortOrder(),
                request.active() == null ? document.getActive() : request.active());
        replaceDocumentTranslations(id, request.translations());
    }

    @Transactional(readOnly = true)
    public List<MatrixRow> findMatrix() {
        Map<Long, String> statusCodes = statusRepository.findAll().stream()
                .collect(Collectors.toMap(ResidentStatus::getId, ResidentStatus::getCode));
        Map<Long, String> documentCodes = documentRepository.findAll().stream()
                .collect(Collectors.toMap(DocumentType::getId, DocumentType::getCode));
        return matrixRepository.findAllByOrderByResidentStatusIdAscIdAsc().stream()
                .map(row -> new MatrixRow(row.getId(),
                        statusCodes.get(row.getResidentStatusId()),
                        documentCodes.get(row.getDocumentTypeId()),
                        row.getIsRequired()))
                .toList();
    }

    @Transactional
    public void createMatrixRow(AdminMatrixRowRequest request) {
        ResidentStatus status = statusRepository.findByCode(request.residentStatusCode().strip().toUpperCase())
                .orElseThrow(() -> new MasterException(MASTER_REFERENCE_NOT_FOUND));
        DocumentType document = documentRepository.findByCode(request.documentTypeCode().strip().toUpperCase())
                .orElseThrow(() -> new MasterException(MASTER_REFERENCE_NOT_FOUND));
        if (matrixRepository.existsByResidentStatusIdAndDocumentTypeId(status.getId(), document.getId())) {
            throw new MasterException(MASTER_DUPLICATE);
        }
        matrixRepository.save(ResidentStatusDocument.builder()
                .residentStatusId(status.getId())
                .documentTypeId(document.getId())
                .isRequired(request.isRequired() == null || request.isRequired())
                .build());
    }

    @Transactional
    public void updateMatrixRow(Long id, AdminMatrixRowRequest request) {
        ResidentStatusDocument row = matrixRepository.findById(id)
                .orElseThrow(() -> new MasterException(MASTER_NOT_FOUND));
        row.update(request.isRequired() == null ? row.getIsRequired() : request.isRequired());
    }

    // 매트릭스 행 삭제는 허용한다 — 스냅샷 덕에 과거 제출과 무관하고, 행 자체가 구성이다.
    @Transactional
    public void deleteMatrixRow(Long id) {
        ResidentStatusDocument row = matrixRepository.findById(id)
                .orElseThrow(() -> new MasterException(MASTER_NOT_FOUND));
        matrixRepository.delete(row);
    }

    private void replaceStatusTranslations(Long statusId,
                                           List<AdminResidentStatusRequest.TranslationEntry> entries) {
        if (entries == null) {
            return;
        }
        statusTranslationRepository.deleteAll(statusTranslationRepository.findByResidentStatusId(statusId));
        entityManager.flush();
        entries.forEach(entry -> statusTranslationRepository.save(ResidentStatusTranslation.builder()
                .residentStatusId(statusId)
                .languageCode(entry.languageCode().strip())
                .name(entry.name().strip())
                .description(entry.description())
                .build()));
    }

    private void replaceDocumentTranslations(Long documentId,
                                             List<AdminDocumentTypeRequest.TranslationEntry> entries) {
        if (entries == null) {
            return;
        }
        documentTranslationRepository.deleteAll(documentTranslationRepository.findByDocumentTypeId(documentId));
        entityManager.flush();
        entries.forEach(entry -> documentTranslationRepository.save(DocumentTypeTranslation.builder()
                .documentTypeId(documentId)
                .languageCode(entry.languageCode().strip())
                .name(entry.name().strip())
                .guideNote(entry.guideNote())
                .build()));
    }

    private <T> Map<String, String> names(List<T> rows, Function<T, String> lang, Function<T, String> value) {
        if (rows == null) {
            return Map.of();
        }
        return rows.stream()
                .filter(row -> value.apply(row) != null)
                .collect(Collectors.toMap(lang, value, (first, second) -> first));
    }

    // 이동 조작의 원자 단위 — 축 전체를 한 트랜잭션에서 1..N으로 재부여한다(§21 /order 관례).
    @Transactional
    public void reorderStatuses(MasterOrderRequest request) {
        List<ResidentStatus> scope = statusRepository.findAllForReorder();
        MasterOrderPolicy.requireExactPermutation(
                scope.stream().map(ResidentStatus::getId).toList(), request.ids());
        Map<Long, ResidentStatus> byId = scope.stream()
                .collect(Collectors.toMap(ResidentStatus::getId, row -> row));
        for (int index = 0; index < request.ids().size(); index++) {
            byId.get(request.ids().get(index)).changeSortOrder(index + 1);
        }
    }

    @Transactional
    public void reorderDocuments(MasterOrderRequest request) {
        List<DocumentType> scope = documentRepository.findAllForReorder();
        MasterOrderPolicy.requireExactPermutation(
                scope.stream().map(DocumentType::getId).toList(), request.ids());
        Map<Long, DocumentType> byId = scope.stream()
                .collect(Collectors.toMap(DocumentType::getId, row -> row));
        for (int index = 0; index < request.ids().size(); index++) {
            byId.get(request.ids().get(index)).changeSortOrder(index + 1);
        }
    }
}
