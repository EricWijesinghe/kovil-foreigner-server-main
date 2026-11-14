package com.kovil.domain.community.service;

import com.kovil.domain.community.dto.request.AdminCategoryRequest;
import com.kovil.domain.community.entity.CommunityCategory;
import com.kovil.domain.community.exception.CommunityException;
import com.kovil.domain.community.repository.CommunityCategoryRepository;
import com.kovil.domain.community.repository.CommunityCategoryTranslationRepository;
import com.kovil.domain.community.repository.CommunityPostRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

// INV-12의 관리자 배선 — 엔티티 가드가 실제 수정 경로에서 발동하는지.
@ExtendWith(MockitoExtension.class)
class AdminCommunityCategoryServiceTest {

    @Mock CommunityCategoryRepository categoryRepository;
    @Mock CommunityCategoryTranslationRepository translationRepository;
    @Mock CommunityPostRepository postRepository;
    @Mock EntityManager entityManager;

    private AdminCommunityCategoryService service;

    @BeforeEach
    void setUp() {
        service = new AdminCommunityCategoryService(categoryRepository, translationRepository,
                postRepository, entityManager);
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

    private static CommunityCategory category(boolean anonymous) {
        return withId(CommunityCategory.builder()
                .uuid("c").code("FREE").isAnonymous(anonymous).sortOrder(1).active(true).build(), 7L);
    }

    @Test
    @DisplayName("글이 있는 게시판의 익명 설정 변경은 409로 끊긴다 — 삭제된 글도 센다")
    void anonymity_change_is_blocked_when_posts_exist() {
        given(categoryRepository.findById(7L)).willReturn(Optional.of(category(true)));
        given(postRepository.existsByCategoryId(7L)).willReturn(true);

        assertThatThrownBy(() -> service.update(7L,
                new AdminCategoryRequest("FREE", false, null, null, null)))
                .isInstanceOfSatisfying(CommunityException.class, e ->
                        assertThat(e.getErrorCode().getCode()).isEqualTo("CATEGORY_HAS_POSTS"));
    }

    @Test
    @DisplayName("글이 없으면 익명 설정을 바꿀 수 있다")
    void empty_category_can_change_anonymity() {
        CommunityCategory target = category(true);
        given(categoryRepository.findById(7L)).willReturn(Optional.of(target));
        given(postRepository.existsByCategoryId(7L)).willReturn(false);

        service.update(7L, new AdminCategoryRequest("FREE", false, null, null, null));

        assertThat(target.isAnonymous()).isFalse();
    }

    @Test
    @DisplayName("익명 설정을 안 보내면(null) 글이 있어도 다른 필드는 수정된다")
    void other_fields_update_without_touching_anonymity() {
        CommunityCategory target = category(true);
        given(categoryRepository.findById(7L)).willReturn(Optional.of(target));

        service.update(7L, new AdminCategoryRequest("FREE", null, 9, false, null));

        assertThat(target.getSortOrder()).isEqualTo(9);
        assertThat(target.isActive()).isFalse();
        assertThat(target.isAnonymous()).isTrue();
    }
}
