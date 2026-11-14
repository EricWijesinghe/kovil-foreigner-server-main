package com.kovil.domain.user.service.support;

import com.kovil.domain.user.exception.UserException;
import com.kovil.domain.user.repository.BannedWordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Pattern;

import static com.kovil.domain.user.exception.UserErrorCode.NICKNAME_BANNED;
import static com.kovil.domain.user.exception.UserErrorCode.NICKNAME_BLANK;
import static com.kovil.domain.user.exception.UserErrorCode.NICKNAME_INVALID_FORMAT;

// 사용자 입력 닉네임 정책(FR-108). 자동 생성 경로는 이 정책을 타지 않는다 —
// 그쪽 위생은 빌드 타임 CI 조합 테스트가 소유한다.
@Component
@RequiredArgsConstructor
public class NicknamePolicy {

    // 다국적 사용자 대상이라 라틴만 허용하지 않는다. 유니코드 문자·숫자면 되고
    // 공백·특수문자는 막는다(표시 깨짐·동형 이의어 방지).
    private static final Pattern FORMAT = Pattern.compile("^[\\p{L}\\p{N}]{2,20}$");

    private final BannedWordRepository bannedWordRepository;

    public String validate(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new UserException(NICKNAME_BLANK);
        }
        String trimmed = raw.trim();
        if (!FORMAT.matcher(trimmed).matches()) {
            throw new UserException(NICKNAME_INVALID_FORMAT);
        }

        String normalized = normalize(trimmed);
        // 닉네임 변경은 드문 이벤트라 매번 SELECT해도 부담이 아니다 —
        // 캐시를 두면 운영자가 금칙어를 등록해도 즉시 반영되지 않는다.
        boolean banned = bannedWordRepository.findAllByOrderByWordAsc().stream()
                .anyMatch(word -> normalized.contains(word.getWord()));
        if (banned) {
            throw new UserException(NICKNAME_BANNED);
        }
        return normalized;
    }

    public String normalize(String nickname) {
        return nickname.trim().toLowerCase(Locale.ROOT);
    }
}
