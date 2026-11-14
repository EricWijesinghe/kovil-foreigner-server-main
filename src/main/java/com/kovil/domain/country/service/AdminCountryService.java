package com.kovil.domain.country.service;

import com.kovil.common.dto.request.MasterOrderRequest;
import com.kovil.common.exception.MasterException;
import com.kovil.common.master.MasterOrderPolicy;
import com.kovil.domain.country.dto.AdminCountryRequest;
import com.kovil.domain.country.dto.AdminCountryResponse;
import com.kovil.domain.country.entity.Country;
import com.kovil.domain.country.repository.CountryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.kovil.common.exception.MasterErrorCode.MASTER_DUPLICATE;
import static com.kovil.common.exception.MasterErrorCode.MASTER_NOT_FOUND;

@Service
@RequiredArgsConstructor
public class AdminCountryService {

    private final CountryRepository countryRepository;

    @Transactional(readOnly = true)
    public List<AdminCountryResponse> findAll() {
        return countryRepository.findAllByOrderBySortOrderAscIdAsc().stream()
                .map(AdminCountryResponse::from)
                .toList();
    }

    @Transactional
    public AdminCountryResponse create(AdminCountryRequest request) {
        String code = request.code().strip().toUpperCase();
        if (countryRepository.existsByCode(code)) {
            throw new MasterException(MASTER_DUPLICATE);
        }
        return AdminCountryResponse.from(countryRepository.save(Country.builder()
                .code(code)
                .nameEn(request.nameEn().strip())
                .flagEmoji(request.flagEmoji())
                .sortOrder(request.sortOrder() == null ? countryRepository.maxSortOrder() + 1 : request.sortOrder())
                .isActive(request.isActive() == null || request.isActive())
                .build()));
    }

    @Transactional
    public AdminCountryResponse update(Long id, AdminCountryRequest request) {
        Country country = countryRepository.findById(id)
                .orElseThrow(() -> new MasterException(MASTER_NOT_FOUND));
        country.update(request.nameEn().strip(), request.flagEmoji(),
                request.sortOrder() == null ? country.getSortOrder() : request.sortOrder(),
                request.isActive() == null ? country.getIsActive() : request.isActive());
        return AdminCountryResponse.from(country);
    }

    // 이동 조작(위/아래/맨 위/맨 아래)의 원자 단위 — 축 전체를 한 트랜잭션에서 1..N으로 재부여한다.
    // 행 단위 PUT을 순차 발사하면 중간 실패 시 순서가 반쯤 섞인 채 남는다.
    @Transactional
    public void reorder(MasterOrderRequest request) {
        List<Country> scope = countryRepository.findAllForReorder();
        MasterOrderPolicy.requireExactPermutation(
                scope.stream().map(Country::getId).toList(), request.ids());

        Map<Long, Country> byId = scope.stream()
                .collect(Collectors.toMap(Country::getId, row -> row));
        for (int index = 0; index < request.ids().size(); index++) {
            byId.get(request.ids().get(index)).changeSortOrder(index + 1);
        }
    }
}
