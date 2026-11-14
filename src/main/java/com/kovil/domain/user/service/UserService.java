package com.kovil.domain.user.service;

import com.kovil.domain.country.repository.CountryRepository;
import com.kovil.domain.device.service.DeviceTokenService;
import com.kovil.domain.language.service.LanguageQueryService;
import com.kovil.domain.listing.service.BrokerOffboardingService;
import com.kovil.domain.listing.service.ListingFavoriteService;
import com.kovil.domain.listing.service.SavedAreaService;
import com.kovil.domain.partner.service.PartnerWithdrawalService;
import com.kovil.domain.refreshtoken.service.RefreshTokenService;
import com.kovil.domain.rentalrequest.service.RentalRequestService;
import com.kovil.domain.user.dto.request.ConsentRequest;
import com.kovil.domain.user.dto.request.UpdateMeRequest;
import com.kovil.domain.user.dto.response.ConsentResponse;
import com.kovil.domain.user.dto.response.MeResponse;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.entity.UserConsent;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.user.entity.UserStatusHistory;
import com.kovil.domain.user.exception.UserErrorCode;
import com.kovil.domain.file.service.FileDeletionService;
import com.kovil.domain.user.exception.UserException;
import com.kovil.domain.verification.service.VerificationPurgeService;
import com.kovil.domain.user.repository.UserAuthRepository;
import com.kovil.domain.user.entity.UserAuth;
import com.kovil.domain.user.enums.AuthType;
import com.kovil.common.tx.AfterCommitRunner;
import com.kovil.infra.crypto.Encryptor;
import com.kovil.infra.oauth.apple.AppleTokenExchanger;
import com.kovil.domain.user.repository.UserConsentRepository;
import com.kovil.domain.user.repository.UserRepository;
import com.kovil.domain.user.service.support.NicknamePolicy;
import com.kovil.common.exception.CommonErrorCode;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import static com.kovil.common.exception.CommonErrorCode.INVALID_REQUEST;
import static com.kovil.domain.user.exception.UserErrorCode.NICKNAME_TAKEN;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final UserQueryService userQueryService;
    private final CountryRepository countryRepository;
    private final LanguageQueryService languageQueryService;
    private final NicknamePolicy nicknamePolicy;
    private final NicknameService nicknameService;
    private final UserAuthRepository userAuthRepository;
    private final AccountBlockService accountBlockService;
    private final UserConsentRepository userConsentRepository;
    private final RefreshTokenService refreshTokenService;
    private final DeviceTokenService deviceTokenService;
    private final FileDeletionService fileDeletionService;
    private final VerificationPurgeService verificationPurgeService;
    private final RentalRequestService rentalRequestService;
    private final ListingFavoriteService listingFavoriteService;
    private final SavedAreaService savedAreaService;
    private final PartnerWithdrawalService partnerWithdrawalService;
    private final BrokerOffboardingService brokerOffboardingService;
    private final AppleTokenExchanger appleTokenExchanger;
    private final Encryptor encryptor;
    private final Clock clock;

    @Transactional
    public MeResponse updateMe(String userUuid, UpdateMeRequest request) {
        User user = userQueryService.getActiveUser(userUuid);

        if (request.name() != null) {
            user.changeName(request.name().isBlank() ? null : request.name().trim());
        }
        if (request.preferredLanguage() != null) {
            if (!languageQueryService.isSupported(request.preferredLanguage())) {
                throw new UserException(INVALID_REQUEST);
            }
            user.changePreferredLanguage(request.preferredLanguage());
        }
        if (request.nationalityCountryCode() != null) {
            user.changeNationality(countryRepository
                    .findByCodeAndIsActiveTrue(request.nationalityCountryCode().toUpperCase())
                    .orElseThrow(() -> new UserException(INVALID_REQUEST))
                    .getId());
        }
        if (request.nickname() != null) {
            changeNickname(user, request.nickname());
        }

        return userQueryService.getMe(userUuid);
    }

    // FR-108 — 형식·금칙어는 정책이 보고, 중복은 여기서 본다.
    // 최종 방어선은 uq_users_nickname_normalized이고 이 확인은 사전 컷이다.
    private void changeNickname(User user, String raw) {
        String normalized = nicknamePolicy.validate(raw);
        if (!normalized.equals(user.getNicknameNormalized())
                && userRepository.existsByNicknameNormalized(normalized)) {
            throw new UserException(NICKNAME_TAKEN);
        }
        user.changeNickname(raw.trim(), normalized, true, LocalDateTime.now(clock));
        // 최종 방어선이 터졌을 때의 번역. 사전 컷과 커밋 사이에 같은 닉네임이 먼저 들어오면
        // uq_users_nickname_normalized 가 잡는데, 그대로 두면 진 쪽에만 500 이 나간다 —
        // 순차 답(NICKNAME_TAKEN)과 경합 답이 갈린다(적대적 리뷰 2026-09-02).
        // saveAndFlush 여야 위반이 커밋이 아니라 여기서 터져 try 에 잡힌다.
        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            if (isNicknameUniqueViolation(e)) {
                throw new UserException(NICKNAME_TAKEN);
            }
            throw e;
        }
    }

    private boolean isNicknameUniqueViolation(DataIntegrityViolationException e) {
        return e.getCause() instanceof ConstraintViolationException violation
                && violation.getConstraintName() != null
                && violation.getConstraintName().toLowerCase().contains("uq_users_nickname_normalized");
    }

    // FR-111 — 철회도 UPDATE가 아니라 새 행이다. 기존 행은 절대 건드리지 않는다.
    @Transactional
    public ConsentResponse recordConsent(String userUuid, ConsentRequest request) {
        User user = userQueryService.getActiveUser(userUuid);

        return ConsentResponse.from(userConsentRepository.save(UserConsent.builder()
                .userId(user.getId())
                .consentType(request.consentType())
                .agreed(request.agreed())
                .version(request.version())
                .build()));
    }

    // Apple 이 연결 철회 · 계정 삭제를 알려 왔을 때. 세션을 끊고 저장한 Apple 토큰을 지운다. 연결(user_auth)은 남긴다.
    // 그 토큰은 이미 Apple 이 무효로 했으므로 철회를 보내지 않는다. 연결된 회원이 없으면 false 다.
    //   락킹 순서: 세션 끊기(refresh_tokens → users)를 먼저 하고 user_auth 를 마지막에 쓴다. 로그인(발급 → user_auth 갱신)과 같은 순서다.
    //   user_auth 는 잠그지 않고 읽으므로 엔티티로 고치지 않는다. 겹친 탈퇴의 deleted_at 을 덮는 이유는 저장소 쪽 주석에 있다.
    @Transactional
    public boolean endAppleSession(String appleSub) {
        return userAuthRepository.findByAuthTypeAndIdentifierAndDeletedAtIsNull(AuthType.APPLE, appleSub)
                .map(auth -> {
                    refreshTokenService.revokeAllFor(auth.getUser().getId());
                    userAuthRepository.clearProviderRefreshTokenIfLinked(auth.getId());
                    return true;
                })
                .orElse(false);
    }

    // FR-110 — PII를 지우고 번호 슬롯을 풀어 재가입을 허용한다.
    // 임대 요청·커뮤니티 이력은 FK가 잡고 있어 그대로 남는다.
    @Transactional
    public void withdraw(String userUuid) {
        User user = userQueryService.getActiveUser(userUuid);

        // ★ 관리자를 0명으로 만드는 탈퇴는 거절한다. 역할 변경에는 같은 가드가 있는데
        //   탈퇴에는 없어서, 마지막 관리자가 DELETE /me 한 번으로 아무도 못 들어가는 상태를
        //   만들 수 있었다 — 되돌릴 화면이 없어 DB 를 직접 고쳐야 한다(적대적 리뷰 2026-09-17).
        //   잠금판으로 읽는 이유는 findIdsByRoleForUpdate 주석에 있다.
        if (user.getRole() == UserRole.ADMIN
                && userRepository.findIdsByRoleForUpdate(UserRole.ADMIN).size() <= 1) {
            throw new UserException(UserErrorCode.LAST_ADMIN_CANNOT_WITHDRAW);
        }
        // ★ 무엇이든 쓰기 전에 내 users 행을 배타 잠금으로 잡는다 — 파트너 쪽 쓰기의 순서(users → 사무소 → 변경 신청 → 부문)를
        //   탈퇴도 코드에 그대로 드러낸다. 안 잡으면 이 행은 아래 상태 이력 INSERT 의 FK 검사로 S 가 먼저 잡히고 한참 뒤 flush 의
        //   UPDATE 에서 X 로 오른다 — 같은 계정의 탈퇴 두 요청이 겹치면 둘 다 S 를 쥔 채 X 를 기다려 교착한다(MySQL 9.5 재현).
        //   처음부터 X 면 뒤의 요청이 줄을 선다. users 를 먼저 잡는 변경 신청 제출 · 변경 승인 · 반려도 여기서 줄을 서고,
        //   탈퇴가 커밋된 뒤 탈퇴한 계정 · 닫힌 대기 건을 보고 물러난다.
        //   관리자 가드 **뒤에** 둔다 — 가드가 관리자 행들을 한 번에 잠그는데, 그 앞에서 내 행만 먼저 잡으면 두 관리자의
        //   동시 탈퇴가 서로의 행을 쥔 채 기다린다.
        //   ⚠ 아래 세션 끊기(revokeAllFor)는 refresh_tokens 를 users 뒤에 잡는다 — 토큰 발급(refresh_tokens → users)과는
        //     순서가 반대다. 이 줄과 무관하게 전부터 그랬고(상태 이력 INSERT 의 S 잠금이 같은 자리였다), 본인이 탈퇴하는 그
        //     순간에 본인 토큰이 발급 · 회전돼야 성립한다. MySQL 이 한쪽을 즉시 되돌린다.
        userRepository.findAuthSnapshotForUpdate(user.getId());
        partnerWithdrawalService.assertWithdrawable(user.getId());

        LocalDateTime now = LocalDateTime.now(clock);

        // 사용자가 직접 넣은 닉네임은 실명일 수 있다. 자동 생성값으로 갈아 PII를 지우면서도
        // 커뮤니티 글의 표시명은 남긴다(비우면 "NULL은 발급 실패의 흔적" 규약과 충돌한다).
        if (Boolean.TRUE.equals(user.getIsNicknameCustom())) {
            NicknameService.Allocation reissued = nicknameService.allocate();
            user.changeNickname(reissued.nickname(), reissued.normalized(), false, now);
            // 사다리 소진 시 allocate 는 점유된 후보를 알면서 반환한다 — 무번역이면 그 상태에서
            // 탈퇴가 확정 500 으로 통째 막힌다. 재시도하면 다른 후보가 뽑히니 CONFLICT 가 답이다
            // (가입 경로의 translateSharedRace 와 같은 판단, 적대적 리뷰 2026-09-02).
            try {
                userRepository.saveAndFlush(user);
            } catch (DataIntegrityViolationException e) {
                if (isNicknameUniqueViolation(e)) {
                    throw new UserException(
                            CommonErrorCode.CONFLICT);
                }
                throw e;
            }
        }

        // ★ withdraw 가 profileImageUrl 을 null 로 지운다 — **지우기 전에** 집어야 한다.
        //   나중에 읽으면 언제나 빈 값이라 사진이 폐기 표시 없이 R2 에 영원히 남는다.
        String profileImageUrl = user.getProfileImageUrl();

        UserStatus previous = user.getStatus();
        user.withdraw(now);
        // 탈퇴도 상태 전이다 — 남기지 않으면 "언제 왜 사라졌나"를 답할 수 없다.
        accountBlockService.recordStatusChange(user.getId(), previous, UserStatus.WITHDRAWN,
                user.getId(), UserStatusHistory.ACTOR_SELF, null);

        // ★ 로그인 수단을 해제한다. 행은 남긴다(PO 확정 2026-09-01 — 하드 삭제 금지).
        //    해제하지 않으면 같은 구글 계정으로 재가입할 때 옛 user_auth 가 그대로 매치되어
        //    탈퇴한 계정을 집어 들고 USER_NOT_ACTIVE 로 막힌다 — 영구히 재가입 불가가 된다.
        //    식별자는 **그대로 둔다** — 재가입 때 정지 이력을 조회하는 키다.
        //    유니크 슬롯은 저절로 풀린다 — 유니크가 살아 있는 행에만 걸려 있어(V19)
        //    deleted_at 이 찍히는 순간 이 행은 슬롯을 놓는다. 재가입은 새 행을 넣는다.
        //
        //    정지 계정은 여기까지 오지 못한다 — getActiveUser 가 SUSPENDED 를 막으므로
        //    탈퇴로 정지를 털고 재가입하는 경로는 없다(PO 확정 2026-09-01).
        //
        //    Apple 은 해제와 함께 저장해 둔 refresh_token 을 지우고, 커밋 뒤에 Apple 에 철회를 보낸다.
        //    외부 호출이라 롤백돼도 되돌릴 수 없어서 커밋 뒤다. 복호화도 그때 한다. 실패해도 탈퇴는 막지 않는다.
        //    철회는 그 토큰을 낸 client_id(앱 번들 ID · 웹 Services ID)로 보내야 Apple 이 받아 준다.
        for (UserAuth auth : userAuthRepository.findAllByUserId(user.getId())) {
            if (auth.getAuthType() == AuthType.APPLE && auth.getProviderRefreshToken() != null) {
                String encrypted = auth.getProviderRefreshToken();
                String clientId = auth.getProviderClientId();
                AfterCommitRunner.run("apple-revoke",
                        () -> appleTokenExchanger.revoke(encryptor.decrypt(encrypted), clientId));
                auth.clearProviderRefreshToken();
            }
            auth.unlink(now);
        }

        // ★ 열린 건은 전부 닫는다(PO 확정 2026-09-29). 남겨 두면 사무소들의 대기 목록에 **연락이 닿지
        //   않는 행**이 계속 떠 있고, 그 행을 잡으려는 시도가 전부 헛일이 된다.
        rentalRequestService.cancelOpenOnWithdrawal(user.getId());

        // ★ 찜은 **지운다**(PO 확정 2026-10-05). 익명화로 가릴 수 있는 것이 아니라서다 —
        //   남겨 두면 같은 번호로 돌아온 사람에게 **전 계정의 관심 매물이 그대로 뜬다.**
        //   카운터도 같이 되돌린다 — 안 하면 아무도 찜하지 않은 매물에 숫자가 남는다.
        listingFavoriteService.purgeOnWithdrawal(user.getId());

        // ★ 파트너였다면 부문 쪽을 닫는다 — 대기 중인 부문은 반려로 닫고, 사무소가 잡고 있던 등록번호를 놓고
        //   (같은 번호로 새 사람이 가입할 수 있게), 등록증 사본을 폐기 표시한다(PO 확정 2026-10-06).
        //   ★★ 아래 사무소 이탈보다 **먼저** 부른다. 사무소는 대표자 연결로 찾는데 이탈이 그 연결을 지운다 —
        //      순서가 바뀌면 사무소를 못 찾아 번호가 영영 잡힌 채 남고 등록증도 남는다.
        //   파트너가 아니면 아무 일도 하지 않는다.
        partnerWithdrawalService.purgeOnWithdrawal(user.getId());

        // ★ 공인중개사 파트너였다면 사무소에서 뗀다. 그 사무소에 남은 중개사가 없으면 공개 매물을 내린다
        //   (PO 확정 2026-10-06) — 관리할 사람 없는 광고가 만료까지 떠 있지 않게.
        //   부문 행(partner_divisions)은 지우지 않는다 — 누가 언제 승인됐었는지의 이력이다.
        //   중개사가 아니면 아무 일도 하지 않는다.
        brokerOffboardingService.offboard(user.getId());

        // ★ 저장한 구역도 찜과 같은 이유로 지운다(PO 확정 2026-10-13).
        //   파트너 정리 · 사무소 이탈 **뒤에** 둔다 — 이 DELETE 가 saved_areas 에 갭 잠금을 거는데, 그 뒤에 남의 users 행을
        //   기다리면(파트너 정리의 알림 INSERT) 그 사람의 첫 저장(users FOR UPDATE → INSERT)과 교착한다.
        savedAreaService.purgeOnWithdrawal(user.getId());

        // 남아 있는 세션을 전부 끊는다 — 탈퇴했는데 토큰이 계속 통하면 안 된다.
        refreshTokenService.revokeAllFor(user.getId());

        // 푸시도 끊는다. users는 soft delete라 device_tokens의 ON DELETE CASCADE가 걸리지 않는다 —
        // 지우지 않으면 탈퇴한 사람의 기기로 월세 알림이 계속 간다.
        deviceTokenService.removeAllFor(user.getId());

        // ★ 파일을 버린다 — 표시만 하고 실제 이동·파기는 정리 배치와 R2 수명 규칙이 한다.
        //   여기서 스토리지를 건드리면 이 트랜잭션이 롤백돼도 사진은 이미 사라진다.
        //
        //   커뮤니티 글의 사진은 **버리지 않는다** — 글은 익명으로 남는다(계정 삭제 안내의
        //   파기 목록에도 없다). 사라지는 것은 프로필 사진과 신분 증빙뿐이다.
        fileDeletionService.discardByUrls(
                profileImageUrl == null ? List.of() : List.of(profileImageUrl));
        verificationPurgeService.purgeOnWithdrawal(user.getId());
    }
}
