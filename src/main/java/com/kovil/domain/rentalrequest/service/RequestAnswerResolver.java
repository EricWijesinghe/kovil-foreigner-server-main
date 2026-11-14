package com.kovil.domain.rentalrequest.service;

import com.kovil.domain.rentalrequest.dto.request.RequestAnswerEntry;
import com.kovil.domain.rentalrequest.entity.RequestOption;
import com.kovil.domain.region.entity.Region;
import com.kovil.domain.region.repository.RegionRepository;
import com.kovil.domain.rentalrequest.enums.ExtraInputType;
import com.kovil.domain.rentalrequest.enums.OptionSelectionType;
import com.kovil.domain.rentalrequest.exception.RentalRequestException;
import com.kovil.domain.rentalrequest.repository.RequestOptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.time.Clock;

import static com.kovil.domain.rentalrequest.exception.RentalRequestErrorCode.OPTION_ANSWER_REQUIRED;
import static com.kovil.domain.rentalrequest.exception.RentalRequestErrorCode.OPTION_DATE_IN_PAST;
import static com.kovil.domain.rentalrequest.exception.RentalRequestErrorCode.OPTION_EXTRA_INVALID;
import static com.kovil.domain.rentalrequest.exception.RentalRequestErrorCode.OPTION_EXTRA_REQUIRED;
import static com.kovil.domain.rentalrequest.exception.RentalRequestErrorCode.REGION_INVALID_LEVEL;
import static com.kovil.domain.rentalrequest.exception.RentalRequestErrorCode.REGION_NOT_FOUND;

@Component
@RequiredArgsConstructor
public class RequestAnswerResolver {

    private static final int DEFAULT_TEXT_MAX = 255;

    private final RequestOptionRepository requestOptionRepository;
    private final RegionRepository regionRepository;
    private final Clock clock;

    /** 검증을 통과한 답변 한 건 — 저장 직전 형태다. ADDRESS 는 지역 하나당 한 건이 된다. */
    public record ResolvedAnswer(
            RequestOption option,
            String textValue,
            Long numberValue,
            LocalDate dateValue,
            Long regionId
    ) {
        static ResolvedAnswer empty(RequestOption option) {
            return new ResolvedAnswer(option, null, null, null, null);
        }
    }

    /**
     * @param picked 이미 코드 검증을 통과한 항목 행들(그룹 아님, 활성, SINGLE 규칙 통과)
     * @param extras 항목 코드 → 그 항목이 연 입력값
     */
    public List<ResolvedAnswer> resolve(List<RequestOption> picked, List<RequestAnswerEntry> extras) {
        Map<String, RequestAnswerEntry> extraByCode = extras == null ? Map.of() : extras.stream()
                .filter(entry -> entry != null && entry.optionCode() != null)
                .collect(Collectors.toMap(entry -> entry.optionCode().trim(), entry -> entry,
                        (first, second) -> second, LinkedHashMap::new));

        Set<Long> pickedIds = picked.stream().map(RequestOption::getId).collect(Collectors.toSet());
        // 활성 트리 전체를 받아 질문(1단)만 추린다 — 저장소에 새 쿼리를 더하지 않는다.
        List<RequestOption> activeTree = requestOptionRepository.findByActiveTrueOrderBySortOrderAscIdAsc();
        List<RequestOption> questions = activeTree.stream().filter(RequestOption::isGroup).toList();
        Set<Long> activeIds = activeTree.stream().map(RequestOption::getId).collect(Collectors.toSet());

        // ① 노출 조건 판정 — 조건이 걸린 질문은 그 항목이 실제로 선택됐을 때만 살아 있다.
        //
        // ★ 조건 대상이 활성 트리에 없으면(관리자가 그 항목을 **비활성화**) "항상 노출"로 판정한다.
        //   트리 API(RequestOptionQueryService)가 그 조건을 null(항상 노출)로 내보내므로 앱은
        //   질문을 그리는데, 여기서 "선택될 수 없는 조건 = 영구 숨김"으로 판정하면 유저가 답한
        //   질문의 답이 소리 없이 버려진다(AUDIT_2026_08_26). 삭제(FK SET NULL → 항상 노출)와도
        //   같은 의미가 된다 — 조건을 잃은 질문은 무조건 노출이다.
        Set<Long> visibleQuestionIds = questions.stream()
                .filter(question -> question.getShowWhenOptionId() == null
                        || !activeIds.contains(question.getShowWhenOptionId())
                        || pickedIds.contains(question.getShowWhenOptionId()))
                .map(RequestOption::getId)
                .collect(Collectors.toSet());

        // ② 숨겨진 질문의 답은 버린다. 조용히 버리는 것이 맞다 — 앞 답을 되돌린 사용자에게
        //    "보이지도 않는 질문의 답을 지우라"고 말할 방법이 없다.
        //
        // ★ 답이 걸린 질문을 찾는 법이 두 가지다. 선택형은 고른 **항목의 부모**가 질문이고,
        //   비선택형(TEXT·NUMBER·DATE·ADDRESS)은 **그 행 자체**가 질문이다.
        //   이걸 구분하지 않으면 비선택형 질문의 parentId(null)로 가시성을 물어보게 되고,
        //   답이 통째로 탈락한다(테스트가 이 버그를 잡았다).
        List<RequestOption> kept = picked.stream()
                .filter(option -> visibleQuestionIds.contains(questionIdOf(option)))
                .toList();
        Set<Long> answeredQuestionIds = kept.stream()
                .map(RequestAnswerResolver::questionIdOf)
                .collect(Collectors.toSet());

        // ③ 필수 — 노출된 질문만 따진다.
        for (RequestOption question : questions) {
            if (!Boolean.TRUE.equals(question.getRequired())) {
                continue;
            }
            if (visibleQuestionIds.contains(question.getId())
                    && !answeredQuestionIds.contains(question.getId())) {
                throw new RentalRequestException(OPTION_ANSWER_REQUIRED);
            }
        }

        // ④ 값 검증 — 선택형은 항목이 연 추가 입력을, 비선택형은 질문 자체의 입력을 본다.
        List<ResolvedAnswer> resolved = new ArrayList<>();
        for (RequestOption option : kept) {
            RequestAnswerEntry entry = extraByCode.get(option.getCode());
            if (option.isGroup()) {
                resolved.addAll(resolveDirect(option, entry));
            } else {
                resolved.add(resolveExtra(option, entry));
            }
        }
        return resolved;
    }

