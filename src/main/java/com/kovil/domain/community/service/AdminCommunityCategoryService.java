package com.kovil.domain.community.service;

import com.kovil.common.dto.request.MasterOrderRequest;
import com.kovil.common.exception.MasterException;
import com.kovil.common.master.MasterOrderPolicy;
import com.kovil.domain.community.dto.request.AdminCategoryRequest;
import com.kovil.domain.community.dto.response.AdminCategoryResponse;
import com.kovil.domain.community.entity.CommunityCategory;
import com.kovil.domain.community.entity.CommunityCategoryTranslation;
import com.kovil.domain.community.repository.CommunityCategoryRepository;
import com.kovil.domain.community.repository.CommunityCategoryTranslationRepository;
import com.kovil.domain.community.repository.CommunityPostRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.Comparator;

import static com.kovil.common.exception.MasterErrorCode.MASTER_DUPLICATE;
import static com.kovil.common.exception.MasterErrorCode.MASTER_NOT_FOUND;

@Service
@RequiredArgsConstructor
public class AdminCommunityCategoryService {

    private final CommunityCategoryRepository categoryRepository;
    private final CommunityCategoryTranslationRepository translationRepository;
    private final CommunityPostRepository postRepository;
    private final EntityManager entityManager;

    @Transactional(readOnly = true)
    public List<AdminCategoryResponse> findAll() {
        List<CommunityCategory> categories = categoryRepository.findAll();
        Map<Long, Map<String, String>> names = translationRepository
                .findByCategoryIdIn(categories.stream().map(CommunityCategory::getId).toList())
                .stream().collect(Collectors.groupingBy(CommunityCategoryTranslation::getCategoryId,
                        Collectors.toMap(CommunityCategoryTranslation::getLanguageCode,
                                CommunityCategoryTranslation::getName, (first, second) -> first)));
        return categories.stream()
                .sorted(Comparator.comparing(CommunityCategory::getSortOrder)
                        .thenComparing(CommunityCategory::getId))
                .map(category -> new AdminCategoryResponse(
                        category.getId(), category.getUuid(), category.getCode(),
                        category.isAnonymous(), category.getSortOrder(), category.isActive(),
        // ★ 알려진 창(INV-12 TOCTOU): 이 검사와 커밋 사이에 그 카테고리의 첫 글이 끼어들 수
        //   있다. community_posts 에 카테고리 FK 가 없어 InnoDB 부모행 잠금에 의한 우연한
        //   직렬화도 없다 — 완전히 닫으려면 글 작성 핫패스에 잠금 읽기를 넣어야 해서 비용이
        //   크고, 결과 상태는 "빈 카테고리 토글 → 첫 글" 합법 직렬 이력과 동일하다
        //   (적대적 리뷰 2026-09-02 판정). v1 은 익명 축 노출이 없어 실해가 없다 —
        //   비익명 노출을 여는 날, 이 주석을 찾아 양쪽 잠금으로 닫을 것.
                        // 익명 토글을 미리 잠그는 판정값 — 게시판 수가 적어 행마다 물어도 된다.
                        postRepository.existsByCategoryId(category.getId()),
                        names.getOrDefault(category.getId(), Map.of())))
                .toList();
    }

    @Transactional
    public void create(AdminCategoryRequest request) {
        String code = request.code().strip().toUpperCase();
        if (categoryRepository.findByCodeAndActiveTrue(code).isPresent()
                || categoryRepository.findAll().stream().anyMatch(c -> c.getCode().equals(code))) {
            throw new MasterException(MASTER_DUPLICATE);
        }
        CommunityCategory category = categoryRepository.save(CommunityCategory.builder()
                .code(code)
                .isAnonymous(request.isAnonymous() == null || request.isAnonymous())
                .sortOrder(request.sortOrder() == null ? categoryRepository.maxSortOrder() + 1 : request.sortOrder())
                .active(request.active() == null || request.active())
                .build());
        replaceTranslations(category.getId(), request.translations());
    }

    // ★ INV-12 — 익명 설정 변경은 엔티티 가드를 지난다. 글이 하나라도 있으면(삭제 포함) 거부.
    @Transactional
    public void update(Long id, AdminCategoryRequest request) {
        CommunityCategory category = categoryRepository.findById(id)
                .orElseThrow(() -> new MasterException(MASTER_NOT_FOUND));
        if (request.isAnonymous() != null) {
            category.changeAnonymous(request.isAnonymous(), postRepository.existsByCategoryId(id));
        }
        category.update(
                request.sortOrder() == null ? category.getSortOrder() : request.sortOrder(),
                request.active() == null ? category.isActive() : request.active());
        replaceTranslations(id, request.translations());
    }

    private void replaceTranslations(Long categoryId, List<AdminCategoryRequest.TranslationEntry> entries) {
        if (entries == null) {
            return;
        }
        translationRepository.deleteAll(translationRepository.findByCategoryId(categoryId));
        entityManager.flush();
        entries.forEach(entry -> translationRepository.save(CommunityCategoryTranslation.builder()
                .categoryId(categoryId)
                .languageCode(entry.languageCode().strip())
                .name(entry.name().strip())
                .build()));
    }

    // 이동 조작(위/아래/맨 위/맨 아래)의 원자 단위 — 축 전체를 한 트랜잭션에서 1..N으로 재부여한다.
    // 행 단위 PUT을 순차 발사하면 중간 실패 시 순서가 반쯤 섞인 채 남는다.
    @Transactional
    public void reorder(MasterOrderRequest request) {
        List<CommunityCategory> scope = categoryRepository.findAllForReorder();
        MasterOrderPolicy.requireExactPermutation(
                scope.stream().map(CommunityCategory::getId).toList(), request.ids());

        Map<Long, CommunityCategory> byId = scope.stream()
                .collect(Collectors.toMap(CommunityCategory::getId, row -> row));
        for (int index = 0; index < request.ids().size(); index++) {
            byId.get(request.ids().get(index)).changeSortOrder(index + 1);
        }
    }
}
