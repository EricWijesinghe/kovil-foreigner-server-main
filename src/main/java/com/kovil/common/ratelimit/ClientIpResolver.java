package com.kovil.common.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 속도 제한의 "누가 보냈나" 축.
 *
 * <p>★ 프록시 뒤에서는 {@code getRemoteAddr()}가 **프록시의 주소**다. 우리 배포는
 * Cloudflare → nginx → (관리자 웹은) Next rewrites 프록시 → 이 서버라, 그대로 두면
 * 관리자 웹의 모든 요청이 컨테이너 IP 하나로 보인다. 그러면 누구의 로그인 실패든 30회에
 * **전 직원이 10분간 차단**된다({@code signin-fail-ip}는 인증 전에 거절한다).
 *
 * <p>그렇다고 헤더를 무조건 믿으면 반대로 뚫린다 — 헤더는 누구나 위조한다. 그래서
 * **신뢰 대역에서 온 요청만** 헤더를 읽고, 체인은 오른쪽부터 훑어 신뢰하지 않는 첫 주소를
 * 고른다(위조된 값을 앞에 끼워 넣어도 우리 프록시가 덧붙인 진짜 주소가 그 오른쪽에 있다).
 */
@Component
@RequiredArgsConstructor
public class ClientIpResolver {

    private static final String X_FORWARDED_FOR = "X-Forwarded-For";

    private final RateLimitProperties properties;

    public String resolve(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        if (remoteAddr == null || remoteAddr.isBlank()) {
            return "unknown";
        }
        String client = trusted(remoteAddr) ? fromForwardedFor(request, remoteAddr) : remoteAddr;
        return normalize(client);
    }

    /** 체인의 오른쪽(우리와 가까운 쪽)부터 훑어 **신뢰하지 않는 첫 주소**가 진짜 클라이언트다. */
    private String fromForwardedFor(HttpServletRequest request, String fallback) {
        String header = request.getHeader(X_FORWARDED_FOR);
        if (header == null || header.isBlank()) {
            return fallback;
        }
        List<String> hops = new ArrayList<>(Arrays.asList(header.split(",")));
        for (int i = hops.size() - 1; i >= 0; i--) {
            String hop = stripPort(hops.get(i).trim());
            if (hop.isEmpty()) {
                continue;
            }
            if (!trusted(hop)) {
                return hop;
            }
        }
        // 전부 신뢰 대역이었다 — 내부 호출이거나 헤더가 비정상이다. 원격 주소로 돌아간다.
        return fallback;
    }

    // "1.2.3.4:56789" 또는 "[::1]:443" 형태로 오는 프록시가 있다.
    private String stripPort(String value) {
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            return end > 0 ? value.substring(1, end) : value;
        }
        int colon = value.indexOf(':');
        // IPv6 원형(콜론 여러 개)은 포트가 아니다.
        if (colon > 0 && value.indexOf(':', colon + 1) < 0) {
            return value.substring(0, colon);
        }
        return value;
    }

    private boolean trusted(String address) {
        for (String cidr : properties.trustedProxies()) {
            if (matches(address, cidr)) {
                return true;
            }
        }
        return false;
    }

    /** CIDR 접두 비교 — 주소 계열(IPv4/IPv6)이 다르면 애초에 같은 대역일 수 없다. */
    private boolean matches(String address, String cidr) {
        try {
            String[] parts = cidr.trim().split("/");
            InetAddress network = InetAddress.getByName(parts[0]);
            InetAddress target = InetAddress.getByName(address);
            byte[] networkBytes = network.getAddress();
            byte[] targetBytes = target.getAddress();
            if (networkBytes.length != targetBytes.length) {
                return false;
            }
            int prefix = parts.length > 1 ? Integer.parseInt(parts[1]) : networkBytes.length * 8;
            int fullBytes = prefix / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (networkBytes[i] != targetBytes[i]) {
                    return false;
                }
            }
            int remainingBits = prefix % 8;
            if (remainingBits == 0) {
                return true;
            }
            int mask = (0xff << (8 - remainingBits)) & 0xff;
            return (networkBytes[fullBytes] & mask) == (targetBytes[fullBytes] & mask);
        } catch (UnknownHostException | NumberFormatException | ArrayIndexOutOfBoundsException e) {
            // 설정 오타·해석 불가 주소는 "신뢰하지 않음"으로 떨어뜨린다 — 열어 두는 것보다 안전하다.
            return false;
        }
    }

    // IPv6는 /64가 통상 단말 하나에 할당되는 단위다. 전체 주소를 키로 쓰면
    // 같은 회선에서 주소만 바꿔가며 우회된다.
    private String normalize(String remoteAddr) {
        if (!remoteAddr.contains(":")) {
            return remoteAddr;
        }
        try {
            InetAddress address = InetAddress.getByName(remoteAddr);
            if (address instanceof Inet6Address ipv6) {
                byte[] bytes = ipv6.getAddress();
                StringBuilder prefix = new StringBuilder();
                for (int i = 0; i < 8; i += 2) {
                    if (i > 0) {
                        prefix.append(':');
                    }
                    prefix.append(Integer.toHexString(((bytes[i] & 0xff) << 8) | (bytes[i + 1] & 0xff)));
                }
                return prefix + "::/64";
            }
            return remoteAddr;
        } catch (UnknownHostException e) {
            return remoteAddr;
        }
    }
}
