package com.kovil.domain.rentalrequest.service;

import com.kovil.domain.listing.entity.BrokerProfile;
import com.kovil.domain.listing.entity.BrokerageOffice;
import com.kovil.domain.listing.repository.BrokerProfileRepository;
import com.kovil.domain.listing.repository.BrokerageOfficeRepository;
import com.kovil.domain.rentalrequest.dto.response.RentalRequestResponse;
import com.kovil.domain.rentalrequest.entity.RentalRequest;
import com.kovil.domain.rentalrequest.enums.RentalRequestStatus;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.repository.UserRepository;
import com.kovil.infra.crypto.Encryptor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/*
 * 유학생 화면의 "맡은 사무소" — 상호 · 대표자명 · 소재지 · 대표번호, 그리고 진행 중일 때만 대표자 휴대폰.
 *
 * ★ 대표자 휴대폰은 **진행 중인 요청의 사무소만** 읽는다. 종료 · 취소된 요청을 위해 복호화하지 않는다 —
 *   서로 연락하는 동안만 번호가 오간다(PO 확정 2026-10-07).
 * ★ 대표자는 broker_profiles 로 찾는다(사무소 : 계정 = 1 : 1). 대표자 계정이 ACTIVE 가 아니면 번호를 내지 않는다 —
 *   공개 매물이 계정 정지로 숨는 것과 같은 조건이다. 정지 · 탈퇴는 그 사무소의 진행 중 요청을 대기로 돌리므로 보통은 여기 오지
 *   않지만, 그 사이의 순간에도 정지된 사람의 번호가 나가지 않게 한다.
 * ★ 목록은 페이지 단위로 한 번에 읽는다 — 요청마다 사무소 · 대표자 · 계정을 부르면 20행이 60왕복이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClaimedOfficeResolver {

    private final BrokerageOfficeRepository officeRepository;
    private final BrokerProfileRepository brokerProfileRepository;
    private final UserRepository userRepository;
    private final Encryptor encryptor;

    // 요청 순서 그대로의 목록 — 맡은 사무소가 없는(대기) 요청 자리는 null 이다.
    public List<RentalRequestResponse.Office> officesOf(List<RentalRequest> requests) {
        List<Long> officeIds = requests.stream().map(RentalRequest::getOfficeId)
                .filter(Objects::nonNull).distinct().toList();
        if (officeIds.isEmpty()) {
            return requests.stream().map(request -> (RentalRequestResponse.Office) null).toList();
        }
        Map<Long, BrokerageOffice> offices = officeRepository.findAllById(officeIds).stream()
                .collect(Collectors.toMap(BrokerageOffice::getId, Function.identity()));
        Map<Long, String> ceoPhones = ceoPhonesOf(requests.stream()
                .filter(request -> request.getStatus() == RentalRequestStatus.IN_PROGRESS)
                .map(RentalRequest::getOfficeId)
                .filter(Objects::nonNull).distinct().toList());

        return requests.stream().map(request -> {
            BrokerageOffice office = request.getOfficeId() == null ? null : offices.get(request.getOfficeId());
            if (office == null) {
                return null;
            }
            String ceoPhone = request.getStatus() == RentalRequestStatus.IN_PROGRESS
                    ? ceoPhones.get(office.getId()) : null;
            return new RentalRequestResponse.Office(
                    office.getName(), office.getCeoName(), office.getAddress(), office.getTel(), ceoPhone);
        }).toList();
    }

    private Map<Long, String> ceoPhonesOf(List<Long> officeIds) {
        if (officeIds.isEmpty()) {
            return Map.of();
        }
        List<BrokerProfile> profiles = brokerProfileRepository.findByOfficeIdIn(officeIds);
        if (profiles.isEmpty()) {
            return Map.of();
        }
        Map<Long, User> ceos = userRepository.findAllById(profiles.stream().map(BrokerProfile::getUserId).toList())
                .stream().collect(Collectors.toMap(User::getId, Function.identity()));
        Map<Long, String> phones = new HashMap<>();
        for (BrokerProfile profile : profiles) {
            User ceo = ceos.get(profile.getUserId());
            if (ceo != null && ceo.isActive()) {
                String phone = decryptPhone(ceo);
                if (phone != null) {
                    phones.put(profile.getOfficeId(), phone);
                }
            }
        }
        return phones;
    }

    // 복호화가 실패해도 요청 화면 전체가 죽으면 안 된다 — 그 번호만 비운다(대표번호로 연락할 수 있다).
    private String decryptPhone(User ceo) {
        if (ceo.getPhone() == null) {
            return null;
        }
        try {
            return encryptor.decrypt(ceo.getPhone());
        } catch (RuntimeException e) {
            log.warn("[rental-request] 대표자 휴대폰 복호화 실패 user={}", ceo.getId());
            return null;
        }
    }
}
