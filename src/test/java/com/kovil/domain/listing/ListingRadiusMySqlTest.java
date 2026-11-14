package com.kovil.domain.listing;

import com.kovil.common.dto.response.PageResponse;
import com.kovil.domain.listing.dto.request.ListingPolygon;
import com.kovil.domain.listing.dto.request.ListingRadius;
import com.kovil.domain.listing.dto.request.ListingSearchCondition;
import com.kovil.domain.listing.dto.response.ListingSummaryResponse;
import com.kovil.domain.listing.entity.BrokerProfile;
import com.kovil.domain.listing.entity.BrokerageOffice;
import com.kovil.domain.listing.entity.Listing;
import com.kovil.domain.listing.enums.*;
import com.kovil.domain.listing.repository.BrokerProfileRepository;
import com.kovil.domain.listing.repository.BrokerageOfficeRepository;
import com.kovil.domain.listing.repository.ListingRepository;
import com.kovil.domain.listing.service.ListingQueryService;
import com.kovil.domain.partner.entity.PartnerDivision;
import com.kovil.domain.partner.enums.PartnerDivisionStatus;
import com.kovil.domain.partner.enums.PartnerDivisionType;
import com.kovil.domain.partner.repository.PartnerDivisionRepository;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * 반경 판정을 **진짜 MySQL** 에 붙여 본다. H2 와 MySQL 은 DECIMAL 곱셈의 자릿수 · 파라미터 형 추론이 달라,
 * H2 에서 맞던 원이 MySQL 에서 반올림으로 찌그러질 수 있다(반지름² 은 1km 에서도 0.00008 남짓 — 소수 7자리로 잘리면 원이 사라진다).
 * 그린 구역도 여기서 본다 — 후보 쿼리(생성자 표현식 + Pageable 정렬 · 상한)와 소수 12자리 외접 사각형 파라미터가 MySQL 에서 그대로 도는지.
 * 사무소 필터(uuid → id 서브쿼리)도 여기서 본다 — 개수 쿼리 · 반경 · 후보 쿼리와 함께.
 * CI 의 MySQL 단계가 이 클래스 하나만 돌리므로 따로 떼지 않았다.
 *
 * 스프링을 그 MySQL 에 붙여 띄우고(Flyway 로 최신까지), 실행마다 바다 위의 고유한 중심에 행을 심는다(지우지 않는다) —
 * 다른 실행의 행과 섞이지 않는다. LISTING_RADIUS_DB_URL 이 없으면 통째로 건너뛴다. 그 이름의 스키마만 쓴다.
 */
@EnabledIfEnvironmentVariable(named = "LISTING_RADIUS_DB_URL", matches = ".+")
@SpringBootTest(properties = {
        "spring.datasource.url=${LISTING_RADIUS_DB_URL}",
        "spring.datasource.username=${LISTING_RADIUS_DB_USER:root}",
        "spring.datasource.password=${LISTING_RADIUS_DB_PASSWORD:}",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQLDialect",
        "spring.flyway.enabled=true"})
class ListingRadiusMySqlTest {

    private static final String REQUIRED_SCHEMA = "wjf_listing_radius";

    @Autowired ListingQueryService queryService;
    @Autowired ListingRepository listingRepository;
    @Autowired UserRepository userRepository;
    @Autowired BrokerageOfficeRepository officeRepository;
    @Autowired BrokerProfileRepository profileRepository;
    @Autowired PartnerDivisionRepository divisionRepository;

    // 세션 끊기의 액세스 토큰 취소선(Redis)은 이 테스트의 관심사가 아니다.
    @MockitoBean BlacklistedTokenStore blacklistedTokenStore;

    @BeforeAll
    static void guard() {
        assertThat(System.getenv("LISTING_RADIUS_DB_URL"))
                .describedAs("LISTING_RADIUS_DB_URL 은 %s 스키마만 가리킬 수 있다", REQUIRED_SCHEMA)
                .contains("/" + REQUIRED_SCHEMA);
    }

