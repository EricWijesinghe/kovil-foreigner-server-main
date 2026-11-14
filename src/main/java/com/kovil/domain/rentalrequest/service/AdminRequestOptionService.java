package com.kovil.domain.rentalrequest.service;

import com.kovil.common.dto.request.MasterTreeOrderRequest;
import com.kovil.common.exception.MasterException;
import com.kovil.common.master.MasterOrderPolicy;
import com.kovil.domain.rentalrequest.dto.request.AdminRequestOptionRequest;
import com.kovil.domain.rentalrequest.dto.response.AdminRequestOptionResponse;
import com.kovil.domain.rentalrequest.entity.RequestOption;
import com.kovil.domain.rentalrequest.enums.RentalRequestStatus;
import com.kovil.domain.rentalrequest.entity.RequestOptionTranslation;
import com.kovil.domain.rentalrequest.enums.ExtraInputType;
import com.kovil.domain.rentalrequest.exception.RentalRequestException;
import com.kovil.domain.rentalrequest.repository.RentalRequestOptionRepository;
import com.kovil.domain.rentalrequest.repository.RequestOptionRepository;
import com.kovil.domain.rentalrequest.repository.RequestOptionTranslationRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.ArrayList;
import java.util.Arrays;

import static com.kovil.common.exception.MasterErrorCode.MASTER_DUPLICATE;
import static com.kovil.common.exception.MasterErrorCode.MASTER_NOT_FOUND;
import static com.kovil.common.exception.MasterErrorCode.MASTER_REFERENCE_NOT_FOUND;
import static com.kovil.domain.rentalrequest.exception.RentalRequestErrorCode.OPTION_CONDITION_INVALID;
import static com.kovil.domain.rentalrequest.exception.RentalRequestErrorCode.OPTION_NUMBER_RANGE_INVALID;

@Service
@RequiredArgsConstructor
public class AdminRequestOptionService {

    private static final int CODE_RETRY = 20;

    private final RequestOptionRepository optionRepository;
    private final RequestOptionTranslationRepository translationRepository;
    private final RentalRequestOptionRepository rentalRequestOptionRepository;
    private final EntityManager entityManager;

    private static final List<RentalRequestStatus> OPEN_STATUSES =
            Arrays.stream(RentalRequestStatus.values())
                    .filter(RentalRequestStatus::isOpen).toList();

    @Transactional(readOnly = true)
    public List<AdminRequestOptionResponse> findAll() {
        List<RequestOption> options = optionRepository.findAllByOrderBySortOrderAscIdAsc();
        Map<Long, Map<String, String>> names = translationRepository
                .findByRequestOptionIdIn(options.stream().map(RequestOption::getId).toList())
                .stream().collect(Collectors.groupingBy(RequestOptionTranslation::getRequestOptionId,
                        Collectors.toMap(RequestOptionTranslation::getLanguageCode,
                                RequestOptionTranslation::getName, (first, second) -> first)));
        Map<Long, Map<String, String>> descriptions = translationRepository
                .findByRequestOptionIdIn(options.stream().map(RequestOption::getId).toList())
                .stream().filter(row -> row.getDescription() != null)
                .collect(Collectors.groupingBy(RequestOptionTranslation::getRequestOptionId,
                        Collectors.toMap(RequestOptionTranslation::getLanguageCode,
                                RequestOptionTranslation::getDescription, (first, second) -> first)));

        Map<Long, List<Long>> childIdsByParent = options.stream()
                .filter(option -> option.getParentId() != null)
                .collect(Collectors.groupingBy(RequestOption::getParentId,
                        Collectors.mapping(RequestOption::getId, Collectors.toList())));

        Set<Long> answeredOptionIds = rentalRequestOptionRepository
                .findAnsweredOptionIdsByStatusIn(
                        options.stream().map(RequestOption::getId).toList(), OPEN_STATUSES);

        return options.stream().map(option -> new AdminRequestOptionResponse(
                option.getId(), option.getParentId(), option.getCode(), option.getSelectionType(),
                option.getIconKey(), option.getSortOrder(), option.getActive(),
                option.getRequired(), option.getShowWhenOptionId(),
                option.getExtraInputType(), option.getExtraMaxLength(), option.getExtraRequired(),
                option.getInputMaxLength(), option.getInputMinNumber(),
                option.getInputMaxNumber(), option.getInputMultiple(),
                option.getInputUnit(), option.getInputMultiline(), option.getInputAllowPast(),
                hasAnswers(option, childIdsByParent, answeredOptionIds),
                names.getOrDefault(option.getId(), Map.of()),
                descriptions.getOrDefault(option.getId(), Map.of())))
                .toList();
    }

