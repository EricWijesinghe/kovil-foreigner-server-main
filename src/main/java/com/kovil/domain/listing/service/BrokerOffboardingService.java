package com.kovil.domain.listing.service;

import com.kovil.domain.listing.entity.BrokerProfile;
import com.kovil.domain.listing.entity.Listing;
import com.kovil.domain.listing.enums.ListingStatus;
import com.kovil.domain.listing.repository.BrokerProfileRepository;
import com.kovil.domain.listing.repository.BrokerageOfficeRepository;
import com.kovil.domain.listing.repository.ListingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class BrokerOffboardingService {

    private static final List<ListingStatus> PUBLIC = List.of(ListingStatus.ACTIVE, ListingStatus.RESERVED);

    private final BrokerProfileRepository profileRepository;
    private final BrokerageOfficeRepository officeRepository;
    private final ListingRepository listingRepository;

    @Transactional
    public void offboard(Long userId) {
        Optional<BrokerProfile> profile = profileRepository.findByUserId(userId);
        if (profile.isEmpty()) {
            return;
        }
        Long officeId = profile.get().getOfficeId();
        officeRepository.lockById(officeId);
        profileRepository.delete(profile.get());
        profileRepository.flush();
        if (!profileRepository.lockIdsByOfficeId(officeId).isEmpty()) {
            return;
        }
        List<Listing> live = listingRepository.findAllByOfficeIdAndStatusIn(officeId, PUBLIC);
        live.forEach(Listing::unpublish);
        if (!live.isEmpty()) {
            log.info("[listing] 사무소에 남은 중개사가 없어 매물 {}건을 내렸다 office={}", live.size(), officeId);
        }
    }
}
