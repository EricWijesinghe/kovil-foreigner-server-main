package com.kovil.common.ratelimit;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum RateLimitPolicy {

    PHONE_VERIFICATION_SEND(RateLimitKeyType.MANUAL),
    PHONE_VERIFICATION_SEND_IP(RateLimitKeyType.IP),
    PHONE_VERIFICATION_VERIFY(RateLimitKeyType.MANUAL),
    SMS_GLOBAL(RateLimitKeyType.GLOBAL),

    SIGNUP(RateLimitKeyType.IP),

    OAUTH_LOGIN(RateLimitKeyType.IP),
    REFRESH(RateLimitKeyType.IP),
    SIGNIN_FAIL_ID(RateLimitKeyType.MANUAL),
    SIGNIN_FAIL_IP(RateLimitKeyType.MANUAL),

    UPLOAD_PRESIGN(RateLimitKeyType.USER),

    LISTING_CREATE(RateLimitKeyType.USER),

    LISTING_UPDATE(RateLimitKeyType.USER),

    LISTING_INQUIRY(RateLimitKeyType.USER),
    LISTING_INQUIRY_GLOBAL(RateLimitKeyType.GLOBAL),

    LISTING_NEARBY(RateLimitKeyType.USER),
    LISTING_NEARBY_GLOBAL(RateLimitKeyType.GLOBAL),

    LISTING_TRANSLATE(RateLimitKeyType.USER),
    LISTING_TRANSLATE_GLOBAL(RateLimitKeyType.GLOBAL),

    LISTING_REPORT(RateLimitKeyType.USER),

    ADDRESS_SEARCH(RateLimitKeyType.USER_OR_IP),
    ADDRESS_SEARCH_DAILY(RateLimitKeyType.USER_OR_IP),
    ADDRESS_SEARCH_GUEST(RateLimitKeyType.GUEST_GLOBAL),
    ADDRESS_SEARCH_BURST(RateLimitKeyType.GLOBAL),
    ADDRESS_SEARCH_GLOBAL(RateLimitKeyType.GLOBAL),

    ADDRESS_COORDINATE(RateLimitKeyType.USER),
    ADDRESS_COORDINATE_DAILY(RateLimitKeyType.USER),
    ADDRESS_COORDINATE_GLOBAL(RateLimitKeyType.GLOBAL),

    DEFAULT_BACKSTOP(RateLimitKeyType.USER_OR_IP);

    private final RateLimitKeyType keyType;
}