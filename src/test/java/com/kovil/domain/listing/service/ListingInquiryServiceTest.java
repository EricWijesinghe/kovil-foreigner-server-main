package com.kovil.domain.listing.service;

import com.kovil.domain.listing.entity.BrokerageOffice;
import com.kovil.domain.listing.entity.Listing;
import com.kovil.domain.listing.enums.*;
import com.kovil.domain.listing.exception.ListingException;
import com.kovil.domain.listing.repository.BrokerageOfficeRepository;
import com.kovil.domain.listing.repository.ListingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/*
 * 문의. **중개사 번호가 나가는 유일한 자리**라 여기서 새면 상세에서 번호를 뺀 의미가 없다.
 */
class ListingInquiryServiceTest {

    private ListingRepository listingRepository;
    private BrokerageOfficeRepository officeRepository;
    private ListingInquiryService service;

    @BeforeEach
    void setUp() {
        listingRepository = mock(ListingRepository.class);
        officeRepository = mock(BrokerageOfficeRepository.class);
        service = new ListingInquiryService(listingRepository, officeRepository);
        given(officeRepository.findById(any())).willReturn(Optional.of(office()));
    }

    /*
     * ★ 매물 지정 번호와 사무소 공시 번호를 가른 이유는 안심번호·대표번호 때문이다
     *   (실측에서 다방도 둘이 달랐다 — agent_tel 과 sms_number).
     */
    @Test
    @DisplayName("매물에 지정된 번호가 있으면 그것을 준다")
    void prefers_listing_contact_numbers() {
        givenListing(listing("01000000000", "010-0000-1111"));

        var out = service.inquire("l-1");

        assertThat(out.smsNumber()).isEqualTo("01000000000");
        assertThat(out.callNumber()).isEqualTo("010-0000-1111");
    }

    // 지정이 없을 때 비워 보내면 유저가 연락할 길이 사라진다.
    @Test
    @DisplayName("지정 번호가 없으면 사무소 공시 번호로 떨어진다")
    void falls_back_to_office_tel() {
        givenListing(listing(null, "  "));

        var out = service.inquire("l-1");

        assertThat(out.smsNumber()).isEqualTo("02-000-0000");
        assertThat(out.callNumber()).isEqualTo("02-000-0000");
    }

    /*
     * ★★ 받는 사람이 **한국인 중개사**다. 유학생 화면은 각자의 언어지만 전송 본문이
     *    영어면 말이 통하지 않는다 — 다방에 없는 우리만의 요구라 테스트로 박는다.
     */
    @Test
    @DisplayName("문자 본문은 한국어이고 매물번호가 들어 있다")
    void sms_body_is_korean_and_carries_listing_number() {
        givenListing(listing(null, null));

        String body = service.inquire("l-1").smsBody();

        assertThat(body).contains("LS-261005-ABC123");
        assertThat(body).containsPattern("[가-힣]");
    }

    // 문의 수 = 번호가 노출된 횟수다. 안 세면 수집 시도를 알아볼 수단이 없다.
    @Test
    @DisplayName("문의하면 문의 수가 오른다")
    void increases_inquiry_count() {
        Listing listing = listing(null, null);
        givenListing(listing);

        service.inquire("l-1");

        verify(listingRepository).increaseInquiryCount(listing.getId());
    }

    /*
     * ★ 내려간 매물의 번호를 내주면 "상세는 막혔는데 문의는 뚫린" 꼴이 된다 —
     *   공개가 끝난 매물의 번호를 긁는 경로가 그대로 남는다.
     */
    @Test
    @DisplayName("공개 중이 아닌 매물은 번호를 주지 않는다")
    void refuses_when_listing_is_not_public() {
        given(listingRepository.findPublicByUuid(eq("l-1"), any())).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.inquire("l-1"))
                .isInstanceOf(ListingException.class)
                .satisfies(e -> assertThat(((ListingException) e).getErrorCode().getCode())
                        .isEqualTo("LISTING_NOT_FOUND"));
    }

    private void givenListing(Listing listing) {
        given(listingRepository.findPublicByUuid(eq("l-1"), any())).willReturn(Optional.of(listing));
    }

    private BrokerageOffice office() {
        return BrokerageOffice.builder().uuid("o-1").regNo("99999-2026-00002")
                .name("테스트공인중개사사무소").ceoName("홍길동")
                .address("서울특별시 관악구").tel("02-000-0000").build();
    }

    private Listing listing(String sms, String call) {
        return Listing.builder()
                .id(1L).uuid("l-1").listingNo("LS-261005-ABC123")
                .officeId(1L).createdByUserId(10L)
                .category(ListingCategory.ONE_TWO_ROOM).roomType(RoomType.ONE_ROOM)
                .title("테스트").sellingType(SellingType.MONTHLY_RENT)
                .deposit(3_000_000L).monthlyRent(300_000L)
                .exclusiveArea(new BigDecimal("26.44")).floorType(FloorType.GROUND)
                .residentRegistration(AvailabilityAnswer.POSSIBLE)
                .depositInsurance(AvailabilityAnswer.ASK)
                .realLat(new BigDecimal("37.4862378")).realLng(new BigDecimal("126.9331220"))
                .displayLat(new BigDecimal("37.4862552")).displayLng(new BigDecimal("126.9332423"))
                .addressJibun("서울특별시 관악구 신림동")
                .contactSmsNumber(sms).contactCallNumber(call)
                .status(ListingStatus.ACTIVE).build();
    }
}
