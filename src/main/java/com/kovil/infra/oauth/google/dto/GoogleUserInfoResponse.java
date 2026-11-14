package com.kovil.infra.oauth.google.dto;

// OIDC userinfo 표준 클레임. sub가 provider sub다.
public record GoogleUserInfoResponse(String sub, String email, String name) {
}
