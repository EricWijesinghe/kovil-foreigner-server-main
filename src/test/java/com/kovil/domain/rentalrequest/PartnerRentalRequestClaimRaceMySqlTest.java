package com.kovil.domain.rentalrequest;

import com.kovil.common.exception.CustomException;
import com.kovil.domain.file.entity.StoredFile;
import com.kovil.domain.file.enums.FilePurpose;
import com.kovil.domain.file.repository.StoredFileRepository;
import com.kovil.domain.listing.entity.BrokerProfile;
import com.kovil.domain.listing.entity.BrokerageOffice;
import com.kovil.domain.listing.repository.BrokerProfileRepository;
import com.kovil.domain.listing.repository.BrokerageOfficeRepository;
import com.kovil.domain.notification.enums.NotiType;
import com.kovil.domain.notification.repository.NotificationOutboxRepository;
import com.kovil.domain.partner.entity.BrokerageOfficeChangeRequest;
import com.kovil.domain.partner.entity.PartnerDivision;
import com.kovil.domain.partner.enums.OfficeChangeRequestStatus;
import com.kovil.domain.partner.enums.PartnerDivisionStatus;
import com.kovil.domain.partner.enums.PartnerDivisionType;
import com.kovil.domain.partner.repository.BrokerageOfficeChangeRequestRepository;
import com.kovil.domain.partner.repository.PartnerDivisionRepository;
import com.kovil.domain.partner.service.AdminOfficeChangeRequestService;
import com.kovil.domain.partner.service.AdminPartnerDivisionService;
import com.kovil.domain.rentalrequest.entity.RentalRequest;
import com.kovil.domain.rentalrequest.entity.RentalRequestClaimEvent;
import com.kovil.domain.rentalrequest.enums.RentalRequestClaimEventType;
import com.kovil.domain.rentalrequest.enums.RentalRequestOutcome;
import com.kovil.domain.rentalrequest.enums.RentalRequestReturnCause;
import com.kovil.domain.rentalrequest.enums.RentalRequestStatus;
import com.kovil.domain.rentalrequest.repository.RentalRequestClaimEventRepository;
import com.kovil.domain.rentalrequest.repository.RentalRequestLastRelease;
import com.kovil.domain.rentalrequest.repository.RentalRequestRepository;
import com.kovil.domain.rentalrequest.service.PartnerRentalRequestService;
import com.kovil.domain.rentalrequest.service.RentalRequestService;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.user.repository.UserRepository;
import com.kovil.domain.user.service.AdminUserService;
import com.kovil.domain.user.service.UserService;
import com.kovil.domain.verification.entity.ForeignerVerification;
import com.kovil.domain.verification.enums.VerificationStatus;
import com.kovil.domain.verification.repository.ForeignerVerificationRepository;
import com.kovil.infra.crypto.Encryptor;
import com.kovil.infra.jwt.BlacklistedTokenStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.assertj.core.groups.Tuple;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * ★★ 선착순을 **진짜 JPA + MySQL(InnoDB, 반복 읽기)** 에 고정한다. H2 · 목 리포지토리는 행 잠금도 갭 잠금도 교착 탐지도 흉내 내지 못한다.
 *   · 같은 요청을 여러 사무소가 동시에 잡으면 **하나만** 이기고 나머지는 전부 RENTAL_REQUEST_ALREADY_CLAIMED — 알림도 한 번.
 *   · 한 사무소가 여러 요청을 동시에 잡아도 진행 중은 상한을 넘지 않는다(나머지는 RENTAL_REQUEST_CLAIM_LIMIT).
 *   · 여러 사무소 × 여러 요청을 섞어 동시에 잡아도 교착(1213) · 일반 409 · 500 이 없다.
 *   · 잡기와 부문 정지 · 대표자 계정 정지가 겹쳐도 정지된 사무소가 진행 중 요청을 쥔 채 남지 않는다(대기 복귀가 그사이의 잡기를 본다).
 *   · 잡기와 신청자 탈퇴가 겹쳐도 교착 없이 한 줄로 선다(신청자 users 행을 요청 행보다 먼저 잡는다).
 *   · 놓기 · 종료와 신청자 탈퇴가 겹쳐도 교착 없이 한 줄로 선다(유학생 쪽 상태 변경이 사무소 행을 잡지 않는다 — V28 의 FK 전용 인덱스).
 *   · 부문 정지와 유학생 취소 · 탈퇴가 겹쳐도 이긴 쪽의 결과만 남는다(진 쪽은 409 — 분포는 관측용으로 찍는다).
 *   · 잡기 · 놓기(두 번 누른 잡기 포함)가 섞여도 교착이 없고, **잡기 이력을 순서대로 되짚으면 요청의 지금 상태가 나온다**
 *     (이력 INSERT 의 FK 검사가 새 잠금을 들이지 않는다 — 요청 · 사무소 행은 이미 쥐고 있고, 행위자에는 FK 가 없다).
 *   · 잡기와 그 파트너의 사무소 변경 승인이 겹쳐도 교착 없이 users 행에서 줄을 선다.
 *
 * 스프링을 그 MySQL 에 붙여 띄우고(Flyway 로 최신까지 올린다), 매 시나리오마다 새 행을 심는다(지우지 않는다).
 * RR_RACE_DB_URL 이 없으면 통째로 건너뛴다. 그 이름의 스키마만 쓴다 — 다른 DB 를 가리키면 시작하지 않는다.
 */
