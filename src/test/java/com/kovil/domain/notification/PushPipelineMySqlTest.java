package com.kovil.domain.notification;

import com.kovil.domain.device.entity.DeviceToken;
import com.kovil.domain.device.enums.DevicePlatform;
import com.kovil.domain.device.exception.DeviceException;
import com.kovil.domain.device.repository.DeviceTokenRepository;
import com.kovil.domain.device.service.DeviceTokenService;
import com.kovil.domain.notification.enums.NotiType;
import com.kovil.domain.notification.service.NotificationDispatcher;
import com.kovil.domain.notification.service.NotificationPublisher;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.user.repository.UserRepository;
import com.kovil.infra.crypto.Encryptor;
import com.kovil.infra.jwt.BlacklistedTokenStore;
import com.kovil.infra.push.PushMessage;
import com.kovil.infra.push.PushSendResult;
import com.kovil.infra.push.PushSender;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

/*
 * 푸시 서버 변경을 **진짜 MySQL** 에서 본다 — 단위 테스트는 목 저장소라 제약 이름 형식 · V37 칸 · 같은 토큰 동시 등록을 보지 못하고,
 * H2 create-drop 은 엔티티에 유니크 선언이 없어 uq_dt_token 위반이 아예 나지 않는다.
 *
 * 스프링을 그 MySQL 에 붙여 띄우고(Flyway 로 최신까지), 실행마다 고유한 값으로 행을 심는다(지우지 않는다).
 * PUSH_DB_URL 이 없으면 통째로 건너뛴다. 그 이름의 스키마만 쓴다.
 */
@EnabledIfEnvironmentVariable(named = "PUSH_DB_URL", matches = ".+")
@SpringBootTest(properties = {
        "spring.datasource.url=${PUSH_DB_URL}",
        "spring.datasource.username=${PUSH_DB_USER:root}",
        "spring.datasource.password=${PUSH_DB_PASSWORD:}",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQLDialect",
        "spring.flyway.enabled=true"})
class PushPipelineMySqlTest {

    private static final String REQUIRED_SCHEMA = "wjf_push";

    @Autowired DeviceTokenService deviceTokenService;
    @Autowired DeviceTokenRepository deviceTokenRepository;
    @Autowired NotificationPublisher publisher;
    @Autowired NotificationDispatcher dispatcher;
    @Autowired UserRepository userRepository;
    @Autowired Encryptor encryptor;
    @Autowired JdbcTemplate jdbc;

    // 실제 발송 대신 보낸 메시지를 붙잡는다 — data 키를 보려는 것이다.
    @MockitoBean PushSender pushSender;
    // 액세스 토큰 취소선(Redis)은 이 테스트의 관심사가 아니다.
    @MockitoBean BlacklistedTokenStore blacklistedTokenStore;

    @BeforeAll
    static void guard() {
        assertThat(System.getenv("PUSH_DB_URL"))
                .describedAs("PUSH_DB_URL 은 %s 스키마만 가리킬 수 있다", REQUIRED_SCHEMA)
                .contains("/" + REQUIRED_SCHEMA);
    }

    private User user() {
        String uuid = UUID.randomUUID().toString();
        return userRepository.save(User.builder().uuid(uuid).name("NGUYEN")
                .nickname(uuid.substring(0, 8)).nicknameNormalized(uuid.substring(0, 8))
                .phone(encryptor.encrypt("01000000021")).phoneHash(UUID.randomUUID().toString())
                .email(uuid.substring(0, 8) + "@example.test").preferredLanguage("ko")
                .status(UserStatus.ACTIVE).role(UserRole.USER).build());
    }

