package com.kovil.infra.oauth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OAuthWebPropertiesTest {

    // env 파일의 `OAUTH_WEB_BASE_URL=` 빈 줄은 yaml 기본값을 건너뛰고 빈 문자열로 들어온다. 그때 부팅이 멈추면 안 된다.
    @Test
    @DisplayName("빈 값이면 운영 기본 주소로 돌린다")
    void blank_falls_back_to_production_default() {
        assertThat(new OAuthWebProperties("").baseUrl()).isEqualTo("https://kovil.co.kr");
        assertThat(new OAuthWebProperties("  ").baseUrl()).isEqualTo("https://kovil.co.kr");
        assertThat(new OAuthWebProperties(null).baseUrl()).isEqualTo("https://kovil.co.kr");
    }

    @Test
    @DisplayName("끝의 / 는 떼고 로컬 http 주소도 받는다")
    void trims_trailing_slash() {
        assertThat(new OAuthWebProperties("http://localhost:8081/").baseUrl()).isEqualTo("http://localhost:8081");
    }

    @Test
    @DisplayName("http(s) 가 아닌 값은 부팅에서 멈춘다")
    void rejects_non_http_scheme() {
        assertThatThrownBy(() -> new OAuthWebProperties("kovil://oauth"))
                .isInstanceOf(IllegalStateException.class);
    }
}
