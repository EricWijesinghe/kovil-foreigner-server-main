package com.kovil.domain.listing.service;

import com.kovil.domain.listing.entity.BrokerProfile;
import com.kovil.domain.listing.entity.BrokerageOffice;
import com.kovil.domain.listing.entity.Listing;
import com.kovil.domain.listing.exception.ListingException;
import com.kovil.domain.listing.repository.BrokerProfileRepository;
import com.kovil.domain.listing.repository.BrokerageOfficeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import static com.kovil.domain.listing.exception.ListingErrorCode.BROKER_PROFILE_NOT_FOUND;
import static com.kovil.domain.listing.exception.ListingErrorCode.LISTING_ACCESS_DENIED;
import static com.kovil.domain.listing.exception.ListingErrorCode.OFFICE_NOT_ACTIVE;


@Component
@RequiredArgsConstructor
public class ListingAccessPolicy {

    private final BrokerProfileRepository profileRepository;
    private final BrokerageOfficeRepository officeRepository;

    public BrokerProfile requireProfile(Long userId) {
        return profileRepository.findByUserId(userId)
                .orElseThrow(() -> new ListingException(BROKER_PROFILE_NOT_FOUND));
    }

    public BrokerageOffice requireAdvertisableOffice(Long userId) {
        BrokerageOffice office = officeRepository.findById(requireProfile(userId).getOfficeId())
                .orElseThrow(() -> new ListingException(BROKER_PROFILE_NOT_FOUND));
        if (!officeRepository.isAdvertisable(office.getId())) {
            throw new ListingException(OFFICE_NOT_ACTIVE);
        }
        return office;
    }

    public void requireOwnership(BrokerProfile profile, Listing listing) {
        if (!profile.getOfficeId().equals(listing.getOfficeId())) {
            throw new ListingException(LISTING_ACCESS_DENIED);
        }
    }
}