    /**
     * 비선택형 질문(TEXT·NUMBER·DATE·ADDRESS)의 답.
     *
     * <p>선택지가 없으므로 값 자체가 답이다 — 값이 비면 "답하지 않은 것"이다.
     * ③의 필수 판정은 코드 포함 여부만 보므로(코드만 넣고 값을 비우는 본문을 걸러내지 못한다)
     * **required 는 여기서 다시 문다.** 필수가 아니면 빈 답은 행을 만들지 않고 버린다 —
     * 전부 null 인 답변 행은 사무소에 "무언가 답했다"는 거짓 신호가 된다.
     */
    private List<ResolvedAnswer> resolveDirect(RequestOption question, RequestAnswerEntry entry) {
        OptionSelectionType type = question.selectionTypeOrMulti();
        switch (type) {
            case TEXT -> {
                String text = entry == null ? null : trimToNull(entry.textValue());
                if (text == null) {
                    return noAnswer(question);
                }
                // 클램프인 이유: text_value 컬럼이 VARCHAR(255)다 — 초과 설정(구 데이터·직접 SQL)을
                // 그대로 믿으면 검증은 통과하고 INSERT가 500으로 죽는다(쓰기 검증과 이중 방어).
                int max = clampTextMax(question.getInputMaxLength());
                if (text.length() > max) {
                    throw new RentalRequestException(OPTION_EXTRA_INVALID);
                }
                return List.of(new ResolvedAnswer(question, text, null, null, null));
            }
            case NUMBER -> {
                Long number = entry == null ? null : entry.numberValue();
                if (number == null) {
                    return noAnswer(question);
                }
                long min = question.getInputMinNumber() == null ? 0L : question.getInputMinNumber();
                Long max = question.getInputMaxNumber();
                if (number < min || (max != null && number > max)) {
                    throw new RentalRequestException(OPTION_EXTRA_INVALID);
                }
                return List.of(new ResolvedAnswer(question, null, number, null, null));
            }
            case DATE -> {
                LocalDate date = entry == null ? null : entry.dateValue();
                if (date == null) {
                    return noAnswer(question);
                }
                // 과거 거부는 질문마다 관리자가 정한다(V16) — "입주 희망일"은 미래,
                // "입국일"은 과거가 답이라 코드가 단정할 수 없다. 오늘은 허용(이후 = 오늘 포함).
                if (!question.allowsPastDate() && date.isBefore(LocalDate.now(clock))) {
                    throw new RentalRequestException(OPTION_DATE_IN_PAST);
                }
                return List.of(new ResolvedAnswer(question, null, null, date, null));
            }
            case ADDRESS -> {
                return resolveAddress(question, entry);
            }
            default -> {
                // 선택형 그룹 행은 selection 단계에서 이미 거부된다 — 방어적으로 아무것도 안 만든다.
                return List.of();
            }
        }
    }

