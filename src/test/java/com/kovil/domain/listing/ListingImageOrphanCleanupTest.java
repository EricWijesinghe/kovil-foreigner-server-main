package com.kovil.domain.listing;

import com.kovil.domain.file.entity.StoredFile;
import com.kovil.domain.file.enums.FilePurpose;
import com.kovil.domain.file.enums.FileStatus;
import com.kovil.domain.file.repository.StoredFileRepository;
import com.kovil.domain.listing.dto.request.BrokerListingSaveRequest;
import com.kovil.domain.listing.entity.BrokerProfile;
import com.kovil.domain.listing.entity.BrokerageOffice;
import com.kovil.domain.listing.entity.Listing;
import com.kovil.domain.listing.entity.ListingImage;
import com.kovil.domain.listing.enums.AvailabilityAnswer;
import com.kovil.domain.listing.enums.FloorType;
import com.kovil.domain.listing.enums.ListingCategory;
import com.kovil.domain.listing.enums.ListingStatus;
import com.kovil.domain.listing.enums.RoomType;
import com.kovil.domain.listing.enums.SellingType;
import com.kovil.domain.listing.repository.BrokerProfileRepository;
import com.kovil.domain.listing.repository.BrokerageOfficeRepository;
import com.kovil.domain.listing.repository.ListingImageRepository;
import com.kovil.domain.listing.repository.ListingRepository;
import com.kovil.domain.listing.service.BrokerListingService;
import com.kovil.domain.listing.service.ListingImageOrphanScheduler;
import com.kovil.domain.partner.entity.PartnerDivision;
import com.kovil.domain.partner.enums.PartnerDivisionStatus;
import com.kovil.domain.partner.enums.PartnerDivisionType;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * 매물 사진 고아 정리를 진짜 DB 로 본다.
 *   · 배치: 유예(24시간)가 지난 미첨부 매물 사진만 폐기 표시 — 붙은 것 · 유예 안 · 다른 용도는 그대로, 두 번 돌려도 같다.
 *   · 수정: 뺀 사진은 그 자리에서 폐기 표시, 다른 매물에도 붙은 사진은 남는다.
 *   · 초안 삭제: 그 사진을 폐기 표시(FK CASCADE 가 listing_images 를 지운 뒤에도 파일 행은 남으므로).
 * ★ @Transactional 을 붙이지 않는다 — 배치는 건마다 REQUIRES_NEW 다. 만든 행은 끝에서 지운다.
 */
@SpringBootTest
class ListingImageOrphanCleanupTest {

    private static final BigDecimal LAT = new BigDecimal("37.4862378");
    private static final BigDecimal LNG = new BigDecimal("126.9331220");

    @Autowired ListingImageOrphanScheduler scheduler;
    @Autowired BrokerListingService service;
    @Autowired UserRepository userRepository;
    @Autowired BrokerageOfficeRepository officeRepository;
    @Autowired BrokerProfileRepository profileRepository;
    @Autowired PartnerDivisionRepository divisionRepository;
    @Autowired ListingRepository listingRepository;
    @Autowired ListingImageRepository imageRepository;
    @Autowired StoredFileRepository fileRepository;
    @Autowired JdbcTemplate jdbc;

    private final String tag = UUID.randomUUID().toString().substring(0, 8);
    private final List<Long> fileIds = new ArrayList<>();
    private final List<String> listingUuids = new ArrayList<>();
    private String userUuid;
    private Long userId;
    private Long officeId;
    private Long profileId;
    private Long divisionId;

    @BeforeEach
    void setUp() {
        userUuid = "u-orp-" + tag;
        userId = userRepository.save(User.builder().uuid(userUuid).name("홍길동")
                .nickname("orp-" + tag).nicknameNormalized("orp-" + tag).isNicknameCustom(false)
                .preferredLanguage("ko").status(UserStatus.ACTIVE).role(UserRole.PARTNER).build()).getId();
        officeId = officeRepository.save(BrokerageOffice.builder().uuid("o-orp-" + tag).regNo("99998-2026-" + tag.substring(0, 5))
                .name("테스트공인중개사사무소").ceoName("홍길동").address("서울특별시 관악구").tel("02-000-0000").build()).getId();
        profileId = profileRepository.save(BrokerProfile.builder().userId(userId).officeId(officeId).build()).getId();
        divisionId = divisionRepository.save(PartnerDivision.builder().uuid("pd-orp-" + tag).userId(userId)
                .division(PartnerDivisionType.REAL_ESTATE_BROKER).status(PartnerDivisionStatus.APPROVED)
                .submittedAt(LocalDateTime.of(2026, 10, 1, 0, 0)).build()).getId();
    }

    @AfterEach
    void tearDown() {
        for (String uuid : listingUuids) {
            listingRepository.findByUuid(uuid).ifPresent(l -> {
                jdbc.update("DELETE FROM listing_images WHERE listing_id = ?", l.getId());
                listingRepository.deleteById(l.getId());
            });
        }
        fileIds.forEach(fileRepository::deleteById);
        divisionRepository.deleteById(divisionId);
        profileRepository.deleteById(profileId);
        officeRepository.deleteById(officeId);
        userRepository.deleteById(userId);
    }

