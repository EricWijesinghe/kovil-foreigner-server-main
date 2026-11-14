package com.kovil.domain.rentalrequest.service;

import com.kovil.domain.region.entity.Region;
import com.kovil.domain.region.repository.RegionRepository;
import com.kovil.domain.rentalrequest.entity.RequestOption;
import com.kovil.domain.rentalrequest.enums.OptionSelectionType;
import com.kovil.domain.rentalrequest.exception.RentalRequestException;
import com.kovil.domain.rentalrequest.repository.RequestOptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static com.kovil.domain.rentalrequest.exception.RentalRequestErrorCode.OPTION_NOT_FOUND;
import static com.kovil.domain.rentalrequest.exception.RentalRequestErrorCode.OPTION_SELECTION_INVALID;
import static com.kovil.domain.rentalrequest.exception.RentalRequestErrorCode.REGION_INVALID_LEVEL;
import static com.kovil.domain.rentalrequest.exception.RentalRequestErrorCode.REGION_NOT_FOUND;
import static com.kovil.domain.rentalrequest.exception.RentalRequestErrorCode.REGION_REQUIRED;

// 코드 목록을 행 목록으로 바꾸면서 FR-304·FR-305를 강제한다.
// 등록과 수정이 같은 규칙을 타야 하므로 한 곳에 모았다 — 두 벌로 두면 반드시 갈라진다.
@Component
@RequiredArgsConstructor
public class RentalRequestSelectionResolver {

    private final RegionRepository regionRepository;
    private final RequestOptionRepository requestOptionRepository;

    // FR-305 — 시군구 행만 고를 수 있다. 시도를 고르면 "서울 전체"가 되어 매칭이 무의미해진다.
    // V14 — 빈 목록 허용: 고정 지역 축이 질문지(ADDRESS 답변)로 이관됐다. 필수 여부는
    //   질문지의 required 가 강제하고, 이 축은 구 요청 수정 시 이월 값만 받는다.
    public List<Region> resolveRegions(List<String> regionCodes) {
        List<String> codes = distinct(regionCodes);
        if (codes.isEmpty()) {
            return List.of();
        }

        Map<String, Region> found = new LinkedHashMap<>();
        regionRepository.findByCodeInAndIsActiveTrue(codes)
                .forEach(region -> found.put(region.getCode(), region));

        List<Region> resolved = new ArrayList<>();
        for (String code : codes) {
            Region region = found.get(code);
            if (region == null) {
                throw new RentalRequestException(REGION_NOT_FOUND);
            }
            if (region.isRoot()) {
                throw new RentalRequestException(REGION_INVALID_LEVEL);
            }
            resolved.add(region);
        }

        // ★ 부모 시도의 활성까지 본다 — 시도만 비활성화하면(자식 연쇄 비활성화 없음) 유저
        //   트리에서는 통째로 사라지는데, 코드를 직접 보내면 행 단위 검사만 통과해 닫힌 시도에
        //   신규 수요가 계속 쌓였다(적대적 리뷰 2026-09-02). 표시 계층의 "닫힘"을 쓰기 계층도 지킨다.
        requireActiveParents(resolved);
        return resolved;
    }

    private void requireActiveParents(List<Region> regions) {
        Set<Long> parentIds = regions.stream()
                .map(Region::getParentId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (!parentIds.isEmpty()
                && regionRepository.findByIdInAndIsActiveTrue(parentIds).size() != parentIds.size()) {
            throw new RentalRequestException(REGION_NOT_FOUND);
        }
    }

    // FR-304 — SINGLE 그룹 복수 선택 거부. 비활성 항목은 신규 선택 대상이 아니다.
    //
    // ★ V11 부터 **질문(그룹) 행도 올 수 있다** — TEXT·NUMBER·DATE·ADDRESS 질문은 선택지가
    //   없어서 답이 질문 행에 직접 붙기 때문이다. 선택형 질문의 그룹 행은 여전히 거부한다
    //   (그건 화면의 묶음이지 선택지가 아니다).
    public List<RequestOption> resolveOptions(List<String> optionCodes) {
        List<String> codes = distinct(optionCodes);
        if (codes.isEmpty()) {
            return List.of();
        }

        Map<String, RequestOption> found = new LinkedHashMap<>();
        requestOptionRepository.findByCodeInAndActiveTrue(codes)
                .forEach(option -> found.put(option.getCode(), option));

        List<RequestOption> resolved = new ArrayList<>();
        Map<Long, Integer> pickedPerGroup = new LinkedHashMap<>();
        for (String code : codes) {
            RequestOption option = found.get(code);
            if (option == null) {
                throw new RentalRequestException(OPTION_NOT_FOUND);
            }
            if (option.isGroup()) {
                // 선택형 질문의 그룹 행은 답이 될 수 없다. 비선택형이면 그 자체가 답의 자리다.
                if (option.selectionTypeOrMulti().hasChoices()) {
                    throw new RentalRequestException(OPTION_SELECTION_INVALID);
                }
                resolved.add(option);
                continue;
            }
            resolved.add(option);
            pickedPerGroup.merge(option.getParentId(), 1, Integer::sum);
        }

        enforceSingleSelection(pickedPerGroup);
        return resolved;
    }

    private void enforceSingleSelection(Map<Long, Integer> pickedPerGroup) {
        List<Long> multiPicked = pickedPerGroup.entrySet().stream()
                .filter(entry -> entry.getValue() > 1)
                .map(Map.Entry::getKey)
                .toList();
        if (multiPicked.isEmpty()) {
            return;
        }
        // 부모가 사라졌으면 판정할 근거가 없다 — 통과시키지 않는다.
        Map<Long, RequestOption> groups = new LinkedHashMap<>();
        requestOptionRepository.findByIdIn(multiPicked)
                .forEach(group -> groups.put(group.getId(), group));

        for (Long groupId : multiPicked) {
            RequestOption group = groups.get(groupId);
            if (group == null || group.selectionTypeOrMulti() == OptionSelectionType.SINGLE) {
                throw new RentalRequestException(OPTION_SELECTION_INVALID);
            }
        }
    }

    // 같은 코드를 두 번 보내는 것은 실수지 SINGLE 위반이 아니다 — 조용히 한 번으로 접는다.
    private List<String> distinct(List<String> codes) {
        if (codes == null) {
            return List.of();
        }
        return new ArrayList<>(new LinkedHashSet<>(codes.stream()
                .filter(code -> code != null && !code.isBlank())
                .map(String::trim)
                .toList()));
    }
}
