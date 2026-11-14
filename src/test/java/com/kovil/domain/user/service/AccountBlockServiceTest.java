package com.kovil.domain.user.service;

import com.kovil.domain.user.entity.BlockedIdentity;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.entity.UserStatusHistory;
import com.kovil.domain.user.enums.BlockedIdentityType;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.user.repository.BlockedIdentityRepository;
import com.kovil.domain.user.repository.UserStatusHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

/**
 * 재가입 차단 원장.
 *
 * ★ 이 클래스가 막는 것은 하나다 — **정지 → 탈퇴 → 재가입** 세탁.
 *   탈퇴는 신원을 풀어 준다(phone_hash 해제, user_auth 해제). 그래야 재가입이 되는데,
 *   그 해제가 정지된 사람에게도 열리면 깨끗한 새 계정이 만들어진다.
 *   여기가 뚫리면 정지 제도 자체가 무의미해진다.
 */
@ExtendWith(MockitoExtension.class)
class AccountBlockServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-01T03:00:00Z");
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime AT = LocalDateTime.ofInstant(NOW, ZONE);

    @Mock BlockedIdentityRepository blockedIdentityRepository;
    @Mock UserStatusHistoryRepository userStatusHistoryRepository;

    private AccountBlockService service;

    @BeforeEach
    void setUp() {
        service = new AccountBlockService(blockedIdentityRepository, userStatusHistoryRepository,
                Clock.fixed(NOW, ZONE));
    }

    private static User user(String phoneHash) {
        User user = User.builder().uuid("uuid-1").role(UserRole.USER)
                .status(UserStatus.ACTIVE).phoneHash(phoneHash).preferredLanguage("ko").build();
        try {
            var field = User.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(user, 7L);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return user;
    }

    @Nested
    @DisplayName("정지 시 신원 등록")
    class Block {

        /*
         * ★ 축은 전화번호 하나다. 소셜 가입도 휴대폰 인증을 강제하고, 계정 생성·소셜 연결이
         *   전부 SignUpProcessor.loadUnconsumed 를 지나므로 번호가 단일 관문이다.
         *
         * ★ 삽입은 멱등이어야 한다. 유니크 충돌을 catch 로 잡으면 트랜잭션이 rollback-only 가 되어
         *   정지 자체가 함께 롤백된다 — 이 저장소가 이미 두 번 겪은 함정이다.
         */
        @Test
        @DisplayName("번호를 원장에 멱등 삽입한다")
        void blocks_phone_idempotently() {
            service.blockIdentitiesOf(user("HASH-1"), 99L, "도배");

            then(blockedIdentityRepository).should().insertIgnore(
                    "PHONE", "HASH-1", "도배", 7L, 99L, AT);
            // 유니크 충돌을 예외로 받는 경로가 없어야 한다.
            then(blockedIdentityRepository).should(never()).save(any());
            then(blockedIdentityRepository).should(never()).saveAndFlush(any());
        }

        // 번호가 없는 계정에서 NULL 이 원장에 들어가면 안 된다.
        @Test
        @DisplayName("번호가 없으면 아무것도 올리지 않는다")
        void skips_when_phone_absent() {
            service.blockIdentitiesOf(user(null), 99L, null);

            then(blockedIdentityRepository).shouldHaveNoInteractions();
        }
    }

    @Nested
    @DisplayName("판정과 해제")
    class Judge {

        @Test
        @DisplayName("번호 축으로 조회한다")
        void checks_phone_axis() {
            given(blockedIdentityRepository.existsActive(BlockedIdentityType.PHONE, "HASH-1"))
                    .willReturn(true);

            assertThat(service.isPhoneBlocked("HASH-1")).isTrue();
        }

        // 값이 없으면 조회 자체를 하지 않는다 — NULL 로 원장을 뒤지면 엉뚱한 매치가 난다.
        @Test
        @DisplayName("값이 없으면 차단으로 보지 않는다")
        void null_is_not_blocked() {
            assertThat(service.isPhoneBlocked(null)).isFalse();

            then(blockedIdentityRepository).shouldHaveNoInteractions();
        }

        // 행을 지우면 왜 풀렸는지가 사라진다 — 해제로만 남긴다.
        @Test
        @DisplayName("해제는 행을 지우지 않고 released_at 을 남긴다")
        void release_keeps_the_row() {
            BlockedIdentity blocked = BlockedIdentity.builder()
                    .identityType(BlockedIdentityType.PHONE).identityValue("HASH-1")
                    .sourceUserId(7L).blockedAt(AT).build();
            given(blockedIdentityRepository.findActiveBySourceUserId(7L)).willReturn(List.of(blocked));

            service.releaseIdentitiesOf(7L, 99L);

            assertThat(blocked.getReleasedAt()).isEqualTo(AT);
            assertThat(blocked.getReleasedBy()).isEqualTo(99L);
            then(blockedIdentityRepository).should(never()).delete(any());
        }
    }

    // users.status 는 한 칸뿐이라 여기 쌓지 않으면 "왜 정지했었나"를 아무도 답할 수 없다.
    @Test
    @DisplayName("상태 전이는 행위자와 사유를 함께 남긴다")
    void records_status_change_with_actor() {
        service.recordStatusChange(7L, UserStatus.ACTIVE, UserStatus.SUSPENDED, 99L,
                UserStatusHistory.ACTOR_ADMIN, "도배");

        ArgumentCaptor<UserStatusHistory> captor = ArgumentCaptor.forClass(UserStatusHistory.class);
        then(userStatusHistoryRepository).should().save(captor.capture());
        assertThat(captor.getValue().getFromStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(captor.getValue().getToStatus()).isEqualTo(UserStatus.SUSPENDED);
        assertThat(captor.getValue().getChangedBy()).isEqualTo(99L);
        assertThat(captor.getValue().getActorNote()).isEqualTo(UserStatusHistory.ACTOR_ADMIN);
        assertThat(captor.getValue().getReason()).isEqualTo("도배");
    }
}