@EnabledIfEnvironmentVariable(named = "RR_RACE_DB_URL", matches = ".+")
@SpringBootTest(properties = {
        "spring.datasource.url=${RR_RACE_DB_URL}",
        "spring.datasource.username=${RR_RACE_DB_USER:root}",
        "spring.datasource.password=${RR_RACE_DB_PASSWORD:}",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.datasource.hikari.maximum-pool-size=40",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQLDialect",
        "spring.flyway.enabled=true",
        "rental-request.max-claims-per-office=3"})
class PartnerRentalRequestClaimRaceMySqlTest {

    private static final String REQUIRED_SCHEMA = "wjf_rr_race";
    private static final int LIMIT = 3;

    @Autowired PartnerRentalRequestService partnerService;
    @Autowired RentalRequestService rentalRequestService;
    @Autowired AdminPartnerDivisionService divisionService;
    @Autowired AdminUserService adminUserService;
    @Autowired UserService userService;
    @Autowired UserRepository userRepository;
    @Autowired ForeignerVerificationRepository verificationRepository;
    @Autowired BrokerageOfficeRepository officeRepository;
    @Autowired BrokerProfileRepository profileRepository;
    @Autowired PartnerDivisionRepository divisionRepository;
    @Autowired RentalRequestRepository requestRepository;
    @Autowired NotificationOutboxRepository outboxRepository;
    @Autowired RentalRequestClaimEventRepository eventRepository;
    @Autowired AdminOfficeChangeRequestService changeRequestService;
    @Autowired BrokerageOfficeChangeRequestRepository changeRequestRepository;
    @Autowired StoredFileRepository storedFileRepository;
    @Autowired Encryptor encryptor;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    // 세션 끊기의 액세스 토큰 취소선(Redis)은 이 테스트의 관심사가 아니다.
    @MockitoBean BlacklistedTokenStore blacklistedTokenStore;

    private record Partner(User user, BrokerageOffice office, PartnerDivision division) {
    }

    @BeforeAll
    static void guard() {
        assertThat(System.getenv("RR_RACE_DB_URL"))
                .describedAs("RR_RACE_DB_URL 은 %s 스키마만 가리킬 수 있다", REQUIRED_SCHEMA)
                .contains("/" + REQUIRED_SCHEMA);
    }

    // ───────────────────────── 시나리오

    @Test
    @DisplayName("같은 요청을 8개 사무소가 동시에 잡으면 하나만 이기고 나머지는 전부 ALREADY_CLAIMED — 알림도 한 번")
    void one_request_many_offices() throws Exception {
        for (int round = 0; round < 5; round++) {
            List<Partner> offices = partners(8);
            RentalRequest request = waiting();

            List<String> outcomes = race(offices.stream()
                    .map(p -> (Callable<String>) () -> code(() -> partnerService.claim(p.user().getUuid(), request.getUuid())))
                    .toList());

            assertThat(outcomes).as("round %d", round).containsOnlyOnce("OK");
            assertThat(outcomes.stream().filter(o -> !o.equals("OK")))
                    .as("진 쪽은 전부 선착순의 답이어야 한다").allMatch("RENTAL_REQUEST_ALREADY_CLAIMED"::equals);
            RentalRequest after = requestRepository.findById(request.getId()).orElseThrow();
            assertThat(after.getStatus()).isEqualTo(RentalRequestStatus.IN_PROGRESS);
            assertThat(offices.stream().map(p -> p.office().getId())).contains(after.getOfficeId());
            assertThat(notifications(request.getUserId(), NotiType.RENTAL_REQUEST_IN_PROGRESS)).isEqualTo(1);
            assertThat(eventRepository.findByRentalRequestIdOrderByIdAsc(request.getId()))
                    .as("이긴 사무소의 잡기 한 행뿐 — 진 쪽은 남기지 않는다").hasSize(1);
            assertHistoryReplaysState(request);
        }
    }

