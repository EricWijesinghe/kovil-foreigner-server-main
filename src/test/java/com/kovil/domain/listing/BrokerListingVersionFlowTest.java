package com.kovil.domain.listing;

import com.kovil.domain.listing.dto.request.BrokerListingSaveRequest;
import com.kovil.domain.listing.dto.response.BrokerListingResponse;
import com.kovil.domain.listing.entity.Listing;
import com.kovil.domain.listing.enums.AvailabilityAnswer;
import com.kovil.domain.listing.enums.FixedDetailType;
import com.kovil.domain.listing.enums.FloorType;
import com.kovil.domain.listing.enums.ListingCategory;
import com.kovil.domain.listing.enums.ListingStatus;
import com.kovil.domain.listing.enums.MaintenanceChargeType;
import com.kovil.domain.listing.enums.RoomType;
import com.kovil.domain.listing.enums.SellingType;
import com.kovil.domain.listing.exception.ListingException;
import com.kovil.domain.listing.repository.ListingMaintenanceRepository;
import com.kovil.domain.listing.repository.ListingRepository;
import com.kovil.domain.listing.service.BrokerListingService;
import com.kovil.domain.listing.entity.BrokerProfile;
import com.kovil.domain.listing.entity.BrokerageOffice;
import com.kovil.domain.partner.entity.PartnerDivision;
import com.kovil.domain.partner.enums.PartnerDivisionStatus;
import com.kovil.domain.partner.enums.PartnerDivisionType;
import com.kovil.domain.listing.repository.BrokerProfileRepository;
import com.kovil.domain.listing.repository.BrokerageOfficeRepository;
import com.kovil.domain.partner.repository.PartnerDivisionRepository;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/*
 * 매물 version 을 진짜 Hibernate 로 본다 — 단위 테스트는 EntityManager 가 목이라 강제 증가 · flush 뒤 값을 보지 못한다.
 *   · 관리비만 바꾼 저장도 version 이 바뀐다(listings 행은 더티가 아니다).
 *   · 저장 응답의 version = 직후 편집 GET 의 version — 그 값으로 다시 저장하면 통과, 옛 값이면 409 이고 아무것도 안 바뀐다.
 *   · 상태 전이 응답의 version 도 직후 GET 과 같다(flush 전에 조립하면 한 칸 낮다).
 * ★ @Transactional 을 붙이지 않는다 — 호출마다 커밋돼야 version 이 실제로 오른다. 만든 행은 끝에서 지운다.
 */
@SpringBootTest
class BrokerListingVersionFlowTest {

    private static final BigDecimal LAT = new BigDecimal("37.4862378");
    private static final BigDecimal LNG = new BigDecimal("126.9331220");

    @Autowired BrokerListingService service;
    @Autowired UserRepository userRepository;
    @Autowired BrokerageOfficeRepository officeRepository;
    @Autowired BrokerProfileRepository profileRepository;
    @Autowired PartnerDivisionRepository divisionRepository;
    @Autowired ListingRepository listingRepository;
    @Autowired ListingMaintenanceRepository maintenanceRepository;
    @Autowired JdbcTemplate jdbc;

    private final String tag = UUID.randomUUID().toString().substring(0, 8);
    private String userUuid;
    private Long userId;
    private Long officeId;
    private Long profileId;
    private Long divisionId;
    private String draftUuid;
    private String activeUuid;

    @BeforeEach
    void setUp() {
        userUuid = "u-ver-" + tag;
        userId = userRepository.save(User.builder().uuid(userUuid).name("홍길동")
                .nickname("ver-" + tag).nicknameNormalized("ver-" + tag).isNicknameCustom(false)
                .preferredLanguage("ko").status(UserStatus.ACTIVE).role(UserRole.PARTNER).build()).getId();
        officeId = officeRepository.save(BrokerageOffice.builder().uuid("o-ver-" + tag).regNo("99999-2026-" + tag.substring(0, 5))
                .name("테스트공인중개사사무소").ceoName("홍길동").address("서울특별시 관악구").tel("02-000-0000").build()).getId();
        profileId = profileRepository.save(BrokerProfile.builder().userId(userId).officeId(officeId).build()).getId();
        divisionId = divisionRepository.save(PartnerDivision.builder().uuid("pd-ver-" + tag).userId(userId)
                .division(PartnerDivisionType.REAL_ESTATE_BROKER).status(PartnerDivisionStatus.APPROVED)
                .submittedAt(LocalDateTime.of(2026, 10, 1, 0, 0)).build()).getId();
        draftUuid = listing("d", ListingStatus.DRAFT);
        activeUuid = listing("a", ListingStatus.ACTIVE);
    }

    @AfterEach
    void tearDown() {
        for (String uuid : List.of(draftUuid, activeUuid)) {
            listingRepository.findByUuid(uuid).ifPresent(l -> {
                jdbc.update("DELETE FROM listing_maintenance_items WHERE listing_id = ?", l.getId());
                jdbc.update("DELETE FROM listing_maintenance WHERE listing_id = ?", l.getId());
                jdbc.update("DELETE FROM listing_price_history WHERE listing_id = ?", l.getId());
                listingRepository.deleteById(l.getId());
            });
        }
        divisionRepository.deleteById(divisionId);
        profileRepository.deleteById(profileId);
        officeRepository.deleteById(officeId);
        userRepository.deleteById(userId);
    }

