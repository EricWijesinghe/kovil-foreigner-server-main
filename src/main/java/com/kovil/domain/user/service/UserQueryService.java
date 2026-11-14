package com.kovil.domain.user.service;

import com.kovil.domain.country.repository.CountryRepository;
import com.kovil.domain.partner.repository.PartnerDivisionRepository;
import com.kovil.domain.user.dto.response.ConsentResponse;
import com.kovil.domain.user.dto.response.MeResponse;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.entity.UserConsent;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.exception.UserException;
import com.kovil.domain.user.repository.UserConsentRepository;
import com.kovil.domain.user.repository.UserRepository;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.user.exception.UserErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.kovil.domain.user.exception.UserErrorCode.USER_NOT_FOUND;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserQueryService {

    private final UserRepository userRepository;
    private final CountryRepository countryRepository;
    private final UserConsentRepository userConsentRepository;
    private final PartnerDivisionRepository partnerDivisionRepository;

    // 모든 서비스가 지나는 관문이다 — 정지 검사가 여기 있으면 살아 있는 토큰도 막힌다.
    public User getActiveUser(String userUuid) {
        User user = userRepository.findByUuidAndDeletedAtIsNull(userUuid)
                .orElseThrow(() -> new UserException(USER_NOT_FOUND));
        if (user.getStatus() == UserStatus.SUSPENDED) {
            throw new UserException(UserErrorCode.USER_SUSPENDED);
        }
        return user;
    }

    public MeResponse getMe(String userUuid) {
        User user = getActiveUser(userUuid);
        String countryCode = user.getNationalityCountryId() == null ? null
                : countryRepository.findById(user.getNationalityCountryId())
                        .map(country -> country.getCode()).orElse(null);
        // 화면용 권한이다 — DB 의 지금 부문 상태로 센다. 인가는 토큰이 하고, 부문 처분 중 세션을 끊는 것은 정지뿐이다.
        // 그래서 승인 · 복구 · 반려 뒤에는 리프레시 전까지 토큰의 권한과 어긋날 수 있다.
        return MeResponse.of(user, countryCode, user.getRole() == UserRole.PARTNER
                ? partnerDivisionRepository.findApprovedTypes(user.getId()) : List.of());
    }

    // 이력은 append-only라 타입마다 여러 행이 쌓인다(FR-111).
    // 현재 상태는 타입별 최신 행 하나다 — 그것만 추린다.
    public List<ConsentResponse> getCurrentConsents(String userUuid) {
        User user = getActiveUser(userUuid);

        Set<String> seen = new HashSet<>();
        List<ConsentResponse> current = new ArrayList<>();
        for (UserConsent consent : userConsentRepository.findByUserIdOrderByIdDesc(user.getId())) {
            if (seen.add(consent.getConsentType().name())) {
                current.add(ConsentResponse.from(consent));
            }
        }
        return current;
    }
}
