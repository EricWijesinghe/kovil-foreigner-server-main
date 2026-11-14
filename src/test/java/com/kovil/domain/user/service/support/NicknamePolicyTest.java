package com.kovil.domain.user.service.support;

import com.kovil.domain.user.entity.BannedWord;
import com.kovil.domain.user.exception.UserException;
import com.kovil.domain.user.repository.BannedWordRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class NicknamePolicyTest {

    @Mock BannedWordRepository bannedWordRepository;

    @InjectMocks NicknamePolicy policy;

    private void noBannedWords() {
        given(bannedWordRepository.findAllByOrderByWordAsc()).willReturn(List.of());
    }

    private String code(Throwable e) {
        return ((UserException) e).getErrorCode().getCode();
    }

    // 다국적 사용자 대상이라 라틴만 허용하지 않는다.
    @Test
    @DisplayName("한글·라틴·키릴·베트남어 표기를 모두 허용한다")
    void accepts_unicode_letters() {
        noBannedWords();

        assertThat(policy.validate("응우옌")).isEqualTo("응우옌");
        assertThat(policy.validate("Nguyen")).isEqualTo("nguyen");
        assertThat(policy.validate("Đặng12")).isEqualTo("đặng12");
        assertThat(policy.validate("Алекс")).isEqualTo("алекс");
    }

    @Test
    @DisplayName("정규화는 소문자 변환이다 — 유니크 판정 축이 이 값이다")
    void normalizes_to_lowercase() {
        noBannedWords();

        assertThat(policy.validate("  Sunny  ")).isEqualTo("sunny");
    }

    @Test
    @DisplayName("공백·특수문자는 거절한다 — 표시가 깨지고 동형 이의어를 만든다")
    void rejects_spaces_and_symbols() {
        for (String bad : new String[]{"a b", "a_b", "a@b", "a-b", "😀"}) {
            assertThatThrownBy(() -> policy.validate(bad))
                    .as("nickname=%s", bad)
                    .isInstanceOf(UserException.class)
                    .satisfies(e -> assertThat(code(e)).isEqualTo("NICKNAME_INVALID_FORMAT"));
        }
    }

    @Test
    @DisplayName("길이는 2~20자다")
    void enforces_length() {
        assertThatThrownBy(() -> policy.validate("a"))
                .satisfies(e -> assertThat(code(e)).isEqualTo("NICKNAME_INVALID_FORMAT"));
        assertThatThrownBy(() -> policy.validate("a".repeat(21)))
                .satisfies(e -> assertThat(code(e)).isEqualTo("NICKNAME_INVALID_FORMAT"));
    }

    @Test
    @DisplayName("비어 있으면 형식 오류와 구분해서 거절한다")
    void blank_is_distinguished() {
        for (String blank : new String[]{null, "", "   "}) {
            assertThatThrownBy(() -> policy.validate(blank))
                    .satisfies(e -> assertThat(code(e)).isEqualTo("NICKNAME_BLANK"));
        }
    }

    // FR-108 — 금칙어는 부분 일치로 본다. 운영자가 등록하는 즉시 반영돼야 하므로 캐시하지 않는다.
    @Test
    @DisplayName("금칙어를 품고 있으면 거절한다 — 부분 일치다")
    void rejects_banned_word_substring() {
        given(bannedWordRepository.findAllByOrderByWordAsc())
                .willReturn(List.of(BannedWord.builder().word("badword").build()));

        assertThatThrownBy(() -> policy.validate("myBadWordHere"))
                .satisfies(e -> assertThat(code(e)).isEqualTo("NICKNAME_BANNED"));
    }

    @Test
    @DisplayName("금칙어 검사는 정규화된 값에 한다 — 대소문자로 우회되지 않는다")
    void banned_check_is_case_insensitive() {
        given(bannedWordRepository.findAllByOrderByWordAsc())
                .willReturn(List.of(BannedWord.builder().word("spam").build()));

        assertThatThrownBy(() -> policy.validate("SPAMuser"))
                .satisfies(e -> assertThat(code(e)).isEqualTo("NICKNAME_BANNED"));
    }
}
