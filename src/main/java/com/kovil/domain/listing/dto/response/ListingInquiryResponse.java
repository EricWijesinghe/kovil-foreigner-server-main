package com.kovil.domain.listing.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "문의 수단")
public record ListingInquiryResponse(

        @Schema(description = "문자 받을 번호", example = "01000000000") String smsNumber,
        @Schema(description = "전화 받을 번호", example = "02-000-0000") String callNumber,

        @Schema(description = "미리 채워 보낼 한국어 본문. 앱이 sms: 링크의 body 로 쓴다")
        String smsBody
) {}
