package com.kovil.domain.listing.service;

import com.kovil.domain.listing.entity.Listing;
import com.kovil.domain.listing.enums.AvailabilityAnswer;
import com.kovil.domain.listing.exception.ListingException;
import com.kovil.domain.listing.repository.ListingImageRepository;
import com.kovil.domain.listing.repository.ListingMaintenanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import static com.kovil.domain.listing.exception.ListingErrorCode.PUBLISH_DESCRIPTION_REQUIRED;
import static com.kovil.domain.listing.exception.ListingErrorCode.PUBLISH_MAINTENANCE_REQUIRED;
import static com.kovil.domain.listing.exception.ListingErrorCode.PUBLISH_PHOTO_REQUIRED;
import static com.kovil.domain.listing.exception.ListingErrorCode.PUBLISH_REGION_REQUIRED;
import static com.kovil.domain.listing.exception.ListingErrorCode.PUBLISH_RESIDENT_REGISTRATION_REQUIRED;

/*
 * **게시 시점의 검증**만 모은다. 등록(DRAFT)은 느슨하게 받는다 —
 * 사진 업로드가 presign→confirm 2단계라 한 번의 POST 로 완성될 수 없고,
 * 중개사가 현장에서 나눠 채우는 흐름이 정상이기 때문이다.
 *
 * ★ 네 가지를 막는다(PO 확정 2026-10-05). 각각 **막지 않으면 생기는 일**이 다르다:
 *   - 사진 없음    → 목록에서 빈칸으로 보여 **목록 전체의 인상**을 깎는다
 *   - 관리비 없음  → 「중개대상물 표시·광고 명시사항 세부기준」 **미충족 광고**가 나간다
 *   - 설명 없음    → 번역 기능이 무의미해지고 외국인에게는 정보가 거의 없는 매물이 된다
 *   - 전입신고 ASK → 유학생에게는 **생존 조건**이다. 모른 채 계약하면 체류 행정이 막힌다
 */
@Component
@RequiredArgsConstructor
public class ListingPublishPolicy {

    private final ListingImageRepository imageRepository;
    private final ListingMaintenanceRepository maintenanceRepository;
    private final ListingRegionResolver regionResolver;

    public void requirePublishable(Listing listing) {
        if (imageRepository.countByListingId(listing.getId()) == 0) {
            throw new ListingException(PUBLISH_PHOTO_REQUIRED);
        }
        if (maintenanceRepository.findById(listing.getId()).isEmpty()) {
            throw new ListingException(PUBLISH_MAINTENANCE_REQUIRED);
        }
        if (isBlank(listing.getDescription())) {
            throw new ListingException(PUBLISH_DESCRIPTION_REQUIRED);
        }
        // ★ '확인불가(ASK)'로는 게시할 수 없다. 중개사가 임대인에게 반드시 묻게 만드는 장치다.
        if (listing.getResidentRegistration() == AvailabilityAnswer.ASK) {
            throw new ListingException(PUBLISH_RESIDENT_REGISTRATION_REQUIRED);
        }
        /*
         * ★ 서비스 지역의 시군구가 있어야 한다(PO 확정 2026-10-06). 지도 묶음이 시군구 단위라
         *   지역 없는 매물은 넓은 지도에서 **사라지고**, 지역 필터로도 못 찾는다.
         * ★ 저장 시점이 아니라 **지금** 활성인지 본다 — 저장 뒤 그 지역이 닫혔으면 게시도 닫혀야 한다.
         */
        if (!regionResolver.isPublishable(listing.getRegionId())) {
            throw new ListingException(PUBLISH_REGION_REQUIRED);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