    @Transactional
    public void delete(Long id) {
        RequestOption option = optionRepository.findById(id)
                .orElseThrow(() -> new MasterException(MASTER_NOT_FOUND));
        List<Long> axis = answerAxisOf(option);

        translationRepository.deleteAll(translationRepository.findByRequestOptionIdIn(axis));
        if (option.isGroup()) {
            optionRepository.deleteAll(optionRepository.findByParentId(option.getId()));
        }
        optionRepository.delete(option);
    }

    private boolean hasAnswers(RequestOption option, Map<Long, List<Long>> childIdsByParent,
                               Set<Long> answeredOptionIds) {
        if (answeredOptionIds.contains(option.getId())) {
            return true;
        }
        return option.isGroup() && childIdsByParent.getOrDefault(option.getId(), List.of()).stream()
                .anyMatch(answeredOptionIds::contains);
    }

    private List<Long> answerAxisOf(RequestOption option) {
        List<Long> axis = new ArrayList<>();
        axis.add(option.getId());
        if (option.isGroup()) {
            optionRepository.findByParentId(option.getId())
                    .forEach(child -> axis.add(child.getId()));
        }
        return axis;
    }

    @Transactional
    public void create(AdminRequestOptionRequest request) {
        validateNumberRange(request);
        requireTopLevelParent(request.parentId());
        String code = resolveCode(request);
        RequestOption option = optionRepository.save(RequestOption.builder()
                .parentId(request.parentId())
                .code(code)
                .selectionType(request.selectionType())
                .iconKey(request.iconKey())
                .sortOrder(request.sortOrder() == null ? optionRepository.maxSortOrderInScope(request.parentId()) + 1 : request.sortOrder())
                .active(request.active() == null || request.active())
                .required(Boolean.TRUE.equals(request.required()))
                .showWhenOptionId(validateCondition(null, request.showWhenOptionId()))
                .extraInputType(extraTypeOrNone(request.extraInputType()))
                .extraMaxLength(request.extraMaxLength())
                .extraRequired(Boolean.TRUE.equals(request.extraRequired()))
                .inputMaxLength(request.inputMaxLength())
                .inputMinNumber(request.inputMinNumber())
                .inputMaxNumber(request.inputMaxNumber())
                .inputMultiple(Boolean.TRUE.equals(request.inputMultiple()))
                .inputUnit(request.inputUnit())
                .inputMultiline(Boolean.TRUE.equals(request.inputMultiline()))
                // DATE 과거 허용 — 비우면 허용(true). 명시로만 잠근다.
                .inputAllowPast(request.inputAllowPast() == null || request.inputAllowPast())
                .build());
        replaceTranslations(option.getId(), request.translations());
    }

    @Transactional
    public void update(Long id, AdminRequestOptionRequest request) {
        validateNumberRange(request);
        RequestOption option = optionRepository.findById(id)
                .orElseThrow(() -> new MasterException(MASTER_NOT_FOUND));
        option.update(new RequestOption.Edit(
                request.selectionType() == null ? option.getSelectionType() : request.selectionType(),
                request.iconKey(),
                request.sortOrder() == null ? option.getSortOrder() : request.sortOrder(),
                request.active() == null ? option.getActive() : request.active(),
                request.required(),
                validateCondition(id, request.showWhenOptionId()),
                extraTypeOrNone(request.extraInputType()),
                request.extraMaxLength(),
                request.extraRequired(),
                request.inputMaxLength(), request.inputMinNumber(),
                request.inputMaxNumber(), request.inputMultiple(),
                request.inputUnit(), request.inputMultiline(), request.inputAllowPast()));
        replaceTranslations(id, request.translations());
    }

    private static void validateNumberRange(AdminRequestOptionRequest request) {
        if (request.inputMinNumber() != null && request.inputMaxNumber() != null
                && request.inputMinNumber() > request.inputMaxNumber()) {
            throw new RentalRequestException(OPTION_NUMBER_RANGE_INVALID);
        }
    }

