package com.kovil.domain.listing.service;

import com.kovil.domain.listing.entity.Listing;
import com.kovil.domain.listing.entity.ListingFavorite;
import com.kovil.domain.listing.enums.ListingStatus;
import com.kovil.domain.listing.exception.ListingException;
import com.kovil.domain.listing.repository.ListingFavoriteRepository;
import com.kovil.domain.listing.repository.ListingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kovil.domain.user.service.UserQueryService;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import static com.kovil.domain.listing.exception.ListingErrorCode.LISTING_NOT_FOUND;

@Slf4j
@Service
@RequiredArgsConstructor
public class ListingFavoriteService {

    private static final List<ListingStatus> VISIBLE =
            List.of(ListingStatus.ACTIVE, ListingStatus.RESERVED);

    private final ListingFavoriteRepository favoriteRepository;
    private final ListingRepository listingRepository;
    private final UserQueryService userQueryService;
    private final Clock clock;

    /*
     * 찜을 켠다. **이미 켜져 있으면 아무것도 하지 않는다** — 같은 요청이 두 번 와도
     * 카운터가 두 번 오르면 안 된다(연타·재시도는 흔하다).
     *
     * ★ 공개 상태인 매물만 찜할 수 있다. 내려간 매물을 찜하게 두면 "내 찜 목록"에
     *   볼 수 없는 매물이 쌓인다.
     */
    @Transactional
    public void add(String userUuid, String listingUuid) {
        Long userId = userQueryService.getActiveUser(userUuid).getId();
        Listing listing = listingRepository.findPublicByUuid(listingUuid, VISIBLE)
                .orElseThrow(() -> new ListingException(LISTING_NOT_FOUND));

        /*
         * ★★ **검사하지 않고 넣고, 센다.** 두 단계 모두 예외가 나지 않는다.
         *
         *    ① exists→insert 는 창이 있고, 그 창을 통과한 PK 위반은 같은 트랜잭션 안에서 잡아도
         *       **커밋에서 UnexpectedRollbackException** 으로 터진다(실측 2026-10-06).
         *       그래서 `ON DUPLICATE KEY UPDATE` 로 **이미 있으면 아무 일도 하지 않는다.**
         *    ② 카운터는 **반환값으로 판정하지 않고 행 수로 다시 센다.** MySQL JDBC 가 기본값에서
         *       matched rows 를 돌려줘 중복에도 1 이 오기 때문이다 — 그걸 믿고 +1 했더니
         *       40회 동시 찜에 카운터가 **40** 이 됐다(실측 2026-10-06).
         *
         * ★ 두 번 틀린 끝에 남은 교훈: **반환값의 의미를 추측하지 말고 최종 상태를 세라.**
         */
        favoriteRepository.insertIfAbsent(userId, listing.getId(), LocalDateTime.now(clock));
        listingRepository.syncFavoriteCount(listing.getId());
    }

    /*
     * 찜을 끈다. 켜져 있지 않으면 카운터를 건드리지 않는다 —
     * 없는 것을 지우면서 카운터만 줄이면 **음수로 간다.**
     *
     * ★ 내려간 매물도 끌 수 있어야 한다(findPublicByUuid 를 쓰지 않는 이유) —
     *   공개가 끝난 매물이 찜 목록에 영원히 박히면 사용자가 지울 길이 없다.
     */
    @Transactional
    public void remove(String userUuid, String listingUuid) {
        Long userId = userQueryService.getActiveUser(userUuid).getId();
        Listing listing = listingRepository.findByUuid(listingUuid)
                .orElseThrow(() -> new ListingException(LISTING_NOT_FOUND));

        /*
         * ★ 해제도 같은 모양이다 — 벌크 DELETE(예외 없음) 뒤에 **행 수로 다시 센다.**
         *   파생 delete 는 엔티티를 읽어 지우다가 동시 해제에서 Hibernate 의 행 수 검사에 걸려
         *   **409** 가 났다(실측 2026-10-06: 40회 중 9건).
         */
        favoriteRepository.deleteOne(userId, listing.getId());
        listingRepository.syncFavoriteCount(listing.getId());
    }

    /*
     * 탈퇴하면 찜을 지운다(PO 확정 2026-10-05).
     *
     * ★ "재가입은 되지만 이전 데이터는 볼 수 없다"는 기존 정책과 같은 축이다. 찜은 개인 취향
     *   데이터라 익명화로 가릴 수 있는 것이 아니다 — 남겨 두면 같은 번호로 돌아온 사람에게
     *   **전 계정의 관심 매물이 그대로 뜬다.**
     *
     * ★★ 지우기 **전에** 어느 매물이었는지 읽어야 한다. 먼저 지우면 카운터를 되돌릴 대상을
     *    영영 알 수 없고, 아무도 찜하지 않은 매물에 숫자만 남는다.
     */
    @Transactional
    public void purgeOnWithdrawal(Long userId) {
        List<Long> listingIds = favoriteRepository.findListingIdsByUserIdForUpdate(userId);
        if (listingIds.isEmpty()) {
            return;
        }
        listingRepository.decrementFavoriteCount(listingIds);
        favoriteRepository.deleteAllByUserId(userId);
        log.info("[listing] 탈퇴 찜 정리 user={} {}건", userId, listingIds.size());
    }
}
