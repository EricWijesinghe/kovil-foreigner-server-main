package com.kovil.domain.refreshtoken.service;

import com.kovil.domain.refreshtoken.RefreshTokenProperties;
import com.kovil.domain.refreshtoken.dto.result.RefreshTokenIssueResult;
import com.kovil.domain.refreshtoken.entity.RefreshToken;
import com.kovil.domain.refreshtoken.enums.RefreshTokenStatus;
import com.kovil.domain.refreshtoken.exception.RefreshTokenException;
import com.kovil.domain.refreshtoken.repository.RefreshTokenRepository;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.infra.crypto.Hasher;
import com.kovil.domain.partner.enums.PartnerDivisionType;
import com.kovil.domain.partner.repository.PartnerDivisionRepository;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.user.repository.UserRepository;
import com.kovil.infra.jwt.BlacklistedTokenStore;
import com.kovil.infra.jwt.JwtProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.ArgumentMatchers;
import org.mockito.BDDMockito;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-11T00:00:00Z");
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    @Mock RefreshTokenRepository repository;
    @Mock RefreshTokenRevoker revoker;
    @Mock Hasher hasher;
    @Mock UserRepository userRepository;
    @Mock BlacklistedTokenStore blacklistedTokenStore;
    @Mock PartnerDivisionRepository partnerDivisionRepository;

    private RefreshTokenService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new RefreshTokenService(
                repository,
                revoker,
                new RefreshTokenProperties(Duration.ofDays(30), 5, Duration.ofSeconds(60)),
                hasher,
                userRepository,
                blacklistedTokenStore,
                new JwtProperties(
                        "x".repeat(64), Duration.ofMinutes(30), "kovil"),
                Clock.fixed(NOW, ZONE),
                partnerDivisionRepository);
        user = withId(User.builder().uuid("user-uuid").role(UserRole.USER).build(), 7L);
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

    private RefreshToken token(Long id, RefreshTokenStatus status, LocalDateTime expiresAt) {
        return withId(RefreshToken.builder()
                .user(user)
                .tokenHash("hash-" + id)
                .status(status)
                .issuedAt(LocalDateTime.now(Clock.fixed(NOW, ZONE)))
                .expiresAt(expiresAt)
                .build(), id);
    }

    // rotate 는 잠금 전에 프로젝션 사전 검사를 지난다 — 상태·만료를 토큰 그대로 비춘다.
    private void givenPeekMirrors(RefreshToken token, String hash) {
        RefreshTokenRepository.RotationPeek peek =
                Mockito.mock(RefreshTokenRepository.RotationPeek.class);
        Mockito.lenient().when(peek.getId()).thenReturn(token.getId());
        Mockito.lenient().when(peek.getStatus()).thenReturn(token.getStatus());
        Mockito.lenient().when(peek.getExpiresAt()).thenReturn(token.getExpiresAt());
        given(repository.peekByTokenHash(hash)).willReturn(Optional.of(peek));
    }

    private void givenSaveEchoes() {
        given(repository.save(any(RefreshToken.class))).willAnswer(inv -> withId(inv.getArgument(0), 99L));
    }

    /*
     * 발급은 역할을 **엔티티가 아니라 users 행 잠금 읽기**에서 가져온다 — 로그인 트랜잭션의
     * 스냅샷은 비밀번호 검증(수백 ms) 중에 커밋된 강등을 보지 못하기 때문이다
     * (적대적 리뷰 2026-09-17 3라운드). 그래서 발급 경로는 이 스텁이 필요하다.
     */
    private void givenAuthSnapshot(UserRole role, UserStatus status) {
        given(userRepository.findAuthSnapshotForUpdate(7L)).willReturn(Optional.of(
                new UserRepository.AuthSnapshot() {
                    @Override public String getUuid() { return "user-uuid"; }
                    @Override public String getRole() { return role.name(); }
                    @Override public String getStatus() { return status.name(); }
                }));
    }

    private LocalDateTime future() {
        return LocalDateTime.now(Clock.fixed(NOW, ZONE)).plusDays(10);
    }

    // 원문은 어디에도 저장되지 않는다(FR-113). 저장되는 것은 HMAC 해시뿐이다.
    @Test
    @DisplayName("발급하면 원문은 응답에만 있고 저장되는 것은 해시다")
    void issue_stores_only_hash() {
        given(repository.findByUserIdAndStatusForUpdate(7L, RefreshTokenStatus.ACTIVE)).willReturn(List.of());
        given(hasher.hash(anyString())).willReturn("hashed");
        givenAuthSnapshot(UserRole.USER, UserStatus.ACTIVE);
        givenSaveEchoes();

        RefreshTokenIssueResult result = service.issue(user);

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        then(repository).should().save(captor.capture());

        assertThat(result.rawToken()).isNotBlank();
        assertThat(captor.getValue().getTokenHash()).isEqualTo("hashed");
        assertThat(captor.getValue().getTokenHash()).isNotEqualTo(result.rawToken());
        assertThat(captor.getValue().getParentToken()).isNull();
    }

    /*
     * ★ 이 테스트가 고정하는 것: 토큰의 역할은 **엔티티가 아니라 users 행 잠금 읽기**에서 온다.
     *
     * 로그인 트랜잭션의 스냅샷은 첫 읽기 시점에 고정되는데(InnoDB REPEATABLE READ), 그 뒤
     * 비밀번호 해시 검증에 수백 ms 가 든다. 그 사이 강등이 커밋돼도 엔티티의 role 은 옛 값
     * 그대로라, 강등된 관리자가 **취소선보다 늦은 iat 로 옛 권한 토큰을 받아 갔다** — 그 한 장이면
     * 공범을 ADMIN 으로 올려 영구 재진입로를 만든다(적대적 리뷰 2026-09-17 3라운드).
     * 잠금 읽기만이 스냅샷을 무시하고 최신 커밋을 본다.
     */
    @Test
    @DisplayName("발급되는 역할은 엔티티가 아니라 잠금 읽기가 정한다")
    void issued_role_comes_from_locking_read_not_entity() {
        User staleAdmin = withId(User.builder().uuid("user-uuid").role(UserRole.ADMIN).build(), 7L);
        given(repository.findByUserIdAndStatusForUpdate(7L, RefreshTokenStatus.ACTIVE)).willReturn(List.of());
        given(hasher.hash(anyString())).willReturn("hashed");
        givenAuthSnapshot(UserRole.USER, UserStatus.ACTIVE);
        givenSaveEchoes();

        RefreshTokenIssueResult result = service.issue(staleAdmin);

        assertThat(result.role()).isEqualTo(UserRole.USER);
    }

    /*
     * ★ 파트너의 승인 부문은 users 잠금 **뒤의 잠금 읽기**로 읽는다 — 심사도 users 행을 먼저 잠그고
     *   부문을 고치므로 둘이 그 행에서 줄을 선다. 일반 읽기면 줄을 서고도 옛 스냅샷을 읽어, 정지 직후의
     *   로그인이 취소선보다 늦은 iat 로 승인 부문을 실은 토큰을 받아 간다.
     */
    @Test
    @DisplayName("파트너는 승인 부문을 잠금 읽기로 읽어 발급 결과에 싣는다")
    void partner_divisions_come_from_locking_read() {
        given(repository.findByUserIdAndStatusForUpdate(7L, RefreshTokenStatus.ACTIVE)).willReturn(List.of());
        given(hasher.hash(anyString())).willReturn("hashed");
        givenAuthSnapshot(UserRole.PARTNER, UserStatus.ACTIVE);
        given(partnerDivisionRepository.findApprovedDivisionNamesForUpdate(7L))
                .willReturn(List.of("REAL_ESTATE_BROKER"));
        givenSaveEchoes();

        RefreshTokenIssueResult result = service.issue(user);

        assertThat(result.role()).isEqualTo(UserRole.PARTNER);
        assertThat(result.approvedDivisions())
                .containsExactly(PartnerDivisionType.REAL_ESTATE_BROKER);
    }

    /*
     * ★ 발급 경로의 잠금 순서 — refresh_tokens → users → 부문. 부문 정지는 세션을 끊어(refresh_tokens → users)
     *   users 행을 쥔 **뒤에** 부문을 고친다. 발급이 부문을 users 보다 먼저 잠그면 둘이 서로를 기다려 교착하고,
     *   users 에서 줄을 서기 전에 읽으면 정지 직전의 승인 부문을 토큰에 싣는다.
     * ★ 엔티티도 PARTNER 로 둔다 — 엔티티의 역할로 부문을 먼저 읽는 순서 변경도 여기서 잡히게.
     */
    @Test
    @DisplayName("발급은 users 행을 잠금 읽기로 본 뒤에 부문을 읽는다")
    void issue_reads_divisions_after_locking_user() {
        User partner = withId(User.builder().uuid("user-uuid").role(UserRole.PARTNER).build(), 7L);
        given(repository.findByUserIdAndStatusForUpdate(7L, RefreshTokenStatus.ACTIVE)).willReturn(List.of());
        given(hasher.hash(anyString())).willReturn("hashed");
        givenAuthSnapshot(UserRole.PARTNER, UserStatus.ACTIVE);
        given(partnerDivisionRepository.findApprovedDivisionNamesForUpdate(7L))
                .willReturn(List.of("REAL_ESTATE_BROKER"));
        givenSaveEchoes();

        service.issue(partner);

        InOrder order = inOrder(repository, userRepository, partnerDivisionRepository);
        order.verify(repository).findByUserIdAndStatusForUpdate(7L, RefreshTokenStatus.ACTIVE);
        order.verify(userRepository).findAuthSnapshotForUpdate(7L);
        order.verify(partnerDivisionRepository).findApprovedDivisionNamesForUpdate(7L);
    }

    // 파트너가 아니면 부문을 묻지 않는다 — 유학생 · 관리자 로그인마다 쿼리가 하나 느는 것도, 부문이 섞이는 것도 막는다.
    @Test
    @DisplayName("파트너가 아니면 부문을 읽지 않고 비워 둔다")
    void non_partner_skips_division_read() {
        given(repository.findByUserIdAndStatusForUpdate(7L, RefreshTokenStatus.ACTIVE)).willReturn(List.of());
        given(hasher.hash(anyString())).willReturn("hashed");
        givenAuthSnapshot(UserRole.ADMIN, UserStatus.ACTIVE);
        givenSaveEchoes();

        RefreshTokenIssueResult result = service.issue(user);

        assertThat(result.approvedDivisions()).isEmpty();
        then(partnerDivisionRepository).shouldHaveNoInteractions();
    }

    // 이름을 바꾼 뒤 남은 행 같은 것 — 로그인 전체가 500 이 되는 것보다 그 부문만 닫히는 쪽이 안전하다.
    @Test
    @DisplayName("코드가 모르는 부문 이름은 버리고 발급한다")
    void unknown_division_name_is_dropped() {
        given(repository.findByUserIdAndStatusForUpdate(7L, RefreshTokenStatus.ACTIVE)).willReturn(List.of());
        given(hasher.hash(anyString())).willReturn("hashed");
        givenAuthSnapshot(UserRole.PARTNER, UserStatus.ACTIVE);
        given(partnerDivisionRepository.findApprovedDivisionNamesForUpdate(7L))
                .willReturn(List.of("RETIRED_DIVISION"));
        givenSaveEchoes();

        assertThat(service.issue(user).approvedDivisions()).isEmpty();
    }

    // 정지·탈퇴가 이 발급과 경합해 커밋됐을 수 있다. 회전이 상태를 다시 보지 않으면
    // 경합을 뚫고 살아남은 리프레시 행이 죽은 계정에서 무기한 돌아간다.
    @Test
    @DisplayName("잠금 읽기가 ACTIVE 가 아니면 발급하지 않는다")
    void suspended_user_gets_no_token() {
        given(repository.findByUserIdAndStatusForUpdate(7L, RefreshTokenStatus.ACTIVE)).willReturn(List.of());
        givenAuthSnapshot(UserRole.USER, UserStatus.SUSPENDED);

        assertThatThrownBy(() -> service.issue(user))
                .isInstanceOf(RefreshTokenException.class);
        then(repository).should(never()).save(any());
    }

    @Test
    @DisplayName("회전하면 옛 토큰은 ROTATED(회전에 쓰임)가 되고 새 토큰이 계보로 이어진다")
    void rotate_revokes_old_and_links_parent() {
        RefreshToken old = token(1L, RefreshTokenStatus.ACTIVE, future());
        // 원문마다 다른 해시를 돌려줘야 한다 — 하나로 뭉개면 조회 키와 저장 키가 섞인다.
        given(hasher.hash(anyString())).willAnswer(inv ->
                "raw".equals(inv.getArgument(0)) ? "hash-1" : "new-hash");
        givenPeekMirrors(old, "hash-1");
        given(repository.findByTokenHashForUpdate("hash-1")).willReturn(Optional.of(old));
        given(repository.findByUserIdAndStatusForUpdate(7L, RefreshTokenStatus.ACTIVE)).willReturn(List.of());
        givenAuthSnapshot(UserRole.USER, UserStatus.ACTIVE);
        givenSaveEchoes();

        service.rotate("raw");

        assertThat(old.getStatus()).isEqualTo(RefreshTokenStatus.ROTATED);
        assertThat(old.getRevokedAt()).isNotNull();

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        then(repository).should().save(captor.capture());
        assertThat(captor.getValue().getParentToken()).isSameAs(old);
    }

    // 정상 클라이언트는 회전 후 옛 토큰을 버린다. 폐기된 토큰이 다시 온다는 것은
    // 사본이 돌아다닌다는 뜻이므로, 탈취자와 사용자를 구분할 수 없는 이상 전부 끊는다.
    @Test
    // ⚠ 이 테스트의 이전 버전이 거짓 확신을 줬다 — 엔티티가 revoke()됐는지만 보고 통과했는데,
    // 실제로는 뒤이어 던진 예외가 트랜잭션을 롤백해 DB에는 아무것도 남지 않았다.
    // 여기서는 **별도 트랜잭션으로 위임했는지**만 고정하고,
    // 실제로 커밋되는지는 RefreshTokenReuseRollbackTest(실컨텍스트)가 본다.
    @DisplayName("회전에 쓴 토큰이 다시 제시되면 세션 전량 폐기를 별도 트랜잭션에 위임한다")
    void reuse_of_rotated_token_delegates_revocation() {
        RefreshToken revoked = token(1L, RefreshTokenStatus.ROTATED, future());

        given(hasher.hash("stolen")).willReturn("hash-1");
        givenPeekMirrors(revoked, "hash-1");
        given(repository.findByTokenHashForUpdate("hash-1")).willReturn(Optional.of(revoked));

        assertThatThrownBy(() -> service.rotate("stolen"))
                .isInstanceOf(RefreshTokenException.class)
                .extracting(e -> ((RefreshTokenException) e).getErrorCode().getCode())
                .isEqualTo("REFRESH_TOKEN_INVALID");

        then(revoker).should().revokeAllFor(7L);
        then(repository).should(never()).save(any(RefreshToken.class));
    }

    // ROTATED 로 만든다 — revokedAt 이 회전 시각이다.
    private RefreshToken rotatedSecondsAgo(long seconds) {
        RefreshToken token = token(1L, RefreshTokenStatus.ACTIVE, future());
        token.rotate(LocalDateTime.now(Clock.fixed(NOW, ZONE)).minusSeconds(seconds));
        return token;
    }

    private void givenPresented(RefreshToken token) {
        given(hasher.hash("old")).willReturn("hash-1");
        givenPeekMirrors(token, "hash-1");
        given(repository.findByTokenHashForUpdate("hash-1")).willReturn(Optional.of(token));
    }

    /*
     * 응답만 잃은 기기(회전은 커밋됐는데 새 쌍이 안 닿음)가 옛 토큰을 다시 보내는 경우.
     * 유예 창 안이고 자식이 안 쓰였으면 그 자식만 끊는다 — 이 기기만 다시 로그인하고 다른 기기는 무사하다.
     */
    @Test
    @DisplayName("회전 직후(유예 창 안) 옛 토큰이 다시 오고 자식이 안 쓰였으면 자식만 끊고 401 — 전량 폐기는 하지 않는다")
    void reuse_within_grace_revokes_only_the_unused_child() {
        RefreshToken rotated = rotatedSecondsAgo(30);
        givenPresented(rotated);
        given(revoker.revokeUnusedChildOf(1L)).willReturn(true);

        assertThatThrownBy(() -> service.rotate("old"))
                .isInstanceOf(RefreshTokenException.class)
                .extracting(e -> ((RefreshTokenException) e).getErrorCode().getCode())
                .isEqualTo("REFRESH_TOKEN_INVALID");

        then(revoker).should(never()).revokeAllFor(any());
        then(repository).should(never()).save(any(RefreshToken.class));
    }

    @Test
    @DisplayName("유예 창 안이어도 자식이 이미 쓰였으면 탈취로 보고 전부 끊는다")
    void reuse_within_grace_after_the_child_was_used_revokes_all() {
        RefreshToken rotated = rotatedSecondsAgo(30);
        givenPresented(rotated);
        given(revoker.revokeUnusedChildOf(1L)).willReturn(false);

        assertThatThrownBy(() -> service.rotate("old")).isInstanceOf(RefreshTokenException.class);

        then(revoker).should().revokeAllFor(7L);
    }

    @Test
    @DisplayName("유예 창 밖이면 자식을 보지 않고 전부 끊는다 — 경계(정확히 60초)는 창 안")
    void reuse_outside_grace_revokes_all() {
        RefreshToken rotated = rotatedSecondsAgo(61);
        givenPresented(rotated);

        assertThatThrownBy(() -> service.rotate("old")).isInstanceOf(RefreshTokenException.class);

        then(revoker).should(never()).revokeUnusedChildOf(any());
        then(revoker).should().revokeAllFor(7L);
    }

    @Test
    @DisplayName("정확히 유예 창 끝(60초)은 창 안이다")
    void grace_boundary_is_inclusive() {
        RefreshToken rotated = rotatedSecondsAgo(60);
        givenPresented(rotated);
        given(revoker.revokeUnusedChildOf(1L)).willReturn(true);

        assertThatThrownBy(() -> service.rotate("old")).isInstanceOf(RefreshTokenException.class);

        then(revoker).should(never()).revokeAllFor(any());
    }

    /*
     * ★ REVOKED(로그아웃 · 세션 상한 정리 · 전 세션 끊기)와 EXPIRED 는 탈취가 아니다 — 정상 기기가 들고 있다가 나중에 보낸다.
     *   그것까지 탈취로 보면 비밀번호를 재설정하고 새 기기로 로그인한 뒤 옛 기기를 열기만 해도 새 기기까지 끊긴다.
     */
    @ParameterizedTest(name = "{0} → 401, 다른 세션은 그대로")
    @EnumSource(value = RefreshTokenStatus.class, names = {"REVOKED", "EXPIRED"})
    @DisplayName("정상 종료된 토큰이 다시 오면 401 만 — 세션 전량 폐기를 부르지 않는다")
    void ended_token_is_rejected_without_cascade(RefreshTokenStatus status) {
        RefreshToken ended = token(1L, status, future());

        given(hasher.hash("old-device")).willReturn("hash-1");
        givenPeekMirrors(ended, "hash-1");
        given(repository.findByTokenHashForUpdate("hash-1")).willReturn(Optional.of(ended));

        assertThatThrownBy(() -> service.rotate("old-device"))
                .isInstanceOf(RefreshTokenException.class)
                .extracting(e -> ((RefreshTokenException) e).getErrorCode().getCode())
                .isEqualTo("REFRESH_TOKEN_INVALID");

        then(revoker).should(never()).revokeAllFor(anyLong());
        then(repository).should(never()).save(any(RefreshToken.class));
        assertThat(ended.getStatus()).isEqualTo(status);
    }

    /*
     * ★ 만료 처리에는 잠금이 **없어야 한다**(적대적 리뷰 2026-09-02). 행 잠금을 쥔 채
     *   REQUIRES_NEW 의 expire 가 같은 행을 갱신하면 자기 자신과 잠금 충돌 — 락 타임아웃까지
     *   매달렸다가 401 대신 500, 커넥션 2개 점유. 그래서 만료는 잠금 이전의 프로젝션 사전
     *   검사에서 끝난다. 잠금 조회가 호출되면 이 테스트가 죽는다(마킹 위임 자체는 AUDIT 대로
     *   별도 트랜잭션 유지 — 같은 트랜잭션이면 예외 롤백에 증발한다).
     */
    @Test
    @DisplayName("만료 토큰은 잠금 없이 EXPIRED 위임 후 거절한다 — 잠금을 쥐면 자기와 충돌한다")
    void expired_token_is_marked_without_holding_the_lock() {
        RefreshToken expired = token(1L, RefreshTokenStatus.ACTIVE,
                LocalDateTime.now(Clock.fixed(NOW, ZONE)).minusDays(1));
        given(hasher.hash("raw")).willReturn("hash-1");
        givenPeekMirrors(expired, "hash-1");

        assertThatThrownBy(() -> service.rotate("raw")).isInstanceOf(RefreshTokenException.class);

        then(revoker).should().expire(expired.getId());
        then(repository).should(never()).findByTokenHashForUpdate(anyString());
        then(repository).should(never()).save(any(RefreshToken.class));
    }

    // 없는 토큰·폐기된 토큰·만료된 토큰이 전부 같은 코드로 나간다 —
    // 구분하면 "그 토큰이 존재하긴 했는가"가 새어 나간다.
    @Test
    @DisplayName("없는 토큰도 같은 코드로 거절한다")
    void unknown_token_is_indistinguishable() {
        given(hasher.hash("raw")).willReturn("nope");
        given(repository.peekByTokenHash("nope")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.rotate("raw"))
                .isInstanceOf(RefreshTokenException.class)
                .extracting(e -> ((RefreshTokenException) e).getErrorCode().getCode())
                .isEqualTo("REFRESH_TOKEN_INVALID");
    }

    @Test
    @DisplayName("세션 상한을 넘으면 오래된 것부터 폐기한다")
    void oldest_sessions_revoked_over_cap() {
        RefreshToken oldest = token(1L, RefreshTokenStatus.ACTIVE, future());
        RefreshToken second = token(2L, RefreshTokenStatus.ACTIVE, future());
        List<RefreshToken> actives = List.of(oldest, second,
                token(3L, RefreshTokenStatus.ACTIVE, future()),
                token(4L, RefreshTokenStatus.ACTIVE, future()),
                token(5L, RefreshTokenStatus.ACTIVE, future()));

        given(repository.findByUserIdAndStatusForUpdate(7L, RefreshTokenStatus.ACTIVE)).willReturn(actives);
        given(hasher.hash(anyString())).willReturn("hashed");
        givenAuthSnapshot(UserRole.USER, UserStatus.ACTIVE);
        givenSaveEchoes();

        service.issue(user);

        // 상한 5 → 새로 하나 넣으려면 기존 5개 중 1개를 비워야 한다.
        assertThat(oldest.getStatus()).isEqualTo(RefreshTokenStatus.REVOKED);
        assertThat(second.getStatus()).isEqualTo(RefreshTokenStatus.ACTIVE);
    }

    @Test
    @DisplayName("로그아웃은 남의 토큰을 끊지 않는다")
    void logout_only_revokes_own_token() {
        User other = withId(User.builder().uuid("other").role(UserRole.USER).build(), 99L);
        RefreshToken othersToken = withId(RefreshToken.builder()
                .user(other).tokenHash("h").status(RefreshTokenStatus.ACTIVE)
                .issuedAt(LocalDateTime.now(Clock.fixed(NOW, ZONE))).expiresAt(future()).build(), 1L);

        given(hasher.hash("raw")).willReturn("h");
        given(repository.findByTokenHash("h")).willReturn(Optional.of(othersToken));

        service.revokeIfOwnedBy("raw", 7L);

        assertThat(othersToken.getStatus()).isEqualTo(RefreshTokenStatus.ACTIVE);
    }
    /*
     * ★ "세션을 끊는다"는 **리프레시와 액세스 토큰 둘 다**여야 한다.
     *
     * 리프레시만 끊으면 액세스 토큰이 발급 시점의 role 을 들고 수명(기본 30분)만큼 통한다.
     * 역할 변경·비밀번호 재설정·정지·탈퇴가 모두 이 메서드를 "세션을 끊는다"는 뜻으로 부르는데,
     * 절반만 끊고 있었다(적대적 리뷰 2026-09-17 2라운드). 호출부마다 챙기면 또 빠뜨리므로
     * 길목인 여기서 함께 하고, 그 계약을 여기서 고정한다.
     */
    @Test
    @DisplayName("세션 전량 폐기는 액세스 토큰 취소선까지 긋는다")
    void revoke_all_cuts_access_tokens_too() {
        BDDMockito.given(repository.findByUserIdAndStatusForUpdate(
                        7L, RefreshTokenStatus.ACTIVE))
                .willReturn(List.of());
        givenAuthSnapshot(UserRole.USER, UserStatus.ACTIVE);

        service.revokeAllFor(7L);

        BDDMockito.then(blacklistedTokenStore).should().revokeAllBefore(
                ArgumentMatchers.eq("user-uuid"),
                ArgumentMatchers.eq(NOW),
                ArgumentMatchers.eq(Duration.ofMinutes(30)));
    }
}