    private StoredFile file(FilePurpose purpose, int hoursAgo) {
        String uuid = UUID.randomUUID().toString();
        StoredFile file = fileRepository.saveAndFlush(StoredFile.builder().uuid(uuid).purpose(purpose).uploaderId(userId)
                .storageKey("listing-images/" + uuid + ".jpg").contentType("image/jpeg").byteSize(1024L)
                .fileUrl("https://file.example.test/" + uuid + ".jpg").build());
        jdbc.update("UPDATE files SET created_at = ? WHERE id = ?", LocalDateTime.now().minusHours(hoursAgo), file.getId());
        fileIds.add(file.getId());
        return file;
    }

    private Listing listing(String suffix, ListingStatus status, StoredFile... photos) {
        String uuid = "orp-" + suffix + "-" + tag;
        Listing listing = listingRepository.saveAndFlush(Listing.builder()
                .uuid(uuid).listingNo("LS-261022-" + (suffix + tag).toUpperCase().substring(0, 6))
                .officeId(officeId).createdByUserId(userId)
                .category(ListingCategory.ONE_TWO_ROOM).roomType(RoomType.ONE_ROOM)
                .title("테스트").description("설명").sellingType(SellingType.MONTHLY_RENT)
                .deposit(3_000_000L).monthlyRent(300_000L)
                .exclusiveArea(new BigDecimal("26.44")).floorType(FloorType.GROUND)
                .residentRegistration(AvailabilityAnswer.ASK).depositInsurance(AvailabilityAnswer.ASK)
                .realLat(LAT).realLng(LNG).displayLat(LAT).displayLng(LNG)
                .addressJibun("서울특별시 관악구 신림동").status(status).build());
        for (int i = 0; i < photos.length; i++) {
            imageRepository.saveAndFlush(ListingImage.builder().listingId(listing.getId()).fileId(photos[i].getId()).sortOrder(i).build());
        }
        listingUuids.add(uuid);
        return listing;
    }

    private FileStatus statusOf(StoredFile file) {
        return fileRepository.findById(file.getId()).orElseThrow().getStatus();
    }

    @Test
    @DisplayName("배치는 유예가 지난 미첨부 매물 사진만 폐기 표시하고, 두 번 돌려도 같다")
    void batch_discards_only_stale_unattached_listing_images() {
        StoredFile staleUnattached = file(FilePurpose.LISTING_IMAGE, 48);
        StoredFile staleAttached = file(FilePurpose.LISTING_IMAGE, 48);
        StoredFile freshUnattached = file(FilePurpose.LISTING_IMAGE, 1);
        StoredFile otherPurpose = file(FilePurpose.COMMUNITY_IMAGE, 48);
        listing("b", ListingStatus.HIDDEN, staleAttached);

        scheduler.run();
        scheduler.run();

        assertThat(statusOf(staleUnattached)).isEqualTo(FileStatus.DELETED);
        assertThat(statusOf(staleAttached)).isEqualTo(FileStatus.ACTIVE);
        assertThat(statusOf(freshUnattached)).isEqualTo(FileStatus.ACTIVE);
        assertThat(statusOf(otherPurpose)).isEqualTo(FileStatus.ACTIVE);
    }

    @Test
    @DisplayName("수정에서 뺀 사진은 폐기 표시되고, 다른 매물에도 붙은 사진은 남는다")
    void edit_discards_removed_photo_but_keeps_shared_one() {
        StoredFile kept = file(FilePurpose.LISTING_IMAGE, 1);
        StoredFile removed = file(FilePurpose.LISTING_IMAGE, 1);
        StoredFile shared = file(FilePurpose.LISTING_IMAGE, 1);
        listing("e", ListingStatus.DRAFT, kept, removed, shared);
        listing("s", ListingStatus.DRAFT, shared);

        service.update(userUuid, "orp-e-" + tag, request(List.of(kept.getUuid())));

        assertThat(statusOf(kept)).isEqualTo(FileStatus.ACTIVE);
        assertThat(statusOf(removed)).isEqualTo(FileStatus.DELETED);
        assertThat(statusOf(shared)).isEqualTo(FileStatus.ACTIVE);
    }

    @Test
    @DisplayName("초안을 지우면 그 사진이 폐기 표시된다")
    void deleting_a_draft_discards_its_photo() {
        StoredFile photo = file(FilePurpose.LISTING_IMAGE, 1);
        listing("d", ListingStatus.DRAFT, photo);

        service.delete(userUuid, "orp-d-" + tag);

        assertThat(statusOf(photo)).isEqualTo(FileStatus.DELETED);
        assertThat(listingRepository.findByUuid("orp-d-" + tag)).isEmpty();
    }

    private BrokerListingSaveRequest request(List<String> imageFileUuids) {
        return new BrokerListingSaveRequest(
                ListingCategory.ONE_TWO_ROOM, RoomType.ONE_ROOM, false, false,
                "테스트", "설명",
                SellingType.MONTHLY_RENT, 3_000_000L, 300_000L,
                new BigDecimal("26.44"), null, null,
                FloorType.GROUND, null, null, null, null,
                null, null, null, null,
                false, null, null, null, null,
                false, null,
                AvailabilityAnswer.ASK, AvailabilityAnswer.ASK, false,
                LAT, LNG, null, null, "서울특별시 관악구 신림동", null, false,
                null, null, null, List.of(), List.of(), imageFileUuids, null);
    }
}
