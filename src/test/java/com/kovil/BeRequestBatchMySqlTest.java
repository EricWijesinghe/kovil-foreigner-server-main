package com.kovil;

import com.kovil.domain.auth.service.AuthService;
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
import com.kovil.domain.listing.enums.FixedDetailType;
import com.kovil.domain.listing.enums.FloorType;
import com.kovil.domain.listing.enums.ListingCategory;
import com.kovil.domain.listing.enums.ListingStatus;
import com.kovil.domain.listing.enums.MaintenanceChargeType;
import com.kovil.domain.listing.enums.RoomType;
import com.kovil.domain.listing.enums.SellingType;
import com.kovil.domain.listing.exception.ListingException;
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
import com.kovil.domain.refreshtoken.dto.result.RefreshTokenIssueResult;
import com.kovil.domain.refreshtoken.exception.RefreshTokenException;
import com.kovil.domain.refreshtoken.service.RefreshTokenService;
import com.kovil.domain.university.dto.response.UniversityResponse;
import com.kovil.domain.university.entity.University;
import com.kovil.domain.university.repository.UniversityRepository;
import com.kovil.domain.university.service.UniversityQueryService;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.user.repository.UserRepository;
import com.kovil.infra.jwt.BlacklistedTokenStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PessimisticLockException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/*
 * 남은 BE_REQUEST 묶음(10-22)을 **진짜 MySQL** 에서 본다. H2 는 잠금 · 제약 · DECIMAL 산술이 운영과 달라
 * 강제 version 증가의 동시성, 미첨부 사진 배치의 NOT EXISTS · 잠금, 가까운 순 정렬, 리프레시 유예의 별도 트랜잭션 잠금을 못 본다.
 * 스프링을 그 MySQL 에 붙여 띄우고(Flyway 로 최신까지), 실행마다 고유한 값으로 행을 심는다(지우지 않는다).
 * BATCH_DB_URL 이 없으면 통째로 건너뛴다. 그 이름의 스키마만 쓴다.
 */
@EnabledIfEnvironmentVariable(named = "BATCH_DB_URL", matches = ".+")
@SpringBootTest(properties = {
        "spring.datasource.url=${BATCH_DB_URL}",
        "spring.datasource.username=${BATCH_DB_USER:root}",
        "spring.datasource.password=${BATCH_DB_PASSWORD:}",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQLDialect",
        "spring.flyway.enabled=true"})
class BeRequestBatchMySqlTest {

    private static final String REQUIRED_SCHEMA = "wjf_batch";
    private static final BigDecimal LAT = new BigDecimal("37.4862378");
    private static final BigDecimal LNG = new BigDecimal("126.9331220");

    @Autowired BrokerListingService listingService;
    @Autowired ListingImageOrphanScheduler orphanScheduler;
    @Autowired UniversityQueryService universityQueryService;
    @Autowired RefreshTokenService refreshTokenService;
    @Autowired AuthService authService;
    @Autowired UserRepository userRepository;
    @Autowired BrokerageOfficeRepository officeRepository;
    @Autowired BrokerProfileRepository profileRepository;
    @Autowired PartnerDivisionRepository divisionRepository;
    @Autowired ListingRepository listingRepository;
    @Autowired ListingImageRepository imageRepository;
    @Autowired StoredFileRepository fileRepository;
    @Autowired UniversityRepository universityRepository;
    @Autowired JdbcTemplate jdbc;

    // 액세스 토큰 취소선(Redis)은 이 테스트의 관심사가 아니다.
    @MockitoBean BlacklistedTokenStore blacklistedTokenStore;

    @BeforeAll
    static void guard() {
        assertThat(System.getenv("BATCH_DB_URL"))
                .describedAs("BATCH_DB_URL 은 %s 스키마만 가리킬 수 있다", REQUIRED_SCHEMA)
                .contains("/" + REQUIRED_SCHEMA);
    }

    private record Broker(String uuid, Long id, Long officeId) {
    }

