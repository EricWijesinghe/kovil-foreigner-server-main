package com.kovil.domain.listing.service;

import com.kovil.domain.listing.dto.response.ListingInquiryResponse;
import com.kovil.domain.listing.entity.BrokerageOffice;
import com.kovil.domain.listing.entity.Listing;
import com.kovil.domain.listing.enums.ListingStatus;
import com.kovil.domain.listing.exception.ListingException;
import com.kovil.domain.listing.repository.BrokerageOfficeRepository;
import com.kovil.domain.listing.repository.ListingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static com.kovil.domain.listing.exception.ListingErrorCode.LISTING_NOT_FOUND;

/*
 * 문의. **서버는 문자를 보내지 않는다** — 미리 채운 문구와 번호를 돌려주면
 * 앱이 `sms:` 링크로 유저 휴대폰의 문자 앱을 열 뿐이다. 발송 비용도 발신번호 등록도 없다
 * (다방도 같은 방식임을 실측으로 확인했다).
 *
 * ★★ 이 서비스가 **중개사 번호를 내주는 유일한 자리**다. 상세 응답에서 번호를 뺀 것이
 *    "목록을 훑어 전수 수집"을 막기 위함이었으므로, 여기에 레이트리밋이 없으면
 *    같은 수집을 여기서 하면 된다 — 방어가 통째로 무의미해진다(RateLimitPolicy 참고).
 */
@Service
@RequiredArgsConstructor
public class ListingInquiryService {

    private static final List<ListingStatus> VISIBLE =
            List.of(ListingStatus.ACTIVE, ListingStatus.RESERVED);

    private final ListingRepository listingRepository;
    private final BrokerageOfficeRepository officeRepository;

    @Transactional
    public ListingInquiryResponse inquire(String listingUuid) {
        // 공개 중인 매물만 — 내려간 매물의 번호를 내주면 "상세는 막고 문의는 뚫린" 꼴이 된다.
        Listing listing = listingRepository.findPublicByUuid(listingUuid, VISIBLE)
                .orElseThrow(() -> new ListingException(LISTING_NOT_FOUND));
        BrokerageOffice office = officeRepository.findById(listing.getOfficeId())
                .orElseThrow(() -> new ListingException(LISTING_NOT_FOUND));

        listingRepository.increaseInquiryCount(listing.getId());

        return new ListingInquiryResponse(
                orOfficeTel(listing.getContactSmsNumber(), office),
                orOfficeTel(listing.getContactCallNumber(), office),
                smsBodyOf(listing));
    }

    /*
     * ★ 매물에 지정된 번호가 없으면 **사무소 공시 번호**로 떨어진다. 둘을 가른 이유는
     *   안심번호·대표번호 때문인데(실측에서 다방도 달랐다), 지정이 없을 때까지
     *   비워 보내면 유저가 연락할 길이 사라진다.
     */
    private String orOfficeTel(String contact, BrokerageOffice office) {
        return contact == null || contact.isBlank() ? office.getTel() : contact;
    }

    /*
     * ★★ **한국어로 고정한다.** 유학생 화면은 각자의 언어지만 이 문구를 받는 사람은
     *    한국인 중개사다 — 영어로 보내면 말이 통하지 않는다. 다방에 없는 우리만의 요구다.
     *
     * ★ 매물번호를 넣는 이유: 중개사는 매물을 수십~수백 건 들고 있어, 번호가 없으면
     *   "어느 집 말씀이신가요"부터 시작한다.
     */
    private String smsBodyOf(Listing listing) {
        return "[KOVIL] 매물 문의드립니다. 상담 가능하실까요? (매물번호: "
                + listing.getListingNo() + ")";
    }
}
