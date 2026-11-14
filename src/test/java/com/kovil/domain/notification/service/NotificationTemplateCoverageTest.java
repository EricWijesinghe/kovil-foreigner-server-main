package com.kovil.domain.notification.service;

import com.kovil.domain.notification.enums.NotiType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.io.InputStreamReader;
import java.text.MessageFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// ★ 알림 타입을 추가하고 문구를 잊으면 유저에게 template_code가 그대로 나간다.
// 번들을 직접 읽어 전수 대조한다 — 빠진 키가 있으면 여기서 깨진다.
class NotificationTemplateCoverageTest {

    private Properties load(String path) throws IOException {
        Properties properties = new Properties();
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(path)) {
            assertThat(stream).as("%s", path).isNotNull();
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
        return properties;
    }

    // 앱이 지원하는 전 언어. 하나라도 키가 비면 그 언어 사용자만 조용히 영어 알림을 받는다 —
    // 아무도 신고하지 않으므로 영영 발견되지 않는다. 그래서 en·ko 만이 아니라 전수로 본다.
    private static final Map<String, String> BUNDLES = Map.of(
            "en", "messages/notifications.properties",
            "ko", "messages/notifications_ko.properties",
            "ja", "messages/notifications_ja.properties",
            "vi", "messages/notifications_vi.properties",
            "zh", "messages/notifications_zh.properties",
            "ne", "messages/notifications_ne.properties");

    @Test
    @DisplayName("모든 알림 타입이 지원 6개 언어 번들 전부에 title·body를 갖는다")
    void every_type_has_both_keys_in_every_bundle() throws IOException {
        for (Map.Entry<String, String> bundle : BUNDLES.entrySet()) {
            Properties messages = load(bundle.getValue());
            for (NotiType type : NotiType.values()) {
                String code = type.templateCode();
                assertThat(messages.getProperty(code + ".title"))
                        .as("%s %s.title", bundle.getKey(), code).isNotBlank();
                assertThat(messages.getProperty(code + ".body"))
                        .as("%s %s.body", bundle.getKey(), code).isNotBlank();
            }
        }
    }

    // 번역만 있고 원문에 없는 키는 오타이거나 삭제된 타입의 잔재다 — 양방향으로 본다.
    @Test
    @DisplayName("번역 번들에 영문 원문에 없는 키가 남아 있지 않다")
    void translations_have_no_orphan_keys() throws IOException {
        Properties en = load("messages/notifications.properties");
        for (Map.Entry<String, String> bundle : BUNDLES.entrySet()) {
            if ("en".equals(bundle.getKey())) {
                continue;
            }
            assertThat(load(bundle.getValue()).stringPropertyNames())
                    .as("%s 번들의 고아 키", bundle.getKey())
                    .isSubsetOf(en.stringPropertyNames());
        }
    }

    /*
     * ★ 반려 알림은 사유({0})를 싣는다 — 번역에서 {0} 이 빠지면 그 언어 사용자만 무엇을 고쳐 낼지 모른 채 반려 알림을 받는다.
     *   정지 알림은 사유를 싣지 않는다(관리자 내부 기록) — 자리표시자가 끼어들면 서버가 넘기지 않은 값이라 "{0}" 이 그대로 나간다.
     *   실제 포맷까지 돌린다 — 홑따옴표 하나가 {0} 을 리터럴로 만드는 함정이 있다(위 영문 번들 머리 주석).
     */
    @Test
    @DisplayName("부문 반려 본문은 전 언어에서 사유를 싣고, 정지 본문은 어느 언어에도 자리표시자가 없다")
    void partner_division_reason_placeholders() throws IOException {
        for (Map.Entry<String, String> bundle : BUNDLES.entrySet()) {
            Properties messages = load(bundle.getValue());
            String rejected = messages.getProperty(NotiType.PARTNER_DIVISION_REJECTED.templateCode() + ".body");
            assertThat(new MessageFormat(rejected).format(new Object[]{"SENTINEL-REASON"}))
                    .as("%s 반려 본문", bundle.getKey())
                    .contains("SENTINEL-REASON");
            assertThat(messages.getProperty(NotiType.PARTNER_DIVISION_SUSPENDED.templateCode() + ".body"))
                    .as("%s 정지 본문", bundle.getKey())
                    .doesNotContain("{");
        }
    }

