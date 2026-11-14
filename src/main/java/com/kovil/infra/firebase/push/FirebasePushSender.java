package com.kovil.infra.firebase.push;

import com.google.firebase.messaging.*;
import com.kovil.infra.push.PushMessage;
import com.kovil.infra.push.PushSendResult;
import com.kovil.infra.push.PushSender;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Optional;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;


// ⚠ 한 번도 실제로 호출된 적이 없다 — 크리덴셜이 없어 provider=fake가 기본이다.
//
// ★ 초기화를 **지연**시킨다. 부팅 시점에 크리덴셜을 열면 파일이 없거나 만료됐을 때
// **컨텍스트가 통째로 뜨지 않아 임대·계약·월세까지 죽는다** — 실측에서 확인했다.
// 푸시는 보조 채널이므로 서비스 가용성을 인질로 잡으면 안 된다.
//
// 초기화에 실패하면 발송을 일시 실패로 처리한다. 그러면 워커가 정해진 횟수만큼 재시도하고
// 결국 GIVEN_UP으로 내려앉아 **관리자 대기열에 보인다** — 조용히 사라지지 않는다.
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "notification.push.provider", havingValue = "firebase")
public class FirebasePushSender implements PushSender {

    private final FirebaseProperties properties;

    private volatile FirebaseMessaging messaging;

    @Override
    public PushSendResult send(PushMessage pushMessage) {
        FirebaseMessaging client;
        try {
            client = messaging();
        } catch (Exception e) {
            log.error("[FirebasePush] 초기화 실패 — 발송을 재시도 대상으로 남긴다", e);
            return PushSendResult.temporaryFailure("FIREBASE_INIT_FAILED");
        }

        Message message = Message.builder()
                .setToken(pushMessage.token())
                .setNotification(Notification.builder()
                        .setTitle(pushMessage.title())
                        .setBody(pushMessage.body())
                        .build())
                .setApnsConfig(ApnsConfig.builder()
                        .setAps(Aps.builder().setSound("default").build())
                        .build())
                .putAllData(pushMessage.data())
                .build();

        try {
            return PushSendResult.success(client.send(message));
        } catch (FirebaseMessagingException e) {
            // ★ 토큰이 죽은 것과 벤더가 잠깐 흔들린 것을 갈라야 한다 — 대응이 정반대다.
            // UNREGISTERED는 앱 삭제·토큰 만료라 지워야 하고, 나머지는 그대로 두고 다시 보낸다.
            MessagingErrorCode code = e.getMessagingErrorCode();
            if (code == MessagingErrorCode.UNREGISTERED || code == MessagingErrorCode.INVALID_ARGUMENT) {
                return PushSendResult.invalidToken(code.name());
            }
            log.warn("[FirebasePush] 발송 실패 code={}", code);
            return PushSendResult.temporaryFailure(code == null ? e.getMessage() : code.name());
        }
    }

    // FirebaseApp은 JVM당 이름별 1개다. 컨텍스트 재기동 시 중복 초기화를 피한다.
    private FirebaseMessaging messaging() throws Exception {
        FirebaseMessaging cached = this.messaging;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (this.messaging == null) {
                this.messaging = FirebaseMessaging.getInstance(existingApp().orElseGet(this::initialize));
            }
            return this.messaging;
        }
    }

    private Optional<FirebaseApp> existingApp() {
        return FirebaseApp.getApps().stream()
                .filter(app -> FirebaseApp.DEFAULT_APP_NAME.equals(app.getName()))
                .findFirst();
    }

    private FirebaseApp initialize() {
        try {
            GoogleCredentials credentials = ServiceAccountCredentials.newBuilder()
                    .setClientEmail(properties.clientEmail())
                    .setPrivateKey(rsaPrivateKey(properties.privateKey()))
                    .setProjectId(properties.projectId())
                    .build();
            return FirebaseApp.initializeApp(FirebaseOptions.builder()
                    .setCredentials(credentials)
                    .setProjectId(properties.projectId())
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException("Firebase 초기화 실패", e);
        }
    }

    // 서비스 계정 JSON의 private_key 본문을 PKCS#8 RSA 키로 읽는다(Apple .p8과 같은 선례).
    // .env 한 줄 값은 개행이 \n 리터럴로 오므로 공백류를 전부 걷어내고 base64만 남긴다.
    private PrivateKey rsaPrivateKey(String pem) throws GeneralSecurityException {
        String base64 = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("\\n", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }
}
