package com.kovil.infra.firebase.push;

import com.google.firebase.messaging.ApnsConfig;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;
import com.kovil.infra.push.PushMessage;
import com.kovil.infra.push.PushSendResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

// 실제 FCM 으로는 보내지 않는다 — 초기화된 클라이언트 자리에 가짜를 넣고, 보내려던 Message 의 모양만 본다.
// Message 의 필드는 비공개라 리플렉션으로 읽는다(기존 테스트의 withId 와 같은 방식).
class FirebasePushSenderTest {

    private static Object field(Object target, String name) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void setField(Object target, String name, Object value) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    // iOS 는 aps.sound 가 없으면 소리 없이 뜬다. alert 는 넣지 않는다 — 제목 · 본문은 공통 notification 이 맡는다.
    @Test
    @DisplayName("한 메시지에 토큰 · 제목 · 본문 · data 와 iOS 소리(aps.sound=default)를 싣는다")
    @SuppressWarnings("unchecked")
    void message_carries_notification_data_and_ios_sound() throws Exception {
        FirebaseMessaging messaging = mock(FirebaseMessaging.class);
        given(messaging.send(any(Message.class))).willReturn("projects/p/messages/1");
        FirebasePushSender sender = new FirebasePushSender(new FirebaseProperties("p", "e", "k"));
        setField(sender, "messaging", messaging);

        PushSendResult result = sender.send(PushMessage.of("fcm-token", "인증 승인", "인증이 승인되었습니다",
                Map.of("deepLink", "/me/verification", "notiType", "VERIFICATION_APPROVED", "notificationUuid", "un-1")));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.messageId()).isEqualTo("projects/p/messages/1");

        ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
        then(messaging).should().send(sent.capture());
        Message message = sent.getValue();
        assertThat(field(message, "token")).isEqualTo("fcm-token");
        assertThat((Map<String, String>) field(message, "data")).containsExactlyInAnyOrderEntriesOf(Map.of(
                "deepLink", "/me/verification",
                "notiType", "VERIFICATION_APPROVED",
                "notificationUuid", "un-1"));

        Notification notification = (Notification) field(message, "notification");
        assertThat(field(notification, "title")).isEqualTo("인증 승인");
        assertThat(field(notification, "body")).isEqualTo("인증이 승인되었습니다");

        ApnsConfig apns = (ApnsConfig) field(message, "apnsConfig");
        Map<String, Object> payload = (Map<String, Object>) field(apns, "payload");
        assertThat((Map<String, Object>) payload.get("aps"))
                .containsEntry("sound", "default")
                .doesNotContainKey("alert");
    }
}