    /**
     * ADDRESS — 지역 마스터를 그대로 쓴다. 고른 지역 하나당 답 한 건이 된다.
     *
     * <p>시군구만 받는 규칙은 고정 질문(희망 지역)과 같다 — 시도를 받으면 "서울 전체"가 되어
     * 사무소가 좁힐 수 없다. 그래서 같은 에러 코드(REGION_INVALID_LEVEL)를 쓴다.
     */
    private List<ResolvedAnswer> resolveAddress(RequestOption question, RequestAnswerEntry entry) {
        List<String> codes = entry == null || entry.regionCodes() == null
                ? List.of()
                : entry.regionCodes().stream().filter(code -> code != null && !code.isBlank())
                        .map(String::trim).distinct().toList();
        if (codes.isEmpty()) {
            return noAnswer(question);
        }
        if (codes.size() > 1 && !Boolean.TRUE.equals(question.getInputMultiple())) {
            throw new RentalRequestException(OPTION_EXTRA_INVALID);
        }

        Map<String, Region> found = new LinkedHashMap<>();
        regionRepository.findByCodeInAndIsActiveTrue(codes)
                .forEach(region -> found.put(region.getCode(), region));

        List<ResolvedAnswer> answers = new ArrayList<>();
        for (String code : codes) {
            Region region = found.get(code);
            if (region == null) {
                throw new RentalRequestException(REGION_NOT_FOUND);
            }
            if (region.isRoot()) {
                throw new RentalRequestException(REGION_INVALID_LEVEL);
            }
            // 부모 시도 활성 검증 — 요청 지역 리졸버와 같은 이유(적대적 리뷰 2026-09-02).
            if (region.getParentId() != null
                    && regionRepository.findByIdInAndIsActiveTrue(List.of(region.getParentId())).isEmpty()) {
                throw new RentalRequestException(REGION_NOT_FOUND);
            }
            answers.add(new ResolvedAnswer(question, null, null, null, region.getId()));
        }
        return answers;
    }

    private ResolvedAnswer resolveExtra(RequestOption option, RequestAnswerEntry entry) {
        ExtraInputType type = option.extraInputTypeOrNone();
        if (type == ExtraInputType.NONE) {
            // 입력칸이 없는 항목에 값이 딸려 와도 조용히 버린다 — 앱 버전이 앞서갔을 뿐이다.
            return ResolvedAnswer.empty(option);
        }

        boolean required = Boolean.TRUE.equals(option.getExtraRequired());
        switch (type) {
            case TEXT -> {
                String text = entry == null ? null : trimToNull(entry.textValue());
                if (text == null) {
                    return emptyOrThrow(option, required);
                }
                int max = clampTextMax(option.getExtraMaxLength());
                if (text.length() > max) {
                    throw new RentalRequestException(OPTION_EXTRA_INVALID);
                }
                return new ResolvedAnswer(option, text, null, null, null);
            }
            case NUMBER -> {
                Long number = entry == null ? null : entry.numberValue();
                if (number == null) {
                    return emptyOrThrow(option, required);
                }
                if (number < 0) {
                    throw new RentalRequestException(OPTION_EXTRA_INVALID);
                }
                return new ResolvedAnswer(option, null, number, null, null);
            }
            case DATE -> {
                LocalDate date = entry == null ? null : entry.dateValue();
                if (date == null) {
                    return emptyOrThrow(option, required);
                }
                return new ResolvedAnswer(option, null, null, date, null);
            }
            default -> {
                return ResolvedAnswer.empty(option);
            }
        }
    }

    private static int clampTextMax(Integer configured) {
        return configured == null ? DEFAULT_TEXT_MAX : Math.min(configured, DEFAULT_TEXT_MAX);
    }

    /** 값이 빈 직접 답 — 필수면 여기서 걸고, 아니면 행을 만들지 않는다. */
    private List<ResolvedAnswer> noAnswer(RequestOption question) {
        if (Boolean.TRUE.equals(question.getRequired())) {
            throw new RentalRequestException(OPTION_ANSWER_REQUIRED);
        }
        return List.of();
    }

    /** 이 답이 걸린 질문의 id — 항목이면 부모, 질문 행이면 자기 자신. */
    private static Long questionIdOf(RequestOption option) {
        return option.isGroup() ? option.getId() : option.getParentId();
    }

    private ResolvedAnswer emptyOrThrow(RequestOption option, boolean required) {
        if (required) {
            throw new RentalRequestException(OPTION_EXTRA_REQUIRED);
        }
        return ResolvedAnswer.empty(option);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
