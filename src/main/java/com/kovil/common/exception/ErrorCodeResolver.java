package com.kovil.common.exception;

import com.kovil.common.security.exception.SecurityErrorCode;
import com.kovil.domain.appversion.exception.AppVersionErrorCode;
import com.kovil.domain.auth.exception.AuthErrorCode;
import com.kovil.domain.community.exception.CommunityErrorCode;
import com.kovil.domain.listing.exception.ListingErrorCode;
import com.kovil.infra.addresssearch.exception.AddressSearchErrorCode;
import com.kovil.infra.geocoding.exception.GeocodingErrorCode;
import com.kovil.domain.device.exception.DeviceErrorCode;
import com.kovil.domain.file.exception.FileErrorCode;
import com.kovil.infra.storage.exception.StorageErrorCode;
import com.kovil.domain.refreshtoken.exception.RefreshTokenErrorCode;
import com.kovil.domain.notification.exception.NotificationErrorCode;
import com.kovil.infra.llm.exception.LlmErrorCode;
import com.kovil.infra.ocr.exception.OcrErrorCode;
import com.kovil.domain.rentalrequest.exception.RentalRequestErrorCode;
import com.kovil.domain.user.exception.UserErrorCode;
import com.kovil.domain.partner.exception.PartnerErrorCode;
import com.kovil.domain.verification.exception.VerificationErrorCode;
import com.kovil.infra.crypto.exception.CryptoErrorCode;
import com.kovil.infra.jwt.exception.JwtErrorCode;
import com.kovil.infra.sms.exception.SmsErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class ErrorCodeResolver {

    private static final List<Class<? extends Enum<?>>> ERROR_CODE_CLASSES = List.of(
            CommonErrorCode.class,
            SecurityErrorCode.class,
            JwtErrorCode.class,
            CryptoErrorCode.class,
            SmsErrorCode.class,
            AuthErrorCode.class,
            RefreshTokenErrorCode.class,
            DeviceErrorCode.class,
            FileErrorCode.class,
            StorageErrorCode.class,
            PartnerErrorCode.class,
            VerificationErrorCode.class,
            UserErrorCode.class,
            RentalRequestErrorCode.class,
            NotificationErrorCode.class,
            OcrErrorCode.class,
            LlmErrorCode.class,
            // AUDIT_2026_08_26 — 아래 셋이 빠져 커뮤니티·마스터·앱버전 검증 실패가 전부
            // INVALID_REQUEST로 뭉개졌다(유저는 무엇이 잘못됐는지 알 수 없다).
            // 새 *ErrorCode enum을 만들면 반드시 여기에도 등록할 것 — 테스트가 전수 대조한다.
            CommunityErrorCode.class,
            ListingErrorCode.class,
            MasterErrorCode.class,
            GeocodingErrorCode.class,
            AddressSearchErrorCode.class,
            AppVersionErrorCode.class
    );

    public ErrorCode resolve(String errorCodeName) {
        if (errorCodeName == null || errorCodeName.isBlank()) {
            log.warn("ErrorCode name is null or blank");
            return CommonErrorCode.INVALID_REQUEST;
        }

        for (Class<? extends Enum<?>> clazz : ERROR_CODE_CLASSES) {
            ErrorCode errorCode = resolveFromEnum(clazz, errorCodeName);
            if (errorCode != null) {
                return errorCode;
            }
            errorCode = resolveByCodeValue(clazz, errorCodeName);
            if (errorCode != null) {
                return errorCode;
            }
        }

        log.warn("ErrorCode {} not found, using default", errorCodeName);
        return CommonErrorCode.INVALID_REQUEST;
    }

    private <E extends Enum<E>> ErrorCode resolveFromEnum(Class<?> enumClass, String name) {
        if (!enumClass.isEnum()) return null;
        try {
            @SuppressWarnings("unchecked")
            Class<E> typed = (Class<E>) enumClass;
            E constant = Enum.valueOf(typed, name);
            if (constant instanceof ErrorCode ec) return ec;
        } catch (IllegalArgumentException ignored) {
        }
        return null;
    }

    private <E extends Enum<E>> ErrorCode resolveByCodeValue(Class<?> enumClass, String codeValue) {
        if (!enumClass.isEnum()) return null;
        try {
            @SuppressWarnings("unchecked")
            Class<E> typed = (Class<E>) enumClass;
            for (E constant : typed.getEnumConstants()) {
                if (constant instanceof ErrorCode ec && ec.getCode().equals(codeValue)) {
                    return ec;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
