package com.kovil.domain.community.service;

import com.kovil.domain.community.dto.request.PostCreateRequest;
import com.kovil.domain.community.dto.request.PostUpdateRequest;
import com.kovil.domain.community.entity.CommunityCategory;
import com.kovil.domain.community.entity.CommunityPost;
import com.kovil.domain.community.entity.CommunityPostAlias;
import com.kovil.domain.community.entity.CommunityPostImage;
import com.kovil.domain.community.entity.CommunityPostTag;
import com.kovil.domain.community.entity.CommunityTag;
import com.kovil.domain.community.enums.CommunityContentStatus;
import com.kovil.domain.community.exception.CommunityException;
import com.kovil.domain.community.repository.CommunityCategoryRepository;
import com.kovil.domain.community.repository.CommunityPostAliasRepository;
import com.kovil.domain.community.repository.CommunityPostImageRepository;
import com.kovil.domain.community.repository.CommunityPostRepository;
import com.kovil.domain.community.repository.CommunityPostTagRepository;
import com.kovil.domain.community.repository.CommunityTagRepository;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.user.service.UserQueryService;
import com.kovil.domain.file.service.FileDeletionService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class CommunityPostServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-12T03:00:00Z");
    private static final String USER_UUID = "user-uuid";
    private static final String POST_UUID = "post-uuid";

    @Mock UserQueryService userQueryService;
    @Mock CommunityCategoryRepository categoryRepository;
    @Mock CommunityPostRepository postRepository;
    @Mock CommunityPostAliasRepository aliasRepository;
    @Mock CommunityTagRepository tagRepository;
    @Mock CommunityPostTagRepository postTagRepository;
    @Mock CommunityPostImageRepository imageRepository;
    @Mock CommunityPostAssembler assembler;
    @Mock CommunityTagResolver tagResolver;
    @Mock EntityManager entityManager;
    @Mock FileDeletionService fileDeletionService;

    private CommunityPostService service;

    @BeforeEach
    void setUp() {
        service = new CommunityPostService(userQueryService, categoryRepository, postRepository,
                aliasRepository, tagResolver, tagRepository, postTagRepository, imageRepository,
                fileDeletionService, assembler, entityManager, Clock.fixed(NOW, ZoneId.of("Asia/Seoul")));
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

    private static User user(Long id, String language) {
        return withId(User.builder().uuid(USER_UUID).name("n").preferredLanguage(language)
                .status(UserStatus.ACTIVE).role(UserRole.USER).build(), id);
    }

    private static CommunityCategory category() {
        return withId(CommunityCategory.builder()
                .uuid("cat").code("HOUSING").isAnonymous(true).sortOrder(1).active(true).build(), 7L);
    }

    private static CommunityPost post(Long ownerId, CommunityContentStatus status) {
        return withId(CommunityPost.builder()
                .uuid(POST_UUID).categoryId(7L).userId(ownerId).languageCode("vi")
                .title("t").body("b").status(status).build(), 100L);
    }

    private static CommunityTag tag(Long id, String name, long usage) {
        return withId(CommunityTag.builder()
                .name(name).nameNormalized(name.toLowerCase()).usageCount(usage).build(), id);
    }

    private static String codeOf(Throwable e) {
        return ((CommunityException) e).getErrorCode().getCode();
    }

    @Nested
    @DisplayName("작성 (FR-704 · FR-707)")
    class Create {

        @Test
        @DisplayName("언어는 유저 설정에서 스냅샷되고 글쓴이 별칭 0이 미리 발급된다")
        void create_snapshots_language_and_issues_alias_zero() {
            given(userQueryService.getActiveUser(USER_UUID)).willReturn(user(10L, "vi"));
            given(categoryRepository.findByCodeAndActiveTrue("HOUSING")).willReturn(Optional.of(category()));
            given(postRepository.save(any())).willAnswer(call -> withId(call.getArgument(0), 100L));
            given(tagResolver.resolve(any())).willReturn(List.of());

            service.create(USER_UUID, new PostCreateRequest("HOUSING", "제목", "본문", null, null));

            ArgumentCaptor<CommunityPost> postCaptor = ArgumentCaptor.forClass(CommunityPost.class);
            then(postRepository).should().save(postCaptor.capture());
            assertThat(postCaptor.getValue().getLanguageCode()).isEqualTo("vi");
            assertThat(postCaptor.getValue().getStatus()).isEqualTo(CommunityContentStatus.ACTIVE);

            ArgumentCaptor<CommunityPostAlias> aliasCaptor = ArgumentCaptor.forClass(CommunityPostAlias.class);
            then(aliasRepository).should().save(aliasCaptor.capture());
            assertThat(aliasCaptor.getValue().getAnonymousNo()).isEqualTo((short) 0);
            assertThat(aliasCaptor.getValue().getUserId()).isEqualTo(10L);
        }

        @Test
        @DisplayName("태그가 붙으면 usage_count가 오르고, 이미지는 보낸 순서가 sort_order다")
        void create_attaches_tags_and_images() {
            given(userQueryService.getActiveUser(USER_UUID)).willReturn(user(10L, "vi"));
            given(categoryRepository.findByCodeAndActiveTrue("HOUSING")).willReturn(Optional.of(category()));
            given(postRepository.save(any())).willAnswer(call -> withId(call.getArgument(0), 100L));
            CommunityTag visa = tag(1L, "Visa", 0L);
            given(tagResolver.resolve(any())).willReturn(List.of(visa));

            service.create(USER_UUID, new PostCreateRequest("HOUSING", "제목", "본문",
                    List.of("Visa"), List.of("https://cdn/a.jpg", "https://cdn/b.jpg")));

            // 집계는 벌크 UPDATE 다 — 더티체킹 +1 은 lost update·detached 증발 두 함정이 있었다.
            then(tagRepository).should().increaseUsage(1L);
            ArgumentCaptor<CommunityPostImage> imageCaptor = ArgumentCaptor.forClass(CommunityPostImage.class);
            then(imageRepository).should(Mockito.times(2)).save(imageCaptor.capture());
            assertThat(imageCaptor.getAllValues().get(0).getSortOrder()).isZero();
            assertThat(imageCaptor.getAllValues().get(1).getSortOrder()).isEqualTo(1);
        }

        @Test
        @DisplayName("이미지 6장은 거부한다")
        void six_images_are_rejected() {
            given(userQueryService.getActiveUser(USER_UUID)).willReturn(user(10L, "vi"));
            given(categoryRepository.findByCodeAndActiveTrue("HOUSING")).willReturn(Optional.of(category()));

            assertThatThrownBy(() -> service.create(USER_UUID, new PostCreateRequest(
                    "HOUSING", "제목", "본문", null, Collections.nCopies(6, "https://cdn/x.jpg"))))
                    .extracting(CommunityPostServiceTest::codeOf).isEqualTo("IMAGE_LIMIT_EXCEEDED");
            then(postRepository).should(Mockito.never()).save(any());
        }

        @Test
        @DisplayName("없는 카테고리 코드는 거부한다")
        void unknown_category_is_rejected() {
            given(userQueryService.getActiveUser(USER_UUID)).willReturn(user(10L, "vi"));
            given(categoryRepository.findByCodeAndActiveTrue("NOPE")).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.create(USER_UUID,
                    new PostCreateRequest("NOPE", "제목", "본문", null, null)))
                    .extracting(CommunityPostServiceTest::codeOf).isEqualTo("CATEGORY_NOT_FOUND");
        }
    }

    @Nested
    @DisplayName("수정·삭제 — 본인만")
    class OwnerOnly {

        @Test
        @DisplayName("남의 글은 수정할 수 없다")
        void others_post_cannot_be_updated() {
            given(userQueryService.getActiveUser(USER_UUID)).willReturn(user(99L, "vi"));
            given(postRepository.findByUuid(POST_UUID))
                    .willReturn(Optional.of(post(10L, CommunityContentStatus.ACTIVE)));

            assertThatThrownBy(() -> service.update(USER_UUID, POST_UUID,
                    new PostUpdateRequest("t", "b", null, null)))
                    .extracting(CommunityPostServiceTest::codeOf).isEqualTo("NOT_CONTENT_OWNER");
        }

        @Test
        @DisplayName("남의 글은 지울 수 없다")
        void others_post_cannot_be_deleted() {
            given(userQueryService.getActiveUser(USER_UUID)).willReturn(user(99L, "vi"));
            given(postRepository.findByUuid(POST_UUID))
                    .willReturn(Optional.of(post(10L, CommunityContentStatus.ACTIVE)));

            assertThatThrownBy(() -> service.delete(USER_UUID, POST_UUID))
                    .extracting(CommunityPostServiceTest::codeOf).isEqualTo("NOT_CONTENT_OWNER");
        }

        @Test
        @DisplayName("삭제하면 목록에서 사라지고 태그 집계가 내려간다")
        void delete_is_soft_and_returns_tag_usage() {
            given(userQueryService.getActiveUser(USER_UUID)).willReturn(user(10L, "vi"));
            CommunityPost target = post(10L, CommunityContentStatus.ACTIVE);
            given(postRepository.findByUuid(POST_UUID)).willReturn(Optional.of(target));
            given(postTagRepository.findByPostId(100L)).willReturn(List.of(
                    withId(CommunityPostTag.builder().postId(100L).tagId(1L).build(), 500L)));
            // 조건부 벌크 삭제 — 영향 행수 1이 "이번 요청이 지웠다"는 판정이다(더블탭 이중 감산 방지).
            given(postRepository.softDeleteIfVisible(ArgumentMatchers.eq(100L), any()))
                    .willReturn(1);

            service.delete(USER_UUID, POST_UUID);

            then(postRepository).should().softDeleteIfVisible(ArgumentMatchers.eq(100L), any());
            then(tagRepository).should().decreaseUsage(1L);
        }

        // 더블탭 패자(영향 행수 0)는 집계를 내리지 않는다 — 두 번 내리면 인기 태그가 거짓말한다.
        @Test
        @DisplayName("동시 이중 삭제의 패자는 태그 집계를 내리지 않는다")
        void concurrent_double_delete_decrements_once() {
            given(userQueryService.getActiveUser(USER_UUID)).willReturn(user(10L, "vi"));
            CommunityPost target = post(10L, CommunityContentStatus.ACTIVE);
            given(postRepository.findByUuid(POST_UUID)).willReturn(Optional.of(target));
            given(postRepository.softDeleteIfVisible(ArgumentMatchers.eq(100L), any()))
                    .willReturn(0);

            service.delete(USER_UUID, POST_UUID);

            then(tagRepository).should(Mockito.never()).decreaseUsage(any());
        }

        // 유지 태그는 그대로, 빠진 태그 -1, 새 태그 +1 — 집계가 어긋나면 인기 태그가 거짓말한다.
        @Test
        @DisplayName("태그 교체는 빠진 것만 내리고 새것만 올린다")
        void replace_adjusts_only_diff() {
            given(userQueryService.getActiveUser(USER_UUID)).willReturn(user(10L, "vi"));
            CommunityPost target = post(10L, CommunityContentStatus.ACTIVE);
            given(postRepository.findByUuid(POST_UUID)).willReturn(Optional.of(target));

            CommunityTag keep = tag(1L, "Visa", 5L);
            CommunityTag added = tag(2L, "Seoul", 7L);
            CommunityTag removed = tag(3L, "Job", 9L);
            given(postTagRepository.findByPostId(100L)).willReturn(List.of(
                    withId(CommunityPostTag.builder().postId(100L).tagId(1L).build(), 501L),
                    withId(CommunityPostTag.builder().postId(100L).tagId(3L).build(), 502L)));
            given(tagResolver.resolve(any())).willReturn(List.of(keep, added));
            given(imageRepository.findByPostIdOrderBySortOrderAsc(100L)).willReturn(List.of());

            service.update(USER_UUID, POST_UUID, new PostUpdateRequest("t", "b",
                    List.of("Visa", "Seoul"), null));

            then(tagRepository).should(Mockito.never()).increaseUsage(1L);
            then(tagRepository).should(Mockito.never()).decreaseUsage(1L);
            then(tagRepository).should().increaseUsage(2L);
            then(tagRepository).should().decreaseUsage(3L);
        }

        /*
         * ★ 글에서 뺀 사진은 **버려야 한다.** 행만 지우면 files 원장은 ACTIVE 로 남아 정리 배치가
         *   집지 않는다 — 커뮤니티 사진은 공개 URL 이라 주소를 아는 사람은 계속 본다.
         *
         *   반대로 남는 사진까지 버리면 살아 있는 글의 사진이 대기실로 끌려간다. 순서만 바꾸거나
         *   한 장만 빼는 수정이 가장 흔하므로 여기서 차집합을 못 박아 둔다.
         */
        @Test
        @DisplayName("사진 교체는 빠진 것만 버리고 남는 것은 건드리지 않는다")
        void replaced_images_discard_only_dropped() {
            given(userQueryService.getActiveUser(USER_UUID)).willReturn(user(10L, "vi"));
            CommunityPost target = post(10L, CommunityContentStatus.ACTIVE);
            given(postRepository.findByUuid(POST_UUID)).willReturn(Optional.of(target));
            given(postTagRepository.findByPostId(100L)).willReturn(List.of());
            given(tagResolver.resolve(any())).willReturn(List.of());
            given(imageRepository.findByPostIdOrderBySortOrderAsc(100L)).willReturn(List.of(
                    CommunityPostImage.builder().postId(100L)
                            .imageUrl("https://x/keep.jpg").sortOrder(0).build(),
                    CommunityPostImage.builder().postId(100L)
                            .imageUrl("https://x/drop.jpg").sortOrder(1).build()));

            service.update(USER_UUID, POST_UUID, new PostUpdateRequest("t", "b",
                    List.of(), List.of("https://x/keep.jpg")));

            then(fileDeletionService).should()
                    .discardByUrlsOwnedBy(List.of("https://x/drop.jpg"), 10L);
        }

        /*
         * ★ **남의 파일을 지우는 통로를 막는다.** image_url 은 사용자가 요청에 담아 보낸 값이고
         *   검증은 개수뿐이다 — 남의 글에서 공개 URL 을 베껴 자기 글에 붙일 수 있다. 그 글을
         *   지울 때 URL 만 보고 파일을 버리면 **원래 주인의 사진이 폐기된다**(적대적 리뷰 2026-09-23).
         *
         *   그래서 폐기는 반드시 업로더 축이 실린 통로로만 나간다. 이 테스트가 고정하는 것은
         *   "무엇을 지우는가"가 아니라 **"누구 것만 지울 수 있는가"**다.
         */
        @Test
        @DisplayName("글 삭제는 내가 올린 사진만 버린다 — 붙여 둔 남의 URL 은 건드리지 않는다")
        void delete_discards_only_own_uploads() {
            given(userQueryService.getActiveUser(USER_UUID)).willReturn(user(10L, "vi"));
            CommunityPost target = post(10L, CommunityContentStatus.ACTIVE);
            given(postRepository.findByUuid(POST_UUID)).willReturn(Optional.of(target));
            given(postRepository.softDeleteIfVisible(eq(100L), any())).willReturn(1);
            given(postTagRepository.findByPostId(100L)).willReturn(List.of());
            given(imageRepository.findByPostIdOrderBySortOrderAsc(100L)).willReturn(List.of(
                    CommunityPostImage.builder().postId(100L)
                            .imageUrl("https://x/mine.jpg").sortOrder(0).build(),
                    CommunityPostImage.builder().postId(100L)
                            .imageUrl("https://x/someone-elses.jpg").sortOrder(1).build()));

            service.delete(USER_UUID, POST_UUID);

            // 업로더 축이 빠진 통로로는 절대 나가면 안 된다 — 그 순간 남의 파일이 지워진다.
            then(fileDeletionService).should(Mockito.never())
                    .discardByUrls(ArgumentMatchers.anyList());
            then(fileDeletionService).should().discardByUrlsOwnedBy(
                    List.of("https://x/mine.jpg", "https://x/someone-elses.jpg"), 10L);
        }
    }
}
