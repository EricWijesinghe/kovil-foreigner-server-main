package com.kovil.domain.user.service;

import com.kovil.domain.user.entity.BlockedIdentity;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.entity.UserStatusHistory;
import com.kovil.domain.user.enums.BlockedIdentityType;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.user.repository.BlockedIdentityRepository;
import com.kovil.domain.user.repository.UserStatusHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 재가입 차단 원장과 상태 이력의 소유자.
 *
 * ★ 왜 원장을 따로 두는가 — 탈퇴는 신원을 풀어 준다(phone_hash 해제, user_auth 해제).
 *   그래야 재가입이 되기 때문인데, 그 해제가 정지된 사람에게도 열리면
 *   **정지 → 탈퇴 → 재가입**이 그대로 세탁이 된다. 신원을 계속 붙잡아 막으면 탈퇴자가
 *   식별자를 계속 들고 있게 되므로, 탈퇴는 허용하고 막을 신원만 이 원장으로 옮긴다.
 *
 * ★ 축은 **전화번호 하나다**(PO 확정 2026-09-01). 우리는 소셜 가입도 휴대폰 인증을 강제하고
 *   (FR-104), 계정 생성과 소셜 연결이 전부 SignUpProcessor.loadUnconsumed 한 곳을 지난다 —
 *   **번호가 이미 단일 관문**이라 수단 축을 따로 막을 필요가 없다.
 *
 *   수단 축이 없어도 새는 곳이 없다: 정지 계정은 탈퇴가 불가능하므로(getActiveUser) user_auth 가
 *   항상 살아 있고, 같은 소셜로 오면 기존 조회가 그 계정을 집어 SUSPENDED_USER 로 끊는다.
 *   수단 축이 실제로 필요해지는 순간은 **탈퇴한 계정을 정지할 수 있게 될 때**다 —
 *   그때는 blockIdentitiesOf 에 수단 등록을 되살리고 가입 경로에 isAuthBlocked 를 다시 걸면 된다
 *   (원장 스키마는 identity_type 축을 이미 갖고 있어 마이그레이션이 필요 없다).
 */
@Service
@RequiredArgsConstructor
public class AccountBlockService {

    private final BlockedIdentityRepository blockedIdentityRepository;
    private final UserStatusHistoryRepository userStatusHistoryRepository;
    private final Clock clock;

    /** 정지 — 그 계정의 번호를 원장에 올린다. 탈퇴해도 이 행이 남아 재가입을 막는다. */
    @Transactional
    public void blockIdentitiesOf(User target, Long adminId, String reason) {
        if (target.getPhoneHash() == null) {
            return;
        }
        block(BlockedIdentityType.PHONE, target.getPhoneHash(), target.getId(), adminId, reason,
                LocalDateTime.now(clock));
    }

    /** 정지 해제 — 그 계정에서 비롯된 활성 차단을 함께 푼다. 행은 지우지 않는다. */
    @Transactional
    public void releaseIdentitiesOf(Long userId, Long adminId) {
        LocalDateTime now = LocalDateTime.now(clock);
        blockedIdentityRepository.findActiveBySourceUserId(userId)
                .forEach(blocked -> blocked.release(adminId, now));
    }

    /** 번호 축 판정. 가입·재가입 전에 부른다. */
    @Transactional(readOnly = true)
    public boolean isPhoneBlocked(String phoneHash) {
        return phoneHash != null
                && blockedIdentityRepository.existsActive(BlockedIdentityType.PHONE, phoneHash);
    }

    /** 상태 전이 기록. users.status 는 한 칸이라 여기 쌓지 않으면 과거가 사라진다. */
    @Transactional
    public void recordStatusChange(Long userId, UserStatus from, UserStatus to,
                                   Long changedBy, String actorNote, String reason) {
        userStatusHistoryRepository.save(UserStatusHistory.builder()
                .userId(userId)
                .fromStatus(from)
                .toStatus(to)
                .changedBy(changedBy)
                .actorNote(actorNote)
                .reason(reason)
                .build());
    }

    /**
     * 멱등 삽입. 이미 활성 차단이 있으면 0행으로 흡수된다.
     *
     * ★ 유니크 충돌을 catch 로 잡으면 안 된다 — 위반이 트랜잭션을 rollback-only 로 만들어
     *   삼키고 진행해도 커밋이 죽고 **정지 자체가 함께 롤백된다.** 이 저장소가 이미 두 번
     *   같은 수리를 했다(CommunityBlockService · CommunityLikeService).
     */
    private void block(BlockedIdentityType type, String value, Long sourceUserId,
                       Long adminId, String reason, LocalDateTime now) {
        blockedIdentityRepository.insertIgnore(
                type.name(), value, reason, sourceUserId, adminId, now);
    }
}