    private String resolveCode(AdminRequestOptionRequest request) {
        String given = request.code() == null ? "" : request.code().strip().toUpperCase();
        if (!given.isEmpty()) {
            if (optionRepository.existsByCode(given)) {
                throw new MasterException(MASTER_DUPLICATE);
            }
            return given;
        }

        String prefix = request.parentId() == null
                ? "Q"
                : optionRepository.findById(request.parentId())
                        .map(RequestOption::getCode)
                        .orElseThrow(() -> new MasterException(MASTER_REFERENCE_NOT_FOUND));

        for (int attempt = 1; attempt <= CODE_RETRY; attempt++) {
            String candidate = "%s_%03d".formatted(prefix, nextSuffix(prefix) + attempt - 1);
            if (!optionRepository.existsByCode(candidate)) {
                return candidate;
            }
        }
        throw new MasterException(MASTER_DUPLICATE);
    }

    private int nextSuffix(String prefix) {
        return optionRepository.countByCodeStartingWith(prefix + "_") + 1;
    }

    private Long validateCondition(Long editingId, Long showWhenOptionId) {
        if (showWhenOptionId == null) {
            return null;
        }
        RequestOption target = optionRepository.findById(showWhenOptionId)
                .orElseThrow(() -> new MasterException(MASTER_REFERENCE_NOT_FOUND));
        if (target.isGroup()) {
            throw new RentalRequestException(OPTION_CONDITION_INVALID);
        }
        if (editingId != null && editingId.equals(target.getParentId())) {
            throw new RentalRequestException(OPTION_CONDITION_INVALID);
        }
        RequestOption owner = optionRepository.findById(target.getParentId())
                .orElseThrow(() -> new MasterException(MASTER_REFERENCE_NOT_FOUND));
        if (owner.getShowWhenOptionId() != null) {
            throw new RentalRequestException(OPTION_CONDITION_INVALID);
        }
        if (editingId != null
                && optionRepository.existsByShowWhenOptionIdParent(editingId)) {
            throw new RentalRequestException(OPTION_CONDITION_INVALID);
        }
        return showWhenOptionId;
    }

    private String extraTypeOrNone(String raw) {
        if (raw == null || raw.isBlank()) {
            return ExtraInputType.NONE.name();
        }
        try {
            return ExtraInputType.valueOf(raw.strip().toUpperCase()).name();
        } catch (IllegalArgumentException e) {
            throw new MasterException(MASTER_REFERENCE_NOT_FOUND);
        }
    }

    private void replaceTranslations(Long optionId, List<AdminRequestOptionRequest.TranslationEntry> entries) {
        if (entries == null) {
            return;
        }
        translationRepository.deleteAll(translationRepository.findByRequestOptionId(optionId));
        entityManager.flush();
        entries.forEach(entry -> translationRepository.save(RequestOptionTranslation.builder()
                .requestOptionId(optionId)
                .languageCode(entry.languageCode().strip())
                .name(entry.name().strip())
                .description(entry.description() == null || entry.description().isBlank()
                        ? null : entry.description().strip())
                .build()));
    }

    @Transactional
    public void reorder(MasterTreeOrderRequest request) {
        requireTopLevelParent(request.parentId());
        List<RequestOption> scope = optionRepository.findSiblingsForReorder(request.parentId());
        MasterOrderPolicy.requireExactPermutation(
                scope.stream().map(RequestOption::getId).toList(), request.ids());
        Map<Long, RequestOption> byId = scope.stream()
                .collect(Collectors.toMap(RequestOption::getId, row -> row));
        for (int index = 0; index < request.ids().size(); index++) {
            byId.get(request.ids().get(index)).changeSortOrder(index + 1);
        }
    }

    private void requireTopLevelParent(Long parentId) {
        if (parentId == null) {
            return;
        }
        RequestOption parent = optionRepository.findById(parentId)
                .orElseThrow(() -> new MasterException(MASTER_REFERENCE_NOT_FOUND));
        if (!parent.isGroup()) {
            throw new MasterException(MASTER_REFERENCE_NOT_FOUND);
        }
    }
}
