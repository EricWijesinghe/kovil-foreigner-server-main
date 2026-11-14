package com.kovil.domain.listing;

import com.kovil.domain.file.entity.StoredFile;
import com.kovil.domain.file.enums.FilePurpose;
import com.kovil.domain.file.enums.FileStatus;
import com.kovil.domain.file.repository.StoredFileRepository;
import com.kovil.domain.listing.entity.BrokerProfile;
import com.kovil.domain.listing.entity.BrokerageOffice;
import com.kovil.domain.listing.entity.Listing;
import com.kovil.domain.listing.entity.ListingBlockLog;
import com.kovil.domain.listing.entity.ListingImage;
import com.kovil.domain.listing.entity.ListingReport;
import com.kovil.domain.listing.enums.*;
import com.kovil.domain.listing.repository.BrokerProfileRepository;
import com.kovil.domain.listing.repository.BrokerageOfficeRepository;
import com.kovil.domain.listing.repository.ListingBlockLogRepository;
import com.kovil.domain.listing.repository.ListingImageRepository;
import com.kovil.domain.listing.repository.ListingReportRepository;
import com.kovil.domain.listing.repository.ListingRepository;
import com.kovil.domain.notification.entity.NotificationOutbox;
import com.kovil.domain.notification.enums.NotiType;
import com.kovil.domain.notification.repository.NotificationOutboxRepository;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * 관리자 매물 처리를 **진짜 보안 필터 · 진짜 검증 · 진짜 DB** 로 태운다.
 * ★ 문은 LISTING_MODERATE 하나다 — 유학생도, 승인된 중개사도 다섯 경로 모두 403 이어야 한다.
 * ★ 신고자는 관리자 응답에도 없다. 차단 사유는 관리자 상세에만 있고 중개사 쪽(알림 · 매물 응답)에는 없다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminListingModerationApiTest {

    private static final String REASON = "사진과 실제 방이 다른 것을 확인함";

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired UserRepository userRepository;
    @Autowired BrokerageOfficeRepository officeRepository;
    @Autowired BrokerProfileRepository profileRepository;
    @Autowired PartnerDivisionRepository divisionRepository;
    @Autowired ListingRepository listingRepository;
    @Autowired ListingReportRepository reportRepository;
    @Autowired ListingBlockLogRepository blockLogRepository;
    @Autowired NotificationOutboxRepository outboxRepository;
    @Autowired ListingImageRepository imageRepository;
    @Autowired StoredFileRepository storedFileRepository;

    private User admin;
    private User broker;
    private User student;
    private Long open;
    private Long other;
    private List<User> reporters;

    @BeforeEach
    void setUp() {
        admin = userRepository.save(User.builder().uuid("am-admin").name("운영자")
                .status(UserStatus.ACTIVE).role(UserRole.ADMIN).preferredLanguage("ko").build());
        broker = representative("am-broker");
        Long office = office("am-office", "99999-2026-00091", broker);
        open = save("am-open", office, broker.getId(), ListingStatus.ACTIVE);
        other = save("am-other", office, broker.getId(), ListingStatus.ACTIVE);
        save("am-done", office, broker.getId(), ListingStatus.COMPLETED);
        student = student("am-student");
        reporters = List.of(student("am-r1"), student("am-r2"), student("am-r3"));
        for (User reporter : reporters) {
            reportRepository.saveAndFlush(row(open, reporter.getId(), ListingReportReason.FAKE_LISTING));
        }
        reportRepository.saveAndFlush(row(other, reporters.get(0).getId(), ListingReportReason.WRONG_INFO));
    }

    private String bearer(User user, UserRole role) {
        return "Bearer " + jwtTokenProvider.issueAccessToken(user.getUuid(), role);
    }

    private String adminBearer() {
        return bearer(admin, UserRole.ADMIN);
    }

    private MockHttpServletRequestBuilder block(String uuid, String json) {
        return post("/api/v1/admin/listings/{uuid}/block", uuid)
                .contentType(MediaType.APPLICATION_JSON).content(json);
    }

    @Test
    @DisplayName("다섯 경로 모두 비로그인 401 · 유학생 403 · 승인된 중개사 403")
    void only_listing_moderators_get_in() throws Exception {
        String brokerBearer = "Bearer " + jwtTokenProvider.issueAccessToken(broker.getUuid(), UserRole.PARTNER,
                Set.of(PartnerDivisionType.REAL_ESTATE_BROKER));
        String reportUuid = reportRepository.findAll().get(0).getUuid();
        List<MockHttpServletRequestBuilder> routes = List.of(
                get("/api/v1/admin/listing-reports"),
                post("/api/v1/admin/listing-reports/{uuid}/dismiss", reportUuid),
                get("/api/v1/admin/listings/{uuid}", "am-open"),
                block("am-open", "{\"reason\":\"" + REASON + "\"}"),
                post("/api/v1/admin/listings/{uuid}/unblock", "am-open"));

        for (MockHttpServletRequestBuilder route : routes) {
            mockMvc.perform(route).andExpect(status().isUnauthorized());
        }
        for (String bearer : List.of(bearer(student, UserRole.USER), brokerBearer)) {
            for (MockHttpServletRequestBuilder route : List.of(
                    get("/api/v1/admin/listing-reports").header("Authorization", bearer),
                    post("/api/v1/admin/listing-reports/{uuid}/dismiss", reportUuid).header("Authorization", bearer),
                    get("/api/v1/admin/listings/{uuid}", "am-open").header("Authorization", bearer),
                    block("am-open", "{\"reason\":\"" + REASON + "\"}").header("Authorization", bearer),
                    post("/api/v1/admin/listings/{uuid}/unblock", "am-open").header("Authorization", bearer))) {
                mockMvc.perform(route).andExpect(status().isForbidden());
            }
        }
        assertThat(listingRepository.findById(open).orElseThrow().getStatus()).isEqualTo(ListingStatus.ACTIVE);
        assertThat(reportRepository.findAll()).allMatch(r -> r.getStatus() == ListingReportStatus.PENDING);
    }

    @Test
    @DisplayName("대기열 — 오래된 순, 같은 매물 대기 수, 신고자는 응답 어디에도 없다")
    void queue_rows() throws Exception {
        String body = mockMvc.perform(get("/api/v1/admin/listing-reports").header("Authorization", adminBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(4))
                .andExpect(jsonPath("$.data.content[0].listingUuid").value("am-open"))
                .andExpect(jsonPath("$.data.content[0].sameListingPendingCount").value(3))
                .andExpect(jsonPath("$.data.content[0].listingStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data.content[0].handledByName").doesNotExist())
                .andExpect(jsonPath("$.data.content[3].listingUuid").value("am-other"))
                .andExpect(jsonPath("$.data.content[3].sameListingPendingCount").value(1))
                .andReturn().getResponse().getContentAsString();

        for (User reporter : reporters) {
            assertThat(body).doesNotContain(reporter.getUuid());
        }
        assertThat(body).doesNotContainIgnoringCase("reporter");
    }

    // 대기 탭에서는 조회 상태와 PENDING 이 같은 값이라 무엇으로 세는지 구분되지 않는다 — 처리된 탭에서 갈린다.
    @Test
    @DisplayName("기각 탭의 같은 매물 대기 수도 대기(PENDING) 신고만 센다 — 기각한 건은 빠진다")
    void dismissed_tab_counts_pending_only() throws Exception {
        ListingReport target = reportRepository.findAll().stream()
                .filter(r -> r.getListingId().equals(open)).findFirst().orElseThrow();
        mockMvc.perform(post("/api/v1/admin/listing-reports/{uuid}/dismiss", target.getUuid())
                        .header("Authorization", adminBearer()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/admin/listing-reports").param("status", "DISMISSED")
                        .header("Authorization", adminBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].reportUuid").value(target.getUuid()))
                .andExpect(jsonPath("$.data.content[0].listingUuid").value("am-open"))
                .andExpect(jsonPath("$.data.content[0].status").value("DISMISSED"))
                .andExpect(jsonPath("$.data.content[0].sameListingPendingCount").value(2));

        mockMvc.perform(get("/api/v1/admin/listing-reports").header("Authorization", adminBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.content[0].listingUuid").value("am-open"))
                .andExpect(jsonPath("$.data.content[0].sameListingPendingCount").value(2));
    }

    @Test
    @DisplayName("차단 — BLOCKED · 그 매물의 대기 신고만 RESOLVED · 이력 · 중개사 알림(사유 없음) · 공개 상세 404")
    void block_end_to_end() throws Exception {
        mockMvc.perform(block("am-open", "{\"reason\":\"  " + REASON + "  \"}").header("Authorization", adminBearer()))
                .andExpect(status().isOk());

        assertThat(listingRepository.findById(open).orElseThrow().getStatus()).isEqualTo(ListingStatus.BLOCKED);
        for (ListingReport report : reportRepository.findAll()) {
            if (report.getListingId().equals(open)) {
                assertThat(report.getStatus()).isEqualTo(ListingReportStatus.RESOLVED);
                assertThat(report.getHandledBy()).isEqualTo(admin.getId());
                assertThat(report.getHandledAt()).isNotNull();
            } else {
                assertThat(report.getStatus()).isEqualTo(ListingReportStatus.PENDING);
            }
        }
        List<ListingBlockLog> history = blockLogRepository.findByListingIdOrderByIdDesc(open);
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getAction()).isEqualTo(ListingBlockAction.BLOCK);
        assertThat(history.get(0).getReason()).isEqualTo(REASON);
        assertThat(history.get(0).getAdminUserId()).isEqualTo(admin.getId());
        assertThat(history.get(0).getCreatedAt()).isNotNull();

        List<NotificationOutbox> sent = outboxRepository.findAll().stream()
                .filter(o -> o.getNotiType() == NotiType.LISTING_BLOCKED).toList();
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).getUserId()).isEqualTo(broker.getId());
        assertThat(sent.get(0).getDeepLink()).isEqualTo("/broker/listings/am-open");
        assertThat(sent.get(0).getParams())
                .contains(listingRepository.findById(open).orElseThrow().getListingNo())
                .doesNotContain(REASON);

        mockMvc.perform(get("/api/v1/listings/{uuid}", "am-open")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/admin/listing-reports").header("Authorization", adminBearer()))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].listingUuid").value("am-other"));
    }

    @Test
    @DisplayName("이미 차단 · 거래 완료는 409 LISTING_NOT_BLOCKABLE · 없는 매물 404")
    void not_blockable() throws Exception {
        String json = "{\"reason\":\"" + REASON + "\"}";
        mockMvc.perform(block("am-open", json).header("Authorization", adminBearer())).andExpect(status().isOk());

        mockMvc.perform(block("am-open", json).header("Authorization", adminBearer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("LISTING_NOT_BLOCKABLE"));
        mockMvc.perform(block("am-done", json).header("Authorization", adminBearer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("LISTING_NOT_BLOCKABLE"));
        mockMvc.perform(block("no-such", json).header("Authorization", adminBearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("LISTING_NOT_FOUND"));
        assertThat(blockLogRepository.findByListingIdOrderByIdDesc(open)).hasSize(1);
    }

    @Test
    @DisplayName("사유가 없거나 공백 · 501자면 400, 500자는 받는다")
    void reason_validation() throws Exception {
        for (String json : List.of("{}", "{\"reason\":\"   \"}", "{\"reason\":\"" + "가".repeat(501) + "\"}")) {
            mockMvc.perform(block("am-open", json).header("Authorization", adminBearer()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
        }
        assertThat(listingRepository.findById(open).orElseThrow().getStatus()).isEqualTo(ListingStatus.ACTIVE);

        mockMvc.perform(block("am-open", "{\"reason\":\"" + "가".repeat(500) + "\"}").header("Authorization", adminBearer()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("해제 — HIDDEN · 해제 이력 · 알림 없음, 두 번째 해제는 409 LISTING_NOT_BLOCKED")
    void unblock_end_to_end() throws Exception {
        mockMvc.perform(block("am-open", "{\"reason\":\"" + REASON + "\"}").header("Authorization", adminBearer()))
                .andExpect(status().isOk());
        long sentBefore = outboxRepository.count();

        mockMvc.perform(post("/api/v1/admin/listings/{uuid}/unblock", "am-open").header("Authorization", adminBearer()))
                .andExpect(status().isOk());

        assertThat(listingRepository.findById(open).orElseThrow().getStatus()).isEqualTo(ListingStatus.HIDDEN);
        List<ListingBlockLog> history = blockLogRepository.findByListingIdOrderByIdDesc(open);
        assertThat(history).extracting(ListingBlockLog::getAction)
                .containsExactly(ListingBlockAction.UNBLOCK, ListingBlockAction.BLOCK);
        assertThat(history.get(0).getReason()).isNull();
        assertThat(outboxRepository.count()).isEqualTo(sentBefore);

        mockMvc.perform(post("/api/v1/admin/listings/{uuid}/unblock", "am-open").header("Authorization", adminBearer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("LISTING_NOT_BLOCKED"));
    }

    @Test
    @DisplayName("기각 — 그 한 건만 DISMISSED · 다시 기각 409 · 차단으로 닫힌 신고 기각 409 · 없는 신고 404")
    void dismiss_grid() throws Exception {
        ListingReport target = reportRepository.findAll().stream()
                .filter(r -> r.getListingId().equals(other)).findFirst().orElseThrow();
        mockMvc.perform(post("/api/v1/admin/listing-reports/{uuid}/dismiss", target.getUuid())
                        .header("Authorization", adminBearer()))
                .andExpect(status().isOk());

        ListingReport dismissed = reportRepository.findById(target.getId()).orElseThrow();
        assertThat(dismissed.getStatus()).isEqualTo(ListingReportStatus.DISMISSED);
        assertThat(dismissed.getHandledBy()).isEqualTo(admin.getId());
        assertThat(listingRepository.findById(other).orElseThrow().getStatus()).isEqualTo(ListingStatus.ACTIVE);

        mockMvc.perform(post("/api/v1/admin/listing-reports/{uuid}/dismiss", target.getUuid())
                        .header("Authorization", adminBearer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("LISTING_REPORT_ALREADY_HANDLED"));

        mockMvc.perform(block("am-open", "{\"reason\":\"" + REASON + "\"}").header("Authorization", adminBearer()))
                .andExpect(status().isOk());
        String resolved = reportRepository.findAll().stream()
                .filter(r -> r.getListingId().equals(open)).findFirst().orElseThrow().getUuid();
        mockMvc.perform(post("/api/v1/admin/listing-reports/{uuid}/dismiss", resolved)
                        .header("Authorization", adminBearer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("LISTING_REPORT_ALREADY_HANDLED"));

        mockMvc.perform(post("/api/v1/admin/listing-reports/{uuid}/dismiss", "no-such")
                        .header("Authorization", adminBearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("LISTING_REPORT_NOT_FOUND"));
    }

    @Test
    @DisplayName("관리자 상세 — 사무소 · 신고 수 · 처리자 이름 · 차단 사유가 실리고 신고자는 없다, 조회수는 그대로")
    void admin_detail() throws Exception {
        mockMvc.perform(block("am-open", "{\"reason\":\"" + REASON + "\"}").header("Authorization", adminBearer()))
                .andExpect(status().isOk());
        long viewsBefore = listingRepository.findById(open).orElseThrow().getViewCount();

        String body = mockMvc.perform(get("/api/v1/admin/listings/{uuid}", "am-open").header("Authorization", adminBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("BLOCKED"))
                .andExpect(jsonPath("$.data.office.officeUuid").value("am-office"))
                .andExpect(jsonPath("$.data.office.advertisable").value(true))
                .andExpect(jsonPath("$.data.reportCount").value(3))
                .andExpect(jsonPath("$.data.pendingReportCount").value(0))
                .andExpect(jsonPath("$.data.reports.length()").value(3))
                .andExpect(jsonPath("$.data.reports[0].status").value("RESOLVED"))
                .andExpect(jsonPath("$.data.reports[0].handledByName").value("운영자"))
                .andExpect(jsonPath("$.data.blockHistory[0].action").value("BLOCK"))
                .andExpect(jsonPath("$.data.blockHistory[0].reason").value(REASON))
                .andExpect(jsonPath("$.data.blockHistory[0].adminName").value("운영자"))
                .andReturn().getResponse().getContentAsString();

        for (User reporter : reporters) {
            assertThat(body).doesNotContain(reporter.getUuid());
        }
        assertThat(listingRepository.findById(open).orElseThrow().getViewCount()).isEqualTo(viewsBefore);

        mockMvc.perform(get("/api/v1/admin/listings/{uuid}", "no-such").header("Authorization", adminBearer()))
                .andExpect(status().isNotFound());
    }

    /*
     * 사진은 차단 판단의 핵심 증거다. 정렬 순서를 넣는 순서 · 파일 id 순서와 엇갈리게 둬서
     * 정렬이 빠지면 순서가 뒤집히게 하고, 삭제 표시 파일은 가운데 끼워 걸러지지 않으면 자리가 드러나게 한다.
     */
    @Test
    @DisplayName("관리자 상세의 사진 URL 은 정렬 순서대로이고 삭제 표시된 파일은 빠진다")
    void admin_detail_image_urls() throws Exception {
        StoredFile last = imageFile("listing-images/am-last.jpg", null);
        StoredFile removed = imageFile("listing-images/am-removed.jpg", LocalDateTime.of(2026, 10, 5, 0, 0));
        StoredFile first = imageFile("listing-images/am-first.jpg", null);
        imageRepository.saveAndFlush(ListingImage.builder().listingId(open).fileId(last.getId()).sortOrder(2).build());
        imageRepository.saveAndFlush(ListingImage.builder().listingId(open).fileId(removed.getId()).sortOrder(1).build());
        imageRepository.saveAndFlush(ListingImage.builder().listingId(open).fileId(first.getId()).sortOrder(0).build());

        mockMvc.perform(get("/api/v1/admin/listings/{uuid}", "am-open").header("Authorization", adminBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.imageUrls.length()").value(2))
                .andExpect(jsonPath("$.data.imageUrls[0]").value(first.getFileUrl()))
                .andExpect(jsonPath("$.data.imageUrls[1]").value(last.getFileUrl()));
    }

    private StoredFile imageFile(String storageKey, LocalDateTime deletedAt) {
        return storedFileRepository.saveAndFlush(StoredFile.builder()
                .storageKey(storageKey)
                .contentType("image/jpeg")
                .byteSize(1024L)
                .purpose(FilePurpose.LISTING_IMAGE)
                .status(deletedAt == null ? FileStatus.ACTIVE : FileStatus.DELETED)
                .fileUrl("https://cdn.example.test/" + storageKey)
                .deletedAt(deletedAt)
                .build());
    }

    // 사유는 관리자 내부 기록이다(PO 결정) — 중개사가 자기 매물을 열어도 사유가 없어야 한다.
    @Test
    @DisplayName("중개사가 차단된 자기 매물을 열면 BLOCKED 만 보이고 사유는 없다")
    void broker_sees_status_without_reason() throws Exception {
        mockMvc.perform(block("am-open", "{\"reason\":\"" + REASON + "\"}").header("Authorization", adminBearer()))
                .andExpect(status().isOk());
        String brokerBearer = "Bearer " + jwtTokenProvider.issueAccessToken(broker.getUuid(), UserRole.PARTNER,
                Set.of(PartnerDivisionType.REAL_ESTATE_BROKER));

        String body = mockMvc.perform(get("/api/v1/broker/listings/{uuid}", "am-open").header("Authorization", brokerBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("BLOCKED"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(REASON);
    }

    private static ListingReport row(Long listingId, Long reporterId, ListingReportReason reason) {
        return ListingReport.builder().listingId(listingId).reporterUserId(reporterId)
                .reasonCode(reason).status(ListingReportStatus.PENDING).build();
    }

    private User student(String uuid) {
        return userRepository.save(User.builder()
                .uuid(uuid).status(UserStatus.ACTIVE).role(UserRole.USER).preferredLanguage("ko").build());
    }

    private User representative(String uuid) {
        return userRepository.save(User.builder()
                .uuid(uuid).name("홍길동")
                .nickname(uuid).nicknameNormalized(uuid).isNicknameCustom(false)
                .preferredLanguage("ko").status(UserStatus.ACTIVE).role(UserRole.PARTNER)
                .build());
    }

    private Long office(String uuid, String regNo, User representative) {
        Long officeId = officeRepository.save(BrokerageOffice.builder().uuid(uuid).regNo(regNo)
                .name("테스트공인중개사사무소").ceoName("홍길동")
                .address("서울특별시 관악구").tel("02-000-0000").build()).getId();
        profileRepository.save(BrokerProfile.builder().userId(representative.getId()).officeId(officeId).build());
        divisionRepository.save(PartnerDivision.builder().uuid("pd-" + representative.getId())
                .userId(representative.getId())
                .division(PartnerDivisionType.REAL_ESTATE_BROKER).status(PartnerDivisionStatus.APPROVED)
                .submittedAt(LocalDateTime.of(2026, 10, 1, 0, 0)).build());
        return officeId;
    }

    private Long save(String uuid, Long officeId, Long createdBy, ListingStatus status) {
        return listingRepository.saveAndFlush(Listing.builder()
                .uuid(uuid).listingNo("LS-261012-" + Integer.toHexString(uuid.hashCode()).toUpperCase())
                .officeId(officeId).createdByUserId(createdBy)
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
