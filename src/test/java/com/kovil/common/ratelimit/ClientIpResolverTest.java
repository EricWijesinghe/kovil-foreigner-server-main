package com.kovil.common.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpResolverTest {

    private final ClientIpResolver resolver = new ClientIpResolver(new RateLimitProperties(false, null, null));

    private String resolve(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        return resolver.resolve(request);
    }

    @Test
    @DisplayName("IPv4는 그대로 키가 된다")
    void ipv4_passthrough() {
        assertThat(resolve("203.0.113.7")).isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("IPv6는 상위 64비트(/64)로 집계된다 — 주소 회전 우회 방지")
    void ipv6_aggregatedToSlash64() {
        assertThat(resolve("2001:db8:abcd:12:aaaa:bbbb:cccc:dddd")).isEqualTo("2001:db8:abcd:12::/64");
        assertThat(resolve("2001:db8:abcd:12:1:2:3:4")).isEqualTo("2001:db8:abcd:12::/64");
        assertThat(resolve("2001:db8:abcd:13::1")).isNotEqualTo(resolve("2001:db8:abcd:12::1"));
    }

    @Test
    @DisplayName("remoteAddr가 비거나 null이면 'unknown' 키로 묶인다 — 해석 실패가 차단 사유가 되면 안 된다")
    void blankOrNull_fallsBackToUnknown() {
        assertThat(resolve("")).isEqualTo("unknown");
        assertThat(resolve(null)).isEqualTo("unknown");
    }

    @Test
    @DisplayName("콜론 없는 비IP 문자열은 파싱 없이 그대로 키가 된다(호스트명 DNS 조회 금지 가드)")
    void nonIpWithoutColon_passthroughWithoutParsing() {
        assertThat(resolve("not-an-ip")).isEqualTo("not-an-ip");
    }

    @Test
    @DisplayName("IPv6 형태지만 무효한 리터럴은 원문 그대로 키가 된다")
    void malformedIpv6_passthrough() {
        assertThat(resolve("gggg::zzzz")).isEqualTo("gggg::zzzz");
    }

    // ★ SPEC_2026_08_16 §45 — 프록시 뒤에서 진짜 클라이언트를 찾는다.
    //
    // 이 값이 틀리면 관리자 웹의 모든 요청이 Next 컨테이너 IP 하나로 집계돼, 누구의 로그인
    // 실패든 30회에 **전 직원이 10분간 차단**된다(signin-fail-ip는 인증 전에 거절한다).
    // 반대로 헤더를 무조건 믿으면 위조 한 줄로 속도 제한이 무의미해진다 — 신뢰 대역이 축이다.
    private static final ClientIpResolver TRUSTING = new ClientIpResolver(
            new RateLimitProperties(false, List.of("127.0.0.1/32", "172.16.0.0/12"), null));

    private String resolveVia(String remoteAddr, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        return TRUSTING.resolve(request);
    }

    @Test
    @DisplayName("신뢰 대역에서 오면 X-Forwarded-For의 진짜 클라이언트를 쓴다")
    void trustedProxy_usesForwardedFor() {
        assertThat(resolveVia("172.18.0.5", "203.0.113.7")).isEqualTo("203.0.113.7");
        // Cloudflare→nginx→Next 체인: 오른쪽은 우리 프록시, 왼쪽이 진짜다.
        assertThat(resolveVia("127.0.0.1", "203.0.113.7, 172.18.0.5")).isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("위조된 앞자리는 무시한다 — 오른쪽부터 훑어 신뢰하지 않는 첫 주소를 고른다")
    void spoofedLeadingHopIsIgnored() {
        // 공격자가 헤더 앞에 아무 값이나 끼워 넣어도, 우리 프록시가 덧붙인 진짜 주소가 오른쪽에 있다.
        assertThat(resolveVia("127.0.0.1", "1.1.1.1, 203.0.113.7, 172.18.0.5"))
                .isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("신뢰하지 않는 원격에서 온 헤더는 무시한다 — 인터넷 직결은 위조가 자유롭다")
    void untrustedRemote_ignoresHeader() {
        assertThat(resolveVia("198.51.100.9", "203.0.113.7")).isEqualTo("198.51.100.9");
    }

    @Test
    @DisplayName("설정이 비어 있으면 아무 헤더도 믿지 않는다 — 기본값은 '안 믿는다'")
    void emptyTrustList_neverTrustsHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "203.0.113.7");
        ClientIpResolver strict = new ClientIpResolver(new RateLimitProperties(false, null, null));
        assertThat(strict.resolve(request)).isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("포트가 붙어 와도 주소만 쓴다")
    void stripsPort() {
        assertThat(resolveVia("127.0.0.1", "203.0.113.7:51234")).isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("체인이 전부 신뢰 대역이면 원격 주소로 돌아간다")
    void allHopsTrusted_fallsBack() {
        assertThat(resolveVia("127.0.0.1", "172.18.0.9, 127.0.0.1")).isEqualTo("127.0.0.1");
    }
}
