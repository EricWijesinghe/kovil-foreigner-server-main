package com.kovil.domain.device.service;

import com.kovil.domain.device.config.DeviceProperties;
import com.kovil.domain.device.dto.response.DeviceTokenResponse;
import com.kovil.domain.device.entity.DeviceToken;
import com.kovil.domain.device.enums.DevicePlatform;
import com.kovil.domain.device.exception.DeviceException;
import com.kovil.domain.device.repository.DeviceTokenRepository;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.user.service.UserQueryService;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class DeviceTokenServiceTest {

    private static final String UUID = "user-uuid";
    private static final String TOKEN = "fcm-token-1";
    private static final Instant NOW = Instant.parse("2026-08-11T00:00:00Z");
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    @Mock DeviceTokenRepository deviceTokenRepository;
    @Mock UserQueryService userQueryService;

    private DeviceTokenService service;

    @BeforeEach
    void setUp() {
        service = new DeviceTokenService(deviceTokenRepository, userQueryService,
                new DeviceProperties(3), Clock.fixed(NOW, ZONE));
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

    private void givenUser(Long id) {
        given(userQueryService.getActiveUser(UUID)).willReturn(
                withId(User.builder().uuid(UUID).status(UserStatus.ACTIVE).role(UserRole.USER).build(), id));
    }

    private DeviceToken token(Long id, Long ownerId, String value) {
        DeviceToken t = DeviceToken.register(ownerId, value, DevicePlatform.IOS,
                LocalDateTime.now(Clock.fixed(NOW, ZONE)));
        return withId(t, id);
    }

    private void givenNoExcess(Long userId) {
        given(deviceTokenRepository.findAllByUserIdOrderByLastUsedAtDesc(userId)).willReturn(List.of());
    }

    @Test
    @DisplayName("처음 보는 토큰이면 새로 등록한다")
    void registers_new_token() {
        givenUser(7L);
        given(deviceTokenRepository.findByToken(TOKEN)).willReturn(Optional.empty());
        given(deviceTokenRepository.saveAndFlush(any(DeviceToken.class)))
                .willAnswer(inv -> withId(inv.getArgument(0), 1L));
        givenNoExcess(7L);

        DeviceTokenResponse response = service.register(UUID, TOKEN, "ios");

        ArgumentCaptor<DeviceToken> captor = ArgumentCaptor.forClass(DeviceToken.class);
        then(deviceTokenRepository).should().saveAndFlush(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(7L);
        assertThat(captor.getValue().getPlatform()).isEqualTo(DevicePlatform.IOS);
        assertThat(response.platform()).isEqualTo("IOS");
    }

    // 토큰은 전역 유니크다. 같은 토큰이 다시 오면 새 행을 만들 수 없고(제약 위반),
    // 만들어서도 안 된다 — 기기를 넘겨받은 사람에게 소유자가 이전되어야 한다.
    @Test
    @DisplayName("이미 있는 토큰이면 행을 만들지 않고 소유자를 이전한다")
    void reassigns_existing_token_to_new_owner() {
        givenUser(99L);
        DeviceToken existing = token(1L, 7L, TOKEN);
        given(deviceTokenRepository.findByToken(TOKEN)).willReturn(Optional.of(existing));
        givenNoExcess(99L);

        service.register(UUID, TOKEN, "android");

        assertThat(existing.getUserId()).isEqualTo(99L);
        assertThat(existing.getPlatform()).isEqualTo(DevicePlatform.ANDROID);
        assertThat(existing.getLastUsedAt()).isNotNull();
        then(deviceTokenRepository).should(never()).saveAndFlush(any(DeviceToken.class));
    }

    @Test
    @DisplayName("같은 사용자가 같은 토큰을 다시 등록해도 행이 늘지 않는다 — 멱등이다")
    void register_is_idempotent() {
        givenUser(7L);
        given(deviceTokenRepository.findByToken(TOKEN)).willReturn(Optional.of(token(1L, 7L, TOKEN)));
        givenNoExcess(7L);

        service.register(UUID, TOKEN, "ios");

        then(deviceTokenRepository).should(never()).saveAndFlush(any(DeviceToken.class));
    }

    // 기기를 바꿔가며 등록하면 죽은 토큰이 쌓이고, 그만큼 무의미한 발송이 늘어난다.
    @Test
    @DisplayName("상한을 넘으면 오래 안 쓴 것부터 지운다")
    void prunes_oldest_over_cap() {
        givenUser(7L);
        given(deviceTokenRepository.findByToken(TOKEN)).willReturn(Optional.empty());
        given(deviceTokenRepository.saveAndFlush(any(DeviceToken.class)))
                .willAnswer(inv -> withId(inv.getArgument(0), 5L));

        List<DeviceToken> all = new ArrayList<>(IntStream.rangeClosed(1, 5)
                .mapToObj(i -> token((long) i, 7L, "t" + i)).toList());
        given(deviceTokenRepository.findAllByUserIdOrderByLastUsedAtDesc(7L)).willReturn(all);

        service.register(UUID, TOKEN, "ios");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DeviceToken>> captor = ArgumentCaptor.forClass(List.class);
        then(deviceTokenRepository).should().deleteAll(captor.capture());
        // 상한 3 → 뒤쪽(오래 안 쓴) 2개가 지워진다.
        assertThat(captor.getValue()).hasSize(2);
        assertThat(captor.getValue()).extracting(DeviceToken::getToken).containsExactly("t4", "t5");
    }

    @Test
    @DisplayName("상한 이내면 아무것도 지우지 않는다")
    void does_not_prune_within_cap() {
        givenUser(7L);
        given(deviceTokenRepository.findByToken(TOKEN)).willReturn(Optional.empty());
        given(deviceTokenRepository.saveAndFlush(any(DeviceToken.class)))
                .willAnswer(inv -> withId(inv.getArgument(0), 1L));
        given(deviceTokenRepository.findAllByUserIdOrderByLastUsedAtDesc(7L))
                .willReturn(List.of(token(1L, 7L, "t1")));

        service.register(UUID, TOKEN, "ios");

        then(deviceTokenRepository).should(never()).deleteAll(any());
    }

    // 같은 토큰이 동시에 두 번 오면 둘 다 findByToken 을 지나고 늦은 쪽이 토큰 유니크에 걸린다.
    // 예외 모양은 운영과 같게 — Hibernate 가 MySQL 1062 를 감싼 것(원인 사슬 끝은 SQLException 이라 getRootCause 로는 못 찾는다).
    // 번역하지 않으면 500 — 다시 부르면 기존 행을 이어받으므로 "다시 하면 된다"만 전한다.
    @Test
    @DisplayName("같은 토큰 동시 등록의 토큰 유니크 위반은 500 이 아니라 CONFLICT 다")
    void concurrent_same_token_becomes_conflict() {
        givenUser(7L);
        given(deviceTokenRepository.findByToken(TOKEN)).willReturn(Optional.empty());
        given(deviceTokenRepository.saveAndFlush(any(DeviceToken.class))).willThrow(
                new DataIntegrityViolationException("dup",
                        new ConstraintViolationException("dup",
                                new SQLIntegrityConstraintViolationException(
                                        "Duplicate entry 'fcm-token-1' for key 'device_tokens.uq_dt_token'", "23000", 1062),
                                "device_tokens.uq_dt_token")));

        assertThatThrownBy(() -> service.register(UUID, TOKEN, "ios"))
                .isInstanceOf(DeviceException.class)
                .extracting(e -> ((DeviceException) e).getErrorCode().getCode())
                .isEqualTo("CONFLICT");

        then(deviceTokenRepository).should(never()).findAllByUserIdOrderByLastUsedAtDesc(any());
    }

    // 토큰 유니크가 아닌 위반(FK 등)은 예상 밖이라 감추지 않는다
    @Test
    @DisplayName("토큰 유니크가 아닌 무결성 위반은 그대로 올려보낸다")
    void other_integrity_violation_is_rethrown() {
        givenUser(7L);
        given(deviceTokenRepository.findByToken(TOKEN)).willReturn(Optional.empty());
        DataIntegrityViolationException fk = new DataIntegrityViolationException("fk",
                new ConstraintViolationException("fk",
                        new SQLIntegrityConstraintViolationException(
                                "Cannot add or update a child row: a foreign key constraint fails", "23000", 1452),
                        "device_tokens.fk_dt_user"));
        given(deviceTokenRepository.saveAndFlush(any(DeviceToken.class))).willThrow(fk);

        assertThatThrownBy(() -> service.register(UUID, TOKEN, "ios")).isSameAs(fk);
    }

    @Test
    @DisplayName("잘못된 플랫폼은 등록 전에 걸린다")
    void invalid_platform_rejected() {
        givenUser(7L);

        assertThatThrownBy(() -> service.register(UUID, TOKEN, "windows"))
                .isInstanceOf(DeviceException.class)
                .extracting(e -> ((DeviceException) e).getErrorCode().getCode())
                .isEqualTo("INVALID_DEVICE_PLATFORM");

        then(deviceTokenRepository).shouldHaveNoInteractions();
    }

    // uuid만으로 지우면 남의 기기를 끊을 수 있다. 조회 자체가 소유자를 함께 본다.
    @Test
    @DisplayName("남의 기기는 해제되지 않는다 — 조회가 소유자까지 본다")
    void cannot_unregister_someone_elses_device() {
        givenUser(7L);
        given(deviceTokenRepository.findByUuidAndUserId("device-uuid", 7L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.unregister(UUID, "device-uuid"))
                .isInstanceOf(DeviceException.class)
                .extracting(e -> ((DeviceException) e).getErrorCode().getCode())
                .isEqualTo("DEVICE_TOKEN_NOT_FOUND");

        then(deviceTokenRepository).should(never()).delete(any());
    }

    @Test
    @DisplayName("자기 기기는 해제된다")
    void unregisters_own_device() {
        givenUser(7L);
        DeviceToken own = token(1L, 7L, TOKEN);
        given(deviceTokenRepository.findByUuidAndUserId("device-uuid", 7L)).willReturn(Optional.of(own));

        service.unregister(UUID, "device-uuid");

        then(deviceTokenRepository).should().delete(own);
    }
}