    private String listing(String suffix, ListingStatus status) {
        String uuid = "ver-" + suffix + "-" + tag;
        listingRepository.saveAndFlush(Listing.builder()
                .uuid(uuid).listingNo("LS-261022-" + (suffix + tag).toUpperCase().substring(0, 6))
                .officeId(officeId).createdByUserId(userId)
                .category(ListingCategory.ONE_TWO_ROOM).roomType(RoomType.ONE_ROOM)
                .title("테스트").description("설명").sellingType(SellingType.MONTHLY_RENT)
                .deposit(3_000_000L).monthlyRent(300_000L)
                .exclusiveArea(new BigDecimal("26.44")).floorType(FloorType.GROUND)
                .residentRegistration(AvailabilityAnswer.ASK).depositInsurance(AvailabilityAnswer.ASK)
                .realLat(LAT).realLng(LNG).displayLat(LAT).displayLng(LNG)
                .addressJibun("서울특별시 관악구 신림동")
                .postedAt(status == ListingStatus.ACTIVE ? LocalDateTime.now() : null)
                .expiresAt(status == ListingStatus.ACTIVE ? LocalDateTime.now().plusMonths(1) : null)
                .status(status).build());
        return uuid;
    }

    @Test
    @DisplayName("관리비만 바꾼 저장도 version 이 바뀌고, 응답 version 이 직후 편집 GET 과 같다")
    void child_only_save_changes_version_and_response_matches_get() {
        long opened = service.get(userUuid, draftUuid).version();

        BrokerListingResponse saved = service.update(userUuid, draftUuid, request(300_000L, opened, 100_000L));

        assertThat(saved.version()).isNotEqualTo(opened);
        assertThat(saved.version()).isEqualTo(service.get(userUuid, draftUuid).version());
        assertThat(saved.maintenance().totalCost()).isEqualTo(100_000L);
    }

    @Test
    @DisplayName("응답 version 으로 다시 저장하면 통과하고, 옛 version 이면 409 CONFLICT 이고 아무것도 바뀌지 않는다")
    void stale_form_is_rejected_and_changes_nothing() {
        long opened = service.get(userUuid, draftUuid).version();
        BrokerListingResponse first = service.update(userUuid, draftUuid, request(300_000L, opened, 100_000L));

        BrokerListingResponse second = service.update(userUuid, draftUuid, request(300_000L, first.version(), 120_000L));
        assertThat(second.maintenance().totalCost()).isEqualTo(120_000L);

        assertThatThrownBy(() -> service.update(userUuid, draftUuid, request(350_000L, opened, 999_000L)))
                .isInstanceOf(ListingException.class)
                .satisfies(e -> assertThat(((ListingException) e).getErrorCode().getCode()).isEqualTo("CONFLICT"));

        BrokerListingResponse now = service.get(userUuid, draftUuid);
        assertThat(now.version()).isEqualTo(second.version());
        assertThat(now.monthlyRent()).isEqualTo(300_000L);
        assertThat(now.maintenance().totalCost()).isEqualTo(120_000L);
    }

    @Test
    @DisplayName("상태 전이 응답의 version 이 직후 편집 GET 과 같다 — 그 값으로 바로 저장할 수 있다")
    void transition_response_carries_the_committed_version() {
        BrokerListingResponse reserved = service.reserve(userUuid, activeUuid);

        assertThat(reserved.version()).isEqualTo(service.get(userUuid, activeUuid).version());
        BrokerListingResponse unreserved = service.unreserve(userUuid, activeUuid);
        assertThat(unreserved.version()).isEqualTo(service.get(userUuid, activeUuid).version());
        assertThat(unreserved.version()).isNotEqualTo(reserved.version());

        // 내린 매물은 게시 관문 없이 고칠 수 있다 — 내리기 응답의 version 으로 바로 저장하면 통과해야 한다(거짓 409 아님).
        BrokerListingResponse hidden = service.unpublish(userUuid, activeUuid);
        assertThat(hidden.version()).isEqualTo(service.get(userUuid, activeUuid).version());
        assertThat(service.update(userUuid, activeUuid, request(300_000L, hidden.version(), 110_000L)).version())
                .isNotEqualTo(hidden.version());
    }

    private BrokerListingSaveRequest request(Long rent, Long version, Long maintenanceTotal) {
        return new BrokerListingSaveRequest(
                ListingCategory.ONE_TWO_ROOM, RoomType.ONE_ROOM, false, false,
                "테스트", "설명",
                SellingType.MONTHLY_RENT, 3_000_000L, rent,
                new BigDecimal("26.44"), null, null,
                FloorType.GROUND, null, null, null, null,
                null, null, null, null,
                false, null, null, null, null,
                false, null,
                AvailabilityAnswer.ASK, AvailabilityAnswer.ASK, false,
                LAT, LNG, null, null, "서울특별시 관악구 신림동", null, false,
                null, null,
                new BrokerListingSaveRequest.MaintenancePart(MaintenanceChargeType.FIXED_FEE,
                        FixedDetailType.BELOW_THRESHOLD, maintenanceTotal, null, null, null, null, null, null),
                List.of(), List.of(), List.of(), version);
    }
}
