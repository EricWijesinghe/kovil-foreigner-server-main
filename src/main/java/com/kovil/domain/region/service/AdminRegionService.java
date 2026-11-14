package com.kovil.domain.region.service;

import com.kovil.common.dto.request.MasterTreeOrderRequest;
import com.kovil.common.exception.MasterException;
import com.kovil.common.master.MasterOrderPolicy;
import com.kovil.domain.listing.service.ListingRegionClosureService;
import com.kovil.domain.region.dto.AdminRegionRequest;
import com.kovil.domain.region.dto.AdminRegionResponse;
import com.kovil.domain.region.entity.Region;
import com.kovil.domain.region.repository.RegionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.kovil.common.exception.MasterErrorCode.MASTER_DUPLICATE;
import static com.kovil.common.exception.MasterErrorCode.MASTER_NOT_FOUND;
import static com.kovil.common.exception.MasterErrorCode.MASTER_REFERENCE_NOT_FOUND;

@Service
@RequiredArgsConstructor
public class AdminRegionService {

    private final RegionRepository regionRepository;
    private final ListingRegionClosureService listingRegionClosureService;

    @Transactional(readOnly = true)
    public List<AdminRegionResponse> findAll() {
        return regionRepository.findAllByOrderBySortOrderAscIdAsc().stream()
                .map(AdminRegionResponse::from)
                .toList();
    }

    @Transactional
    public AdminRegionResponse create(AdminRegionRequest request) {
        String code = request.code().strip();
        if (regionRepository.existsByCode(code)) {
            throw new MasterException(MASTER_DUPLICATE);
        }
        requireTopLevelParent(request.parentId());
        return AdminRegionResponse.from(regionRepository.save(Region.builder()
                .parentId(request.parentId())
                .code(code)
                .nameKo(request.nameKo().strip())
                .nameEn(request.nameEn().strip())
                .sortOrder(request.sortOrder() == null ? regionRepository.maxSortOrderInScope(request.parentId()) + 1 : request.sortOrder())
                .isActive(request.isActive() == null || request.isActive())
                .build()));
    }

    @Transactional
    public AdminRegionResponse update(Long id, AdminRegionRequest request) {
        Region region = regionRepository.findById(id)
                .orElseThrow(() -> new MasterException(MASTER_NOT_FOUND));
        boolean wasActive = Boolean.TRUE.equals(region.getIsActive());
        region.update(request.nameKo().strip(), request.nameEn().strip(),
                request.sortOrder() == null ? region.getSortOrder() : request.sortOrder(),
                request.isActive() == null ? region.getIsActive() : request.isActive());
        // 닫히는 순간에만 매물을 내린다(PO 확정 2026-10-06). 이름·순서만 고치는 저장이나 이미 닫힌
        // 지역을 다시 저장하는 것으로는 아무것도 내리지 않고, 다시 열어도 자동으로 되살리지 않는다.
        if (wasActive && !Boolean.TRUE.equals(region.getIsActive())) {
            listingRegionClosureService.hideListingsIn(region);
        }
        return AdminRegionResponse.from(region);
    }

    // 이동 조작의 원자 단위 — 같은 부모의 형제 전체를 한 트랜잭션에서 1..N으로 재부여한다.
    // 부모별 재부여가 유저 화면을 섞지 않는 이유: 유저 트리는 parentId로 그룹핑해 버킷 내
    // 상대 순서만 쓴다. 부모가 다른 행끼리 번호가 겹쳐도 화면 순서와 무관하다.
    @Transactional
    public void reorder(MasterTreeOrderRequest request) {
        requireTopLevelParent(request.parentId());
        List<Region> scope = regionRepository.findSiblingsForReorder(request.parentId());
        MasterOrderPolicy.requireExactPermutation(
                scope.stream().map(Region::getId).toList(), request.ids());
        Map<Long, Region> byId = scope.stream()
                .collect(Collectors.toMap(Region::getId, row -> row));
        for (int index = 0; index < request.ids().size(); index++) {
            byId.get(request.ids().get(index)).changeSortOrder(index + 1);
        }
    }

    // 깊이 2 고정(V1 스키마 주석: "3단은 서비스가 거부")의 유일한 관문 — create와 reorder가
    // 같은 검사를 쓴다. 존재-만-검사로 두면 리프를 부모로 준 3단 행이 생기는데, 그 행은
    // 유저 트리·관리자 트리 어디에도 그려지지 않고 재정렬로도 접근 불가한 유령이 된다.
    private void requireTopLevelParent(Long parentId) {
        if (parentId == null) {
            return;
        }
        Region parent = regionRepository.findById(parentId)
                .orElseThrow(() -> new MasterException(MASTER_REFERENCE_NOT_FOUND));
        if (!parent.isRoot()) {
            throw new MasterException(MASTER_REFERENCE_NOT_FOUND);
        }
    }
}
