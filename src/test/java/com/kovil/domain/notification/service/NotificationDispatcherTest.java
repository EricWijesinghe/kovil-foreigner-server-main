package com.kovil.domain.notification.service;

import com.kovil.domain.device.entity.DeviceToken;
import com.kovil.domain.device.enums.DevicePlatform;
import com.kovil.domain.device.repository.DeviceTokenRepository;
import com.kovil.domain.notification.entity.NotificationOutbox;
import com.kovil.domain.notification.entity.PushNotification;
import com.kovil.domain.notification.entity.UserNotification;
import com.kovil.domain.notification.entity.UserNotificationSetting;
import com.kovil.domain.notification.enums.NotiCategory;
import com.kovil.domain.notification.enums.NotiType;
import com.kovil.domain.notification.enums.OutboxStatus;
import com.kovil.domain.notification.repository.NotificationOutboxRepository;
import com.kovil.domain.notification.repository.PushNotificationRepository;
import com.kovil.domain.notification.repository.UserNotificationRepository;
import com.kovil.domain.notification.repository.UserNotificationSettingRepository;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.user.repository.UserRepository;
import com.kovil.infra.push.PushMessage;
import com.kovil.infra.push.PushSendResult;
import com.kovil.infra.push.PushSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.ResourceBundleMessageSource;
import tools.jackson.databind.json.JsonMapper;

