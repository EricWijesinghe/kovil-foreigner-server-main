package com.kovil.domain.partner.service;

import com.kovil.domain.listing.entity.BrokerageOffice;
import com.kovil.domain.listing.repository.BrokerageOfficeRepository;
import com.kovil.domain.partner.dto.response.RegNoSuspensionView;
import com.kovil.domain.partner.entity.SuspendedRegNumber;
import com.kovil.domain.partner.repository.SuspendedRegNumberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/*
 * 정지된 중개등록번호의 이력 — 남기기(부문 정지) · 읽기(부문 심사 · 변경 신청 심사의 경고).
 *
 * ★ 경고만 한다(PO 확정 2026-10-07). 이력이 있다고 신청 · 승인을 막지 않는다 — 번호 중복은 승인에서(claimed_reg_no),
 *   정지 세탁은 "정지 중 탈퇴 불가"로 막고, 이 이력은 그 위에서 관리자가 판단할 근거를 준다.
 * ★ 번호로도, 사무소로도 찾는다. 번호만 보면 정지됐던 사무소가 변경 승인으로 번호를 바꾼 뒤 자기 경고가 사라지고,
 *   사무소만 보면 대표자가 떠나 번호가 풀린 뒤 같은 번호로 온 다른 사람의 신청에서 이력이 끊긴다.
 */
@Service
@RequiredArgsConstructor
public class RegNoSuspensionService {

    private final SuspendedRegNumberRepository repository;
    private final BrokerageOfficeRepository officeRepository;

    // 정지 한 번 = 한 행. 사유는 다듬어 받은 그대로 남긴다(관리자 내부 기록).
    @Transactional
    public void record(String regNo, Long officeId, Long divisionId, Long suspendedBy, String reason,
                       LocalDateTime suspendedAt) {
        repository.save(SuspendedRegNumber.builder()
                .regNo(regNo).officeId(officeId).divisionId(divisionId)
                .suspendedBy(suspendedBy).reason(reason).suspendedAt(suspendedAt)
                .build());
    }

    /*
     * 심사 상세의 정지 이력 — 심사 대상 번호들(부문 심사면 신청 번호, 변경 신청이면 지금 번호와 바꿀 번호)의 정지와
     * 그 사무소 자신의 정지(officeId — 없으면 번호로만). 최근 정지가 먼저이고, 번호와 사무소 둘 다로 걸린 행도 한 번만 나온다.
     * 상호는 저장하지 않고 그 사무소의 지금 상호를 읽는다 — 사무소 행은 지워지지 않는다. 화면은 officeUuid 로
     * "이 사무소 자신의 정지"를 가른다.
     */
    @Transactional(readOnly = true)
    public List<RegNoSuspensionView> historyOf(Collection<String> regNos, Long officeId) {
        List<String> numbers = distinctNonNull(regNos);
        if (numbers.isEmpty()) {
            return List.of();
        }
        List<SuspendedRegNumber> rows = repository.findHistory(numbers, officeId);
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, BrokerageOffice> offices = officeRepository
                .findAllById(rows.stream().map(SuspendedRegNumber::getOfficeId).distinct().toList()).stream()
                .collect(Collectors.toMap(BrokerageOffice::getId, Function.identity()));
        return rows.stream()
                .map(row -> {
                    BrokerageOffice office = offices.get(row.getOfficeId());
                    return new RegNoSuspensionView(row.getSuspendedAt(), row.getRegNo(),
                            office == null ? null : office.getUuid(),
                            office == null ? null : office.getName(),
                            row.getReason());
                })
                .toList();
    }

    // 목록 한 페이지의 경고 표시 — 이 중 정지된 적이 있는 번호들과 사무소들을 한 번에 읽는다.
    @Transactional(readOnly = true)
    public SuspensionMarks marksAmong(Collection<String> regNos, Collection<Long> officeIds) {
        List<String> numbers = distinctNonNull(regNos);
        List<Long> offices = officeIds.stream().filter(Objects::nonNull).distinct().toList();
        return new SuspensionMarks(
                numbers.isEmpty() ? Set.of() : new HashSet<>(repository.findRegNosIn(numbers)),
                offices.isEmpty() ? Set.of() : new HashSet<>(repository.findOfficeIdsIn(offices)));
    }

    private static List<String> distinctNonNull(Collection<String> values) {
        return values.stream().filter(Objects::nonNull).distinct().toList();
    }

    // 정지된 적이 있는 번호 · 사무소. 한 줄의 경고 = 그 줄의 번호들 중 하나 또는 그 사무소가 여기 있다.
    public record SuspensionMarks(Set<String> regNos, Set<Long> officeIds) {

        public boolean any(Long officeId, String... numbers) {
            if (officeId != null && officeIds.contains(officeId)) {
                return true;
            }
            for (String number : numbers) {
                if (number != null && regNos.contains(number)) {
                    return true;
                }
            }
            return false;
        }
    }
}