    @Test
    @DisplayName("MySQL — 300m · 500m · 1km 원이 H2 와 같은 매물을 고른다(모서리 · 원 밖 제외), 추천순 · 최신순 모두")
    void circles_on_mysql() {
        // 실행마다 다른 바다 위 중심(동해 먼바다) — 다른 실행의 행과 겹치지 않게 경도를 조금씩 민다.
        BigDecimal lat = new BigDecimal("37.5000000");
        BigDecimal lng = new BigDecimal("131.0000000")
                .add(BigDecimal.valueOf(Math.floorMod(UUID.randomUUID().hashCode(), 400_000) / 100_000.0))
                .setScale(7, RoundingMode.HALF_UP);
        Broker broker = broker();
        String tag = UUID.randomUUID().toString().substring(0, 6);

        at(broker, tag + "-n250", lat, lng, 250, 0);
        at(broker, tag + "-e480", lat, lng, 0, 480);
        at(broker, tag + "-corner", lat, lng, 400, 400);
        at(broker, tag + "-e950", lat, lng, 0, 950);
        at(broker, tag + "-w1100", lat, lng, 0, -1_100);

        assertThat(uuids(tag, lat, lng, 300, ListingSort.RECOMMENDED)).containsExactlyInAnyOrder(tag + "-n250");
        assertThat(uuids(tag, lat, lng, 500, ListingSort.RECOMMENDED))
                .containsExactlyInAnyOrder(tag + "-n250", tag + "-e480");
        assertThat(uuids(tag, lat, lng, 1_000, ListingSort.LATEST))
                .containsExactlyInAnyOrder(tag + "-n250", tag + "-e480", tag + "-corner", tag + "-e950");
    }

    // ★ 반지름² 은 100m 에서 0.0000008088 남짓이다 — 소수 7자리로 잘리면 실제 반지름이 99.45m 가 되어 99.5m 매물이 빠진다.
    @Test
    @DisplayName("MySQL — 100m 원에서 99.5m 는 들어오고 100.5m 는 빠진다(반지름²이 반올림으로 잘리지 않는다)")
    void small_circle_keeps_precision() {
        BigDecimal lat = new BigDecimal("37.4000000");
        BigDecimal lng = new BigDecimal("131.0000000")
                .add(BigDecimal.valueOf(Math.floorMod(UUID.randomUUID().hashCode(), 400_000) / 100_000.0))
                .setScale(7, RoundingMode.HALF_UP);
        Broker broker = broker();
        String tag = UUID.randomUUID().toString().substring(0, 6);

        atMeters(broker, tag + "-in", lat, lng, 99.5);
        atMeters(broker, tag + "-out", lat, lng, 100.5);

        assertThat(uuids(tag, lat, lng, 100, ListingSort.RECOMMENDED)).containsExactly(tag + "-in");
    }

    @Test
    @DisplayName("MySQL — U 자 구역이 홈의 매물을 빼고, 추천순 · 최신순 · 가격순 모두 같은 매물을 고른다")
    void polygon_on_mysql() {
        // 반경 테스트와 다른 위도(37.3)의 바다 위 — 다른 실행 · 반경 테스트의 행과 겹치지 않게 경도를 민다.
        BigDecimal lat = new BigDecimal("37.3000000");
        BigDecimal lng = new BigDecimal("131.0000000")
                .add(BigDecimal.valueOf(Math.floorMod(UUID.randomUUID().hashCode(), 400_000) / 100_000.0))
                .setScale(7, RoundingMode.HALF_UP);
        Broker broker = broker();
        String tag = UUID.randomUUID().toString().substring(0, 6);

        at(broker, tag + "-south", lat, lng, -150, 0);
        at(broker, tag + "-arm", lat, lng, 200, 200);
        at(broker, tag + "-notch", lat, lng, 200, 0);
        at(broker, tag + "-far", lat, lng, 600, 0);

        // ± 300m 사각형에서 북쪽 가운데(동서 ± 100m, 남북 0 ~ 300m)를 파낸 U 자.
        String uShape = polygonOf(lat, lng, new int[][]{
                {-300, -300}, {-300, 300}, {300, 300}, {300, 100}, {0, 100}, {0, -100}, {300, -100}, {300, -300}});

        for (ListingSort sort : new ListingSort[]{ListingSort.RECOMMENDED, ListingSort.LATEST, ListingSort.PRICE_ASC}) {
            assertThat(drawnUuids(tag, uShape, sort)).as(sort.name())
                    .containsExactlyInAnyOrder(tag + "-south", tag + "-arm");
        }
    }