import com.kovil.domain.notification.NotificationProperties;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class NotificationDispatcherTest {

    private static final Instant NOW = Instant.parse("2026-10-25T00:00:00Z");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Mock NotificationOutboxRepository outboxRepository;
    @Mock UserNotificationRepository userNotificationRepository;
    @Mock PushNotificationRepository pushNotificationRepository;
    @Mock UserNotificationSettingRepository settingRepository;
    @Mock DeviceTokenRepository deviceTokenRepository;
    @Mock UserRepository userRepository;
    @Mock PushSender pushSender;

    private NotificationDispatcher worker;

    @BeforeEach
    void setUp() {
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasenames("messages/notifications");
        messageSource.setDefaultEncoding("UTF-8");
        messageSource.setFallbackToSystemLocale(false);

        worker = new NotificationDispatcher(outboxRepository,
                pushNotificationRepository, settingRepository, deviceTokenRepository, userRepository,
                new NotificationRenderer(messageSource, JsonMapper.builder().build()),
                pushSender, new NotificationProperties(3, Duration.ofMinutes(5)),
                Clock.fixed(NOW, SEOUL));
    }

    private static <T> T withId(T entity, Long id) {
        try {
            var field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return entity;
    }

    private static NotificationOutbox outbox(NotiType type, String params) {
        return withId(NotificationOutbox.builder()
                .uuid("o").userId(10L).notiType(type).dedupeKey("k")
                .templateCode(type.templateCode()).params(params)
                .deepLink("/rental-requests/abc")
                .scheduledAt(LocalDateTime.now(Clock.fixed(NOW, SEOUL)))
                .status(OutboxStatus.PENDING).build(), 7L);
    }

    private static User user(String language) {
        return withId(User.builder().uuid("u").name("n").preferredLanguage(language)
                .status(UserStatus.ACTIVE).role(UserRole.USER).build(), 10L);
    }

    private static DeviceToken device(String token) {
        return withId(DeviceToken.builder().uuid("d-" + token).userId(10L)
                .token(token).platform(DevicePlatform.IOS).build(), 1L);
    }

    private void givenUserWithDevices(String language, String... tokens) {
        given(userRepository.findById(10L)).willReturn(Optional.of(user(language)));
        given(deviceTokenRepository.findAllByUserIdOrderByLastUsedAtDesc(10L))
                .willReturn(Arrays.stream(tokens).map(NotificationDispatcherTest::device).toList());
    }

    @Nested
    @DisplayName("발송 (FR-603 · FR-604)")
    class Dispatch {

        @Test
        @DisplayName("유저 언어로 렌더해 보내고 그 문자열을 스냅샷으로 남긴다")
        void renders_in_user_language_and_snapshots() {
            NotificationOutbox target = outbox(NotiType.RENTAL_REQUEST_IN_PROGRESS,
                    "{\"0\":\"RQ-260811-ABC\",\"1\":\"행복공인중개사사무소\"}");
            given(outboxRepository.findById(7L)).willReturn(Optional.of(target));
            givenUserWithDevices("ko", "tok-1");
            given(pushSender.send(any())).willReturn(PushSendResult.success("m1"));

            worker.dispatch(7L);

            ArgumentCaptor<PushMessage> sent = ArgumentCaptor.forClass(PushMessage.class);
            then(pushSender).should().send(sent.capture());
            assertThat(sent.getValue().title()).isEqualTo("행복공인중개사사무소에서 요청을 맡았어요");
            assertThat(sent.getValue().body()).contains("RQ-260811-ABC");
            assertThat(sent.getValue().data()).containsEntry("deepLink", "/rental-requests/abc");

            ArgumentCaptor<PushNotification> snapshot = ArgumentCaptor.forClass(PushNotification.class);
            then(pushNotificationRepository).should().save(snapshot.capture());
            assertThat(snapshot.getValue().getLanguageCode()).isEqualTo("ko");
            assertThat(snapshot.getValue().getDeviceCount()).isEqualTo(1);
            assertThat(snapshot.getValue().getSuccessCount()).isEqualTo(1);
            assertThat(target.getStatus()).isEqualTo(OutboxStatus.SENT);
        }

        @Test
        @DisplayName("영어 사용자에게는 영어로 나간다")
        void renders_in_english() {
            given(outboxRepository.findById(7L))
                    .willReturn(Optional.of(outbox(NotiType.VERIFICATION_APPROVED, null)));
            givenUserWithDevices("en", "tok-1");
            given(pushSender.send(any())).willReturn(PushSendResult.success("m1"));

            worker.dispatch(7L);

            ArgumentCaptor<PushMessage> sent = ArgumentCaptor.forClass(PushMessage.class);
            then(pushSender).should().send(sent.capture());
            assertThat(sent.getValue().title()).isEqualTo("Verification approved");
        }

        // 기기가 없는 것은 실패가 아니다 — 알림함에는 적재 시점에 이미 남았다.
        @Test
        @DisplayName("기기가 없으면 SKIPPED다 — 실패가 아니라 재시도 대상이 아니다")
        void no_devices_is_skipped() {
            NotificationOutbox target = outbox(NotiType.RENTAL_REQUEST_IN_PROGRESS, null);
            given(outboxRepository.findById(7L)).willReturn(Optional.of(target));
            given(userRepository.findById(10L)).willReturn(Optional.of(user("ko")));
            given(deviceTokenRepository.findAllByUserIdOrderByLastUsedAtDesc(10L)).willReturn(List.of());

            worker.dispatch(7L);

            then(pushSender).should(never()).send(any());
            assertThat(target.getStatus()).isEqualTo(OutboxStatus.SKIPPED);
            assertThat(target.getStatus().isRetryable()).isFalse();
        }

        @Test
        @DisplayName("이미 처리된 행은 다시 보내지 않는다")
        void already_processed_is_skipped() {
            NotificationOutbox sent = outbox(NotiType.RENTAL_REQUEST_IN_PROGRESS, null);
            sent.markSent();
            given(outboxRepository.findById(7L)).willReturn(Optional.of(sent));

            worker.dispatch(7L);

            then(pushSender).should(never()).send(any());
        }

        @Test
        @DisplayName("전 기기 발송이 실패하면 FAILED로 남아 재시도 대상이 된다")
        void all_failed_is_failed() {
            NotificationOutbox target = outbox(NotiType.RENTAL_REQUEST_IN_PROGRESS, null);
            given(outboxRepository.findById(7L)).willReturn(Optional.of(target));
            givenUserWithDevices("ko", "tok-1");
            given(pushSender.send(any())).willReturn(PushSendResult.temporaryFailure("UNAVAILABLE"));

            worker.dispatch(7L);

            assertThat(target.getStatus()).isEqualTo(OutboxStatus.FAILED);
        }

        // BE_REQUEST_08_20 §2 — 집계 문구만 남기면 토큰 만료(재시도 가치)와 벤더 장애(대기)를
        // 구분할 수 없다. 기기별 벤더 사유가 종류별 개수로 lastError에 남아야 한다.
        @Test
        @DisplayName("전 기기 실패의 lastError에 벤더 사유가 종류별 개수로 남는다")
        void all_failed_preserves_vendor_details() {
            NotificationOutbox target = outbox(NotiType.RENTAL_REQUEST_IN_PROGRESS, null);
            given(outboxRepository.findById(7L)).willReturn(Optional.of(target));
            givenUserWithDevices("ko", "tok-1", "tok-2", "tok-3");
            given(pushSender.send(any()))
                    .willReturn(PushSendResult.invalidToken("UNREGISTERED"))
                    .willReturn(PushSendResult.invalidToken("UNREGISTERED"))
                    .willReturn(PushSendResult.temporaryFailure("UNAVAILABLE"));

            worker.dispatch(7L);

            assertThat(target.getLastError())
                    .contains("기기 3대")
                    .contains("UNREGISTERED\u00d72")
                    .contains("UNAVAILABLE\u00d71");
        }

        @Test
        @DisplayName("벤더가 사유를 안 줘도 발송 실패 처리는 깨지지 않는다")
        void missing_detail_does_not_break() {
            NotificationOutbox target = outbox(NotiType.RENTAL_REQUEST_IN_PROGRESS, null);
            given(outboxRepository.findById(7L)).willReturn(Optional.of(target));
            givenUserWithDevices("ko", "tok-1");
            given(pushSender.send(any())).willReturn(PushSendResult.temporaryFailure(null));

            worker.dispatch(7L);

            assertThat(target.getStatus()).isEqualTo(OutboxStatus.FAILED);
            assertThat(target.getLastError()).contains("원인 미상\u00d71");
        }

        @Test
        @DisplayName("한 대라도 성공하면 보낸 것이다")
        void partial_success_is_sent() {
            NotificationOutbox target = outbox(NotiType.RENTAL_REQUEST_IN_PROGRESS, null);
            given(outboxRepository.findById(7L)).willReturn(Optional.of(target));
            givenUserWithDevices("ko", "tok-1", "tok-2");
            given(pushSender.send(any()))
                    .willReturn(PushSendResult.temporaryFailure("UNAVAILABLE"))
                    .willReturn(PushSendResult.success("m2"));

            worker.dispatch(7L);

            assertThat(target.getStatus()).isEqualTo(OutboxStatus.SENT);
        }

        // 앱이 푸시를 누르면 notiType 으로 목적지를 가르고 notificationUuid 로 그 알림함 행을 읽음 처리한다
        @Test
        @DisplayName("data 에 deepLink · notiType · notificationUuid 가 실리고 모든 기기에 같은 값이 간다")
        void data_carries_type_and_inbox_uuid_to_every_device() {
            NotificationOutbox target = withId(NotificationOutbox.builder()
                    .uuid("o").userId(10L).notiType(NotiType.RENTAL_REQUEST_IN_PROGRESS).dedupeKey("k")
                    .templateCode(NotiType.RENTAL_REQUEST_IN_PROGRESS.templateCode())
                    .deepLink("/rental-requests/abc").userNotificationUuid("un-1")
                    .scheduledAt(LocalDateTime.now(Clock.fixed(NOW, SEOUL)))
                    .status(OutboxStatus.PENDING).build(), 7L);
            given(outboxRepository.findById(7L)).willReturn(Optional.of(target));
            givenUserWithDevices("ko", "tok-1", "tok-2");
            given(pushSender.send(any())).willReturn(PushSendResult.success("m1"));

            worker.dispatch(7L);

            ArgumentCaptor<PushMessage> sent = ArgumentCaptor.forClass(PushMessage.class);
            then(pushSender).should(times(2)).send(sent.capture());
            assertThat(sent.getAllValues()).allSatisfy(message -> assertThat(message.data())
                    .containsExactlyInAnyOrderEntriesOf(Map.of(
                            "deepLink", "/rental-requests/abc",
                            "notiType", "RENTAL_REQUEST_IN_PROGRESS",
                            "notificationUuid", "un-1")));
        }

        // 알림함 uuid 칸이 생기기 전에 쌓인 행 · 이동 경로가 없는 알림 — 없는 키는 싣지 않는다(앱은 없으면 읽음을 안 보냄)
        @Test
        @DisplayName("알림함 uuid 가 없는 옛 행 · deepLink 없는 알림은 notiType 만 싣는다")
        void old_row_without_inbox_uuid_sends_type_only() {
            NotificationOutbox target = withId(NotificationOutbox.builder()
                    .uuid("o").userId(10L).notiType(NotiType.VERIFICATION_APPROVED).dedupeKey("k")
                    .templateCode(NotiType.VERIFICATION_APPROVED.templateCode())
                    .scheduledAt(LocalDateTime.now(Clock.fixed(NOW, SEOUL)))
                    .status(OutboxStatus.PENDING).build(), 7L);
            given(outboxRepository.findById(7L)).willReturn(Optional.of(target));
            givenUserWithDevices("ko", "tok-1");
            given(pushSender.send(any())).willReturn(PushSendResult.success("m1"));

            worker.dispatch(7L);

            ArgumentCaptor<PushMessage> sent = ArgumentCaptor.forClass(PushMessage.class);
            then(pushSender).should().send(sent.capture());
            assertThat(sent.getValue().data())
                    .containsExactlyEntriesOf(Map.of("notiType", "VERIFICATION_APPROVED"));
        }
    }

    @Nested
    @DisplayName("★ 죽은 토큰 정리 (FR-607 · T-3)")
    class DeadTokens {

        @Test
        @DisplayName("만료 토큰만 지운다 — 일시 장애 토큰은 살려 둔다")
        void only_invalid_tokens_are_deleted() {
            given(outboxRepository.findById(7L)).willReturn(Optional.of(outbox(NotiType.RENTAL_REQUEST_IN_PROGRESS, null)));
            givenUserWithDevices("ko", "dead", "flaky", "ok");
            given(pushSender.send(any()))
                    .willReturn(PushSendResult.invalidToken("UNREGISTERED"))
                    .willReturn(PushSendResult.temporaryFailure("UNAVAILABLE"))
                    .willReturn(PushSendResult.success("m3"));

            worker.dispatch(7L);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<DeviceToken>> captor = ArgumentCaptor.forClass(List.class);
            then(deviceTokenRepository).should().deleteAll(captor.capture());
            assertThat(captor.getValue()).extracting(DeviceToken::getToken).containsExactly("dead");
        }
    }

    @Nested
    @DisplayName("★ 수신 설정 (FR-606 · FR-608)")
    class Settings {

        // 월세·계약·인증은 꺼 두면 "몰라서 못 냈다"가 성립해 버린다.
        @Test
        @DisplayName("CONTRACT는 설정을 꺼 놔도 나간다 — 토글 대상이 아니다")
        void contract_ignores_settings() {
            NotificationOutbox target = outbox(NotiType.RENTAL_REQUEST_IN_PROGRESS, null);
            given(outboxRepository.findById(7L)).willReturn(Optional.of(target));
            givenUserWithDevices("ko", "tok-1");
            given(pushSender.send(any())).willReturn(PushSendResult.success("m1"));

            worker.dispatch(7L);

            assertThat(target.getStatus()).isEqualTo(OutboxStatus.SENT);
            then(settingRepository).should(never()).findByUserIdAndCategory(any(), any());
        }

        @Test
        @DisplayName("COMMUNITY를 껐으면 푸시는 멈추고 SKIPPED다 — 알림함에는 남는다")
        void community_off_skips_push_but_keeps_inapp() {
            NotificationOutbox target = outbox(NotiType.COMMUNITY_COMMENT, null);
            given(outboxRepository.findById(7L)).willReturn(Optional.of(target));
            given(userRepository.findById(10L)).willReturn(Optional.of(user("ko")));
            given(settingRepository.findByUserIdAndCategory(10L, NotiCategory.COMMUNITY))
                    .willReturn(Optional.of(UserNotificationSetting.builder()
                            .userId(10L).category(NotiCategory.COMMUNITY).enabled(false)
                            .updatedAt(LocalDateTime.now(Clock.fixed(NOW, SEOUL))).build()));

            worker.dispatch(7L);

            then(pushSender).should(never()).send(any());
            assertThat(target.getStatus()).isEqualTo(OutboxStatus.SKIPPED);
        }

        @Test
        @DisplayName("설정 행이 없으면 기본 ON이다")
        void missing_setting_defaults_to_on() {
            NotificationOutbox target = outbox(NotiType.COMMUNITY_COMMENT, null);
            given(outboxRepository.findById(7L)).willReturn(Optional.of(target));
            givenUserWithDevices("ko", "tok-1");
            given(settingRepository.findByUserIdAndCategory(10L, NotiCategory.COMMUNITY))
                    .willReturn(Optional.empty());
            given(pushSender.send(any())).willReturn(PushSendResult.success("m1"));

            worker.dispatch(7L);

            assertThat(target.getStatus()).isEqualTo(OutboxStatus.SENT);
        }
    }

    @Nested
    @DisplayName("★ 재시도와 포기")
    class RetryAndGiveUp {

        // 한 번 실패했다고 영영 잊히면 안 된다 — 벤더는 흔들렸다 돌아온다.
        @Test
        @DisplayName("실패하면 FAILED로 남아 다음 시각에 다시 집힌다 — 간격은 시도마다 벌어진다")
        void failure_is_rescheduled_with_backoff() {
            NotificationOutbox target = outbox(NotiType.RENTAL_REQUEST_IN_PROGRESS, null);
            given(outboxRepository.findById(7L)).willReturn(Optional.of(target));
            givenUserWithDevices("ko", "tok-1");
            given(pushSender.send(any())).willReturn(PushSendResult.temporaryFailure("UNAVAILABLE"));

            worker.dispatch(7L);

            assertThat(target.getStatus()).isEqualTo(OutboxStatus.FAILED);
            assertThat(target.getAttemptCount()).isEqualTo(1);
            assertThat(target.getScheduledAt())
                    .isEqualTo(LocalDateTime.now(Clock.fixed(NOW, SEOUL)).plusMinutes(5));
        }

        // 무한 재시도는 벤더 장애 때 큐를 태운다.
        @Test
        @DisplayName("정해진 횟수를 다 쓰면 GIVEN_UP이 되어 워커가 더는 집지 않는다")
        void gives_up_after_max_attempts() {
            NotificationOutbox target = outbox(NotiType.RENTAL_REQUEST_IN_PROGRESS, null);
            given(outboxRepository.findById(7L)).willReturn(Optional.of(target));
            givenUserWithDevices("ko", "tok-1");
            given(pushSender.send(any())).willReturn(PushSendResult.temporaryFailure("UNAVAILABLE"));

            worker.dispatch(7L);   // 1
            worker.dispatch(7L);   // 2
            assertThat(target.getStatus()).isEqualTo(OutboxStatus.FAILED);

            worker.dispatch(7L);   // 3 = maxAttempts
            assertThat(target.getStatus()).isEqualTo(OutboxStatus.GIVEN_UP);
            assertThat(target.getStatus().isRetryable()).isFalse();
        }

        // 워커는 푸시만 한다 — 알림함은 적재 시점에 이미 남았으므로 재시도가 건드릴 것이 없다.
        @Test
        @DisplayName("재시도로 성공하면 SENT가 되고 알림함은 손대지 않는다")
        void retry_succeeds_without_touching_inapp() {
            NotificationOutbox target = outbox(NotiType.RENTAL_REQUEST_IN_PROGRESS, null);
            given(outboxRepository.findById(7L)).willReturn(Optional.of(target));
            givenUserWithDevices("ko", "tok-1");
            given(pushSender.send(any()))
                    .willReturn(PushSendResult.temporaryFailure("UNAVAILABLE"))
                    .willReturn(PushSendResult.success("m1"));

            worker.dispatch(7L);
            worker.dispatch(7L);

            then(userNotificationRepository).shouldHaveNoInteractions();
            assertThat(target.getStatus()).isEqualTo(OutboxStatus.SENT);
        }

        @Test
        @DisplayName("GIVEN_UP 건은 워커가 다시 집어도 아무 일도 하지 않는다")
        void given_up_is_terminal() {
            NotificationOutbox target = outbox(NotiType.RENTAL_REQUEST_IN_PROGRESS, null);
            target.markFailed("e", 1, Duration.ofMinutes(5), LocalDateTime.now(Clock.fixed(NOW, SEOUL)));
            given(outboxRepository.findById(7L)).willReturn(Optional.of(target));

            worker.dispatch(7L);

            then(pushSender).should(never()).send(any());
        }

        // 원인을 고친 뒤 한 번에 되살리는 용도다.
        @Test
        @DisplayName("관리자 재시도는 시도 횟수를 0으로 되돌려 기회를 새로 준다")
        void admin_retry_resets_attempts() {
            NotificationOutbox target = outbox(NotiType.RENTAL_REQUEST_IN_PROGRESS, null);
            target.markFailed("e", 1, Duration.ofMinutes(5), LocalDateTime.now(Clock.fixed(NOW, SEOUL)));
            assertThat(target.getStatus()).isEqualTo(OutboxStatus.GIVEN_UP);

            target.retryByAdmin(LocalDateTime.now(Clock.fixed(NOW, SEOUL)));

            assertThat(target.getStatus()).isEqualTo(OutboxStatus.PENDING);
            assertThat(target.getAttemptCount()).isZero();
            assertThat(target.getLastError()).isNull();
        }
    }
}
