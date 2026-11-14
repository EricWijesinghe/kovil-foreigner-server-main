package com.kovil.domain.listing.service;

import com.kovil.domain.listing.entity.BrokerProfile;
import com.kovil.domain.listing.entity.Listing;
import com.kovil.domain.listing.repository.BrokerProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/*
 * 매물 알림(만료 · 지역 닫힘)을 누구에게 보내는가. **PO 확정 2026-10-06: 올린 중개사가 아직 그 사무소에 있으면
 * 그 사람에게만, 떠났으면 사무소의 현재 중개사 전원에게.**
 *
 * ★ 매물은 사무소 것이라 올린 사람이 떠나도 동료가 이어서 관리한다. 올린 사람에게만 보내면 알림이
 *   **떠난 사람에게 가고 실제 관리자는 모른다.** 반대로 늘 전원에게 보내면 평소 알림이 중개사 수만큼 불어난다.
 * ★ "아직 그 사무소에 있다"는 **프로필의 사무소가 매물의 사무소와 같은가**로 본다 — 다른 사무소로 옮긴
 *   사람은 프로필이 남아 있어도 떠난 사람이다.
 */
@Component
@RequiredArgsConstructor
public class ListingAlertRecipients {

    private final BrokerProfileRepository profileRepository;

    public List<Long> of(Listing listing) {
        Long uploader = listing.getCreatedByUserId();
        Optional<BrokerProfile> profile = profileRepository.findByUserId(uploader);
        if (profile.isPresent() && profile.get().getOfficeId().equals(listing.getOfficeId())) {
            return List.of(uploader);
        }
        return profileRepository.findByOfficeId(listing.getOfficeId()).stream()
                .map(BrokerProfile::getUserId)
                .toList();
    }
}
