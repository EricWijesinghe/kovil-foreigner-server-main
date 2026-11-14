package com.kovil.domain.listing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.text.MessageFormat;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * 매물 알림 문구의 **자리표시자**. 번들 커버리지 테스트는 키가 있는지만 본다 — 한 언어가 {0} 을 빠뜨리거나
 * 옛 의미(매물 번호)로 되돌아가도 초록이다. 지역 닫힘 알림의 {0} 은 내려간 **건수**라(중개사별 1건, PO 확정 2026-10-06)
 * 빠지면 "매물이 내려갔습니다"만 남아 몇 건인지 모른다.
 */
class ListingNotificationTemplateTest {

    private static final String[] BUNDLES = {"", "_ko", "_ja", "_ne", "_vi", "_zh"};

    @Test
    @DisplayName("지역 닫힘 알림 본문은 6개 언어 모두 건수 {0} 을 싣는다")
    void region_closed_body_carries_the_count_everywhere() throws Exception {
        for (String suffix : BUNDLES) {
            String body = load("messages/notifications" + suffix + ".properties").getProperty("listing_region_closed.body");
            assertThat(body).as("notifications%s", suffix).isNotBlank().contains("{0}").doesNotContain("{1}");
        }
    }

    @Test
    @DisplayName("만료 알림 본문은 6개 언어 모두 매물 번호 {0} 과 연장 마감일 {1} 을 싣는다")
    void expired_body_carries_number_and_deadline_everywhere() throws Exception {
        for (String suffix : BUNDLES) {
            String body = load("messages/notifications" + suffix + ".properties").getProperty("listing_expired.body");
            assertThat(body).as("notifications%s", suffix).contains("{0}").contains("{1}");
        }
    }

    // 차단 사유는 관리자 내부 기록이다 — 자리표시자가 하나 더 끼면 서버가 넘기지 않은 "{1}" 이 그대로 나가거나, 누가 사유를 실으려 한 흔적이다.
    @Test
    @DisplayName("차단 알림 본문은 6개 언어 모두 매물 번호 {0} 만 싣는다")
    void blocked_body_carries_only_the_number_everywhere() throws Exception {
        for (String suffix : BUNDLES) {
            String body = load("messages/notifications" + suffix + ".properties").getProperty("listing_blocked.body");
            assertThat(body).as("notifications%s", suffix).isNotBlank().contains("{0}").doesNotContain("{1}");
            assertThat(new MessageFormat(body).format(new Object[]{"LS-261012-AAAAAA"}))
                    .as("notifications%s", suffix).contains("LS-261012-AAAAAA");
        }
    }

    private Properties load(String path) throws Exception {
        Properties props = new Properties();
        try (var in = getClass().getClassLoader().getResourceAsStream(path)) {
            assertThat(in).as(path).isNotNull();
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return props;
    }
}
