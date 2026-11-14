package com.kovil.domain.community.service;

import com.kovil.domain.community.entity.CommunityTag;
import com.kovil.domain.community.exception.CommunityException;
import com.kovil.domain.community.repository.CommunityTagRepository;
import com.kovil.domain.user.entity.BannedWord;
import com.kovil.domain.user.repository.BannedWordRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

// FR-710 — "Visa"·"visa"·"VISA"는 한 태그다. 유니크는 정규화 컬럼에 있다.
@ExtendWith(MockitoExtension.class)
class CommunityTagResolverTest {

    @Mock CommunityTagRepository tagRepository;
    @Mock CommunityTagIssuer tagIssuer;
    @Mock BannedWordRepository bannedWordRepository;

    private CommunityTagResolver resolver() {
        return new CommunityTagResolver(tagRepository, tagIssuer, bannedWordRepository);
    }

    private static String codeOf(Throwable e) {
        return ((CommunityException) e).getErrorCode().getCode();
    }

    @Test
    @DisplayName("대소문자·공백·# 이 달라도 한 태그로 접힌다 — 최초 표기가 남는다")
    void variants_collapse_to_one_tag() {
        given(bannedWordRepository.findAllByOrderByWordAsc()).willReturn(List.of());
        given(tagRepository.findByNameNormalized("visa")).willReturn(Optional.empty());
        // 신규 태그는 발급기(REQUIRES_NEW)가 만든다 — 이 트랜잭션의 find-then-save 는 경합 시
        // 글 전체를 500 으로 죽였다(적대적 리뷰 2026-09-02).
        given(tagIssuer.issue("Visa", "visa")).willAnswer(call ->
                CommunityTag.builder().name(call.getArgument(0))
                        .nameNormalized(call.getArgument(1)).usageCount(0L).build());

        List<CommunityTag> resolved = resolver().resolve(List.of("Visa", "#visa", " VISA "));

        assertThat(resolved).hasSize(1);
        assertThat(resolved.get(0).getName()).isEqualTo("Visa");
        assertThat(resolved.get(0).getNameNormalized()).isEqualTo("visa");
    }

    @Test
    @DisplayName("이미 있는 태그는 재사용한다")
    void existing_tag_is_reused() {
        given(bannedWordRepository.findAllByOrderByWordAsc()).willReturn(List.of());
        CommunityTag existing = CommunityTag.builder().name("Visa").nameNormalized("visa").usageCount(5L).build();
        given(tagRepository.findByNameNormalized("visa")).willReturn(Optional.of(existing));

        List<CommunityTag> resolved = resolver().resolve(List.of("visa"));

        assertThat(resolved).containsExactly(existing);
    }

    @Test
    @DisplayName("11개면 거부한다")
    void more_than_ten_is_rejected() {
        assertThatThrownBy(() -> resolver().resolve(Collections.nCopies(11, "tag")))
                .extracting(CommunityTagResolverTest::codeOf).isEqualTo("TAG_LIMIT_EXCEEDED");
    }

    @Test
    @DisplayName("21자 태그는 거부한다")
    void too_long_is_rejected() {
        given(bannedWordRepository.findAllByOrderByWordAsc()).willReturn(List.of());

        assertThatThrownBy(() -> resolver().resolve(List.of("a".repeat(21))))
                .extracting(CommunityTagResolverTest::codeOf).isEqualTo("TAG_INVALID");
    }

    @Test
    @DisplayName("금칙어가 들어간 태그는 거부한다")
    void banned_word_is_rejected() {
        given(bannedWordRepository.findAllByOrderByWordAsc())
                .willReturn(List.of(BannedWord.builder().word("badword").build()));

        assertThatThrownBy(() -> resolver().resolve(List.of("MyBadwordTag")))
                .extracting(CommunityTagResolverTest::codeOf).isEqualTo("TAG_BANNED");
    }
}
