package com.kovil.domain.partner.service;

import com.kovil.domain.listing.entity.BrokerProfile;
import com.kovil.domain.listing.entity.BrokerageOffice;
import com.kovil.domain.listing.repository.BrokerProfileRepository;
import com.kovil.domain.listing.repository.BrokerageOfficeRepository;
import com.kovil.domain.partner.dto.response.AdminPartnerDivisionDetailResponse.SameRegNoApplication;
import com.kovil.domain.partner.entity.BrokerageOfficeChangeRequest;
import com.kovil.domain.partner.entity.PartnerDivision;
import com.kovil.domain.partner.enums.OfficeChangeRequestStatus;
import com.kovil.domain.partner.enums.PartnerDivisionType;
import com.kovil.domain.partner.enums.SameRegNoApplicationKind;
import com.kovil.domain.partner.repository.BrokerageOfficeChangeRequestRepository;
import com.kovil.domain.partner.repository.PartnerDivisionRepository;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/*
 * 심사 화면의 "같은 등록번호의 다른 신청" — 부문 심사 상세와 변경 심사 상세가 함께 쓴다. 심사자는 "이 번호로 누가 또 신청했나,
 * 누가 이미 쓰고 있나, 누가 이 번호로 옮기려 하나"를 보고 한 명만 승인한다.
 *   · 부문 신청 줄 — 그 번호를 신청 번호로 낸 사무소와 그 번호를 잡고 있는 사무소. 잡고 있는 줄이 먼저 나온다 — 그 줄이 있으면
 *     이 번호의 승인(부문 승인 · 이 번호로 옮기는 변경 승인)이 409 로 막힌다.
 *   · 변경 신청 줄 — 승인된 다른 사무소가 이 번호를 바꿀 번호로 둔 대기 건(먼저 낸 것부터). 그쪽이 먼저 승인되면 번호를 잡아 간다.
 *     번호를 옮기지 않는 변경 신청(그 사무소가 이미 이 번호를 신청 번호나 잡은 번호로 쓰는 것)은 싣지 않는다 — 그 사무소는
 *     부문 신청 줄로 이미 나오고, 상호만 바꾸는 신청이 경쟁자로 두 번 보이면 판단을 흐린다.
 * ★ 대표자 연결이 없는 사무소(대표자가 탈퇴해 떠난 것)의 부문 신청 줄은 싣지 않는다. 승인할 신청도 아니고 번호도 풀려 있어
 *   이 판정에 영향이 없다. 그 사무소의 대기 중 변경 신청은 탈퇴 정리가 닫으므로 남아 있지 않다.
 * ★ 사무소 · 연결 · 부문 · 사람을 한 번에 읽는다 — 줄마다 찾으면 같은 번호의 신청 수만큼 쿼리가 는다.
 */
@Component
@RequiredArgsConstructor
public class SameRegNoApplicationFinder {

    private final BrokerageOfficeRepository officeRepository;
    private final BrokerProfileRepository profileRepository;
    private final PartnerDivisionRepository divisionRepository;
    private final BrokerageOfficeChangeRequestRepository changeRequestRepository;
    private final UserRepository userRepository;

    // regNo 를 쓰거나 쓰려는 다른 사무소의 신청. excludeOfficeId(지금 심사 중인 사무소)의 것은 뺀다.
    public List<SameRegNoApplication> find(String regNo, Long excludeOfficeId) {
        if (regNo == null) {
            return List.of();
        }
        List<BrokerageOffice> others = officeRepository.findOthersWithRegNo(regNo, excludeOfficeId);
        List<BrokerageOfficeChangeRequest> changes = changeRequestRepository
                .findByRegNoAndStatusOrderBySubmittedAtAscIdAsc(regNo, OfficeChangeRequestStatus.PENDING).stream()
                .filter(r -> !r.getOfficeId().equals(excludeOfficeId))
                .toList();
        if (others.isEmpty() && changes.isEmpty()) {
            return List.of();
        }

        Map<Long, BrokerageOffice> changeOffices = changes.isEmpty() ? Map.of() : officeRepository
                .findAllById(changes.stream().map(BrokerageOfficeChangeRequest::getOfficeId).distinct().toList())
                .stream().collect(Collectors.toMap(BrokerageOffice::getId, Function.identity()));
        List<BrokerageOfficeChangeRequest> moving = changes.stream()
                .filter(r -> movesTo(changeOffices.get(r.getOfficeId()), regNo))
                .toList();

        Map<Long, Long> userByOffice = others.isEmpty() ? Map.of() : profileRepository
                .findByOfficeIdIn(others.stream().map(BrokerageOffice::getId).toList()).stream()
                .collect(Collectors.toMap(BrokerProfile::getOfficeId, BrokerProfile::getUserId));
        Set<Long> userIds = new LinkedHashSet<>(userByOffice.values());
        moving.forEach(r -> userIds.add(r.getRequestedBy()));
        if (userIds.isEmpty()) {
            return List.of();
        }
        Map<Long, PartnerDivision> divisionByUser = divisionRepository
                .findByUserIdInAndDivision(userIds, PartnerDivisionType.REAL_ESTATE_BROKER).stream()
                .collect(Collectors.toMap(PartnerDivision::getUserId, Function.identity()));
        Map<Long, User> users = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));

        List<SameRegNoApplication> rows = new ArrayList<>(others.stream()
                .filter(other -> divisionByUser.containsKey(userByOffice.get(other.getId())))
                .map(other -> divisionRow(other, regNo, divisionByUser.get(userByOffice.get(other.getId())),
                        users.get(userByOffice.get(other.getId()))))
                .sorted(Comparator.comparing(SameRegNoApplication::claimed).reversed())
                .toList());
        moving.forEach(r -> rows.add(changeRow(r, changeOffices.get(r.getOfficeId()),
                divisionByUser.get(r.getRequestedBy()), users.get(r.getRequestedBy()))));
        return List.copyOf(rows);
    }

    // 그 변경 신청이 이 번호로 **옮기는가** — 사무소가 이미 이 번호를 신청 번호나 잡은 번호로 쓰고 있으면 옮기는 것이 아니다.
    private static boolean movesTo(BrokerageOffice office, String regNo) {
        return office == null || !(regNo.equals(office.getRegNo()) || regNo.equals(office.getClaimedRegNo()));
    }

    private static SameRegNoApplication divisionRow(BrokerageOffice other, String regNo, PartnerDivision division,
                                                    User user) {
        return new SameRegNoApplication(SameRegNoApplicationKind.DIVISION_APPLICATION, division.getUuid(), null,
                other.getUuid(), division.getStatus(), division.getSubmittedAt(), other.getName(), other.getCeoName(),
                user == null ? null : user.getStatus(), regNo.equals(other.getClaimedRegNo()));
    }

    // 상호 · 대표자는 그 번호와 함께 낸 값(바꿀 값)이다 — 새 등록증에 적힌 값과 맞춰 볼 것이 이것이다.
    private static SameRegNoApplication changeRow(BrokerageOfficeChangeRequest request, BrokerageOffice office,
                                                  PartnerDivision division, User user) {
        return new SameRegNoApplication(SameRegNoApplicationKind.OFFICE_CHANGE_REQUEST,
                division == null ? null : division.getUuid(), request.getUuid(),
                office == null ? null : office.getUuid(), division == null ? null : division.getStatus(),
                request.getSubmittedAt(), request.getName(), request.getCeoName(),
                user == null ? null : user.getStatus(), false);
    }
}
