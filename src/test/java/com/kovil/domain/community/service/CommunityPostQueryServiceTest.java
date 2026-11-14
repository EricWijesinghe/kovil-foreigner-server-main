package com.kovil.domain.community.service;

import com.kovil.domain.community.dto.response.CommentResponse;
import com.kovil.domain.community.dto.response.PostDetailResponse;
import com.kovil.domain.community.dto.response.PostSummaryResponse;
import com.kovil.domain.community.entity.CommunityPost;
import com.kovil.domain.community.enums.CommunityContentStatus;
import com.kovil.domain.community.exception.CommunityException;
import com.kovil.domain.community.repository.CommunityCategoryRepository;
import com.kovil.domain.community.repository.CommunityPostRepository;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.user.service.UserQueryService;
import com.kovil.domain.community.enums.PopularPeriod;
import com.kovil.domain.community.enums.PostSort;
import com.kovil.domain.community.repository.UserBlockRepository;
import com.kovil.domain.community.service.support.CommunityPopularRankCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.Nested;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class CommunityPostQueryServiceTest {

    private static final String USER_UUID = "user-uuid";
    private static final String POST_UUID = "post-uuid";

    @Mock UserQueryService userQueryService;
    @Mock CommunityCategoryRepository categoryRepository;
    @Mock CommunityPostRepository postRepository;
    @Mock UserBlockRepository blockRepository;
    @Mock CommunityPostAssembler assembler;
    @Mock CommunityViewCountBuffer viewCountBuffer;
    @Mock CommunityPopularRankCache rankCache;

    private static final Instant NOW = Instant.parse("2026-08-13T00:00:00Z");

    private CommunityPostQueryService service;

    @BeforeEach
    void setUp() {
        service = new CommunityPostQueryService(userQueryService, categoryRepository,
                postRepository, blockRepository, assembler, viewCountBuffer, rankCache,
                new com.kovil.domain.community.service.support
                        .CommunityPopularProperties(4, 10, 1.5, Duration.ofHours(1), 100),
                Clock.fixed(NOW, ZoneId.of("Asia/Seoul")));
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

    private static User user(Long id) {
        return withId(User.builder().uuid(USER_UUID).name("n").preferredLanguage("vi")
                .status(UserStatus.ACTIVE).role(UserRole.USER).build(), id);
    }

    private static CommunityPost post(CommunityContentStatus status, LocalDateTime deletedAt) {
        CommunityPost built = withId(CommunityPost.builder()
                .uuid(POST_UUID).categoryId(7L).userId(10L).languageCode("vi")
                .title("t").body("b").status(status).build(), 100L);
        if (deletedAt != null) {
            built.softDelete(deletedAt);
        }
        return built;
    }

    // ★ INV-11 — 상세 조회는 버퍼에만 올린다. DB UPDATE가 여기서 나가면 인기 글이 풀을 태운다.
    @Test
    @DisplayName("상세 조회는 Redis 버퍼만 올리고 DB 조회수는 건드리지 않는다")
    void detail_increases_buffer_not_db() {
        given(userQueryService.getActiveUser(USER_UUID)).willReturn(user(20L));
        CommunityPost target = post(CommunityContentStatus.ACTIVE, null);
        given(postRepository.findByUuid(POST_UUID)).willReturn(Optional.of(target));
        given(assembler.toDetail(target, 20L)).willReturn(null);

        service.getDetail(USER_UUID, POST_UUID);

        then(viewCountBuffer).should().increase(100L);
        then(postRepository).should(never()).increaseViewCount(any(), ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("삭제된 글과 블라인드 글은 404다")
    void deleted_and_hidden_are_not_found() {
        given(userQueryService.getActiveUser(USER_UUID)).willReturn(user(20L));
        given(postRepository.findByUuid(POST_UUID))
                .willReturn(Optional.of(post(CommunityContentStatus.ACTIVE, LocalDateTime.now())))
                .willReturn(Optional.of(post(CommunityContentStatus.HIDDEN, null)));

        for (int round = 0; round < 2; round++) {
            assertThatThrownBy(() -> service.getDetail(USER_UUID, POST_UUID))
                    .isInstanceOfSatisfying(CommunityException.class, e ->
                            assertThat(e.getErrorCode().getCode()).isEqualTo("POST_NOT_FOUND"));
        }
        then(viewCountBuffer).should(never()).increase(any());
    }

    // 표시 번호는 계정이 아니라 글에서 파생된다 — 글이 다르면 번호가 달라야 추적이 불가능하다.
    @Test
    @DisplayName("작성자 표시 번호는 글 단위로 결정적이고 100~999 범위다")
    void anonymous_tag_is_post_scoped_and_deterministic() {
        int first = CommunityPostAssembler.anonymousTag("uuid-aaaa");
        int again = CommunityPostAssembler.anonymousTag("uuid-aaaa");
        int other = CommunityPostAssembler.anonymousTag("uuid-bbbb");

        assertThat(first).isEqualTo(again);
        assertThat(first).isBetween(100, 999);
        assertThat(other).isBetween(100, 999);
        assertThat(first).isNotEqualTo(other);
    }

    // ★ INV-10 — 익명 응답의 구조 자체를 고정한다. 필드가 생기는 순간 이 테스트가 깨진다.
    @Test
    @DisplayName("커뮤니티 응답 DTO에는 작성자 계정 정보 필드가 존재하지 않는다")
    void response_dtos_have_no_author_identity_fields() {
        List<String> forbidden = List.of("userUuid", "userId", "nickname", "profileImageUrl", "nationality");
        for (Class<?> dto : List.of(PostSummaryResponse.class, PostDetailResponse.class, CommentResponse.class)) {
            List<String> fields = Arrays.stream(dto.getRecordComponents())
                    .map(component -> component.getName()).toList();
            assertThat(fields).noneMatch(forbidden::contains);
        }
    }

    @Nested
    @DisplayName("검색 (디자인 스테이지 7)")
    class Search {

        @Test
        @DisplayName("q는 제목·본문 검색으로 리포지토리에 전달된다")
        void query_reaches_repository() {
            given(userQueryService.getActiveUser(USER_UUID)).willReturn(user(20L));
            given(postRepository.findFeed(any(), any(), any(),
                    ArgumentMatchers.eq("전기요금"), any(), any()))
                    .willReturn(new PageImpl<>(List.of()));
            given(assembler.toSummaries(any(), any())).willReturn(List.of());

            var result = service.findFeed(USER_UUID, null, null, false, " 전기요금 ",
                    PostSort.LATEST,
                    null, PageRequest.of(0, 20));

            // 결과 0건은 빈 페이지다 — 404가 아니다.
            assertThat(result.totalElements()).isZero();
        }

        // 1자는 LIKE 풀스캔 대비 변별력이 없다 — 계약(2자 이상)대로 거절한다.
        @Test
        @DisplayName("1자 검색은 400이다")
        void single_char_rejected() {
            given(userQueryService.getActiveUser(USER_UUID)).willReturn(user(20L));

            assertThatThrownBy(() -> service.findFeed(USER_UUID, null, null, false, "전",
                    PostSort.LATEST,
                    null, PageRequest.of(0, 20)))
                    .isInstanceOfSatisfying(CommunityException.class, e ->
                            assertThat(e.getErrorCode().getCode()).isEqualTo("INVALID_REQUEST"));
        }

        @Test
        @DisplayName("검색은 인기 정렬과 조합할 수 없다")
        void query_rejects_popular() {
            given(userQueryService.getActiveUser(USER_UUID)).willReturn(user(20L));

            assertThatThrownBy(() -> service.findFeed(USER_UUID, null, null, false, "전기요금",
                    PostSort.POPULAR,
                    null, PageRequest.of(0, 20)))
                    .isInstanceOfSatisfying(CommunityException.class, e ->
                            assertThat(e.getErrorCode().getCode()).isEqualTo("INVALID_REQUEST"));
        }
    }

    @Nested
    @DisplayName("인기 정렬")
    class Popular {

        private static CommunityPost visiblePost(Long id, Long authorId) {
            return withId(CommunityPost.builder()
                    .uuid("post-" + id).categoryId(7L).userId(authorId).languageCode("vi")
                    .title("t" + id).body("b").status(CommunityContentStatus.ACTIVE).build(), id);
        }

        @SuppressWarnings("unchecked")
        private void givenCachePassThrough() {
            // 캐시 컴포넌트는 자체 테스트가 고정한다 — 여기서는 로더를 그대로 통과시켜 서비스 조립만 본다.
            given(rankCache.getOrCompute(any(), any())).willAnswer(inv ->
                    ((Supplier<List<Long>>) inv.getArgument(1)).get());
        }

        @Test
        @DisplayName("랭킹 순서를 복원하고 차단 작성자·비노출 글을 적재 후 거른다")
        void restores_rank_order_and_filters_after_load() {
            given(userQueryService.getActiveUser(USER_UUID)).willReturn(user(20L));
            givenCachePassThrough();
            given(postRepository.findPopularIds(any(), any(), any(), any(),
                    ArgumentMatchers.anyDouble(), ArgumentMatchers.anyDouble(),
                    ArgumentMatchers.anyDouble(), ArgumentMatchers.anyInt()))
                    .willReturn(List.of(3L, 1L, 2L, 4L));
            CommunityPost hidden = withId(CommunityPost.builder()
                    .uuid("post-2").categoryId(7L).userId(11L).languageCode("vi")
                    .title("t").body("b").status(CommunityContentStatus.HIDDEN).build(), 2L);
            // findAllById는 순서를 보장하지 않는다 — 일부러 뒤섞어 돌려준다.
            given(postRepository.findAllById(List.of(3L, 1L, 2L, 4L))).willReturn(List.of(
                    visiblePost(1L, 10L), hidden, visiblePost(4L, 99L), visiblePost(3L, 10L)));
            given(blockRepository.findBlockedUserIds(20L)).willReturn(Set.of(99L));
            given(assembler.toSummaries(any(), ArgumentMatchers.eq(20L))).willReturn(List.of());

            var result = service.findFeed(USER_UUID, null, null, false, null,
                    PostSort.POPULAR,
                    null, PageRequest.of(0, 20));

            // 2(블라인드)·4(차단 작성자) 제외, 랭킹 순서 3 → 1 유지
            ArgumentCaptor<List<CommunityPost>> captor =
                    ArgumentCaptor.forClass(List.class);
            then(assembler).should().toSummaries(captor.capture(), ArgumentMatchers.eq(20L));
            assertThat(captor.getValue()).extracting(CommunityPost::getId).containsExactly(3L, 1L);
            assertThat(result.totalElements()).isEqualTo(2);
        }

        @Test
        @DisplayName("period 기본은 WEEK — 후보 시간창이 7일 전으로 계산된다")
        void default_period_is_week() {
            given(userQueryService.getActiveUser(USER_UUID)).willReturn(user(20L));
            givenCachePassThrough();
            given(postRepository.findPopularIds(any(), any(), any(), any(),
                    ArgumentMatchers.anyDouble(), ArgumentMatchers.anyDouble(),
                    ArgumentMatchers.anyDouble(), ArgumentMatchers.anyInt()))
                    .willReturn(List.of());

            service.findFeed(USER_UUID, null, null, false, null,
                    PostSort.POPULAR,
                    null, PageRequest.of(0, 3));

            ArgumentCaptor<LocalDateTime> since =
                    ArgumentCaptor.forClass(LocalDateTime.class);
            then(postRepository).should().findPopularIds(any(), since.capture(), any(), any(),
                    ArgumentMatchers.anyDouble(), ArgumentMatchers.anyDouble(),
                    ArgumentMatchers.anyDouble(), ArgumentMatchers.anyInt());
            LocalDateTime expected = LocalDateTime.ofInstant(NOW, ZoneId.of("Asia/Seoul")).minusDays(7);
            assertThat(since.getValue()).isEqualTo(expected);
        }

        // 조용히 최신순으로 바꿔치기하면 앱은 인기순인 줄 안다 — 명시적으로 거절한다.
        @Test
        @DisplayName("인기 정렬은 tag와 조합할 수 없다")
        void popular_rejects_tag_combination() {
            given(userQueryService.getActiveUser(USER_UUID)).willReturn(user(20L));

            assertThatThrownBy(() -> service.findFeed(USER_UUID, null, "#tip", false, null,
                    PostSort.POPULAR,
                    null, PageRequest.of(0, 20)))
                    .isInstanceOfSatisfying(CommunityException.class, e ->
                            assertThat(e.getErrorCode().getCode()).isEqualTo("INVALID_REQUEST"));
            then(rankCache).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("LATEST는 기존 피드 경로 그대로다 — 랭킹 캐시를 건드리지 않는다")
        void latest_keeps_existing_path() {
            given(userQueryService.getActiveUser(USER_UUID)).willReturn(user(20L));
            given(postRepository.findFeed(any(), any(), any(), any(), any(), any()))
                    .willReturn(new PageImpl<>(List.of()));
            given(assembler.toSummaries(any(), any())).willReturn(List.of());

            service.findFeed(USER_UUID, null, null, false, null,
                    PostSort.LATEST,
                    PopularPeriod.DAY,
                    PageRequest.of(0, 20));

            then(rankCache).shouldHaveNoInteractions();
            then(postRepository).should().findFeed(any(), any(), any(), any(), any(), any());
        }
    }

    /*
     * ★ 내 글 목록은 블라인드(HIDDEN)를 뺀다 — SPEC §42-5(FR-714) "목록·상세 404" 다.
     *   넣으면 탭·수정·삭제가 전부 404 인 유령 카드가 목록에 영구히 남았다
     *   (적대적 리뷰 2026-09-02). 상태 필터를 빼면 죽는다.
     */
    @Test
    @DisplayName("내 글 목록은 블라인드 글을 뺀다 — 열 수 없는 유령 카드를 만들지 않는다")
    void find_mine_excludes_hidden_posts() {
        given(userQueryService.getActiveUser(USER_UUID)).willReturn(user(20L));
        given(postRepository.findByUserIdAndStatusAndDeletedAtIsNullOrderByIdDesc(
                ArgumentMatchers.eq(20L),
                ArgumentMatchers.eq(
                        CommunityContentStatus.ACTIVE),
                any())).willReturn(Page.empty());
        given(assembler.toSummaries(any(), any())).willReturn(List.of());

        service.findMine(USER_UUID, PageRequest.of(0, 10));

        then(postRepository).should().findByUserIdAndStatusAndDeletedAtIsNullOrderByIdDesc(
                ArgumentMatchers.eq(20L),
                ArgumentMatchers.eq(
                        CommunityContentStatus.ACTIVE),
                any());
    }
}
