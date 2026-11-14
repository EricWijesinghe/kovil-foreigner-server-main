package com.kovil.domain.listing;

import com.kovil.domain.listing.entity.BrokerProfile;
import com.kovil.domain.listing.entity.BrokerageOffice;
import com.kovil.domain.listing.entity.Listing;
import com.kovil.domain.listing.entity.ListingReport;
import com.kovil.domain.listing.enums.*;
import com.kovil.domain.listing.repository.BrokerProfileRepository;
import com.kovil.domain.listing.repository.BrokerageOfficeRepository;
import com.kovil.domain.listing.repository.ListingReportRepository;
import com.kovil.domain.listing.repository.ListingRepository;
import com.kovil.domain.partner.entity.PartnerDivision;
import com.kovil.domain.partner.enums.PartnerDivisionStatus;
import com.kovil.domain.partner.enums.PartnerDivisionType;
import com.kovil.domain.partner.repository.PartnerDivisionRepository;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.user.repository.UserRepository;
import com.kovil.infra.jwt.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * 매물 신고를 **진짜 보안 필터 · 진짜 검증 · 진짜 DB** 로 태운다.
 * ★ 비로그인은 401 — 비로그인 신고는 경쟁 사무소의 허위 신고 남발 통로다(공개 경로 목록에 넣지 않았다).
 * ★ 같은 사람 · 같은 매물 두 번은 409. 서비스 검사가 먼저 막고, DB 유니크가 동시 제출을 막는다 —
 *   H2 에도 같은 유니크가 걸려 있는지(엔티티 선언) 따로 박는다.
 * ★ 신고는 아무것도 내리지 않는다 — 신고 뒤에도 매물은 공개 그대로다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ListingReportApiTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired UserRepository userRepository;
    @Autowired BrokerageOfficeRepository officeRepository;
    @Autowired BrokerProfileRepository profileRepository;
    @Autowired PartnerDivisionRepository divisionRepository;
    @Autowired ListingRepository listingRepository;
    @Autowired ListingReportRepository reportRepository;

    private Long office;
    private Long open;
    private User student;

    @BeforeEach
    void setUp() {
        office = officeOf("rp-office", "99999-2026-00081");
        open = save("rp-open", office, ListingStatus.ACTIVE);
        save("rp-done", office, ListingStatus.COMPLETED);
        student = student("rp-student");
    }

    private String bearer(User user) {
        return "Bearer " + jwtTokenProvider.issueAccessToken(user.getUuid(), UserRole.USER);
    }

    private MockHttpServletRequestBuilder report(String listingUuid, String json) {
        return post("/api/v1/listings/{uuid}/reports", listingUuid)
                .contentType(MediaType.APPLICATION_JSON).content(json);
    }

    @Test
    @DisplayName("비로그인은 401 — 보안 필터에서 막혀 신고 행이 생기지 않는다")
    void anonymous_is_unauthorized() throws Exception {
        mockMvc.perform(report("rp-open", "{\"reasonCode\":\"FAKE_LISTING\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(reportRepository.count()).isZero();
    }

    @Test
    @DisplayName("공개 매물을 신고하면 대기(PENDING) 행이 생기고 상세는 앞뒤 공백을 지워 담는다 — 매물은 그대로 공개")
    void report_creates_pending_row() throws Exception {
        mockMvc.perform(report("rp-open", "{\"reasonCode\":\"FOREIGNER_REFUSED\",\"detail\":\"  외국인이라 안 된대요  \"}")
                        .header("Authorization", bearer(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        List<ListingReport> rows = reportRepository.findAll();
        assertThat(rows).hasSize(1);
        ListingReport row = rows.get(0);
        assertThat(row.getListingId()).isEqualTo(open);
        assertThat(row.getReporterUserId()).isEqualTo(student.getId());
        assertThat(row.getReasonCode()).isEqualTo(ListingReportReason.FOREIGNER_REFUSED);
        assertThat(row.getDetail()).isEqualTo("외국인이라 안 된대요");
        assertThat(row.getStatus()).isEqualTo(ListingReportStatus.PENDING);
        assertThat(row.getUuid()).hasSize(36);
        assertThat(row.getHandledBy()).isNull();
        assertThat(listingRepository.findById(open).orElseThrow().getStatus()).isEqualTo(ListingStatus.ACTIVE);
    }

    @Test
    @DisplayName("빈 상세는 저장하지 않는다(null)")
    void blank_detail_is_null() throws Exception {
        mockMvc.perform(report("rp-open", "{\"reasonCode\":\"ETC\",\"detail\":\"   \"}")
                        .header("Authorization", bearer(student)))
                .andExpect(status().isOk());

        assertThat(reportRepository.findAll().get(0).getDetail()).isNull();
    }

    @Test
    @DisplayName("거래 완료 · 없는 매물은 404 LISTING_NOT_FOUND — 공개 상세와 같은 기준")
    void non_public_is_not_found() throws Exception {
        for (String uuid : new String[]{"rp-done", "no-such"}) {
            mockMvc.perform(report(uuid, "{\"reasonCode\":\"SCAM\"}").header("Authorization", bearer(student)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.errorCode").value("LISTING_NOT_FOUND"));
        }
        assertThat(reportRepository.count()).isZero();
    }

    // 예약 중인 방도 유학생에게 보이므로 신고 대상이다 — 보이는데 신고가 404 면 허위매물을 알릴 길이 막힌다.
    @Test
    @DisplayName("예약 중(RESERVED) 매물도 신고하면 대기(PENDING) 행이 생기고 매물 상태는 그대로다")
    void reserved_is_reportable() throws Exception {
        Long held = save("rp-held", office, ListingStatus.RESERVED);

        mockMvc.perform(report("rp-held", "{\"reasonCode\":\"FAKE_LISTING\"}").header("Authorization", bearer(student)))
                .andExpect(status().isOk());

        List<ListingReport> rows = reportRepository.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getListingId()).isEqualTo(held);
        assertThat(rows.get(0).getStatus()).isEqualTo(ListingReportStatus.PENDING);
        assertThat(listingRepository.findById(held).orElseThrow().getStatus()).isEqualTo(ListingStatus.RESERVED);
    }

    @Test
    @DisplayName("숨김 · 차단 · 초안 · 만료 · 종료 매물은 404 LISTING_NOT_FOUND 이고 신고 행이 생기지 않는다")
    void non_visible_states_are_not_found() throws Exception {
        for (ListingStatus state : List.of(ListingStatus.HIDDEN, ListingStatus.BLOCKED, ListingStatus.DRAFT,
                ListingStatus.EXPIRED, ListingStatus.ARCHIVED)) {
            String uuid = "rp-" + state.name().toLowerCase();
            save(uuid, office, state);

            mockMvc.perform(report(uuid, "{\"reasonCode\":\"SCAM\"}").header("Authorization", bearer(student)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.errorCode").value("LISTING_NOT_FOUND"));
        }
        assertThat(reportRepository.count()).isZero();
    }

    @Test
    @DisplayName("같은 사람이 두 번 신고하면 409 LISTING_REPORT_DUPLICATE — 다른 사람은 각자 신고할 수 있다")
    void duplicate_is_conflict_per_reporter() throws Exception {
        mockMvc.perform(report("rp-open", "{\"reasonCode\":\"FAKE_LISTING\"}").header("Authorization", bearer(student)))
                .andExpect(status().isOk());
        mockMvc.perform(report("rp-open", "{\"reasonCode\":\"WRONG_INFO\"}").header("Authorization", bearer(student)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("LISTING_REPORT_DUPLICATE"));

        User other = student("rp-other");
        mockMvc.perform(report("rp-open", "{\"reasonCode\":\"WRONG_INFO\"}").header("Authorization", bearer(other)))
                .andExpect(status().isOk());
        assertThat(reportRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("사유가 없으면 400 — 검증 오류 목록에 LISTING_REPORT_REASON_REQUIRED")
    void reason_required() throws Exception {
        mockMvc.perform(report("rp-open", "{\"detail\":\"사유 없이\"}").header("Authorization", bearer(student)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.validationErrors[0].errorCode").value("LISTING_REPORT_REASON_REQUIRED"));
    }

    @Test
    @DisplayName("상세 501자 · 모르는 사유 코드는 400")
    void detail_too_long_or_unknown_reason() throws Exception {
        String longDetail = "가".repeat(501);
        mockMvc.perform(report("rp-open", "{\"reasonCode\":\"ETC\",\"detail\":\"" + longDetail + "\"}")
                        .header("Authorization", bearer(student)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(report("rp-open", "{\"reasonCode\":\"NOPE\"}").header("Authorization", bearer(student)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
        assertThat(reportRepository.count()).isZero();
    }

    @Test
    @DisplayName("상세 500자는 받는다")
    void detail_at_limit() throws Exception {
        mockMvc.perform(report("rp-open", "{\"reasonCode\":\"ETC\",\"detail\":\"" + "가".repeat(500) + "\"}")
                        .header("Authorization", bearer(student)))
                .andExpect(status().isOk());
    }

    // 서비스 검사를 스쳐 지나간 동시 제출을 막는 것은 DB 유니크다 — H2 에도 같은 제약이 있어야 그 경로를 시험할 수 있다.
    @Test
    @DisplayName("(매물, 신고자) 유니크가 DB 에 걸려 있다")
    void db_unique_on_listing_and_reporter() {
        reportRepository.saveAndFlush(row(open, student.getId()));

        assertThatThrownBy(() -> reportRepository.saveAndFlush(row(open, student.getId())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static ListingReport row(Long listingId, Long reporterId) {
        return ListingReport.builder().listingId(listingId).reporterUserId(reporterId)
                .reasonCode(ListingReportReason.ETC).status(ListingReportStatus.PENDING).build();
    }

    private User student(String uuid) {
        return userRepository.save(User.builder()
                .uuid(uuid).status(UserStatus.ACTIVE).role(UserRole.USER).preferredLanguage("ko").build());
    }

    private Long officeOf(String uuid, String regNo) {
        Long representativeId = userRepository.save(User.builder()
                .uuid("u-" + uuid).name("홍길동")
                .nickname("u-" + uuid).nicknameNormalized("u-" + uuid).isNicknameCustom(false)
                .preferredLanguage("ko").status(UserStatus.ACTIVE).role(UserRole.PARTNER)
                .build()).getId();
        Long officeId = officeRepository.save(BrokerageOffice.builder().uuid(uuid).regNo(regNo)
                .name("테스트공인중개사사무소").ceoName("홍길동")
                .address("서울특별시 관악구").tel("02-000-0000").build()).getId();
        profileRepository.save(BrokerProfile.builder().userId(representativeId).officeId(officeId).build());
        divisionRepository.save(PartnerDivision.builder().uuid("pd-" + representativeId).userId(representativeId)
                .division(PartnerDivisionType.REAL_ESTATE_BROKER).status(PartnerDivisionStatus.APPROVED)
                .submittedAt(LocalDateTime.of(2026, 10, 1, 0, 0)).build());
        return officeId;
    }

    private Long save(String uuid, Long officeId, ListingStatus status) {
        return listingRepository.saveAndFlush(Listing.builder()
                .uuid(uuid).listingNo("LS-261012-" + Integer.toHexString(uuid.hashCode()).toUpperCase())
                .officeId(officeId).createdByUserId(10L)
                .category(ListingCategory.ONE_TWO_ROOM).roomType(RoomType.ONE_ROOM)
                .title("테스트").sellingType(SellingType.MONTHLY_RENT)
                .deposit(3_000_000L).monthlyRent(300_000L)
                .exclusiveArea(new BigDecimal("26.44")).floorType(FloorType.GROUND)
                .residentRegistration(AvailabilityAnswer.ASK).depositInsurance(AvailabilityAnswer.ASK)
                .realLat(new BigDecimal("37.4862378")).realLng(new BigDecimal("126.9331220"))
                .displayLat(new BigDecimal("37.4862552")).displayLng(new BigDecimal("126.9332423"))
                .addressJibun("서울특별시 관악구 신림동")
                .postedAt(LocalDateTime.of(2026, 10, 1, 0, 0))
                .status(status).build()).getId();
    }
}
