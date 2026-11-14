package com.kovil.domain.listing;

import com.kovil.domain.listing.dto.request.SavedAreaCreateRequest;
import com.kovil.domain.listing.dto.request.SavedAreaFilters;
import com.kovil.domain.listing.dto.response.SavedAreaResponse;
import com.kovil.domain.listing.enums.AvailabilityAnswer;
import com.kovil.domain.listing.enums.FloorType;
import com.kovil.domain.listing.enums.ListingCategory;
import com.kovil.domain.listing.enums.RoomType;
import com.kovil.domain.listing.enums.SellingType;
import com.kovil.domain.listing.entity.SavedArea;
import com.kovil.domain.listing.exception.ListingException;
import com.kovil.domain.listing.repository.SavedAreaRepository;
import com.kovil.domain.listing.service.SavedAreaService;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * 저장한 구역 20개 상한을 **진짜 MySQL 동시 저장**으로 본다. H2 · 목으로는 잠금 순서를 확인할 수 없다.
 * ★ 상한은 '센 뒤 넣는다'라 잠금이 없으면 19개인 사람이 동시에 두 번 저장할 때 둘 다 통과해 21개가 된다.
 *   사용자 행 잠금이 첫 문장이면 두 저장이 줄을 서고, 뒤 저장의 count 가 앞 저장의 커밋을 본다.
 * 반경 MySQL 테스트와 같은 스키마(LISTING_RADIUS_DB_URL)를 쓴다 — 실행마다 새 사용자를 만들고 지우지 않는다.
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
class SavedAreaLimitRaceMySqlTest {

    private static final String REQUIRED_SCHEMA = "wjf_listing_radius";
    private static final String SQUARE = "37.55:126.90,37.55:126.94,37.59:126.94,37.59:126.90";
    private static final int ROUNDS = 5;

    @Autowired SavedAreaService savedAreaService;
    @Autowired SavedAreaRepository savedAreaRepository;
    @Autowired UserRepository userRepository;

    // 세션 끊기의 액세스 토큰 취소선(Redis)은 이 테스트의 관심사가 아니다.
    @MockitoBean BlacklistedTokenStore blacklistedTokenStore;

    @BeforeAll
    static void guard() {
        assertThat(System.getenv("LISTING_RADIUS_DB_URL"))
                .describedAs("LISTING_RADIUS_DB_URL 은 %s 스키마만 가리킬 수 있다", REQUIRED_SCHEMA)
                .contains("/" + REQUIRED_SCHEMA);
    }

    @Test
    @DisplayName("MySQL — 19개인 사람이 동시에 두 번 저장하면 하나만 들어가고 하나는 409 SAVED_AREA_LIMIT_EXCEEDED")
    void concurrent_saves_respect_the_limit() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            User user = userRepository.save(User.builder().uuid(UUID.randomUUID().toString())
                    .status(UserStatus.ACTIVE).role(UserRole.USER).preferredLanguage("ko").build());
            for (int i = 0; i < 19; i++) {
                savedAreaRepository.save(SavedArea.builder()
                        .userId(user.getId()).name("구역 " + i).polygon(SQUARE).filters("{}").build());
            }

            List<String> outcomes = race(user.getUuid());

            assertThat(outcomes).as("round %d", round)
                    .containsExactlyInAnyOrder("OK", "SAVED_AREA_LIMIT_EXCEEDED");
            assertThat(savedAreaRepository.countByUserId(user.getId())).as("round %d", round).isEqualTo(20);
        }
    }

    // 지우기가 읽기 전용 트랜잭션이면 Connector/J 가 커넥션을 read-only 로 돌려 DELETE 가 거절된다.
    // H2 는 read-only 를 힌트로만 받고 API 테스트는 테스트 트랜잭션에 합류하므로, 진짜 MySQL 에서 트랜잭션 밖 호출로만 드러난다.
    @Test
    @DisplayName("MySQL — 트랜잭션 밖에서 부른 지우기가 실제로 지운다(쓰기 트랜잭션)")
    void delete_runs_in_writable_transaction() {
        User user = newUser();
        SavedArea area = savedAreaRepository.save(SavedArea.builder()
                .userId(user.getId()).name("지울 구역").polygon(SQUARE).filters("{}").build());

        savedAreaService.delete(user.getUuid(), area.getUuid());

        assertThat(savedAreaRepository.countByUserId(user.getId())).isZero();
    }

    /*
     * 서버가 받는 가장 긴 값이 운영 칸(V32)에 그대로 들어가는지 본다 — 칸 폭은 H2(엔티티 length)가 아니라 마이그레이션이 정한다.
     * 꼭짓점 30개 · 소수 15자리 구역(약 1,200자) · 필터 전부 · 이름 20자 · 동 이름 40자.
     */
    @Test
    @DisplayName("MySQL — 꼭짓점 30개 구역 · 필터 전부 · 이름 20자 · 동 이름 40자가 실제 칸에 들어가고 그대로 돌아온다")
    void widest_values_fit_real_columns() {
        User user = newUser();
        String poly30 = IntStream.range(0, 30).mapToObj(i -> {
            double t = 2 * Math.PI * i / 30;
            return BigDecimal.valueOf(37.5 + 0.01 * Math.sin(t)).setScale(15, RoundingMode.HALF_UP).toPlainString()
                    + ":" + BigDecimal.valueOf(127.0 + 0.01 * Math.cos(t)).setScale(15, RoundingMode.HALF_UP).toPlainString();
        }).collect(Collectors.joining(","));
        BigDecimal area = new BigDecimal("99999999.123456789012345");
        SavedAreaFilters full = new SavedAreaFilters(ListingCategory.HOUSE_VILLA,
                List.of(SellingType.MONTHLY_RENT, SellingType.LEASE),
                List.of(RoomType.ONE_ROOM, RoomType.TWO_ROOM, RoomType.THREE_ROOM, RoomType.OFFICETEL),
                List.of(FloorType.GROUND, FloorType.SEMI_BASEMENT, FloorType.ROOFTOP),
                Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, area, area,
                true, true, AvailabilityAnswer.IMPOSSIBLE, null);
        assertThat(poly30).hasSizeGreaterThan(1_000);

        savedAreaService.save(user.getUuid(),
                new SavedAreaCreateRequest("가".repeat(20), "가".repeat(40), poly30, full));

        assertThat(savedAreaService.findMine(user.getUuid())).singleElement().satisfies(r -> {
            assertThat(r.name()).isEqualTo("가".repeat(20));
            assertThat(r.placeLabel()).isEqualTo("가".repeat(40));
            assertThat(r.polygon()).isEqualTo(poly30);
            assertThat(r.filters()).isEqualTo(full);
        });
    }

    private User newUser() {
        return userRepository.save(User.builder().uuid(UUID.randomUUID().toString())
                .status(UserStatus.ACTIVE).role(UserRole.USER).preferredLanguage("ko").build());
    }

    private List<String> race(String userUuid) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                String name = "동시 저장 " + i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        savedAreaService.save(userUuid, new SavedAreaCreateRequest(name, null, SQUARE, null));
                        return "OK";
                    } catch (ListingException e) {
                        return e.getErrorCode().getCode();
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<String> outcomes = new ArrayList<>();
            for (Future<String> f : futures) {
                outcomes.add(f.get(30, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }
}