    @Test
    @DisplayName("실 MySQL: 토큰 유니크 위반의 제약 이름이 번역 조건(uq_dt_token)에 걸린다")
    void mysql_reports_the_token_unique_constraint_by_name() {
        User owner = user();
        String token = "fcm-" + UUID.randomUUID();
        deviceTokenService.register(owner.getUuid(), token, "IOS");

        assertThatThrownBy(() -> deviceTokenRepository.saveAndFlush(
                DeviceToken.register(owner.getId(), token, DevicePlatform.IOS, LocalDateTime.now())))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> {
                    assertThat(e.getCause()).isInstanceOf(ConstraintViolationException.class);
                    assertThat(((ConstraintViolationException) e.getCause()).getConstraintName())
                            .containsIgnoringCase("uq_dt_token");
                });
    }

    // 같은 토큰을 동시에 두 번 — 늦은 쪽은 이어받기(200) 또는 CONFLICT 여야 하고, 무결성 예외(500)가 새면 안 된다.
    // 경합이 한 번도 안 나도(20회 모두 순차) 통과한다 — 경합 경로를 탔는지는 출력의 CONFLICT 횟수로 본다.
    @Test
    @DisplayName("실 MySQL: 같은 토큰 동시 등록은 행 하나로 끝나고 500 이 새지 않는다(CONFLICT 아니면 이어받기)")
    void concurrent_same_token_never_leaks_integrity_errors() throws Exception {
        User owner = user();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        int conflicts = 0;
        List<Throwable> unexpected = new ArrayList<>();
        try {
            for (int round = 0; round < 20; round++) {
                String token = "fcm-race-" + UUID.randomUUID();
                CountDownLatch start = new CountDownLatch(1);
                List<Future<?>> calls = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    calls.add(pool.submit(() -> {
                        start.await();
                        return deviceTokenService.register(owner.getUuid(), token, "IOS");
                    }));
                }
                start.countDown();
                for (Future<?> call : calls) {
                    try {
                        call.get(30, TimeUnit.SECONDS);
                    } catch (ExecutionException e) {
                        if (e.getCause() instanceof DeviceException device
                                && "CONFLICT".equals(device.getErrorCode().getCode())) {
                            conflicts++;
                        } else {
                            unexpected.add(e.getCause());
                        }
                    }
                }
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_tokens WHERE token = ?",
                        Integer.class, token)).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(unexpected).isEmpty();
        System.out.println("[push-mysql] 같은 토큰 동시 등록 20회 중 CONFLICT " + conflicts + "회(나머지는 이어받기)");
    }

    @Test
    @DisplayName("실 MySQL: 발행이 알림함 uuid 를 outbox 에 남기고, 발송 data 에 deepLink · notiType · notificationUuid 가 실린다")
    void publish_then_dispatch_carries_inbox_uuid() {
        User receiver = user();
        deviceTokenService.register(receiver.getUuid(), "fcm-" + UUID.randomUUID(), "ANDROID");
        String dedupeKey = "PUSH_MYSQL:" + UUID.randomUUID();
        given(pushSender.send(any())).willReturn(PushSendResult.success("m1"));

        publisher.publish(receiver.getId(), NotiType.VERIFICATION_APPROVED, dedupeKey, Map.of(), "/me/verification");

        Map<String, Object> outbox = jdbc.queryForMap(
                "SELECT id, user_notification_uuid FROM notification_outbox WHERE dedupe_key = ?", dedupeKey);
        String inboxUuid = (String) outbox.get("user_notification_uuid");
        // V37 칸은 NULL 허용이어야 한다 — NOT NULL 이면 배포 전에 쌓인 행이 '' 로 채워져 notificationUuid "" 가 나간다.
        assertThat(jdbc.queryForObject("SELECT IS_NULLABLE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()"
                + " AND TABLE_NAME = 'notification_outbox' AND COLUMN_NAME = 'user_notification_uuid'", String.class))
                .isEqualTo("YES");
        assertThat(inboxUuid).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_notifications WHERE uuid = ? AND user_id = ?",
                Integer.class, inboxUuid, receiver.getId())).isEqualTo(1);

        dispatcher.dispatch(((Number) outbox.get("id")).longValue());

        ArgumentCaptor<PushMessage> sent = ArgumentCaptor.forClass(PushMessage.class);
        then(pushSender).should().send(sent.capture());
        assertThat(sent.getValue().data()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "deepLink", "/me/verification",
                "notiType", "VERIFICATION_APPROVED",
                "notificationUuid", inboxUuid));
        assertThat(jdbc.queryForObject("SELECT status FROM notification_outbox WHERE dedupe_key = ?",
                String.class, dedupeKey)).isEqualTo("SENT");
    }
}
