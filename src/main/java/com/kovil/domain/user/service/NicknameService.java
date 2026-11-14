package com.kovil.domain.user.service;

import com.kovil.domain.user.repository.UserRepository;
import com.kovil.domain.user.service.support.NicknameGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class NicknameService {

    private static final int ATTEMPTS_PER_STAGE = 5;
    private static final int BASE_DIGITS = 4;
    private static final int MAX_DIGITS = 6;

    private final NicknameGenerator generator;
    private final UserRepository userRepository;

    // 가입 트랜잭션에서 호출된다(FR-106). 사용자에게 "닉네임을 만들지 못했다"는 오류가
    // 나가지 않는 것이 계약이다 — 사다리를 소진해도 후보를 돌려주고, 최종 판정은 유니크 제약에 맡긴다.
    public Allocation allocate() {
        String candidate = null;
        for (int digits = BASE_DIGITS; digits <= MAX_DIGITS; digits++) {
            for (int attempt = 0; attempt < ATTEMPTS_PER_STAGE; attempt++) {
                candidate = generator.generate(digits);
                if (isFree(candidate)) {
                    return Allocation.of(candidate, generator.normalize(candidate));
                }
            }
            log.warn("[nickname] {}자리 후보가 {}회 모두 충돌 — 자리수를 늘린다", digits, ATTEMPTS_PER_STAGE);
        }

        // 여기까지 왔다는 건 사전이 사실상 소진됐다는 뜻이다. 가입을 막는 것보다는
        // 제약이 잡게 두고 호출부가 재시도를 안내하는 편이 낫다.
        log.error("[nickname] 사다리 소진 — 사전 확장이 필요하다. 조합 공간을 확인하라");
        return Allocation.of(candidate, generator.normalize(candidate));
    }

    // 최종 판정이 아니라 유니크 제약에 걸리는 빈도를 낮추는 사전 컷이다.
    // 확인과 INSERT 사이의 경합은 제약이 잡는다.
    private boolean isFree(String candidate) {
        return !userRepository.existsByNicknameNormalized(generator.normalize(candidate));
    }

    public record Allocation(String nickname, String normalized) {
        public static Allocation of(String nickname, String normalized) {
            return new Allocation(nickname, normalized);
        }
    }
}
