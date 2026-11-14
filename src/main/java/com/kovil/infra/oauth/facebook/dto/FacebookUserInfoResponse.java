package com.kovil.infra.oauth.facebook.dto;

// ⚠ email은 사용자가 권한을 거부하면 오지 않는다. 신원 축은 언제나 id다.
public record FacebookUserInfoResponse(String id, String name, String email) {
}
