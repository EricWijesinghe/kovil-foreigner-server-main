package com.kovil.domain.rentalrequest.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = """
        임대 요청 한 건. `view` 로 모양을 가른다 — `SUMMARY`(잡기 전 요약) · `FULL`(내 사무소가 맡은 요청).
        """, oneOf = {PartnerRentalRequestSummaryResponse.class, PartnerRentalRequestResponse.class})
public sealed interface PartnerRentalRequestView
        permits PartnerRentalRequestSummaryResponse, PartnerRentalRequestResponse {

    String view();
}
