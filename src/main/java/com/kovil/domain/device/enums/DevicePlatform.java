package com.kovil.domain.device.enums;

import com.kovil.domain.device.exception.DeviceException;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

import static com.kovil.domain.device.exception.DeviceErrorCode.DEVICE_PLATFORM_NULL;
import static com.kovil.domain.device.exception.DeviceErrorCode.INVALID_DEVICE_PLATFORM;

@Getter
@RequiredArgsConstructor
public enum DevicePlatform {
    IOS("IOS"), ANDROID("ANDROID"), WEB("WEB");

    private final String platform;

    public static DevicePlatform from(String value) {
        if (value == null) {
            throw new DeviceException(DEVICE_PLATFORM_NULL);
        }
        return Arrays.stream(values())
                .filter(v -> v.getPlatform().equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new DeviceException(INVALID_DEVICE_PLATFORM));
    }
}
