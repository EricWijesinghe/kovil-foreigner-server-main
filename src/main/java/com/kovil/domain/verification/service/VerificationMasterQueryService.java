package com.kovil.domain.verification.service;

import com.kovil.domain.verification.dto.response.RequiredDocumentResponse;
import com.kovil.domain.verification.dto.response.ResidentStatusResponse;
import com.kovil.domain.verification.entity.DocumentType;
import com.kovil.domain.verification.entity.DocumentTypeTranslation;
import com.kovil.domain.verification.entity.ResidentStatus;
import com.kovil.domain.verification.entity.ResidentStatusDocument;
import com.kovil.domain.verification.entity.ResidentStatusTranslation;
import com.kovil.domain.verification.exception.VerificationException;
import com.kovil.domain.verification.repository.DocumentTypeRepository;
import com.kovil.domain.verification.repository.DocumentTypeTranslationRepository;
import com.kovil.domain.verification.repository.ResidentStatusDocumentRepository;
import com.kovil.domain.verification.repository.ResidentStatusRepository;
import com.kovil.domain.verification.repository.ResidentStatusTranslationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import static com.kovil.domain.verification.exception.VerificationErrorCode.RESIDENT_STATUS_NOT_FOUND;

// ★ INV-03 — 이 클래스 어디에도 신분별 분기가 없다.
// 요구 문서는 언제나 resident_status_documents에서 읽으며, 신분이 늘어도 코드는 그대로다.
//
// 마스터 이름은 번역 테이블에만 있다(countries와 달리 인라인 name_en이 없다).
// 그래서 폴백이 더 중요하다: 요청 언어 → 기본 언어(en) → 코드.
// 번역이 없다고 항목을 감추면 사용자가 그 신분을 아예 고를 수 없게 된다.
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VerificationMasterQueryService {

    private static final String DEFAULT_LANGUAGE = "en";

    // 기타 증빙 — 매트릭스 밖의 전 신분 공통 슬롯. 요건 목록 마지막에 선택(required=false)으로 붙는다.
    private static final String EXTRA_DOCUMENT_CODE = "ETC";

    private final ResidentStatusRepository residentStatusRepository;
    private final ResidentStatusTranslationRepository residentStatusTranslationRepository;
    private final ResidentStatusDocumentRepository residentStatusDocumentRepository;
    private final DocumentTypeRepository documentTypeRepository;
    private final DocumentTypeTranslationRepository documentTypeTranslationRepository;

    public List<ResidentStatusResponse> findActiveStatuses() {
        return findActiveStatuses(LocaleContextHolder.getLocale().getLanguage());
    }

    public List<ResidentStatusResponse> findActiveStatuses(String languageCode) {
        Map<Long, ResidentStatusTranslation> byStatusId =
                resolve(residentStatusTranslationRepository.findByLanguageCodeIn(languages(languageCode)),
                        ResidentStatusTranslation::getResidentStatusId,
                        ResidentStatusTranslation::getLanguageCode, languageCode);

        return residentStatusRepository.findByActiveTrueOrderBySortOrderAscCodeAsc().stream()
                .map(status -> toResponse(status, byStatusId.get(status.getId())))
                .toList();
    }

    public List<RequiredDocumentResponse> findRequirements(String statusCode) {
        return findRequirements(statusCode, LocaleContextHolder.getLocale().getLanguage());
    }

    public List<RequiredDocumentResponse> findRequirements(String statusCode, String languageCode) {
        ResidentStatus status = residentStatusRepository.findByCodeAndActiveTrue(statusCode)
                .orElseThrow(() -> new VerificationException(RESIDENT_STATUS_NOT_FOUND));

        // 순서의 진실은 매트릭스가 아니라 **문서 유형 축**이다(단일 진실 — 관리자 이동 UI 하나가
        // 유저가 보는 서류 순서까지 통제한다). 매트릭스는 "무엇을 내나"만 답한다.
        List<ResidentStatusDocument> matrix =
                residentStatusDocumentRepository.findByResidentStatusId(status.getId());
        if (matrix.isEmpty()) {
            // 요건이 하나도 없는 신분에 기타 슬롯만 띄우면 시드 누락을 가리는 화면이 된다.
            return List.of();
        }

        // 비활성 문서 종류는 요건에서 빠진다 — 매트릭스 행이 남아 있어도 제출 화면에 그리지 않는다.
        Map<Long, DocumentType> typesById = new LinkedHashMap<>();
        documentTypeRepository
                .findByIdInAndActiveTrue(matrix.stream().map(ResidentStatusDocument::getDocumentTypeId).toList())
                .forEach(type -> typesById.put(type.getId(), type));

        Map<Long, DocumentTypeTranslation> byTypeId =
                resolve(documentTypeTranslationRepository.findByLanguageCodeIn(languages(languageCode)),
                        DocumentTypeTranslation::getDocumentTypeId,
                        DocumentTypeTranslation::getLanguageCode, languageCode);

        // 정렬은 반드시 **비활성 필터 이후**의 (row, type) 쌍에서 한다 — 필터 전 정렬은
        // 비활성 행(typesById에 없음)에서 NPE다(inactive_document_type_is_dropped가 밟는 경로).
        // ETC는 정렬에서 빼서 **항상 최후미**에 붙인다(PO 확정 2026-08-20) — "기타 증빙"은
        // 캐치올이라 마지막이 유저 기대이고, 승격 여부로 규칙이 갈리면 관리자가 ETC를 옮겨도
        // 유저 화면이 안 바뀌는 거짓말 UI가 된다. 999 센티널 의존도 이것으로 끝난다.
        record Pair(ResidentStatusDocument row, DocumentType type) {
        }
        List<Pair> pairs = matrix.stream()
                .map(row -> {
                    DocumentType type = typesById.get(row.getDocumentTypeId());
                    return type == null ? null : new Pair(row, type);
                })
                .filter(Objects::nonNull)
                .toList();

        // ★ 매트릭스 유래 요건이 전부 비활성으로 걸러졌으면 빈 매트릭스와 같다 — 여기서 ETC
        //   폴백을 붙이면 [ETC(선택)]만 남은 목록이 제출 가드(requirements.isEmpty())를 우회해
        //   서류 0장 제출이 다시 통과한다(적대적 리뷰 2026-09-02). 승격 ETC 행만 있는 매트릭스는
        //   pairs 가 비지 않으므로 보존된다.
        if (pairs.isEmpty()) {
            return List.of();
        }

        List<RequiredDocumentResponse> requirements = new ArrayList<>(pairs.stream()
                .filter(pair -> !EXTRA_DOCUMENT_CODE.equals(pair.type().getCode()))
                .sorted(Comparator
                        .comparing((Pair pair) -> pair.type().getSortOrder(),
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(pair -> pair.type().getId()))
                .map(pair -> toResponse(pair.row(), pair.type(), byTypeId.get(pair.type().getId())))
                .toList());

        // 승격된 ETC(매트릭스 행)는 그 행의 isRequired를 지키되 자리는 여전히 마지막이다.
        Optional<Pair> promotedEtc = pairs.stream()
                .filter(pair -> EXTRA_DOCUMENT_CODE.equals(pair.type().getCode()))
                .findFirst();
        if (promotedEtc.isPresent()) {
            Pair pair = promotedEtc.get();
            requirements.add(toResponse(pair.row(), pair.type(), byTypeId.get(pair.type().getId())));
        } else {
            documentTypeRepository.findByCodeAndActiveTrue(EXTRA_DOCUMENT_CODE).ifPresent(type -> {
                DocumentTypeTranslation translation = byTypeId.get(type.getId());
                requirements.add(new RequiredDocumentResponse(
                        type.getCode(),
                        translation != null ? translation.getName() : type.getCode(),
                        translation != null ? translation.getGuideNote() : null,
                        false,
                        type.getMaxFiles(),
                        Boolean.TRUE.equals(type.getIsSensitive())));
            });
        }
        return requirements;
    }

    // Set.of는 중복 원소를 거부한다 — 요청 언어가 기본 언어와 같으면 하나만 넣는다.
    // 이걸 놓치면 en 요청이 전부 500이 된다(en이 이 서비스의 기본 언어다).
    private Set<String> languages(String requested) {
        String language = (requested == null || requested.isBlank()) ? DEFAULT_LANGUAGE : requested;
        return DEFAULT_LANGUAGE.equals(language)
                ? Set.of(DEFAULT_LANGUAGE)
                : Set.of(language, DEFAULT_LANGUAGE);
    }

    // 요청 언어 행이 있으면 그것, 없으면 기본 언어 행을 남긴다.
    private <T> Map<Long, T> resolve(List<T> translations,
                                     Function<T, Long> keyOf,
                                     Function<T, String> langOf,
                                     String requested) {
        Map<Long, T> resolved = new LinkedHashMap<>();
        for (T translation : translations) {
            Long key = keyOf.apply(translation);
            T current = resolved.get(key);
            if (current == null || langOf.apply(translation).equals(requested)) {
                resolved.put(key, translation);
            }
        }
        return resolved;
    }

    private ResidentStatusResponse toResponse(ResidentStatus status, ResidentStatusTranslation translation) {
        return new ResidentStatusResponse(
                status.getCode(),
                // 번역이 아예 없으면 코드라도 보여준다 — 빈 이름이 나가면 선택 자체가 불가능해진다.
                translation != null ? translation.getName() : status.getCode(),
                translation != null ? translation.getDescription() : null,
                status.getIconKey());
    }

    private RequiredDocumentResponse toResponse(
            ResidentStatusDocument row, DocumentType type, DocumentTypeTranslation translation) {
        return new RequiredDocumentResponse(
                type.getCode(),
                translation != null ? translation.getName() : type.getCode(),
                translation != null ? translation.getGuideNote() : null,
                Boolean.TRUE.equals(row.getIsRequired()),
                type.getMaxFiles(),
                Boolean.TRUE.equals(type.getIsSensitive()));
    }
}
