package com.kovil.domain.partner;

import com.kovil.domain.file.entity.StoredFile;
import com.kovil.domain.file.enums.FilePurpose;
import com.kovil.domain.file.repository.StoredFileRepository;
import com.kovil.domain.listing.entity.BrokerProfile;
import com.kovil.domain.listing.entity.BrokerageOffice;
import com.kovil.domain.listing.repository.BrokerProfileRepository;
import com.kovil.domain.listing.repository.BrokerageOfficeRepository;
import com.kovil.domain.partner.entity.BrokerageOfficeChangeRequest;
import com.kovil.domain.partner.entity.PartnerDivision;
import com.kovil.domain.partner.enums.OfficeChangeRequestStatus;
import com.kovil.domain.partner.enums.PartnerDivisionStatus;
import com.kovil.domain.partner.enums.PartnerDivisionType;
import com.kovil.domain.partner.repository.BrokerageOfficeChangeRequestRepository;
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
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * 변경 신청 취소를 **진짜 보안 필터 · 진짜 DB** 로 태운다.
 * ★ 취소는 신청 행만 닫는다 — 사무소 값 · 등록증은 그대로여야 한다(심사 동안에도 그대로였던 것).
 * ★ 취소 뒤 관리자가 열어 둔 화면에서 승인하면 409 — 거둬들인 값을 사무소에 적용하면 안 된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PartnerOfficeChangeCancelApiTest {

    private static final String CANCEL = "/api/v1/partner/divisions/REAL_ESTATE_BROKER/office-change";

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired UserRepository userRepository;
    @Autowired BrokerageOfficeRepository officeRepository;
    @Autowired BrokerProfileRepository profileRepository;
    @Autowired PartnerDivisionRepository divisionRepository;
    @Autowired StoredFileRepository storedFileRepository;
    @Autowired BrokerageOfficeChangeRequestRepository changeRequestRepository;

    private User partner;
    private String partnerBearer;
    private Long officeId;
    private Long officeLicenseId;

    @BeforeEach
    void setUp() {
        partner = userRepository.save(User.builder()
                .uuid("oc-partner").name("홍길동")
                .nickname("oc-partner").nicknameNormalized("oc-partner").isNicknameCustom(false)
                .preferredLanguage("ko").status(UserStatus.ACTIVE).role(UserRole.PARTNER).build());
        officeLicenseId = license("oc-office-license");
        officeId = officeRepository.save(BrokerageOffice.builder().uuid("oc-office")
                .regNo("99999-2026-00111").claimedRegNo("99999-2026-00111")
                .name("테스트공인중개사사무소").ceoName("홍길동")
                .address("서울특별시 관악구").tel("02-000-0000").licenseFileId(officeLicenseId).build()).getId();
        profileRepository.save(BrokerProfile.builder().userId(partner.getId()).officeId(officeId).build());
        divisionRepository.save(PartnerDivision.builder().uuid("pd-oc").userId(partner.getId()).officeId(officeId)
                .division(PartnerDivisionType.REAL_ESTATE_BROKER).status(PartnerDivisionStatus.APPROVED)
                .submittedAt(LocalDateTime.of(2026, 10, 1, 0, 0)).lastApprovedAt(LocalDateTime.of(2026, 10, 2, 0, 0))
                .build());
        partnerBearer = "Bearer " + jwtTokenProvider.issueAccessToken(partner.getUuid(), UserRole.PARTNER,
                Set.of(PartnerDivisionType.REAL_ESTATE_BROKER));
    }

    @Test
    @DisplayName("비로그인 401 · 유학생 403 NOT_A_PARTNER")
    void guard() throws Exception {
        mockMvc.perform(delete(CANCEL)).andExpect(status().isUnauthorized());

        User student = userRepository.save(User.builder().uuid("oc-student")
                .status(UserStatus.ACTIVE).role(UserRole.USER).preferredLanguage("ko").build());
        mockMvc.perform(delete(CANCEL).header("Authorization",
                        "Bearer " + jwtTokenProvider.issueAccessToken(student.getUuid(), UserRole.USER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("NOT_A_PARTNER"));
    }

    @Test
    @DisplayName("취소 → CANCELED · 새 등록증 폐기 · 사무소 값과 등록증은 그대로 · 내 상태에 CANCELED · 관리자 대기열에서 빠짐")
    void cancel_end_to_end() throws Exception {
        Long newLicense = license("oc-new-license");
        BrokerageOfficeChangeRequest request = pending(newLicense);

        mockMvc.perform(delete(CANCEL).header("Authorization", partnerBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.officeChange.requestUuid").value(request.getUuid()))
                .andExpect(jsonPath("$.data.officeChange.status").value("CANCELED"))
                .andExpect(jsonPath("$.data.brokerageOffice.name").value("테스트공인중개사사무소"));

        BrokerageOfficeChangeRequest saved = changeRequestRepository.findById(request.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(OfficeChangeRequestStatus.CANCELED);
        assertThat(saved.getReviewedBy()).isNull();
        assertThat(saved.getReviewedAt()).isNull();
        assertThat(storedFileRepository.findById(newLicense).orElseThrow().getDeletedAt()).isNotNull();

        BrokerageOffice office = officeRepository.findById(officeId).orElseThrow();
        assertThat(office.getName()).isEqualTo("테스트공인중개사사무소");
        assertThat(office.getRegNo()).isEqualTo("99999-2026-00111");
        assertThat(office.getLicenseFileId()).isEqualTo(officeLicenseId);
        assertThat(storedFileRepository.findById(officeLicenseId).orElseThrow().getDeletedAt()).isNull();

        mockMvc.perform(get("/api/v1/partner/me").header("Authorization", partnerBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.officeChange.status").value("CANCELED"));

        String adminBearer = adminBearer();
        mockMvc.perform(get("/api/v1/admin/office-change-requests").header("Authorization", adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));
        mockMvc.perform(get("/api/v1/admin/office-change-requests").param("status", "CANCELED")
                        .header("Authorization", adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    @DisplayName("지금 사무소 등록증을 그대로 낸 신청을 취소해도 그 파일은 살아 있다")
    void shared_license_survives() throws Exception {
        pending(officeLicenseId);

        mockMvc.perform(delete(CANCEL).header("Authorization", partnerBearer)).andExpect(status().isOk());

        assertThat(storedFileRepository.findById(officeLicenseId).orElseThrow().getDeletedAt()).isNull();
    }

    // 닫는 방향이라 부문이 정지돼도 거둬들일 수 있다 — 정지 중엔 승인될 수도 없는 신청이 대기열에 남지 않게.
    @Test
    @DisplayName("부문이 정지돼도 취소할 수 있다")
    void suspended_division_can_cancel() throws Exception {
        divisionRepository.deleteAll();
        divisionRepository.saveAndFlush(PartnerDivision.builder().uuid("pd-oc-s").userId(partner.getId()).officeId(officeId)
                .division(PartnerDivisionType.REAL_ESTATE_BROKER).status(PartnerDivisionStatus.SUSPENDED)
                .submittedAt(LocalDateTime.of(2026, 10, 1, 0, 0)).build());
        BrokerageOfficeChangeRequest request = pending(license("oc-suspended"));

        mockMvc.perform(delete(CANCEL).header("Authorization", partnerBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.officeChange.status").value("CANCELED"));
        assertThat(changeRequestRepository.findById(request.getId()).orElseThrow().getStatus())
                .isEqualTo(OfficeChangeRequestStatus.CANCELED);
    }

    @Test
    @DisplayName("대기 건이 없으면 409 OFFICE_CHANGE_NOT_PENDING — 두 번째 취소도 같다")
    void nothing_pending() throws Exception {
        mockMvc.perform(delete(CANCEL).header("Authorization", partnerBearer))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("OFFICE_CHANGE_NOT_PENDING"));

        pending(license("oc-once"));
        mockMvc.perform(delete(CANCEL).header("Authorization", partnerBearer)).andExpect(status().isOk());
        mockMvc.perform(delete(CANCEL).header("Authorization", partnerBearer))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("OFFICE_CHANGE_NOT_PENDING"));
    }

    @Test
    @DisplayName("취소 뒤 관리자가 전에 본 판으로 승인하면 409 OFFICE_CHANGE_REQUEST_CHANGED — 사무소는 그대로")
    void admin_cannot_approve_canceled_request() throws Exception {
        BrokerageOfficeChangeRequest request = pending(license("oc-stale"));
        Long seenVersion = request.getVersion();

        mockMvc.perform(delete(CANCEL).header("Authorization", partnerBearer)).andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/admin/office-change-requests/{uuid}/approve", request.getUuid())
                        .header("Authorization", adminBearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + seenVersion + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("OFFICE_CHANGE_REQUEST_CHANGED"));
        assertThat(officeRepository.findById(officeId).orElseThrow().getName()).isEqualTo("테스트공인중개사사무소");
    }

    private String adminBearer() {
        User admin = userRepository.save(User.builder().uuid("oc-admin")
                .status(UserStatus.ACTIVE).role(UserRole.ADMIN).preferredLanguage("ko").build());
        return "Bearer " + jwtTokenProvider.issueAccessToken(admin.getUuid(), UserRole.ADMIN);
    }

    private Long license(String uuid) {
        return storedFileRepository.save(StoredFile.builder().uuid(uuid).storageKey("k/" + uuid)
                .contentType("application/pdf").byteSize(1L).purpose(FilePurpose.BROKER_LICENSE)
                .uploaderId(partner.getId()).build()).getId();
    }

    private BrokerageOfficeChangeRequest pending(Long licenseFileId) {
        return changeRequestRepository.saveAndFlush(BrokerageOfficeChangeRequest.builder()
                .uuid(UUID.randomUUID().toString()).officeId(officeId).requestedBy(partner.getId())
                .regNo("99999-2026-00112").name("새상호공인중개사사무소").ceoName("홍길동")
                .address("서울특별시 관악구").licenseFileId(licenseFileId)
                .status(OfficeChangeRequestStatus.PENDING).submittedAt(LocalDateTime.of(2026, 10, 12, 9, 0))
                .build());
    }
}