    @Test
    @DisplayName("한 사무소가 8건을 동시에 잡아도 진행 중은 상한(3)을 넘지 않는다 — 나머지는 CLAIM_LIMIT")
    void same_office_never_exceeds_limit() throws Exception {
        for (int round = 0; round < 3; round++) {
            Partner office = partners(1).get(0);
            List<RentalRequest> requests = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                requests.add(waiting());
            }

            List<String> outcomes = race(requests.stream()
                    .map(r -> (Callable<String>) () -> code(() -> partnerService.claim(office.user().getUuid(), r.getUuid())))
                    .toList());

            assertThat(outcomes.stream().filter("OK"::equals).count()).as("round %d", round).isEqualTo(LIMIT);
            assertThat(outcomes.stream().filter(o -> !o.equals("OK")))
                    .allMatch("RENTAL_REQUEST_CLAIM_LIMIT"::equals);
            assertThat(requestRepository.countByOfficeIdAndStatus(office.office().getId(), RentalRequestStatus.IN_PROGRESS))
                    .isEqualTo(LIMIT);
        }
    }

    @Test
    @DisplayName("6개 사무소 × 6건을 섞어 동시에 잡아도 요청마다 한 사무소 · 사무소마다 상한 이하이고 교착 · 일반 409 가 없다")
    void mixed_claims_have_no_deadlock() throws Exception {
        for (int round = 0; round < 3; round++) {
            List<Partner> offices = partners(6);
            List<RentalRequest> requests = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                requests.add(waiting());
            }
            List<Callable<String>> attempts = new ArrayList<>();
            for (Partner p : offices) {
                for (RentalRequest r : requests) {
                    attempts.add(() -> code(() -> partnerService.claim(p.user().getUuid(), r.getUuid())));
                }
            }
            Collections.shuffle(attempts);

            List<String> outcomes = race(attempts);

            assertThat(outcomes).as("round %d", round)
                    .allMatch(o -> o.equals("OK") || o.equals("RENTAL_REQUEST_ALREADY_CLAIMED")
                            || o.equals("RENTAL_REQUEST_CLAIM_LIMIT"));
            assertThat(outcomes.stream().filter("OK"::equals).count()).isEqualTo(requests.size());
            Map<Long, Long> perOffice = requests.stream()
                    .map(r -> requestRepository.findById(r.getId()).orElseThrow())
                    .peek(r -> assertThat(r.getStatus()).isEqualTo(RentalRequestStatus.IN_PROGRESS))
                    .collect(Collectors.groupingBy(RentalRequest::getOfficeId, Collectors.counting()));
            assertThat(perOffice.values()).allMatch(n -> n <= LIMIT);
            for (RentalRequest r : requests) {
                assertThat(eventRepository.findByRentalRequestIdOrderByIdAsc(r.getId())).hasSize(1);
                assertHistoryReplaysState(r);
            }
        }
    }

    /*
     * ★ 대기 복귀의 정합성 — 정지가 커밋된 뒤에 그 사무소가 진행 중 요청을 쥐고 있으면 안 된다. 정지 쪽의 스냅숏은 사무소 행을
     *   잠그기 전에 고정되므로, 대기 복귀가 일반 읽기였다면 그사이 커밋된 잡기를 놓친다(잠금 읽기여야 한다).
     */
    @Test
    @DisplayName("잡기와 부문 정지가 겹쳐도 정지된 사무소는 진행 중 요청을 쥔 채 남지 않는다")
    void division_suspension_vs_claims() throws Exception {
        User admin = user(UserRole.ADMIN, null);
        for (int round = 0; round < 10; round++) {
            Partner office = partners(1).get(0);
            List<RentalRequest> requests = List.of(waiting(), waiting(), waiting());
            List<Callable<String>> work = new ArrayList<>();
            for (RentalRequest r : requests) {
                work.add(() -> code(() -> partnerService.claim(office.user().getUuid(), r.getUuid())));
            }
            work.add(() -> code(() -> divisionService.suspend(admin.getUuid(), office.division().getUuid(), "센티널 사유")));

            List<String> outcomes = race(work);

            assertThat(outcomes.get(3)).as("정지 round %d", round).isEqualTo("OK");
            assertThat(outcomes.subList(0, 3)).allMatch(o -> o.equals("OK") || o.equals("RENTAL_REQUEST_OFFICE_NOT_ACTIVE"));
            assertThat(requestRepository.countByOfficeIdAndStatus(office.office().getId(), RentalRequestStatus.IN_PROGRESS))
                    .as("정지된 사무소의 진행 중 round %d", round).isZero();
            for (RentalRequest r : requests) {
                long claimed = notifications(r.getUserId(), NotiType.RENTAL_REQUEST_IN_PROGRESS);
                long released = notifications(r.getUserId(), NotiType.RENTAL_REQUEST_RELEASED);
                assertThat(released).as("잡혔던 요청은 다시 대기 알림을 받는다").isEqualTo(claimed);
                assertHistoryReplaysState(r);
                assertThat(eventRepository.findByRentalRequestIdOrderByIdAsc(r.getId()).stream()
                        .filter(e -> e.getEventType() == RentalRequestClaimEventType.RETURNED))
                        .allMatch(e -> e.getReturnCause() == RentalRequestReturnCause.DIVISION_SUSPENDED);
            }
        }
    }

    @Test
    @DisplayName("잡기와 대표자 계정 정지가 겹쳐도 정지된 대표자의 사무소는 진행 중 요청을 쥔 채 남지 않는다")
    void account_suspension_vs_claims() throws Exception {
        User admin = user(UserRole.ADMIN, null);
        for (int round = 0; round < 10; round++) {
            Partner office = partners(1).get(0);
            List<RentalRequest> requests = List.of(waiting(), waiting(), waiting());
            List<Callable<String>> work = new ArrayList<>();
            for (RentalRequest r : requests) {
                work.add(() -> code(() -> partnerService.claim(office.user().getUuid(), r.getUuid())));
            }
            work.add(() -> code(() -> adminUserService.suspend(admin.getUuid(), office.user().getUuid(), "센티널 사유")));

            List<String> outcomes = race(work);

            assertThat(outcomes.get(3)).as("정지 round %d", round).isEqualTo("OK");
            assertThat(outcomes.subList(0, 3)).allMatch(o -> o.equals("OK") || o.equals("USER_SUSPENDED"));
            assertThat(requestRepository.countByOfficeIdAndStatus(office.office().getId(), RentalRequestStatus.IN_PROGRESS))
                    .as("정지된 대표자 사무소의 진행 중 round %d", round).isZero();
            for (RentalRequest r : requests) {
                assertHistoryReplaysState(r);
            }
        }
    }

    /*
     * ★ 잡기 · 놓기 · 두 번 누른 잡기가 섞인다. 놓기 · 종료 · 잡기는 모두 사무소 행 → 요청 행을 쥔 뒤 이력을 남기므로, 이력 INSERT 의
     *   FK 검사(요청 · 사무소 행 S)는 새 잠금이 아니다. 행위자(users)에는 FK 가 없어 users 행도 건드리지 않는다.
     *   고정하는 것: 교착 · 일반 409 · 500 이 없다 / A 의 놓기는 언제나 성공 / 이력을 되짚으면 지금 상태가 나온다 /
     *   알림 수 = 이력 수(잡힘 알림 = CLAIMED 행, 다시 대기 알림 = RELEASED · RETURNED 행) — 멱등 잡기는 어느 쪽도 늘리지 않는다.
     */
    @Test
    @DisplayName("잡기 · 놓기 · 두 번 누른 잡기가 겹쳐도 교착이 없고, 잡기 이력을 되짚으면 요청의 지금 상태가 나온다")
    void claims_and_releases_interleave_and_history_replays_state() throws Exception {
        Map<String, Long> seen = new TreeMap<>();
        for (int round = 0; round < 20; round++) {
            List<Partner> pair = partners(2);
            Partner a = pair.get(0);
            Partner b = pair.get(1);
            RentalRequest held = waiting();
            RentalRequest open = waiting();
            partnerService.claim(a.user().getUuid(), held.getUuid());

            List<String> outcomes = race(List.of(
                    () -> code(() -> partnerService.release(a.user().getUuid(), held.getUuid())),
                    () -> code(() -> partnerService.claim(b.user().getUuid(), held.getUuid())),
                    () -> code(() -> partnerService.claim(a.user().getUuid(), open.getUuid())),
                    () -> code(() -> partnerService.claim(a.user().getUuid(), open.getUuid())),
                    () -> code(() -> partnerService.claim(b.user().getUuid(), open.getUuid())),
                    () -> code(() -> partnerService.release(b.user().getUuid(), open.getUuid()))));
            for (int i = 0; i < outcomes.size(); i++) {
                seen.merge(i + ":" + outcomes.get(i), 1L, Long::sum);
            }

            assertThat(outcomes).as("round %d — %s", round, outcomes)
                    .allMatch(o -> o.equals("OK") || o.equals("RENTAL_REQUEST_ALREADY_CLAIMED")
                            || o.equals("RENTAL_REQUEST_NOT_FOUND"));
            assertThat(outcomes.get(0)).as("A 의 놓기 round %d", round).isEqualTo("OK");
            assertThat(outcomes.get(1)).isIn("OK", "RENTAL_REQUEST_ALREADY_CLAIMED");
            for (RentalRequest r : List.of(held, open)) {
                assertHistoryReplaysState(r);
                List<RentalRequestClaimEvent> events = eventRepository.findByRentalRequestIdOrderByIdAsc(r.getId());
                assertThat(notifications(r.getUserId(), NotiType.RENTAL_REQUEST_IN_PROGRESS))
                        .as("잡힘 알림 = CLAIMED 행 round %d", round)
                        .isEqualTo(events.stream().filter(e -> e.getEventType() == RentalRequestClaimEventType.CLAIMED).count());
                assertThat(notifications(r.getUserId(), NotiType.RENTAL_REQUEST_RELEASED))
                        .as("다시 대기 알림 = RELEASED · RETURNED 행 round %d", round)
                        .isEqualTo(events.stream().filter(e -> e.getEventType() == RentalRequestClaimEventType.RELEASED
                                || e.getEventType() == RentalRequestClaimEventType.RETURNED).count());
            }
            // 유학생 응답의 '대기 복귀' 시각 — 진짜 MySQL 에서 묶음 조회(생성자 표현식 + MAX + GROUP BY)가 이력과 같은 값을 낸다.
            Map<Long, LocalDateTime> lastReleases = eventRepository.findLastReleases(
                            List.of(held.getId(), open.getId()),
                            List.of(RentalRequestClaimEventType.RELEASED, RentalRequestClaimEventType.RETURNED)).stream()
                    .collect(Collectors.toMap(RentalRequestLastRelease::requestId, RentalRequestLastRelease::releasedAt));
            for (RentalRequest r : List.of(held, open)) {
                Optional<LocalDateTime> expected = eventRepository.findByRentalRequestIdOrderByIdAsc(r.getId())
                        .stream()
                        .filter(e -> e.getEventType() == RentalRequestClaimEventType.RELEASED
                                || e.getEventType() == RentalRequestClaimEventType.RETURNED)
                        .map(RentalRequestClaimEvent::getOccurredAt)
                        .max(Comparator.naturalOrder());
                assertThat(Optional.ofNullable(lastReleases.get(r.getId())))
                        .as("마지막 놓기 · 대기 복귀 시각 round %d", round).isEqualTo(expected);
            }
            assertThat(eventRepository.findByRentalRequestIdOrderByIdAsc(open.getId()).stream()
                    .filter(e -> e.getEventType() == RentalRequestClaimEventType.CLAIMED
                            && e.getOfficeId().equals(a.office().getId())))
                    .as("A 는 open 을 놓지 않으므로 두 번 눌러도 CLAIMED 는 많아야 한 행 round %d", round).hasSizeLessThanOrEqualTo(1);
        }
        System.out.println("[race] 잡기 · 놓기 섞기 — 자리별 결과 분포 " + seen);
    }

    /*
     * ★ 잡기와 그 파트너의 사무소 변경 승인 — 둘 다 그 파트너의 users 행을 맨 앞에서 배타로 잡고(잡기: 관문의 잠금 읽기,
     *   승인: 신청자 잠금) 사무소 → 대표자 연결 → 부문 순으로 간다. 줄을 서기만 하고 교착하지 않아야 한다.
     *   승인은 사무소 행을 고치고(@Version), 잡기는 사무소 행을 잠그기만 한다 — 어느 쪽도 상대 때문에 409 가 나지 않는다.
     */
    @Test
    @DisplayName("잡기와 그 파트너의 사무소 변경 승인이 겹쳐도 교착 없이 둘 다 성공한다")
    void claims_vs_change_approval_of_the_same_partner() throws Exception {
        User admin = user(UserRole.ADMIN, null);
        for (int round = 0; round < 10; round++) {
            Partner office = partners(1).get(0);
            BrokerageOfficeChangeRequest change = pendingChange(office);
            List<RentalRequest> requests = List.of(waiting(), waiting(), waiting());
            List<Callable<String>> work = new ArrayList<>();
            work.add(() -> code(() -> changeRequestService.approve(admin.getUuid(), change.getUuid(), change.getVersion())));
            for (RentalRequest r : requests) {
                work.add(() -> code(() -> partnerService.claim(office.user().getUuid(), r.getUuid())));
            }

            List<String> outcomes = race(work);

            assertThat(outcomes).as("round %d — %s", round, outcomes).allMatch("OK"::equals);
            assertThat(changeRequestRepository.findById(change.getId()).orElseThrow().getStatus())
                    .isEqualTo(OfficeChangeRequestStatus.APPROVED);
            assertThat(officeRepository.findById(office.office().getId()).orElseThrow().getName())
                    .isEqualTo("새상호공인중개사사무소");
            assertThat(requestRepository.countByOfficeIdAndStatus(office.office().getId(), RentalRequestStatus.IN_PROGRESS))
                    .isEqualTo(requests.size());
            for (RentalRequest r : requests) {
                assertThat(eventRepository.findByRentalRequestIdOrderByIdAsc(r.getId()))
                        .extracting(RentalRequestClaimEvent::getEventType, RentalRequestClaimEvent::getOfficeId,
                                RentalRequestClaimEvent::getActorId)
                        .containsExactly(Tuple.tuple(RentalRequestClaimEventType.CLAIMED,
                                office.office().getId(), office.user().getId()));
            }
        }
    }

    /*
     * ★ 잡기는 신청자 users 행(공유)을 요청 행보다 먼저 잡는다. 거꾸로면 신청자의 탈퇴(users 배타 → 요청 행)와 서로의 행을 쥔 채
     *   기다린다 — 잡기가 요청 행을 쥔 채 알림 INSERT 의 FK 검사로 신청자 행을 기다리기 때문이다.
     */
    @Test
    @DisplayName("잡기와 신청자 탈퇴가 겹쳐도 교착 없이 한 줄로 선다 — 탈퇴가 먼저면 404, 잡기가 먼저면 탈퇴가 취소로 접는다")
    void claim_vs_applicant_withdrawal() throws Exception {
        List<String> claimOutcomes = new ArrayList<>();
        for (int round = 0; round < 20; round++) {
            Partner office = partners(1).get(0);
            RentalRequest request = waiting();
            User applicant = userRepository.findById(request.getUserId()).orElseThrow();
            // 출발을 몇 ms 씩 어긋나게 해 두 순서(잡기 먼저 · 탈퇴 먼저)와 그 사이의 겹침을 모두 지나가게 한다.
            long claimDelay = round % 2 == 0 ? (round % 5) * 3L : 0L;
            long withdrawDelay = round % 2 == 1 ? (round % 5) * 3L : 0L;

            List<String> outcomes = race(List.of(
                    () -> {
                        Thread.sleep(claimDelay);
                        return code(() -> partnerService.claim(office.user().getUuid(), request.getUuid()));
                    },
                    () -> {
                        Thread.sleep(withdrawDelay);
                        return code(() -> userService.withdraw(applicant.getUuid()));
                    }));
            claimOutcomes.add(outcomes.get(0));

            assertThat(outcomes.get(1)).as("탈퇴 round %d", round).isEqualTo("OK");
            assertThat(outcomes.get(0)).isIn("OK", "RENTAL_REQUEST_NOT_FOUND");
            RentalRequest after = requestRepository.findById(request.getId()).orElseThrow();
            assertThat(after.getStatus()).isEqualTo(RentalRequestStatus.CANCELED);
            assertThat(after.getOfficeId())
                    .isEqualTo(outcomes.get(0).equals("OK") ? office.office().getId() : null);
        }
        System.out.println("[race] 잡기 vs 신청자 탈퇴 — 잡기 결과 분포 " + claimOutcomes.stream()
                .collect(Collectors.groupingBy(o -> o, Collectors.counting())));
    }

    /*
     * ★ 놓기 · 종료는 사무소 행(배타)을 쥔 뒤 신청자 users 행(공유)을 잡는다. 신청자의 탈퇴는 그 users 행(배타)을 쥔 채 요청을 취소한다 —
     *   그 UPDATE 가 사무소 행을 잡지 않아야 둘이 엇갈리지 않는다. 맡은 사무소 FK 가 (office_id, status, id) 복합 인덱스에 얹혀 있던
     *   동안은 상태만 바꿔도 FK 검사가 사무소 행을 공유로 잡아, 40라운드 중 12번 놓기 · 종료 쪽이 1213 으로 졌다.
     */
    @Test
    @DisplayName("놓기 · 종료와 신청자 탈퇴가 겹쳐도 교착 없이 한 줄로 선다 — 탈퇴는 언제나 성공하고 요청은 정해진 셋 중 하나로 끝난다")
    void release_or_close_vs_applicant_withdrawal() throws Exception {
        Map<String, Long> seen = new TreeMap<>();
        for (int round = 0; round < 40; round++) {
            Partner office = partners(1).get(0);
            RentalRequest request = waiting();
            User applicant = userRepository.findById(request.getUserId()).orElseThrow();
            partnerService.claim(office.user().getUuid(), request.getUuid());
            boolean release = round % 2 == 0;
            long partnerDelay = (round / 2) % 2 == 0 ? (round % 5) * 2L : 0L;
            long withdrawDelay = (round / 2) % 2 == 1 ? (round % 5) * 2L : 0L;

            List<String> outcomes = race(List.of(
                    () -> {
                        Thread.sleep(partnerDelay);
                        return code(() -> {
                            if (release) {
                                partnerService.release(office.user().getUuid(), request.getUuid());
                            } else {
                                partnerService.close(office.user().getUuid(), request.getUuid(), RentalRequestOutcome.FOUND);
                            }
                        });
                    },
                    () -> {
                        Thread.sleep(withdrawDelay);
                        return code(() -> userService.withdraw(applicant.getUuid()));
                    }));
            seen.merge((release ? "놓기 " : "종료 ") + outcomes.get(0), 1L, Long::sum);

            assertThat(outcomes.get(1)).as("탈퇴 round %d", round).isEqualTo("OK");
            assertThat(outcomes.get(0)).as("%s round %d", release ? "놓기" : "종료", round)
                    .isIn("OK", "RENTAL_REQUEST_NOT_IN_PROGRESS");
            RentalRequest after = requestRepository.findById(request.getId()).orElseThrow();
            if (!release && outcomes.get(0).equals("OK")) {
                // 종료가 먼저 — 탈퇴는 닫힌 요청을 건드리지 않는다.
                assertThat(after.getStatus()).isEqualTo(RentalRequestStatus.CLOSED);
                assertThat(after.getOfficeId()).isEqualTo(office.office().getId());
            } else {
                // 놓기가 먼저면 대기로 돌아간 요청을, 탈퇴가 먼저면 맡은 사무소가 남은 요청을 탈퇴가 취소한다.
                assertThat(after.getStatus()).isEqualTo(RentalRequestStatus.CANCELED);
                assertThat(after.getOfficeId())
                        .isEqualTo(outcomes.get(0).equals("OK") ? null : office.office().getId());
            }
        }
        System.out.println("[race] 놓기 · 종료 vs 신청자 탈퇴 — 결과 분포 " + seen);
    }

    /*
     * ★ 대기 복귀(여기서는 부문 정지)와 그 요청들의 유학생 취소 · 탈퇴가 겹치는 경우. 대기 복귀는 사무소 행을 쥔 채 진행 중 요청을
     *   잠금 읽기로 훑고 요청마다 알림을 넣는다 — 유학생 쪽과 맞물리는 길이 아직 남아 있어(RentalRequestPoolReturner 주석) 한쪽이
     *   409 로 질 수 있다. 여기서 고정하는 것은 **정합성**이다: 이긴 쪽의 결과만 남고 진 쪽은 아무것도 바꾸지 않았다 —
     *   정지가 성공했으면 그 사무소는 진행 중 요청을 쥐지 않고, 실패했으면 부문 · 요청이 그대로다. 진 쪽 분포는 관측용으로 찍는다.
     */
    @Test
    @DisplayName("부문 정지와 유학생 취소 · 탈퇴가 겹쳐도 이긴 쪽의 결과만 남는다 — 정지된 사무소는 진행 중 요청을 쥐지 않는다")
    void division_suspension_vs_student_cancel_and_withdrawal() throws Exception {
        User admin = user(UserRole.ADMIN, null);
        Map<String, Long> seen = new TreeMap<>();
        for (int round = 0; round < 15; round++) {
            Partner office = partners(1).get(0);
            RentalRequest canceled = waiting();
            RentalRequest withdrawn = waiting();
            RentalRequest untouched = waiting();
            for (RentalRequest r : List.of(canceled, withdrawn, untouched)) {
                partnerService.claim(office.user().getUuid(), r.getUuid());
            }
            User canceler = userRepository.findById(canceled.getUserId()).orElseThrow();
            User leaver = userRepository.findById(withdrawn.getUserId()).orElseThrow();

            List<String> outcomes = race(List.of(
                    () -> code(() -> divisionService.suspend(admin.getUuid(), office.division().getUuid(), "센티널 사유")),
                    () -> code(() -> rentalRequestService.cancel(canceler.getUuid(), canceled.getUuid(), "센티널")),
                    () -> code(() -> userService.withdraw(leaver.getUuid()))));
            seen.merge("정지 " + kind(outcomes.get(0)), 1L, Long::sum);
            seen.merge("취소 " + kind(outcomes.get(1)), 1L, Long::sum);
            seen.merge("탈퇴 " + kind(outcomes.get(2)), 1L, Long::sum);

            assertThat(outcomes).as("round %d — 진 쪽은 409(교착 · 낙관 잠금)뿐이다", round)
                    .allMatch(o -> o.equals("OK") || kind(o).equals("교착") || kind(o).equals("낙관 잠금"));
            boolean suspended = outcomes.get(0).equals("OK");
            assertThat(divisionRepository.findById(office.division().getId()).orElseThrow().getStatus())
                    .isEqualTo(suspended ? PartnerDivisionStatus.SUSPENDED : PartnerDivisionStatus.APPROVED);
            RentalRequestStatus held = suspended ? RentalRequestStatus.SUBMITTED : RentalRequestStatus.IN_PROGRESS;
            assertThat(statusOf(canceled)).isEqualTo(outcomes.get(1).equals("OK") ? RentalRequestStatus.CANCELED : held);
            assertThat(statusOf(withdrawn)).isEqualTo(outcomes.get(2).equals("OK") ? RentalRequestStatus.CANCELED : held);
            assertThat(statusOf(untouched)).isEqualTo(held);
            if (suspended) {
                assertThat(requestRepository.countByOfficeIdAndStatus(office.office().getId(), RentalRequestStatus.IN_PROGRESS))
                        .as("정지된 사무소의 진행 중 round %d", round).isZero();
            }
        }
        System.out.println("[race] 부문 정지 vs 유학생 취소 · 탈퇴 — 결과 분포 " + seen);
    }

    // ───────────────────────── 도구

    /*
     * 잡기 이력을 순서대로 되짚어 요청의 지금 상태(상태 · 맡은 사무소)와 맞는지 본다. 사건마다 앞 상태가 맞아야 한다 —
     * 잡기는 대기에서, 놓기 · 대기 복귀 · 종료는 그 사무소가 쥔 진행 중에서. 행위자는 대기 복귀만 비어 있다.
     */
    private void assertHistoryReplaysState(RentalRequest request) {
        RentalRequestStatus state = RentalRequestStatus.SUBMITTED;
        Long holder = null;
        for (RentalRequestClaimEvent event : eventRepository.findByRentalRequestIdOrderByIdAsc(request.getId())) {
            switch (event.getEventType()) {
                case CLAIMED -> {
                    assertThat(state).as("잡기는 대기에서 — 요청 %d", request.getId()).isEqualTo(RentalRequestStatus.SUBMITTED);
                    state = RentalRequestStatus.IN_PROGRESS;
                    holder = event.getOfficeId();
                }
                case RELEASED, RETURNED -> {
                    assertThat(state).isEqualTo(RentalRequestStatus.IN_PROGRESS);
                    assertThat(event.getOfficeId()).isEqualTo(holder);
                    state = RentalRequestStatus.SUBMITTED;
                    holder = null;
                }
                case CLOSED -> {
                    assertThat(state).isEqualTo(RentalRequestStatus.IN_PROGRESS);
                    assertThat(event.getOfficeId()).isEqualTo(holder);
                    state = RentalRequestStatus.CLOSED;
                }
            }
            assertThat(event.getActorId() == null).as("행위자는 대기 복귀만 비어 있다")
                    .isEqualTo(event.getEventType() == RentalRequestClaimEventType.RETURNED);
        }
        RentalRequest now = requestRepository.findById(request.getId()).orElseThrow();
        assertThat(now.getStatus()).as("이력을 되짚은 상태 — 요청 %d", request.getId()).isEqualTo(state);
        assertThat(now.getOfficeId()).as("이력을 되짚은 맡은 사무소 — 요청 %d", request.getId()).isEqualTo(holder);
    }

    // 그 사무소의 대기 중인 변경 신청(상호 · 번호를 바꾼다) — 등록증 파일 행이 있어야 한다(FK).
    private BrokerageOfficeChangeRequest pendingChange(Partner partner) {
        return tx.execute(status -> {
            String uuid = UUID.randomUUID().toString();
            StoredFile license = storedFileRepository.save(StoredFile.builder().uuid(uuid)
                    .storageKey("broker-licenses/" + uuid + ".pdf").contentType("application/pdf").byteSize(1024L)
                    .purpose(FilePurpose.BROKER_LICENSE).uploaderId(partner.user().getId()).build());
            return changeRequestRepository.save(BrokerageOfficeChangeRequest.builder()
                    .uuid(UUID.randomUUID().toString()).officeId(partner.office().getId())
                    .requestedBy(partner.user().getId())
                    .regNo("99999-2026-" + UUID.randomUUID().toString().substring(0, 8))
                    .name("새상호공인중개사사무소").ceoName("홍길동").address("서울특별시 광진구 능동로 000")
                    .licenseFileId(license.getId()).submittedAt(LocalDateTime.now()).build());
        });
    }

    // 모두 같은 순간에 출발시키고 결과(성공이면 OK, 아니면 오류 코드 · 예외 이름)를 순서대로 모은다.
    private List<String> race(List<Callable<String>> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(work.size());
        try {
            CountDownLatch ready = new CountDownLatch(work.size());
            CountDownLatch go = new CountDownLatch(1);
            List<Future<String>> futures = new ArrayList<>();
            for (Callable<String> task : work) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            ready.await(10, TimeUnit.SECONDS);
            go.countDown();
            List<String> outcomes = new ArrayList<>();
            for (Future<String> future : futures) {
                outcomes.add(future.get(120, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private static String code(Runnable action) {
        try {
            action.run();
            return "OK";
        } catch (CustomException e) {
            return e.getErrorCode().getCode();
        } catch (RuntimeException e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    // 결과를 갈래로 묶는다 — 교착 · 잠금 대기 초과(1213 · 1205)와 @Version 충돌은 둘 다 409 로 나가 재시도하면 된다.
    private static String kind(String outcome) {
        if (outcome.startsWith("CannotAcquireLockException") || outcome.startsWith("PessimisticLockingFailureException")) {
            return "교착";
        }
        if (outcome.startsWith("ObjectOptimisticLockingFailureException")) {
            return "낙관 잠금";
        }
        return outcome;
    }

    private RentalRequestStatus statusOf(RentalRequest request) {
        return requestRepository.findById(request.getId()).orElseThrow().getStatus();
    }

    private long notifications(Long userId, NotiType type) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox WHERE user_id = ? AND noti_type = ?",
                Long.class, userId, type.name());
    }

    private User user(UserRole role, String phone) {
        String uuid = UUID.randomUUID().toString();
        return tx.execute(status -> userRepository.save(User.builder().uuid(uuid).name("홍길동")
                .nickname(uuid.substring(0, 20)).nicknameNormalized(uuid.substring(0, 20))
                .phone(phone == null ? null : encryptor.encrypt(phone)).phoneHash(UUID.randomUUID().toString())
                .email(uuid + "@example.test").preferredLanguage("ko")
                .status(UserStatus.ACTIVE).role(role).build()));
    }

    private List<Partner> partners(int count) {
        List<Partner> partners = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            User user = user(UserRole.PARTNER, "01000000002");
            partners.add(tx.execute(status -> {
                String regNo = "99999-2026-" + UUID.randomUUID().toString().substring(0, 8);
                BrokerageOffice office = officeRepository.save(BrokerageOffice.builder()
                        .uuid(UUID.randomUUID().toString()).regNo(regNo).claimedRegNo(regNo)
                        .name("테스트공인중개사사무소").ceoName("홍길동")
                        .address("서울특별시 광진구 능동로 000").tel("02-000-0000").build());
                profileRepository.save(BrokerProfile.builder().userId(user.getId()).officeId(office.getId()).build());
                PartnerDivision division = divisionRepository.save(PartnerDivision.builder()
                        .uuid(UUID.randomUUID().toString()).userId(user.getId())
                        .division(PartnerDivisionType.REAL_ESTATE_BROKER).officeId(office.getId())
                        .status(PartnerDivisionStatus.APPROVED).build());
                return new Partner(user, office, division);
            }));
        }
        return partners;
    }

    // 신청자마다 다른 유학생 — 실제처럼 한 사람에 열린 요청 하나.
    private RentalRequest waiting() {
        User student = user(UserRole.USER, "01000000001");
        return tx.execute(status -> {
            Long residentStatusId = jdbc.queryForObject("SELECT MIN(id) FROM resident_statuses", Long.class);
            Long countryId = jdbc.queryForObject("SELECT MIN(id) FROM countries", Long.class);
            Long verificationId = verificationRepository.save(ForeignerVerification.builder()
                    .uuid(UUID.randomUUID().toString()).userId(student.getId())
                    .residentStatusId(residentStatusId).residentStatusCode("STUDENT").requiredDocumentCodes("[]")
                    .nationalityCountryId(countryId).fullName("HONG GILDONG").birthDate(LocalDate.of(1998, 3, 15))
                    .status(VerificationStatus.APPROVED).build()).getId();
            return requestRepository.save(RentalRequest.builder()
                    .requestNumber("RQ-261007-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase())
                    .userId(student.getId()).verificationId(verificationId).status(RentalRequestStatus.SUBMITTED)
                    .moveInDate(LocalDate.of(2026, 11, 1)).build());
        });
    }
}
