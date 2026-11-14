package com.kovil.domain.community.service;

import com.kovil.domain.community.dto.request.PostCreateRequest;
import com.kovil.domain.community.dto.request.PostUpdateRequest;
import com.kovil.domain.community.entity.CommunityCategory;
import com.kovil.domain.community.repository.CommunityCategoryRepository;
import com.kovil.domain.community.repository.CommunityTagRepository;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

// "전부 지우고 다시 넣는" 교체는 Hibernate가 INSERT를 DELETE보다 먼저 플러시해
// 유니크(post_id, tag_id)에서 터진다. 같은 태그를 그대로 두는, 실제로 가장 흔한
// 수정이 500이 되는 부류다 — 목으로는 볼 수 없다.
@SpringBootTest
class CommunityReplaceTagsFlushTest {

    @Autowired CommunityPostService postService;
    @Autowired CommunityCategoryRepository categoryRepository;
    @Autowired CommunityTagRepository tagRepository;
    @Autowired UserRepository userRepository;
    @Autowired EntityManager entityManager;

    @Test
    @Transactional
    @DisplayName("같은 태그를 그대로 두고 수정해도 유니크 키가 충돌하지 않는다")
    void keeping_same_tags_does_not_collide() {
        User author = userRepository.save(User.builder()
                .uuid(UUID.randomUUID().toString()).name("글쓴이").preferredLanguage("vi")
                .status(UserStatus.ACTIVE).role(UserRole.USER).build());
        CommunityCategory category = categoryRepository.save(CommunityCategory.builder()
                .uuid(UUID.randomUUID().toString()).code("FLUSH-" + UUID.randomUUID().toString().substring(0, 8))
                .isAnonymous(true).sortOrder(99).active(true).build());

        String postUuid = postService.create(author.getUuid(), new PostCreateRequest(
                category.getCode(), "제목", "본문", List.of("visa", "seoul"), null)).postUuid();

        assertThatNoException().isThrownBy(() -> postService.update(author.getUuid(), postUuid,
                new PostUpdateRequest("제목2", "본문2", List.of("visa", "busan"), null)));

        // 집계는 벌크 UPDATE 라 1차 캐시의 엔티티가 낡는다 — 지우고 DB 값으로 단언한다.
        entityManager.clear();
        assertThat(tagRepository.findByNameNormalized("visa").orElseThrow().getUsageCount()).isEqualTo(1L);
        assertThat(tagRepository.findByNameNormalized("seoul").orElseThrow().getUsageCount()).isZero();
        assertThat(tagRepository.findByNameNormalized("busan").orElseThrow().getUsageCount()).isEqualTo(1L);
    }
}
