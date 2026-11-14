package com.kovil.domain.user.service;

import com.kovil.common.dto.response.PageResponse;
import com.kovil.domain.auth.exception.PhoneVerificationException;
import com.kovil.domain.community.repository.CommunityPostRepository;
import com.kovil.domain.community.repository.CommunityReportRepository;
import com.kovil.domain.refreshtoken.service.RefreshTokenService;
import com.kovil.domain.rentalrequest.enums.RentalRequestStatus;
import com.kovil.domain.rentalrequest.repository.RentalRequestRepository;
import com.kovil.domain.rentalrequest.service.RentalRequestPoolReturner;
import com.kovil.domain.user.dto.response.AdminPhoneRevealResponse;
import com.kovil.domain.user.dto.response.AdminUserDetailResponse;
import com.kovil.domain.user.dto.response.AdminUserStatusHistoryResponse;
import com.kovil.domain.user.dto.response.AdminUserSummaryResponse;
import com.kovil.domain.user.entity.AdminPhoneReveal;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.entity.UserStatusHistory;
import com.kovil.domain.user.entity.UserAuth;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.user.exception.UserErrorCode;
import com.kovil.domain.user.exception.UserException;
import com.kovil.domain.user.repository.AdminPhoneRevealRepository;
import com.kovil.domain.user.repository.UserAuthRepository;
import com.kovil.domain.user.repository.UserRepository;
import com.kovil.domain.user.repository.UserStatusHistoryRepository;
import com.kovil.domain.verification.entity.ForeignerVerification;
import com.kovil.domain.verification.service.VerificationQueryService;
import com.kovil.infra.crypto.Encryptor;
import com.kovil.domain.auth.service.PhoneNumberNormalizer;
import com.kovil.infra.crypto.Hasher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import static com.kovil.domain.user.exception.UserErrorCode.SELF_SUSPEND;
import static com.kovil.domain.user.exception.UserErrorCode.USER_NOT_FOUND;