    private Broker broker() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        Long userId = userRepository.save(User.builder().uuid("u-b-" + tag).name("홍길동")
                .nickname("b-" + tag).nicknameNormalized("b-" + tag).isNicknameCustom(false)
                .phoneHash(UUID.randomUUID().toString())
                .preferredLanguage("ko").status(UserStatus.ACTIVE).role(UserRole.PARTNER).build()).getId();
        Long officeId = officeRepository.save(BrokerageOffice.builder().uuid("o-b-" + tag)
                .regNo("9" + tag.substring(0, 4).replaceAll("[^0-9]", "1") + "-2026-" + String.format("%05d", Math.floorMod(tag.hashCode(), 100000)))
                .name("테스트공인중개사사무소").ceoName("홍길동").address("서울특별시 관악구").tel("02-000-0000").build()).getId();
        profileRepository.save(BrokerProfile.builder().userId(userId).officeId(officeId).build());
        divisionRepository.save(PartnerDivision.builder().uuid("pd-b-" + tag).userId(userId)
                .division(PartnerDivisionType.REAL_ESTATE_BROKER).status(PartnerDivisionStatus.APPROVED)
                .submittedAt(LocalDateTime.of(2026, 10, 1, 0, 0)).build());
        return new Broker("u-b-" + tag, userId, officeId);
    }

    private Listing listing(Broker broker, ListingStatus status, StoredFile... photos) {
        String tag = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        Listing listing = listingRepository.saveAndFlush(Listing.builder()
                .uuid(UUID.randomUUID().toString()).listingNo("LS-261022-" + tag)
                .officeId(broker.officeId()).createdByUserId(broker.id())
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
        return listing;
    }

    private BrokerListingSaveRequest request(Long version, Long maintenanceTotal, List<String> photos) {
        return new BrokerListingSaveRequest(
                ListingCategory.ONE_TWO_ROOM, RoomType.ONE_ROOM, false, false, "테스트", "설명",
                SellingType.MONTHLY_RENT, 3_000_000L, 300_000L, new BigDecimal("26.44"), null, null,
                FloorType.GROUND, null, null, null, null, null, null, null, null,
                false, null, null, null, null, false, null,
                AvailabilityAnswer.ASK, AvailabilityAnswer.ASK, false,
                LAT, LNG, null, null, "서울특별시 관악구 신림동", null, false, null, null,
                new BrokerListingSaveRequest.MaintenancePart(MaintenanceChargeType.FIXED_FEE,
                        FixedDetailType.BELOW_THRESHOLD, maintenanceTotal, null, null, null, null, null, null),
                List.of(), List.of(), photos, version);
    }

    // 운영에서 409 로 나가는 것들(GlobalExceptionHandler) — 그 밖의 예외가 새면 500 이다.
    private static boolean isConflict(Throwable e) {
        return (e instanceof ListingException listing && "CONFLICT".equals(listing.getErrorCode().getCode()))
                || e instanceof OptimisticLockException
                || e instanceof PessimisticLockException
                || e instanceof LockTimeoutException
                || e instanceof ObjectOptimisticLockingFailureException
                || e instanceof PessimisticLockingFailureException;
    }

    @Test
    @DisplayName("실 MySQL: 같은 version 으로 동시에 두 번 저장하면 하나만 저장되고 다른 하나는 409(500 아님)")
    void concurrent_saves_with_the_same_version() throws Exception {
        Broker broker = broker();
        Listing listing = listing(broker, ListingStatus.DRAFT);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<String> seen = new ArrayList<>();
        List<Throwable> unexpected = new ArrayList<>();
        try {
            for (int round = 0; round < 10; round++) {
                long version = listingService.get(broker.uuid(), listing.getUuid()).version();
                CountDownLatch start = new CountDownLatch(1);
                List<Future<?>> calls = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    long total = 100_000L + round * 10 + i;
                    calls.add(pool.submit(() -> {
                        start.await();
                        return listingService.update(broker.uuid(), listing.getUuid(), request(version, total, List.of()));
                    }));
                }
                start.countDown();
                int saved = 0;
                for (Future<?> call : calls) {
                    try {
                        call.get(30, TimeUnit.SECONDS);
                        saved++;
                    } catch (ExecutionException e) {
                        seen.add(e.getCause().getClass().getSimpleName());
                        if (!isConflict(e.getCause())) {
                            unexpected.add(e.getCause());
                        }
                    }
                }
                assertThat(saved).as("round %d", round).isEqualTo(1);
                assertThat(listingService.get(broker.uuid(), listing.getUuid()).version()).isNotEqualTo(version);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(unexpected).isEmpty();
        System.out.println("[batch-mysql] 동시 저장 10회 — 진 쪽 예외 " + seen);
    }

    @Test
    @DisplayName("실 MySQL: 관리비만 바꾼 저장도 version 이 오르고, 옛 version 은 409 · 아무것도 안 바뀐다")
    void child_only_save_and_stale_form() {
        Broker broker = broker();
        Listing listing = listing(broker, ListingStatus.DRAFT);
        long opened = listingService.get(broker.uuid(), listing.getUuid()).version();

        long saved = listingService.update(broker.uuid(), listing.getUuid(), request(opened, 100_000L, List.of())).version();

        assertThat(saved).isNotEqualTo(opened).isEqualTo(listingService.get(broker.uuid(), listing.getUuid()).version());
        assertThatThrownBy(() -> listingService.update(broker.uuid(), listing.getUuid(), request(opened, 999_000L, List.of())))
                .satisfies(e -> assertThat(isConflict(e)).isTrue());
        assertThat(listingService.get(broker.uuid(), listing.getUuid()).maintenance().totalCost()).isEqualTo(100_000L);
    }

    private StoredFile file(Long uploaderId, FilePurpose purpose, int hoursAgo) {
        String uuid = UUID.randomUUID().toString();
        StoredFile file = fileRepository.saveAndFlush(StoredFile.builder().uuid(uuid).purpose(purpose).uploaderId(uploaderId)
                .storageKey("listing-images/" + uuid + ".jpg").contentType("image/jpeg").byteSize(1024L)
                .fileUrl("https://file.example.test/" + uuid + ".jpg").build());
        jdbc.update("UPDATE files SET created_at = ? WHERE id = ?", LocalDateTime.now().minusHours(hoursAgo), file.getId());
        return file;
    }

    private FileStatus statusOf(StoredFile file) {
        return fileRepository.findById(file.getId()).orElseThrow().getStatus();
    }

    @Test
    @DisplayName("실 MySQL: 미첨부 사진 배치 — 유예 지난 미첨부 매물 사진만 폐기, 수정에서 뺀 사진 폐기 · 공유 사진 유지")
    void orphan_listing_images() {
        Broker broker = broker();
        StoredFile staleUnattached = file(broker.id(), FilePurpose.LISTING_IMAGE, 48);
        StoredFile staleAttached = file(broker.id(), FilePurpose.LISTING_IMAGE, 48);
        StoredFile fresh = file(broker.id(), FilePurpose.LISTING_IMAGE, 1);
        StoredFile otherPurpose = file(broker.id(), FilePurpose.COMMUNITY_IMAGE, 48);
        listing(broker, ListingStatus.HIDDEN, staleAttached);

        orphanScheduler.run();
        orphanScheduler.run();

        assertThat(statusOf(staleUnattached)).isEqualTo(FileStatus.DELETED);
        assertThat(statusOf(staleAttached)).isEqualTo(FileStatus.ACTIVE);
        assertThat(statusOf(fresh)).isEqualTo(FileStatus.ACTIVE);
        assertThat(statusOf(otherPurpose)).isEqualTo(FileStatus.ACTIVE);

        StoredFile kept = file(broker.id(), FilePurpose.LISTING_IMAGE, 1);
        StoredFile removed = file(broker.id(), FilePurpose.LISTING_IMAGE, 1);
        StoredFile shared = file(broker.id(), FilePurpose.LISTING_IMAGE, 1);
        Listing edited = listing(broker, ListingStatus.DRAFT, kept, removed, shared);
        listing(broker, ListingStatus.DRAFT, shared);

        listingService.update(broker.uuid(), edited.getUuid(), request(null, 100_000L, List.of(kept.getUuid())));

        assertThat(statusOf(kept)).isEqualTo(FileStatus.ACTIVE);
        assertThat(statusOf(removed)).isEqualTo(FileStatus.DELETED);
        assertThat(statusOf(shared)).isEqualTo(FileStatus.ACTIVE);
    }

    @Test
    // 위도 33.15 에서 cos² ≈ 0.70 — 동쪽 0.011도(약 1.02km)가 북쪽 0.010도(약 1.11km)보다 가깝다.
    @DisplayName("실 MySQL: 대학교는 지도 영역 가운데에서 가까운 순(같은 거리면 코드 순, 경도는 위도에 맞춰 줄임)")
    void universities_nearest_first() {
        String prefix = String.format("%03d", Math.floorMod(UUID.randomUUID().hashCode(), 1000));
        // 바다 한가운데 상자 — 지난 실행의 행이 남아 있으니 이번 접두사만 본다.
        save(prefix + "9990", "33.1900000", "124.5900000");
        save(prefix + "9992", "33.1520000", "124.5500000");
        save(prefix + "9991", "33.1480000", "124.5500000");
        save(prefix + "9993", "33.1500000", "124.5610000");
        save(prefix + "9994", "33.1600000", "124.5500000");

        List<String> codes = universityQueryService.findWithin(new BigDecimal("33.10"), new BigDecimal("124.50"),
                new BigDecimal("33.20"), new BigDecimal("124.60")).stream().map(UniversityResponse::code)
                .filter(code -> code.startsWith(prefix)).toList();

        assertThat(codes).containsExactly(prefix + "9991", prefix + "9992", prefix + "9993", prefix + "9994", prefix + "9990");
    }

    private void save(String code, String lat, String lng) {
        universityRepository.saveAndFlush(University.builder().code(code).nameKo("테스트대학교 " + code).nameEn("Test " + code)
                .addressRoad("테스트로 1").lat(new BigDecimal(lat)).lng(new BigDecimal(lng)).isActive(true).build());
    }

    private User student() {
        return userRepository.save(User.builder().uuid(UUID.randomUUID().toString()).name("reuse")
                .phoneHash(UUID.randomUUID().toString()).status(UserStatus.ACTIVE).role(UserRole.USER).build());
    }

    private long active(User user) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ? AND status = 'ACTIVE'",
                Long.class, user.getId());
    }

    @Test
    @DisplayName("실 MySQL: 회전 직후 옛 토큰이 다시 오면 그 자식만 끊기고(별도 트랜잭션 커밋) 다른 기기는 산다")
    void refresh_reuse_within_grace() {
        User user = student();
        RefreshTokenIssueResult lost = refreshTokenService.issue(user);
        RefreshTokenIssueResult other = refreshTokenService.issue(user);
        RefreshTokenIssueResult unseen = refreshTokenService.rotate(lost.rawToken());

        assertThatThrownBy(() -> authService.refresh(lost.rawToken())).isInstanceOf(RefreshTokenException.class);
        assertThat(active(user)).isEqualTo(1);
        // 그 401 도 사라져 창 안에 한 번 더 — 다른 기기는 여전히 산다.
        assertThatThrownBy(() -> authService.refresh(lost.rawToken())).isInstanceOf(RefreshTokenException.class);
        assertThat(active(user)).isEqualTo(1);
        assertThatThrownBy(() -> authService.refresh(unseen.rawToken())).isInstanceOf(RefreshTokenException.class);
        assertThat(authService.refresh(other.rawToken())).isNotNull();
    }

    // 같은 토큰을 동시에 두 번 — 늦은 쪽은 앞쪽 커밋 뒤 ROTATED 를 보고 유예 규칙을 탄다. 다른 기기는 살아야 하고 500 이 새면 안 된다.
    @Test
    @DisplayName("실 MySQL: 같은 토큰 동시 회전 — 하나는 200, 하나는 401, 다른 기기는 무사, 500 없음")
    void refresh_double_send() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Throwable> unexpected = new ArrayList<>();
        int raced = 0;
        try {
            for (int round = 0; round < 10; round++) {
                User user = student();
                RefreshTokenIssueResult token = refreshTokenService.issue(user);
                RefreshTokenIssueResult other = refreshTokenService.issue(user);
                CountDownLatch start = new CountDownLatch(1);
                List<Future<?>> calls = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    calls.add(pool.submit(() -> {
                        start.await();
                        return authService.refresh(token.rawToken());
                    }));
                }
                start.countDown();
                int ok = 0;
                for (Future<?> call : calls) {
                    try {
                        call.get(30, TimeUnit.SECONDS);
                        ok++;
                    } catch (ExecutionException e) {
                        if (!(e.getCause() instanceof RefreshTokenException)) {
                            unexpected.add(e.getCause());
                        }
                    }
                }
                assertThat(ok).as("round %d", round).isEqualTo(1);
                assertThat(authService.refresh(other.rawToken())).as("다른 기기는 무사").isNotNull();
                raced++;
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(unexpected).isEmpty();
        System.out.println("[batch-mysql] 같은 토큰 동시 회전 " + raced + "회 — 매 회 200 하나 · 401 하나");
    }
}
