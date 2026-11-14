package com.kovil.domain.listing.dto;

import com.kovil.domain.listing.dto.response.ListingDetailResponse;
import com.kovil.domain.listing.dto.response.ListingSummaryResponse;
import com.kovil.domain.listing.entity.BrokerageOffice;
import com.kovil.domain.listing.entity.Listing;
import com.kovil.domain.listing.enums.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * **공개 응답이 흘려서는 안 되는 것**을 고정한다.
 *
 * ★ 이 테스트가 없으면 유출은 조용히 일어난다 — 필드를 하나 더하는 것은 리뷰에서
 *   "편의 기능"으로 보이고, 한 번 나간 좌표·번호는 회수할 수 없다.
 */
class PublicResponseLeakTest {

    private static final BigDecimal REAL_LAT = new BigDecimal("37.4862378");
    private static final BigDecimal REAL_LNG = new BigDecimal("126.9331220");
    private static final BigDecimal DISPLAY_LAT = new BigDecimal("37.4862552");
    private static final BigDecimal DISPLAY_LNG = new BigDecimal("126.9332423");

    /*
     * ★★ 목록을 훑어 임대인 주소를 전수 수집하는 것을 막는 유일한 방벽이다.
     *    다방도 목록에는 randomLocation 만 싣는다.
     */
    @Test
    @DisplayName("공개 응답의 좌표는 흐린 값이다 — 실좌표가 아니다")
    void never_exposes_real_coordinates() {
        var summary = ListingSummaryResponse.of(listing(null, null), null, false, null, null,
                "https://file.example.test/a.jpg", false);
        assertThat(summary.lat()).isEqualByComparingTo(DISPLAY_LAT);
        assertThat(summary.lng()).isEqualByComparingTo(DISPLAY_LNG);
        assertThat(summary.lat()).isNotEqualByComparingTo(REAL_LAT);

        var detail = detailOf(listing(null, null));
        assertThat(detail.lat()).isEqualByComparingTo(DISPLAY_LAT);
        assertThat(detail.lng()).isEqualByComparingTo(DISPLAY_LNG);
    }

    // 중개사가 숨기기로 한 것을 우리가 흘리면 약속을 깬 것이다.
    @Test
    @DisplayName("층을 숨긴 매물은 공개 응답에 정확한 층이 없다")
    void never_exposes_hidden_floor() {
        var summary = ListingSummaryResponse.of(listing(5, FloorBand.MID), null, false, null, null, null, false);
        assertThat(summary.floor()).isNull();
        assertThat(summary.floorBand()).isEqualTo(FloorBand.MID);

        assertThat(detailOf(listing(5, FloorBand.MID)).floor()).isNull();
    }

    @Test
    @DisplayName("숨기지 않은 매물은 층이 그대로 나온다")
    void exposes_floor_when_not_hidden() {
        assertThat(ListingSummaryResponse.of(listing(5, null), null, false, null, null, null, false).floor())
                .isEqualTo(5);
    }

    /*
     * ★★ 상세에 번호를 실으면 **목록을 훑어 중개사 번호를 전수 수집**할 수 있다.
     *    다방은 상세에 실어 주지만 우리는 문의 엔드포인트에서만 준다.
     *    필드 **이름**으로 검사하는 이유: 나중에 누가 tel 을 추가해도 여기서 걸린다.
     */
    @Test
    @DisplayName("공개 응답 어디에도 전화번호 필드가 없다")
    void has_no_phone_field_at_all() {
        for (Class<?> type : List.of(ListingSummaryResponse.class, ListingDetailResponse.class,
                ListingDetailResponse.OfficeView.class)) {
            List<String> suspicious = Arrays.stream(type.getRecordComponents())
                    .map(RecordComponent::getName)
                    .filter(n -> n.toLowerCase().contains("tel")
                            || n.toLowerCase().contains("phone")
                            || n.toLowerCase().contains("contact"))
                    .toList();
            assertThat(suspicious)
                    .as("%s 에 연락처로 보이는 필드가 생겼다 — 번호는 문의 엔드포인트에서만 준다",
                            type.getSimpleName())
                    .isEmpty();
        }
    }

    // 사무소 정보는 「공인중개사법」 제18조의2 법정 표시 항목이라 **빠지면 위법**이다.
    @Test
    @DisplayName("상세에 사무소 법정 표시 항목이 실린다")
    void includes_statutory_office_fields() {
        var office = ListingDetailResponse.OfficeView.of(office());
        assertThat(office.officeUuid()).isEqualTo("o-1");
        assertThat(office.name()).isNotBlank();
        assertThat(office.ceoName()).isNotBlank();
        assertThat(office.regNo()).isNotBlank();
        assertThat(office.address()).isNotBlank();
    }

    /*
     * ★ 토글이 꺼져 있으면 **담지 않는다.** "화면이 안 보여 준다"로 두면 응답에는 남아
     *   누구나 개발자 도구로 읽는다.
     */
    @Test
    @DisplayName("상세 주소 공개를 끄면 주소가 응답에서 빠진다")
    void hides_full_address_when_toggle_is_off() {
        assertThat(detailOf(listing(null, null)).address()).isNull();
        assertThat(detailOf(base().showDetailAddress(true).build()).address()).isNotBlank();
    }

    private ListingDetailResponse detailOf(Listing l) {
        return ListingDetailResponse.of(l, office(), null, List.of(), List.of(), List.of(), false);
    }

    private BrokerageOffice office() {
        return BrokerageOffice.builder().uuid("o-1").regNo("99999-2026-00002")
                .name("테스트공인중개사사무소").ceoName("홍길동")
                .address("서울특별시 관악구 신림동").tel("02-000-0000").build();
    }

    private Listing listing(Integer floor, FloorBand band) {
        return base().floor(floor).floorBand(band).build();
    }

    private Listing.ListingBuilder<?, ?> base() {
        return Listing.builder()
                .id(1L).uuid("l-1").listingNo("LS-261005-ABC123")
                .officeId(1L).createdByUserId(10L)
                .category(ListingCategory.ONE_TWO_ROOM).roomType(RoomType.ONE_ROOM)
                .title("테스트").sellingType(SellingType.MONTHLY_RENT)
                .deposit(3_000_000L).monthlyRent(300_000L)
                .exclusiveArea(new BigDecimal("26.44")).floorType(FloorType.GROUND)
                .residentRegistration(AvailabilityAnswer.POSSIBLE)
                .depositInsurance(AvailabilityAnswer.ASK)
                .realLat(REAL_LAT).realLng(REAL_LNG)
                .displayLat(DISPLAY_LAT).displayLng(DISPLAY_LNG)
                .addressJibun("서울특별시 관악구 신림동 103-188").dongName("신림동");
    }
}
