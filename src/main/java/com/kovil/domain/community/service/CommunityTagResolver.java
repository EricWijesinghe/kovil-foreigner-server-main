package com.kovil.domain.community.service;

import com.kovil.domain.community.entity.CommunityTag;
import com.kovil.domain.community.exception.CommunityException;
import com.kovil.domain.community.repository.CommunityTagRepository;
import com.kovil.domain.user.entity.BannedWord;
import com.kovil.domain.user.repository.BannedWordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.kovil.domain.community.exception.CommunityErrorCode.TAG_BANNED;
import static com.kovil.domain.community.exception.CommunityErrorCode.TAG_INVALID;
import static com.kovil.domain.community.exception.CommunityErrorCode.TAG_LIMIT_EXCEEDED;

// FR-710 — 정규화(소문자 + 공백 제거) 컬럼이 유니크다. "Visa"·"visa"·"VISA"는 한 태그다.
// 표시 원문은 최초 등록자의 표기를 유지한다.
@Component
@RequiredArgsConstructor
public class CommunityTagResolver {

    private static final int MAX_TAGS = 10;
    private static final int MAX_LENGTH = 20;

    private final CommunityTagRepository tagRepository;
    private final CommunityTagIssuer tagIssuer;
    private final BannedWordRepository bannedWordRepository;

    // 입력 순서를 지키고 정규화 기준으로 중복을 접는다.
    public List<CommunityTag> resolve(List<String> rawTags) {
        if (rawTags == null || rawTags.isEmpty()) {
            return List.of();
        }
        if (rawTags.size() > MAX_TAGS) {
            throw new CommunityException(TAG_LIMIT_EXCEEDED);
        }
        List<String> banned = bannedWordRepository.findAllByOrderByWordAsc().stream()
                .map(BannedWord::getWord).map(String::toLowerCase).toList();

        Map<String, String> byNormalized = new LinkedHashMap<>();
        for (String raw : rawTags) {
            String display = raw == null ? "" : raw.strip().replaceFirst("^#", "");
            String normalized = display.toLowerCase().replaceAll("\\s+", "");
            if (normalized.isEmpty() || normalized.length() > MAX_LENGTH) {
                throw new CommunityException(TAG_INVALID);
            }
            if (banned.stream().anyMatch(normalized::contains)) {
                throw new CommunityException(TAG_BANNED);
            }
            byNormalized.putIfAbsent(normalized, display);
        }

        List<CommunityTag> resolved = new ArrayList<>();
        // 신규 태그는 발급기(REQUIRES_NEW)로 만든다 — find-then-save 를 이 트랜잭션에서 하면
        // 동시 신규 태그의 유니크 충돌이 글 작성 전체를 500 으로 죽인다(적대적 리뷰 2026-09-02).
        // 발급기가 준 엔티티는 detached 일 수 있다 — 집계 증감은 벌크 UPDATE 만 쓸 것.
        byNormalized.forEach((normalized, display) -> resolved.add(
                tagRepository.findByNameNormalized(normalized)
                        .orElseGet(() -> tagIssuer.issue(display, normalized))));
        return resolved;
    }
}