    /*
     * ★ 사무소 변경 반려 알림도 사유({0})를 싣는다 — 빠지면 그 언어 사용자는 무엇을 고쳐 다시 낼지 모른다.
     *   승인 알림은 자리표시자가 없다 — 서버가 넘기지 않은 값이라 끼어들면 "{0}" 이 그대로 나간다.
     */
    @Test
    @DisplayName("사무소 변경 반려 본문은 전 언어에서 사유를 싣고, 승인 본문은 어느 언어에도 자리표시자가 없다")
    void office_change_reason_placeholders() throws IOException {
        for (Map.Entry<String, String> bundle : BUNDLES.entrySet()) {
            Properties messages = load(bundle.getValue());
            String rejected = messages.getProperty(NotiType.OFFICE_CHANGE_REJECTED.templateCode() + ".body");
            assertThat(new MessageFormat(rejected).format(new Object[]{"SENTINEL-REASON"}))
                    .as("%s 변경 반려 본문", bundle.getKey())
                    .contains("SENTINEL-REASON");
            assertThat(messages.getProperty(NotiType.OFFICE_CHANGE_APPROVED.templateCode() + ".body"))
                    .as("%s 변경 승인 본문", bundle.getKey())
                    .doesNotContain("{");
            assertThat(messages.getProperty(NotiType.OFFICE_CHANGE_APPROVED.templateCode() + ".title"))
                    .as("%s 변경 승인 제목", bundle.getKey())
                    .doesNotContain("{");
            assertThat(messages.getProperty(NotiType.OFFICE_CHANGE_REJECTED.templateCode() + ".title"))
                    .as("%s 변경 반려 제목", bundle.getKey())
                    .doesNotContain("{");
        }
    }

    /*
     * ★ 임대 요청 알림의 자리표시자는 서버가 넘기는 값과 정확히 맞아야 한다(RentalRequestNotifier).
     *   잡힘은 {0} 요청 번호 · {1} 사무소 상호 — 번역에서 {1} 이 빠지면 그 언어 사용자만 어느 사무소가 맡았는지 모른다.
     *   종료 · 다시 대기는 {0} 하나 — {1} 이 끼어들면 서버가 넘기지 않은 값이라 "{1}" 이 그대로 나간다.
     *   실제 포맷까지 돌린다(홑따옴표 함정 — 영문 번들 머리 주석).
     */
    @Test
    @DisplayName("임대 요청 잡힘은 전 언어에서 사무소 상호와 요청 번호를 싣고, 종료 · 다시 대기는 요청 번호만 싣는다")
    void rental_request_placeholders() throws IOException {
        Object[] claimed = {"RQ-SENTINEL", "SENTINEL-OFFICE"};
        Object[] numberOnly = {"RQ-SENTINEL"};
        for (Map.Entry<String, String> bundle : BUNDLES.entrySet()) {
            Properties messages = load(bundle.getValue());
            String code = NotiType.RENTAL_REQUEST_IN_PROGRESS.templateCode();
            String title = new MessageFormat(messages.getProperty(code + ".title")).format(claimed);
            String body = new MessageFormat(messages.getProperty(code + ".body")).format(claimed);
            assertThat(title).as("%s 잡힘 제목", bundle.getKey()).contains("SENTINEL-OFFICE").doesNotContain("{");
            assertThat(body).as("%s 잡힘 본문", bundle.getKey()).contains("RQ-SENTINEL").doesNotContain("{");

            for (NotiType type : List.of(NotiType.RENTAL_REQUEST_CLOSED, NotiType.RENTAL_REQUEST_RELEASED)) {
                String rawTitle = messages.getProperty(type.templateCode() + ".title");
                String rawBody = messages.getProperty(type.templateCode() + ".body");
                assertThat(rawTitle).as("%s %s 제목", bundle.getKey(), type).doesNotContain("{");
                assertThat(new MessageFormat(rawBody).format(numberOnly))
                        .as("%s %s 본문", bundle.getKey(), type)
                        .contains("RQ-SENTINEL").doesNotContain("{");
            }
        }
    }

    /*
     * ★ 사무소가 잡는 흐름 이전의 "처리 시작" 알림은 치환 값이 요청 번호 하나뿐이다. 알림함은 읽는 시점에 렌더하므로
     *   마이그레이션이 그 행들을 옛 문구 키로 옮겼다 — 그 키가 어느 언어에서 빠지면 옛 알림의 제목이 키 이름으로 나간다.
     *   NotiType 에 없는 키라 위의 전수 대조가 보지 못해 따로 본다.
     */
    @Test
    @DisplayName("옛 처리 시작 알림의 문구 키가 전 언어에 있고 요청 번호만 싣는다")
    void legacy_in_progress_key_exists_everywhere() throws IOException {
        String legacy = NotiType.RENTAL_REQUEST_IN_PROGRESS.templateCode() + "_legacy";
        for (Map.Entry<String, String> bundle : BUNDLES.entrySet()) {
            Properties messages = load(bundle.getValue());
            assertThat(messages.getProperty(legacy + ".title")).as("%s %s.title", bundle.getKey(), legacy)
                    .isNotBlank().doesNotContain("{");
            assertThat(new MessageFormat(messages.getProperty(legacy + ".body"))
                    .format(new Object[]{"RQ-SENTINEL"}))
                    .as("%s %s.body", bundle.getKey(), legacy)
                    .contains("RQ-SENTINEL").doesNotContain("{");
        }
    }

    // 번들 키는 타입 이름을 소문자로 옮긴 것이다 — 둘이 어긋날 여지를 없앤다.
    @Test
    @DisplayName("template_code는 타입 이름과 1:1이다")
    void template_code_mirrors_type_name() {
        for (NotiType type : NotiType.values()) {
            assertThat(type.templateCode()).isEqualTo(type.name().toLowerCase(Locale.ROOT));
        }
    }
}
