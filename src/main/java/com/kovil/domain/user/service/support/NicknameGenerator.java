package com.kovil.domain.user.service.support;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.List;

// 형용사 + 명사 + 숫자(FR-106). 유일성은 여기서 판정하지 않는다 —
// 후보만 만들고 최종 방어선은 uq_users_nickname_normalized다.
@Component
@RequiredArgsConstructor
public class NicknameGenerator {

    private final NicknameDictionary dictionary;

    public String generate(int digits) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        String adjective = pick(dictionary.getAdjectives(), random);
        String noun = pick(dictionary.getNouns(), random);
        int bound = (int) Math.pow(10, digits);
        // 앞자리 0을 허용한다 — 자리수가 곧 변형 공간이다(4자리 = 1만 배).
        String number = String.format("%0" + digits + "d", random.nextInt(bound));
        return capitalize(adjective) + capitalize(noun) + number;
    }

    // 허용 문자가 영문·숫자뿐이라 정규화는 소문자 변환이 전부다.
    public String normalize(String nickname) {
        return nickname.trim().toLowerCase(Locale.ROOT);
    }

    private String pick(List<String> words, ThreadLocalRandom random) {
        return words.get(random.nextInt(words.size()));
    }

    private String capitalize(String word) {
        return Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }
}
