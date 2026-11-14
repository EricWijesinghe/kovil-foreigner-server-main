package com.kovil.common.exception;

import com.kovil.common.security.exception.SecurityErrorCode;
import com.kovil.infra.jwt.exception.JwtErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.context.MessageSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;

import java.util.Locale;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = {MessageSourceAutoConfiguration.class, ErrorMessageResolver.class})
@TestPropertySource(properties = {
        "spring.messages.basename=messages/errors",
        "spring.messages.encoding=UTF-8",
        "spring.messages.fallback-to-system-locale=false",
        "spring.messages.use-code-as-default-message=false"
})
class ErrorMessageResolverTest {

    @Autowired
    ErrorMessageResolver resolver;

    @Test
    @DisplayName("한국어 요청은 한국어 문구를 받는다")
    void korean() {
        assertThat(resolver.resolve(CommonErrorCode.NOT_FOUND, Locale.KOREAN))
                .isEqualTo("요청하신 정보를 찾을 수 없어요.");
        assertThat(resolver.resolve(SecurityErrorCode.UNAUTHORIZED_ACCESS, Locale.KOREAN))
                .isEqualTo("로그인이 필요해요.");
    }

    @Test
    @DisplayName("영어 요청은 영어 문구를 받는다 — 한국어가 새지 않는다")
    void english() {
        String message = resolver.resolve(CommonErrorCode.NOT_FOUND, Locale.ENGLISH);

        assertThat(message).isEqualTo("The requested information could not be found.");
        assertThat(message).doesNotMatch(".*[가-힣].*");
    }

    // 베트남·몽골 사용자가 첫 화면에서 한국어를 보는 일이 없어야 한다.
    // fallback-to-system-locale=false 라야 서버 로케일(ko)로 새지 않는다.
    @Test
    @DisplayName("아직 번역하지 않은 언어는 서버 언어가 아니라 기본 번들(영어)로 떨어진다")
    void unsupported_locale_falls_back_to_base_bundle_not_server_locale() {
        for (Locale locale : new Locale[]{Locale.forLanguageTag("fr"), Locale.forLanguageTag("de"), Locale.forLanguageTag("es")}) {
            String message = resolver.resolve(CommonErrorCode.NOT_FOUND, locale);

            assertThat(message)
                    .as("locale=%s", locale)
                    .isEqualTo("The requested information could not be found.")
                    .doesNotMatch(".*[가-힣].*");
        }
    }

    @Test
    @DisplayName("지역 변형(ko-KR, en-US)도 언어 번들로 해석된다")
    void region_variants_resolve() {
        assertThat(resolver.resolve(CommonErrorCode.NOT_FOUND, Locale.KOREA))
                .isEqualTo("요청하신 정보를 찾을 수 없어요.");
        assertThat(resolver.resolve(CommonErrorCode.NOT_FOUND, Locale.US))
                .isEqualTo("The requested information could not be found.");
    }

    // 새 에러 코드를 만들고 번들에 키를 안 넣는 실수는 반드시 난다.
    // 그때 코드 이름("NOT_FOUND")이 그대로 노출되면 안 되고 enum의 기본 문구가 나가야 한다.
    @Test
    @DisplayName("번들에 키가 없으면 코드 이름이 아니라 ErrorCode의 기본 문구가 나간다")
    void missing_key_falls_back_to_default_message() {
        ErrorCode unregistered = new ErrorCode() {
            @Override
            public String getCode() {
                return "SOME_CODE_NOT_IN_BUNDLE";
            }

            @Override
            public String getDefaultMessage() {
                return "기본 문구";
            }

            @Override
            public HttpStatus getHttpStatus() {
                return HttpStatus.BAD_REQUEST;
            }
        };

        assertThat(resolver.resolve(unregistered, Locale.ENGLISH)).isEqualTo("기본 문구");
    }

    // 새 코드를 추가하고 번들에 영어를 안 넣으면, 영어 요청에 한국어 기본 문구가 나간다.
    // 그 순간을 이 테스트가 잡는다.
    @Test
    @DisplayName("모든 ErrorCode 상수가 영어 번들에 등록돼 있다 — 영어 응답에 한글이 없다")
    void every_error_code_has_english() {
        for (ErrorCode code : concat(CommonErrorCode.values(), SecurityErrorCode.values(), JwtErrorCode.values())) {
            assertThat(resolver.resolve(code, Locale.ENGLISH))
                    .as("%s 영어 번역 누락 — 한국어 기본 문구로 폴백됐다", code.getCode())
                    .isNotBlank()
                    .doesNotMatch(".*[가-힣].*");

            assertThat(resolver.resolve(code, Locale.KOREAN))
                    .as("%s 한국어 문구 누락", code.getCode())
                    .isNotBlank();
        }
    }

    private static ErrorCode[] concat(ErrorCode[]... groups) {
        return Arrays.stream(groups).flatMap(Arrays::stream).toArray(ErrorCode[]::new);
    }
}
