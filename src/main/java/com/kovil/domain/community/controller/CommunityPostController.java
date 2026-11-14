package com.kovil.domain.community.controller;

import com.kovil.common.dto.response.ApiResponse;
import com.kovil.common.web.Paging;
import com.kovil.common.dto.response.PageResponse;
import com.kovil.common.security.AuthenticatedUser;
import com.kovil.common.security.annotation.CurrentUser;
import com.kovil.domain.community.dto.request.PostCreateRequest;
import com.kovil.domain.community.dto.request.PostUpdateRequest;
import com.kovil.domain.community.dto.response.PostDetailResponse;
import com.kovil.domain.community.dto.response.PostSummaryResponse;
import com.kovil.domain.community.enums.PopularPeriod;
import com.kovil.domain.community.enums.PostSort;
import com.kovil.domain.community.service.CommunityPostQueryService;
import com.kovil.domain.community.service.CommunityPostService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Community · Post", description = """
        익명 게시판의 글. 응답에 작성자 계정 정보가 없다 — 익명 축은 `anonymousNo`·`isMine`뿐이다.

        글은 작성 시점의 내 언어가 스냅샷되고, `myLanguageOnly=true`로 내 언어 글만 볼 수 있다.
        """)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/community/posts")
public class CommunityPostController {

    private final CommunityPostService postService;
    private final CommunityPostQueryService postQueryService;

    @Operation(summary = "글 작성", description = "태그 10개·이미지 5장까지. 언어는 내 설정에서 스냅샷된다.")
    @PostMapping
    public ApiResponse<PostDetailResponse> create(
            @CurrentUser AuthenticatedUser principal,
            @Valid @RequestBody PostCreateRequest request) {
        return ApiResponse.success(postService.create(principal.userUuid(), request));
    }

    @Operation(summary = "글 목록", description = """
            `category`(코드) · `tag` · `myLanguageOnly` · `q`(검색) 필터를 조합할 수 있다.
            `bodyPreview`는 본문 앞 120자, `thumbnailUrl`은 첫 번째 이미지다.

            **검색(`q`)**
            - 2자 이상. **제목과 본문을 함께** 찾는다. 결과 없음 = 빈 페이지(404 아님).
            - 글은 원문 언어 그대로 검색된다 — 언어를 넘는 검색은 없다. 결과가 비면 앱이
              "전체 언어로 다시 검색"(`myLanguageOnly=false` 재요청)을 제안하는 걸 권장.
            - 1자는 `INVALID_REQUEST`(400). `sort=POPULAR`와 조합 불가(400).

            **정렬**
            - `sort=LATEST`(기본) — 최신순. `period`는 무시된다.
            - `sort=POPULAR` — 인기순. 점수 = (조회 + 좋아요·댓글 가중) / 경과시간 감쇠라
              "지금 반응이 빠른 글"이 위로 온다. `period`(`DAY`|`WEEK`(기본)|`MONTH`)가 후보
              시간창이다. 홈 "지금 많이 보는 글"은 `sort=POPULAR&period=WEEK&size=3`으로,
              **`myLanguageOnly` 없이(전체 언어)** 호출한다.
            - 인기 랭킹은 서버가 **일정 시간 캐시**한다 — 순위 반영이 최대 캐시 TTL만큼 늦을 수
              있다. 삭제·블라인드·차단 글은 캐시와 무관하게 즉시 빠진다.
            - 인기 목록의 `totalElements`는 전체 글 수가 아니라 **랭킹 풀(Top-N)에서 볼 수 있는
              글 수**다. 무한 스크롤 끝이 곧 랭킹의 끝이다.
            - `sort=POPULAR`는 `tag`와 조합할 수 없다 — `INVALID_REQUEST`(400).
            """)
    @GetMapping
    public ApiResponse<PageResponse<PostSummaryResponse>> findFeed(
            @CurrentUser AuthenticatedUser principal,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String tag,
            @RequestParam(defaultValue = "false") boolean myLanguageOnly,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "LATEST") PostSort sort,
            @RequestParam(required = false) PopularPeriod period,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(postQueryService.findFeed(
                principal.userUuid(), category, tag, myLanguageOnly, q, sort, period, Paging.of(page, size)));
    }

    @Operation(summary = "글 상세", description = """
            조회수는 즉시 오르지 않는다 — 버퍼를 배치가 **최대 1분 지연**으로 가산한다.
            삭제·블라인드 글은 404다.
            """)
    @GetMapping("/{postUuid}")
    public ApiResponse<PostDetailResponse> get(
            @CurrentUser AuthenticatedUser principal,
            @PathVariable String postUuid) {
        return ApiResponse.success(postQueryService.getDetail(principal.userUuid(), postUuid));
    }

    @Operation(summary = "글 수정", description = "본인만(`NOT_CONTENT_OWNER` 403). 태그·이미지는 보낸 목록으로 통째로 교체된다.")
    @PutMapping("/{postUuid}")
    public ApiResponse<PostDetailResponse> update(
            @CurrentUser AuthenticatedUser principal,
            @PathVariable String postUuid,
            @Valid @RequestBody PostUpdateRequest request) {
        return ApiResponse.success(postService.update(principal.userUuid(), postUuid, request));
    }

    @Operation(summary = "글 삭제", description = "본인만. 소프트 삭제 — 목록·상세에서 사라진다.")
    @DeleteMapping("/{postUuid}")
    public ApiResponse<Void> delete(
            @CurrentUser AuthenticatedUser principal,
            @PathVariable String postUuid) {
        postService.delete(principal.userUuid(), postUuid);
        return ApiResponse.ok();
    }
}
