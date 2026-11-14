package com.kovil.domain.rentalrequest.service;

import com.kovil.domain.rentalrequest.dto.response.RequestOptionGroupResponse;
import com.kovil.domain.rentalrequest.entity.RequestOption;
import com.kovil.domain.rentalrequest.entity.RequestOptionTranslation;
import com.kovil.domain.rentalrequest.repository.RequestOptionRepository;
import com.kovil.domain.rentalrequest.repository.RequestOptionTranslationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

// 코드 어디에도 옵션 코드가 없다 — 항목이 늘어도 이 클래스는 그대로다(FR-304의 설계 전제).
// 이름은 번역 테이블에만 있으므로 폴백이 중요하다: 요청 언어 → en → code.
// 번역이 없다고 항목을 감추면 그 옵션을 아예 고를 수 없게 된다.
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RequestOptionQueryService {

    private static final String DEFAULT_LANGUAGE = "en";

    private final RequestOptionRepository requestOptionRepository;
    private final RequestOptionTranslationRepository translationRepository;

    public List<RequestOptionGroupResponse> findActiveTree() {
        return findActiveTree(LocaleContextHolder.getLocale().getLanguage());
    }

    // 트리를 한 번의 조회로 만든다. 그룹별로 자식을 다시 읽으면 그룹 수만큼 더 읽게 된다.
    public List<RequestOptionGroupResponse> findActiveTree(String languageCode) {
        List<RequestOption> active = requestOptionRepository.findByActiveTrueOrderBySortOrderAscIdAsc();
        Map<Long, RequestOptionTranslation> names = resolveNames(languageCode);

        Map<Long, List<RequestOption>> itemsByGroupId = new LinkedHashMap<>();
        List<RequestOption> groups = new ArrayList<>();
        for (RequestOption option : active) {
            if (option.isGroup()) {
                groups.add(option);
            } else {
                itemsByGroupId.computeIfAbsent(option.getParentId(), key -> new ArrayList<>()).add(option);
            }
        }

        // 조건부 대상은 **id가 아니라 code로** 내보낸다 — 앱이 들고 있는 것도 code라
        // id를 주면 앱이 다시 매핑해야 하고, 그 매핑이 어긋나면 조건이 조용히 틀어진다.
        Map<Long, String> codeById = new HashMap<>();
        groups.forEach(group -> codeById.put(group.getId(), group.getCode()));
        itemsByGroupId.values().forEach(items ->
                items.forEach(item -> codeById.put(item.getId(), item.getCode())));

        return groups.stream()
                .map(group -> new RequestOptionGroupResponse(
                        group.getCode(),
                        nameOf(group, names),
                        group.selectionTypeOrMulti().name(),
                        group.getIconKey(),
                        descriptionOf(group, names),
                        group.getRequired(),
                        codeById.get(group.getShowWhenOptionId()),
                        group.getInputUnit(),
                        group.getInputMultiline(),
                        group.getInputAllowPast(),
                        group.getInputMaxLength(),
                        group.getInputMinNumber(),
                        group.getInputMaxNumber(),
                        group.getInputMultiple(),
                        itemsByGroupId.getOrDefault(group.getId(), List.of()).stream()
                                .map(item -> new RequestOptionGroupResponse.Item(
                                        item.getCode(), nameOf(item, names), item.getIconKey(),
                                        item.extraInputTypeOrNone().name(),
                                        item.getExtraMaxLength(), item.getExtraRequired()))
                                .toList()))
                .toList();
    }

    // 설명은 없을 수 있다 — 없으면 null 그대로 내보낸다(빈 문자열을 만들지 않는다).
    private String descriptionOf(RequestOption option, Map<Long, RequestOptionTranslation> names) {
        RequestOptionTranslation translation = names.get(option.getId());
        if (translation == null || translation.getDescription() == null
                || translation.getDescription().isBlank()) {
            return null;
        }
        return translation.getDescription();
    }

    // 스냅샷 기록이 질문(부모) 행을 읽는 통로 — 비활성 행도 준다(이미 낸 답의 부모일 수 있다).
    public List<RequestOption> findRows(Collection<Long> ids) {
        return requestOptionRepository.findByIdIn(ids);
    }

    public Map<Long, RequestOptionTranslation> resolveNames(String languageCode) {
        String requested = (languageCode == null || languageCode.isBlank()) ? DEFAULT_LANGUAGE : languageCode;
        // Set.of는 중복 원소를 거부한다 — 요청 언어가 en이면 원소가 하나여야 한다.
        Set<String> lookup = DEFAULT_LANGUAGE.equals(requested)
                ? Set.of(DEFAULT_LANGUAGE) : Set.of(requested, DEFAULT_LANGUAGE);

        Map<Long, RequestOptionTranslation> resolved = new LinkedHashMap<>();
        for (RequestOptionTranslation translation : translationRepository.findByLanguageCodeIn(lookup)) {
            RequestOptionTranslation current = resolved.get(translation.getRequestOptionId());
            if (current == null || translation.getLanguageCode().equals(requested)) {
                resolved.put(translation.getRequestOptionId(), translation);
            }
        }
        return resolved;
    }

    // 번역이 아예 없으면 code라도 보여준다 — 빈 이름이 나가면 선택 자체가 불가능해진다.
    public String nameOf(RequestOption option, Map<Long, RequestOptionTranslation> names) {
        RequestOptionTranslation translation = names.get(option.getId());
        return translation != null ? translation.getName() : option.getCode();
    }
}
