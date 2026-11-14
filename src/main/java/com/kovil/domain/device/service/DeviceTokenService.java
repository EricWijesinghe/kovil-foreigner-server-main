package com.kovil.domain.device.service;

import com.kovil.common.exception.CommonErrorCode;
import com.kovil.domain.device.config.DeviceProperties;
import com.kovil.domain.device.dto.response.DeviceTokenResponse;
import com.kovil.domain.device.entity.DeviceToken;
import com.kovil.domain.device.enums.DevicePlatform;
import com.kovil.domain.device.exception.DeviceException;
import com.kovil.domain.device.repository.DeviceTokenRepository;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.service.UserQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import static com.kovil.domain.device.exception.DeviceErrorCode.DEVICE_TOKEN_NOT_FOUND;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceTokenService {

    private final DeviceTokenRepository deviceTokenRepository;
    private final UserQueryService userQueryService;
    private final DeviceProperties deviceProperties;
    private final Clock clock;

    @Transactional
    public DeviceTokenResponse register(String userUuid, String token, String platform) {
        User user = userQueryService.getActiveUser(userUuid);
        DevicePlatform devicePlatform = DevicePlatform.from(platform);
        LocalDateTime now = LocalDateTime.now(clock);

        DeviceToken deviceToken = deviceTokenRepository.findByToken(token)
                .map(existing -> {
                    existing.reassignTo(user.getId(), devicePlatform, now);
                    return existing;
                })
                .orElseGet(() -> insert(user.getId(), token, devicePlatform, now));

        pruneExcess(user.getId());
        return DeviceTokenResponse.from(deviceToken);
    }

    @Transactional
    public void unregister(String userUuid, String deviceUuid) {
        User user = userQueryService.getActiveUser(userUuid);

        DeviceToken deviceToken = deviceTokenRepository
                .findByUuidAndUserId(deviceUuid, user.getId())
                .orElseThrow(() -> new DeviceException(DEVICE_TOKEN_NOT_FOUND));

        deviceTokenRepository.delete(deviceToken);
    }

    @Transactional
    public void removeAllFor(Long userId) {
        deviceTokenRepository.deleteAllByUserId(userId);
    }

    private DeviceToken insert(Long userId, String token, DevicePlatform platform, LocalDateTime now) {
        try {
            return deviceTokenRepository.saveAndFlush(DeviceToken.register(userId, token, platform, now));
        } catch (DataIntegrityViolationException e) {
            if (isViolationOf(e, "uq_dt_token")) {
                throw new DeviceException(CommonErrorCode.CONFLICT);
            }
            throw e;
        }
    }

    private boolean isViolationOf(DataIntegrityViolationException e, String constraint) {
        return e.getCause() instanceof ConstraintViolationException violation
                && violation.getConstraintName() != null
                && violation.getConstraintName().toLowerCase().contains(constraint);
    }

    private void pruneExcess(Long userId) {
        List<DeviceToken> tokens = deviceTokenRepository.findAllByUserIdOrderByLastUsedAtDesc(userId);
        int max = deviceProperties.maxTokensPerUser();
        if (tokens.size() <= max) {
            return;
        }
        List<DeviceToken> excess = tokens.subList(max, tokens.size());
        deviceTokenRepository.deleteAll(excess);
        log.info("[device] 토큰 상한 초과 정리 userId={} kept={} removed={}", userId, max, excess.size());
    }
}