    // 사무소 필터는 서브쿼리(uuid → id)다 — MySQL 에서 개수 쿼리 · 정렬 · 반경 · 후보 점 쿼리와 함께 그대로 도는지 본다.
    @Test
    @DisplayName("MySQL — 사무소 필터: 그 사무소 매물만 · 개수도 같음 · 반경 · 그린 구역과 AND · 없는 uuid 는 0건")
    void office_filter_on_mysql() {
        BigDecimal lat = new BigDecimal("37.2000000");
        BigDecimal lng = new BigDecimal("131.0000000")
                .add(BigDecimal.valueOf(Math.floorMod(UUID.randomUUID().hashCode(), 400_000) / 100_000.0))
                .setScale(7, RoundingMode.HALF_UP);
        Broker mine = broker();
        Broker other = broker();
        String tag = UUID.randomUUID().toString().substring(0, 6);

        at(mine, tag + "-m0", lat, lng, 0, 0);
        at(mine, tag + "-m200", lat, lng, 200, 0);
        at(mine, tag + "-m2000", lat, lng, 2_000, 0);
        at(other, tag + "-o0", lat, lng, 0, 0);

        String square = polygonOf(lat, lng, new int[][]{{-400, -400}, {-400, 400}, {400, 400}, {400, -400}});
        for (ListingSort sort : new ListingSort[]{ListingSort.RECOMMENDED, ListingSort.LATEST}) {
            PageResponse<ListingSummaryResponse> first = queryService.search(null, ofOffice(mine.officeUuid()),
                    null, null, sort, 0, 1);
            assertThat(first.content()).as(sort.name()).hasSize(1);
            assertThat(first.totalElements()).as(sort.name()).isEqualTo(3);
            assertThat(queryService.search(null, ofOffice(mine.officeUuid()), null, null, sort, 0, 50).content())
                    .extracting(ListingSummaryResponse::listingUuid).as(sort.name())
                    .containsExactlyInAnyOrder(tag + "-m0", tag + "-m200", tag + "-m2000");
            assertThat(queryService.search(null, ofOffice(mine.officeUuid()),
                    ListingRadius.ofNullable(lat, lng, 500), null, sort, 0, 50).content())
                    .extracting(ListingSummaryResponse::listingUuid).as(sort.name())
                    .containsExactlyInAnyOrder(tag + "-m0", tag + "-m200");
            assertThat(queryService.search(null, ofOffice(mine.officeUuid()),
                    null, ListingPolygon.parseOrNull(square), sort, 0, 50).content())
                    .extracting(ListingSummaryResponse::listingUuid).as(sort.name())
                    .containsExactlyInAnyOrder(tag + "-m0", tag + "-m200");
        }
        assertThat(queryService.search(null, ofOffice(UUID.randomUUID().toString()), null, null, null, 0, 50)
                .totalElements()).isZero();
    }

    private static ListingSearchCondition ofOffice(String officeUuid) {
        return new ListingSearchCondition(null, null, null, null, null, null, null, null, null, null,
                false, false, null, null, officeUuid, null, null, null, null);
    }

    // 꼭짓점은 소수 12자리로 보낸다 — 외접 사각형 파라미터가 DECIMAL(10,7) 칸보다 자릿수가 많아도 MySQL 이 정확히 비교하는지 본다.
    private static String polygonOf(BigDecimal lat, BigDecimal lng, int[][] meters) {
        double cos = Math.cos(Math.toRadians(lat.doubleValue()));
        return Arrays.stream(meters).map(m ->
                lat.add(BigDecimal.valueOf(m[0] / 111_195.08)).setScale(12, RoundingMode.HALF_UP).toPlainString() + ":"
                        + lng.add(BigDecimal.valueOf(m[1] / (111_195.08 * cos))).setScale(12, RoundingMode.HALF_UP).toPlainString())
                .collect(Collectors.joining(","));
    }

    /*
     * 이 스키마는 실행마다 행이 쌓인다(지우지 않는다) — 중심을 바다 위에서 무작위로 밀어도, 지난 실행의 점이 이번 원 · 다각형 안에
     * 떨어질 수 있다(로컬에서 여러 번 돌리면 실제로 걸린다). 판정은 이번 실행의 태그가 붙은 행만으로 한다.
     */
    private List<String> drawnUuids(String tag, String polygon, ListingSort sort) {
        PageResponse<ListingSummaryResponse> page = queryService.search(null,
                new ListingSearchCondition(null, null, null, null, null, null, null, null, null, null,
                        false, false, null, null, null, null, null, null, null),
                null, ListingPolygon.parseOrNull(polygon), sort, 0, 50);
        return page.content().stream().map(ListingSummaryResponse::listingUuid).filter(u -> u.startsWith(tag + "-")).toList();
    }

