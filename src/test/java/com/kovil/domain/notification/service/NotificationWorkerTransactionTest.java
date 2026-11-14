package com.kovil.domain.notification.service;

import com.kovil.domain.notification.entity.NotificationOutbox;
import com.kovil.domain.notification.enums.NotiType;
import com.kovil.domain.notification.enums.OutboxStatus;
import com.kovil.domain.notification.repository.NotificationOutboxRepository;
import com.kovil.domain.notification.repository.UserNotificationRepository;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// ★ 실측에서 잡은 결함의 회귀 테스트.
//
// 워커가 발송 로직을 **같은 클래스 안에서** 부르고 있었다. 자기호출은 프록시를 우회하므로
// @Transactional(REQUIRES_NEW)이 걸리지 않았고, 그러면 outbox 상태 변경(더티 체킹)이 유실된다.
// 저장소 save()는 자체 트랜잭션이라 커밋되므로 **인앱 알림만 계속 쌓이고 상태는 PENDING에 남았다.**
// 30초마다 같은 행이 다시 처리돼 알림이 무한 증식했다.
//
// 단위 테스트는 dispatch()를 직접 불러서 이 구간을 볼 수 없다 — 트랜잭션이 없기 때문이다.
// 그래서 실제 컨텍스트에서 drain()을 타고 커밋 결과를 본다.
@SpringBootTest
class NotificationWorkerTransactionTest {

    @Autowired
    NotificationWorker worker;

    @Autowired
    NotificationPublisher publisher;

    @Autowired
    NotificationOutboxRepository outboxRepository;

    @Autowired
    UserNotificationRepository userNotificationRepository;

    @Autowired
    UserRepository userRepository;

    private User newUser() {
        return userRepository.save(User.builder()
                .uuid(UUID.randomUUID().toString())
                .name("worker-test")
                .phoneHash(UUID.randomUUID().toString())
                .preferredLanguage("ko")
                .status(UserStatus.ACTIVE)
                .role(UserRole.USER)
                .build());
    }

    @Test
    @DisplayName("발송 후 outbox 상태가 실제로 커밋되어 같은 행이 다시 처리되지 않는다")
    void status_change_survives_and_prevents_reprocessing() {
        User user = newUser();
        String dedupeKey = "TEST:" + UUID.randomUUID();
        // 실제 경로로 적재한다 — 알림함 기록도 여기서 함께 생긴다.
        publisher.publish(user.getId(), NotiType.RENTAL_REQUEST_IN_PROGRESS, dedupeKey, Map.of(), null);

        worker.drain();

        // ★ 여기가 핵심 — 자기호출이면 PENDING으로 남는다.
        NotificationOutbox after = outboxRepository.findAll().stream()
                .filter(row -> dedupeKey.equals(row.getDedupeKey()))
                .findFirst().orElseThrow();
        assertThat(after.getStatus())
                .as("발송 뒤에도 PENDING이면 상태 변경이 트랜잭션 밖에서 유실된 것이다")
                .isNotEqualTo(OutboxStatus.PENDING);

        // 알림함은 적재 시점에 1건만 생겼고, 워커는 손대지 않는다.
        assertThat(countFor(user.getId())).isEqualTo(1);

        // 두 번째 폴링에서 같은 행을 다시 집으면 안 된다.
        worker.drain();
        assertThat(countFor(user.getId()))
                .as("같은 알림이 두 번 쌓이면 워커가 같은 행을 재처리한 것이다")
                .isEqualTo(1);
    }

    private long countFor(Long userId) {
        return userNotificationRepository.findByUserIdOrderByIdDesc(userId, Pageable.unpaged())
                .getTotalElements();
    }
}
