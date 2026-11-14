package com.kovil.infra.oauth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "oauth.app")
public record OAuthAppSchemeProperties(String scheme) {
}