// FR-907 — 조회·정지. 정지의 효과는 로그인·API 전면 차단이다(관문은 getActiveUser).
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUserService {

    private static final List<RentalRequestStatus> OPEN_REQUESTS =
            List.of(RentalRequestStatus.SUBMITTED, RentalRequestStatus.IN_PROGRESS);

    private final UserRepository userRepository;
    private final UserAuthRepository userAuthRepository;
    private final UserStatusHistoryRepository userStatusHistoryRepository;
    private final UserQueryService userQueryService;
    private final VerificationQueryService verificationQueryService;
    private final RefreshTokenService refreshTokenService;
    private final AccountBlockService accountBlockService;
    private final RentalRequestRepository rentalRequestRepository;
    private final CommunityPostRepository communityPostRepository;
    private final CommunityReportRepository communityReportRepository;
    private final AdminPhoneRevealRepository phoneRevealRepository;
    private final Encryptor encryptor;
    private final Hasher hasher;
    private final PhoneNumberNormalizer phoneNumberNormalizer;
    private final RentalRequestPoolReturner rentalRequestPoolReturner;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PageResponse<AdminUserSummaryResponse> search(UserStatus status, UserRole role,
                                                         String query, Pageable pageable) {
        String normalized = query == null || query.isBlank() ? null : query.strip();
        // 검색어가 전체 전화번호로 정규화되면 해시 완전일치 축으로 보낸다 — 번호는 암호문이라
        // LIKE 로는 영영 못 찾는다. 정규화는 가입과 같은 경로다(표기 차이 = 다른 해시, INV-01).
        String phoneHash = null;
        if (normalized != null) {
            try {
                phoneHash = hasher.hash(phoneNumberNormalizer.normalize(normalized));
                normalized = null;
            } catch (PhoneVerificationException e) {
                // 전화번호 모양이 아니다 — 텍스트 축으로 그대로 간다.
            }
        }
        return PageResponse.from(userRepository.searchForAdmin(status, role, normalized, phoneHash, pageable)
                .map(user -> new AdminUserSummaryResponse(
                        user.getUuid(), user.getEmail(), user.getName(), user.getNickname(),
                        user.getRole(), user.getStatus(), user.getPreferredLanguage(),
                        user.getCreatedAt())));
    }

    @Transactional(readOnly = true)
    public AdminUserDetailResponse getDetail(String userUuid) {
        User user = getForRead(userUuid);
        String verificationStatus = verificationQueryService.findEffective(user.getId())
                .map(ForeignerVerification::getStatus)
                .map(Enum::name)
                .orElse(null);

        return new AdminUserDetailResponse(
                user.getUuid(), user.getEmail(), user.getName(), user.getNickname(),
                user.getRole(), user.getStatus(), user.getPreferredLanguage(),
                verificationStatus,
                userAuthRepository.findByUserIdAndDeletedAtIsNull(user.getId()).stream()
                        .map(UserAuth::getAuthType).map(Enum::name).toList(),
                user.getSuspendedAt(), user.getSuspendReason(),
                new AdminUserDetailResponse.Activity(
                        rentalRequestRepository.countByUserIdAndStatusIn(user.getId(), OPEN_REQUESTS),
                        communityPostRepository.countByUserIdAndDeletedAtIsNull(user.getId()),
                        communityReportRepository.countReceivedBy(user.getId())),
                user.getCreatedAt());
    }

    // 평문 열람은 이 경로 하나뿐이고, 호출이 곧 기록이다.
    // 상세 응답에 번호를 실어 두면 화면을 열기만 해도 평문이 흘러 기록이 무의미해진다.
    @Transactional
    public AdminPhoneRevealResponse revealPhone(String adminUuid, String userUuid) {
        User admin = userQueryService.getActiveUser(adminUuid);
        User target = getManaged(userUuid);

        phoneRevealRepository.save(AdminPhoneReveal.builder()
                .adminUserId(admin.getId())
                .targetUserId(target.getId())
                .build());

        log.info("[admin] 전화번호 열람 target={} by={}", userUuid, admin.getId());
        return new AdminPhoneRevealResponse(
                target.getPhone() == null ? null : encryptor.decrypt(target.getPhone()),
                phoneRevealRepository.countByTargetUserId(target.getId()));
    }

    // 멱등 — 이미 정지된 계정을 다시 정지해도 조용히 지나간다.
    @Transactional
    public void suspend(String adminUuid, String userUuid, String reason) {
        User admin = userQueryService.getActiveUser(adminUuid);
        User target = getManaged(userUuid);
        if (target.getId().equals(admin.getId())) {
            throw new UserException(SELF_SUSPEND);
        }
        if (target.getStatus() == UserStatus.SUSPENDED) {
            return;
        }
        // ★ 정지도 관리자를 줄인다 — 세는 쪽이 ACTIVE 만 세므로 정지된 관리자는 빠진다.
        //
        //   오늘은 이 가드가 발동하지 않는다(정지 권한이 ADMIN 뿐이라 행위자+대상 = 둘 이상).
        //   그래도 남기는 이유는 UserErrorCode.LAST_ADMIN_CANNOT_BE_SUSPENDED 주석에 있다 —
        //   USER_MANAGE 를 가진 비-ADMIN 역할이 생기는 순간 이것이 유일한 방어가 된다.
        if (target.getRole() == UserRole.ADMIN
                && userRepository.findIdsByRoleForUpdate(UserRole.ADMIN).size() <= 1) {
            throw new UserException(UserErrorCode.LAST_ADMIN_CANNOT_BE_SUSPENDED);
        }
        UserStatus previous = target.getStatus();
        String trimmedReason = trimToNull(reason);
        LocalDateTime now = LocalDateTime.now(clock);
        target.suspend(trimmedReason, now);

        // ★ 신원을 차단 원장에 올린다. 이게 없으면 정지 → 탈퇴 → 재가입이 세탁 경로가 된다 —
        //   탈퇴가 phone_hash 와 user_auth 를 풀어 주기 때문이다(AccountBlockService 주석).
        accountBlockService.blockIdentitiesOf(target, admin.getId(), trimmedReason);
        accountBlockService.recordStatusChange(target.getId(), previous, UserStatus.SUSPENDED,
                admin.getId(), UserStatusHistory.ACTOR_ADMIN, trimmedReason);

        // 살아 있는 세션도 끊는다 — 액세스 토큰은 만료까지 남지만 getActiveUser가 막는다.
        refreshTokenService.revokeAllFor(target.getId());

        // ★ 사무소 대표자(파트너)면 그 사무소가 맡은 진행 중 임대 요청을 대기로 되돌린다 — 정지된 사람은 연락을 이어 갈 수 없다
        //   (부문 정지와 같은 판단, 공개 매물이 계정 정지로 숨는 것과 짝). 정지를 풀어도 되찾지 않는다.
        //   세션을 끊은 **뒤**에 둔다 — 거기서 이 사람의 users 행을 잡고, 되돌리기는 그 뒤에 사무소 → 요청 행 순으로 잡는다.
        if (target.getRole() == UserRole.PARTNER) {
            rentalRequestPoolReturner.returnHeldByRepresentative(target.getId(), now);
        }
        log.info("[admin] 회원 정지 user={} by={}", userUuid, admin.getId());
    }

    @Transactional
    public void unsuspend(String adminUuid, String userUuid) {
        User admin = userQueryService.getActiveUser(adminUuid);
        User target = getManaged(userUuid);
        if (target.getStatus() != UserStatus.SUSPENDED) {
            return;
        }
        target.unsuspend();
        // 차단도 함께 푼다. 행은 지우지 않고 해제로 남긴다 — 지우면 왜 풀렸는지가 사라진다.
        accountBlockService.releaseIdentitiesOf(target.getId(), admin.getId());
        accountBlockService.recordStatusChange(target.getId(), UserStatus.SUSPENDED, UserStatus.ACTIVE,
                admin.getId(), UserStatusHistory.ACTOR_ADMIN, null);
        log.info("[admin] 정지 해제 user={} by={}", userUuid, admin.getId());
    }

    /**
     * 상태 전이 이력 (관리자 전용).
     *
     * ★ unsuspend 가 users.suspend_reason 을 NULL 로 지운다. 해제 뒤 "왜 정지했었나"를 답할 수
     *   있는 곳은 이 원장뿐인데 읽는 경로가 없었다 — 쌓기만 하고 안 읽으면 없는 것과 같다.
     *   reason 은 관리자 내부 메모이므로 **사용자 응답에는 절대 싣지 않는다**(PO 확정 2026-09-02).
     */
    @Transactional(readOnly = true)
    public List<AdminUserStatusHistoryResponse> getStatusHistories(String userUuid) {
        User user = getForRead(userUuid);
        return userStatusHistoryRepository.findByUserIdOrderByIdDesc(user.getId()).stream()
                .map(history -> new AdminUserStatusHistoryResponse(
                        history.getFromStatus(), history.getToStatus(),
                        history.getActorNote(), history.getReason(), history.getCreatedAt()))
                .toList();
    }

    // 정지 대상 조회는 getActiveUser를 쓰면 안 된다 — 정지된 유저를 못 찾게 된다.
    // 탈퇴자는 여기서 걸러진다 — 이미 떠난 계정을 정지·해제하는 것은 조치가 아니다.
    private User getManaged(String userUuid) {
        return userRepository.findByUuidAndDeletedAtIsNull(userUuid)
                .orElseThrow(() -> new UserException(USER_NOT_FOUND));
    }

    /**
     * 읽기 전용 조회 — **탈퇴자도 찾는다.**
     *
     * 목록이 status=WITHDRAWN 을 보여주게 됐으므로(UserRepository.searchForAdmin) 상세·이력도
     * 열려야 한다. 목록에 뜨는 행을 클릭하면 404 가 나는 화면은 고장난 화면이다.
     * 변경 경로(getManaged)는 그대로 탈퇴자를 막는다.
     */
    private User getForRead(String userUuid) {
        return userRepository.findByUuid(userUuid)
                .orElseThrow(() -> new UserException(USER_NOT_FOUND));
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
