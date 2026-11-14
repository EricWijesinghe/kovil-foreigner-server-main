package com.kovil.infra.juso.addresssearch;

import com.kovil.infra.addresssearch.AddressSearchClient;
import com.kovil.infra.addresssearch.exception.AddressSearchErrorCode;
import com.kovil.infra.addresssearch.exception.AddressSearchException;
import com.kovil.infra.geocoding.GeocodedAddress;
import com.kovil.infra.geocoding.GeocodedPage;
import com.kovil.infra.juso.addresssearch.dto.JusoSearchResponse;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/*
 * 행정안전부 도로명주소 검색(business.juso.go.kr). 네이버 지오코딩은 도로명과 건물번호가 다 있어야 후보를 주지만,
 * 여기는 건물명 · 동 이름으로도 찾는다. 무료이고 좌표는 주지 않는다 — 좌표는 고를 때 한 번 네이버로 받는다
 * (`/partner/address/coordinate`). 법정동 코드 · 읍면동 이름을 바로 줘서 지역 판정은 네이버보다 정확하다.
 *
 * 점검 · 장애 · 키 만료 · 연결 실패는 503 이다. 검색어가 잘못된 것(한 글자 · 특수문자만 등)은 빈 목록이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "address-search.provider", havingValue = "juso")
public class JusoAddressSearchClient implements AddressSearchClient {

    // 검색어 탓인 오류 — 행정안전부 오류 코드표(CLEANUS 10-30 확인과 같은 목록)
    private static final Set<String> INPUT_ERRORS =
            Set.of("E0005", "E0006", "E0008", "E0009", "E0010", "E0011", "E0012", "E0013", "E0015");
    private static final Pattern LEGAL_DONG_CODE = Pattern.compile("\\d{10}");

    private final JusoFeignClient feignClient;
    private final JusoProperties properties;

    // 키 없이 juso 로 켜지면 모든 검색이 조용히 503 이 된다 — 부팅에서 멈춰 배포 헬스체크가 실패하게(이전 이미지로 되돌아간다).
    // 만료(E0014)는 부팅에서 알 수 없어 검색 때 error 로그로 남는다.
    @PostConstruct
    void requireKey() {
        if (properties.confmKey() == null || properties.confmKey().isBlank()) {
            throw new IllegalStateException("address-search.provider=juso 인데 JUSO_SEARCH_CONFM_KEY 가 비어 있다");
        }
    }

    @Override
    public GeocodedPage search(String query, int page, int count) {
        Optional<String> keyword = JusoKeyword.of(query);
        if (keyword.isEmpty()) {
            return new GeocodedPage(0, page, List.of());
        }
        JusoSearchResponse.Results results;
        try {
            JusoSearchResponse response = feignClient.search(Map.of(
                    "confmKey", properties.confmKey(),
                    "currentPage", page,
                    "countPerPage", count,
                    "keyword", keyword.get(),
                    "resultType", "json"));
            results = response == null ? null : response.results();
        } catch (Exception e) {
            // 예외 객체를 찍지 않는다 — 벤더 예외 메시지에 요청 내용(검색어)이 섞일 수 있다.
            log.warn("[address] 행정안전부 주소 검색 실패 cause={}", e.getClass().getSimpleName());
            throw new AddressSearchException(AddressSearchErrorCode.ADDRESS_SEARCH_UNAVAILABLE);
        }
        String code = results == null || results.common() == null ? null : results.common().errorCode();
        if ("0".equals(code)) {
            return toPage(results, page);
        }
        // Set.of 는 contains(null) 에서 던진다 — 본문이 비어 온 것은 장애로 보낸다.
        if (code != null && INPUT_ERRORS.contains(code)) {
            return new GeocodedPage(0, page, List.of());
        }
        // 키 만료(E0014) · 미승인(E0001)은 사람이 키를 갈아야 끝난다 — 경보가 보이게 error 로 남긴다.
        log.error("[address] 행정안전부 주소 검색 오류 code={}", code);
        throw new AddressSearchException(AddressSearchErrorCode.ADDRESS_SEARCH_UNAVAILABLE);
    }

    private static GeocodedPage toPage(JusoSearchResponse.Results results, int page) {
        List<GeocodedAddress> addresses = results.juso() == null ? List.of() : results.juso().stream()
                .map(JusoAddressSearchClient::toAddress)
                .toList();
        return new GeocodedPage(total(results.common().totalCount(), addresses.size()), page, addresses);
    }

    // 도로명은 참고항목(법정동 · 단지명)까지 붙은 roadAddr — 단지 이름이 보여야 사람이 맞는 후보를 고른다.
    // 네이버 좌표 변환은 괄호가 붙은 채로도 같은 좌표를 준다(CLEANUS 10-30 확인).
    private static GeocodedAddress toAddress(JusoSearchResponse.Juso juso) {
        String legalDongCode = blankToNull(juso.admCd());
        if (legalDongCode != null && !LEGAL_DONG_CODE.matcher(legalDongCode).matches()) {
            legalDongCode = null;
        }
        return new GeocodedAddress(blankToNull(juso.roadAddr()), blankToNull(juso.jibunAddr()),
                blankToNull(juso.engAddr()), blankToNull(juso.zipNo()), blankToNull(juso.siNm()),
                blankToNull(juso.sggNm()), null, null, legalDongCode, blankToNull(juso.emdNm()));
    }

    private static long total(String raw, int fallback) {
        try {
            return raw == null || raw.isBlank() ? fallback : Long.parseLong(raw.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
