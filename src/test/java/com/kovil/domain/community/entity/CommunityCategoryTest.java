package com.kovil.domain.community.entity;

import com.kovil.domain.community.exception.CommunityException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// INV-12 — 익명 → 비익명 전환은 과거 익명 글의 작성자를 소급 노출시키는 사고다.
class CommunityCategoryTest {

    private static CommunityCategory anonymousCategory() {
        return CommunityCategory.builder()
                .uuid("c").code("FREE").isAnonymous(true).sortOrder(1).active(true).build();
    }

    @Test
    @DisplayName("글이 하나라도 있으면 익명 설정을 바꿀 수 없다")
    void category_with_posts_is_frozen() {
        CommunityCategory category = anonymousCategory();

        assertThatThrownBy(() -> category.changeAnonymous(false, true))
                .isInstanceOfSatisfying(CommunityException.class, e ->
                        assertThat(e.getErrorCode().getCode()).isEqualTo("CATEGORY_HAS_POSTS"));
        assertThat(category.isAnonymous()).isTrue();
    }

    @Test
    @DisplayName("글이 없으면 바꿀 수 있다")
    void empty_category_can_change() {
        CommunityCategory category = anonymousCategory();

        category.changeAnonymous(false, false);

        assertThat(category.isAnonymous()).isFalse();
    }

    @Test
    @DisplayName("같은 값으로의 변경은 글이 있어도 조용히 지나간다")
    void same_value_is_noop() {
        CommunityCategory category = anonymousCategory();

        assertThatCode(() -> category.changeAnonymous(true, true)).doesNotThrowAnyException();
    }
}