    private void atMeters(Broker broker, String uuid, BigDecimal lat, BigDecimal lng, double north) {
        BigDecimal dLat = lat.add(BigDecimal.valueOf(north / 111_195.08)).setScale(7, RoundingMode.HALF_UP);
        save(broker, uuid, dLat, lng);
    }

    private List<String> uuids(String tag, BigDecimal lat, BigDecimal lng, int meters, ListingSort sort) {
        PageResponse<ListingSummaryResponse> page = queryService.search(null,
                new ListingSearchCondition(null, null, null, null, null, null, null, null, null, null,
                        false, false, null, null, null, null, null, null, null),
                ListingRadius.ofNullable(lat, lng, meters), null, sort, 0, 50);
        return page.content().stream().map(ListingSummaryResponse::listingUuid).filter(u -> u.startsWith(tag + "-")).toList();
    }

    private record Broker(Long userId, Long officeId, String officeUuid) {
    }

    private void at(Broker broker, String uuid, BigDecimal lat, BigDecimal lng, int north, int east) {
        double cos = Math.cos(Math.toRadians(lat.doubleValue()));
        BigDecimal dLat = lat.add(BigDecimal.valueOf(north / 111_195.08)).setScale(7, RoundingMode.HALF_UP);
        BigDecimal dLng = lng.add(BigDecimal.valueOf(east / (111_195.08 * cos))).setScale(7, RoundingMode.HALF_UP);
        save(broker, uuid, dLat, dLng);
    }

    private void save(Broker broker, String uuid, BigDecimal dLat, BigDecimal dLng) {
        listingRepository.saveAndFlush(Listing.builder()
                .uuid(uuid).listingNo("LR-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16))
                .officeId(broker.officeId()).createdByUserId(broker.userId())
                .category(ListingCategory.ONE_TWO_ROOM).roomType(RoomType.ONE_ROOM)
                .title(uuid).sellingType(SellingType.MONTHLY_RENT)
                .deposit(3_000_000L).monthlyRent(300_000L)
                .exclusiveArea(new BigDecimal("26.44")).floorType(FloorType.GROUND)
                .residentRegistration(AvailabilityAnswer.ASK).depositInsurance(AvailabilityAnswer.ASK)
                .realLat(dLat).realLng(dLng).displayLat(dLat).displayLng(dLng)
                .addressJibun("테스트 주소")
                .postedAt(LocalDateTime.of(2026, 10, 13, 9, 0))
                .status(ListingStatus.ACTIVE).build());
    }

    private Broker broker() {
        String uuid = UUID.randomUUID().toString();
        User user = userRepository.save(User.builder().uuid(uuid).name("홍길동")
                .nickname(uuid.substring(0, 8)).nicknameNormalized(uuid.substring(0, 8)).isNicknameCustom(false)
                .phoneHash(UUID.randomUUID().toString()).email(uuid.substring(0, 8) + "@example.test")
                .preferredLanguage("ko").status(UserStatus.ACTIVE).role(UserRole.PARTNER).build());
        String regNo = "99999-2026-" + uuid.substring(0, 5);
        BrokerageOffice office = officeRepository.save(BrokerageOffice.builder().uuid(UUID.randomUUID().toString())
                .regNo(regNo).claimedRegNo(regNo).name("테스트공인중개사사무소").ceoName("홍길동")
                .address("서울특별시 서대문구").tel("02-000-0000").build());
        profileRepository.save(BrokerProfile.builder().userId(user.getId()).officeId(office.getId()).build());
        divisionRepository.save(PartnerDivision.builder().uuid(UUID.randomUUID().toString())
                .userId(user.getId()).division(PartnerDivisionType.REAL_ESTATE_BROKER).officeId(office.getId())
                .status(PartnerDivisionStatus.APPROVED).build());
        return new Broker(user.getId(), office.getId(), office.getUuid());
    }
}
