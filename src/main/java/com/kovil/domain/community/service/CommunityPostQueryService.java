package com.kovil.domain.community.service;

import com.kovil.common.dto.response.PageResponse;
import com.kovil.domain.community.dto.response.PostDetailResponse;
import com.kovil.domain.community.dto.response.PostSummaryResponse;
import com.kovil.domain.community.entity.CommunityCategory;
import com.kovil.domain.community.entity.CommunityPost;
import com.kovil.domain.community.enums.CommunityContentStatus;
import com.kovil.domain.community.enums.PopularPeriod;
import com.kovil.domain.community.enums.PostSort;
import com.kovil.domain.community.exception.CommunityException;
import com.kovil.domain.community.repository.CommunityCategoryRepository;
import com.kovil.domain.community.repository.CommunityPostRepository;
import com.kovil.domain.community.repository.UserBlockRepository;
import com.kovil.domain.community.service.support.CommunityPopularProperties;
import com.kovil.domain.community.service.support.CommunityPopularRankCache;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.service.UserQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.Objects;

import static com.kovil.common.exception.CommonErrorCode.INVALID_REQUEST;
import static com.kovil.domain.community.exception.CommunityErrorCode.CATEGORY_NOT_FOUND;
import static com.kovil.domain.community.exception.CommunityErrorCode.POST_NOT_FOUND;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CommunityPostQueryService {

    private final UserQueryService userQueryService;
    private final CommunityCategoryRepository categoryRepository;
    private final CommunityPostRepository postRepository;
    private final UserBlockRepository blockRepository;
    private final CommunityPostAssembler assembler;
    private final CommunityViewCountBuffer viewCountBuffer;
    private final CommunityPopularRankCache rankCache;
    private final CommunityPopularProperties popularProperties;
    private final Clock clock;

    public PageResponse<PostSummaryResponse> findFeed(String userUuid, String categoryCode, String tag,
                                                      boolean myLanguageOnly, String query, PostSort sort,
                                                      PopularPeriod period, Pageable pageable) {
        User viewer = userQueryService.getActiveUser(userUuid);
        Long categoryId = resolveCategoryId(categoryCode);
        String languageCode = myLanguageOnly ? viewer.getPreferredLanguage() : null;

        String tagNormalized = tag == null || tag.isBlank()
                ? null : tag.strip().replaceFirst("^#", "").toLowerCase().replaceAll("\\s+", "");

        // 1자 검색은 LIKE 풀스캔 대비 변별력이 없다 — 계약(2자 이상)대로 거절한다.
        String q = query == null || query.isBlank() ? null : query.strip();
        if (q != null && q.length() < 2) {
            throw new CommunityException(INVALID_REQUEST);
        }

        if (sort == PostSort.POPULAR) {
            if (tagNormalized != null || q != null) {
                // 태그·검색 축은 랭킹 풀에 없다 — 조용히 최신순으로 바꿔치기하면 앱은 인기순인 줄 안다.
                throw new CommunityException(INVALID_REQUEST);
            }
            return findPopular(viewer, categoryId, languageCode, period, pageable);
        }

        Page<CommunityPost> page = tagNormalized == null
                ? postRepository.findFeed(CommunityContentStatus.ACTIVE, categoryId, languageCode,
                        q, viewer.getId(), pageable)
                : postRepository.findFeedByTag(CommunityContentStatus.ACTIVE, tagNormalized,
                        categoryId, languageCode, q, viewer.getId(), pageable);

        return PageResponse.from(new PageImpl<>(
                assembler.toSummaries(page.getContent(), viewer.getId()), pageable, page.getTotalElements()));
    }

    /**
     * 인기 = 캐시된 전역 랭킹 풀(필터 조합 키)에서의 페이지네이션. 총 건수는 전체 글이 아니라
     * "풀에서 내가 볼 수 있는 글"의 수다 — Top-N 계약이라 그렇게 읽어야 한다.
     * 랭킹은 최대 cache-ttl만큼 낡을 수 있지만, 삭제·블라인드·차단은 적재 후 걸러 즉시 반영된다.
     */
    private PageResponse<PostSummaryResponse> findPopular(User viewer, Long categoryId,
                                                          String languageCode, PopularPeriod period,
                                                          Pageable pageable) {
        PopularPeriod window = period == null ? PopularPeriod.WEEK : period;
        String key = window.name()
                + ":" + (categoryId == null ? "-" : categoryId)
                + ":" + (languageCode == null ? "-" : languageCode);

        List<Long> ranked = rankCache.getOrCompute(key, () -> postRepository.findPopularIds(
                CommunityContentStatus.ACTIVE.name(),
                LocalDateTime.now(clock).minusDays(window.days()),
                categoryId, languageCode,
                popularProperties.likeWeight(), popularProperties.commentWeight(),
                popularProperties.decayExponent(), popularProperties.poolSize()));
        if (ranked.isEmpty()) {
            return PageResponse.from(new PageImpl<>(List.of(), pageable, 0));
        }

        Set<Long> blockedIds = blockRepository.findBlockedUserIds(viewer.getId());
        // findAllById는 순서를 보장하지 않는다 — 랭킹 순서는 id 목록이 소유하므로 그 순서로 복원한다.
        // isReadable 재확인은 캐시 수명 중 삭제·블라인드된 글을 걸러낸다(관리자 조치가 TTL을 기다리지 않는다).
        Map<Long, CommunityPost> byId = postRepository.findAllById(ranked).stream()
                .collect(Collectors.toMap(CommunityPost::getId, Function.identity()));
        List<CommunityPost> visible = ranked.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .filter(CommunityPost::isReadable)
                .filter(post -> !blockedIds.contains(post.getUserId()))
                .toList();

        int from = (int) Math.min(pageable.getOffset(), visible.size());
        int to = Math.min(from + pageable.getPageSize(), visible.size());
        return PageResponse.from(new PageImpl<>(
                assembler.toSummaries(visible.subList(from, to), viewer.getId()),
                pageable, visible.size()));
    }

    // INV-11 — 상세 조회가 조회수를 올리는 유일한 곳이고, DB가 아니라 버퍼에 올린다.
    public PostDetailResponse getDetail(String userUuid, String postUuid) {
        User viewer = userQueryService.getActiveUser(userUuid);
        CommunityPost post = postRepository.findByUuid(postUuid)
                .filter(CommunityPost::isReadable)
                .orElseThrow(() -> new CommunityException(POST_NOT_FOUND));

        viewCountBuffer.increase(post.getId());
        return assembler.toDetail(post, viewer.getId());
    }

    // 익명 게시판에서 내 글의 수정·삭제 진입점 — 목록을 훑어 isMine을 찾게 할 수는 없다.
    public PageResponse<PostSummaryResponse> findMine(String userUuid, Pageable pageable) {
        User viewer = userQueryService.getActiveUser(userUuid);
        // 블라인드(HIDDEN)는 목록에서도 뺀다 — SPEC(§42-5, FR-714)이 고정한 "목록·상세 404" 다.
        // 넣으면 탭·수정·삭제가 전부 404 인 유령 카드가 내 글 목록에 영구히 남는다
        // (적대적 리뷰 2026-09-02).
        Page<CommunityPost> page = postRepository
                .findByUserIdAndStatusAndDeletedAtIsNullOrderByIdDesc(
                        viewer.getId(), CommunityContentStatus.ACTIVE, pageable);
        return PageResponse.from(new PageImpl<>(
                assembler.toSummaries(page.getContent(), viewer.getId()), pageable, page.getTotalElements()));
    }

    private Long resolveCategoryId(String categoryCode) {
        if (categoryCode == null || categoryCode.isBlank()) {
            return null;
        }
        return categoryRepository.findByCodeAndActiveTrue(categoryCode.strip())
                .map(CommunityCategory::getId)
                .orElseThrow(() -> new CommunityException(CATEGORY_NOT_FOUND));
    }
}
