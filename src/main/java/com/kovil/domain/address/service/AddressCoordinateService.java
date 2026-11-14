package com.kovil.domain.address.service;

import com.kovil.domain.address.dto.response.AddressCoordinateResponse;
import com.kovil.infra.geocoding.GeocodedAddress;
import com.kovil.infra.geocoding.GeocodingClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

// 검색은 행정안전부(좌표 없음), 좌표는 고른 주소 하나만 네이버로(PO 11-02) — 검색어마다가 아니라 고를 때 한 번이라 호출이 크게 준다.
@Service
@RequiredArgsConstructor
public class AddressCoordinateService {

    // 주소 한 줄의 상한. 도로명에 참고항목(법정동 · 단지명)이 붙어도 이 안이다 — 넘는 값은 벤더에 그대로 넘기지 않는다.
    static final int MAX_ADDRESS_LENGTH = 200;

    private final GeocodingClient geocodingClient;

    // 도로명 → (못 찾으면) 지번 순으로 한 건씩 묻는다. 둘 다 못 찾으면 좌표 없이 — 앱이 그 후보를 고르지 않고 안내한다.
    // 벤더 오류(키 · 한도 · 장애)는 그대로 올린다(502) — "주소가 없다"와 "지금 못 찾는다"를 화면이 가를 수 있게.
    public AddressCoordinateResponse locate(String roadAddress, String jibunAddress) {
        for (String query : queriesOf(roadAddress, jibunAddress)) {
            Optional<GeocodedAddress> hit = geocodingClient.search(query, 1, 1).addresses().stream()
                    .filter(AddressCoordinateService::hasCoordinate)
                    .findFirst();
            if (hit.isPresent()) {
                return AddressCoordinateResponse.found(hit.get().longitude().strip(), hit.get().latitude().strip());
            }
        }
        return AddressCoordinateResponse.notFound();
    }

    private static List<String> queriesOf(String roadAddress, String jibunAddress) {
        List<String> queries = new ArrayList<>(2);
        for (String raw : new String[]{roadAddress, jibunAddress}) {
            String query = clip(raw == null ? "" : raw.strip());
            if (!query.isEmpty() && !queries.contains(query)) {
                queries.add(query);
            }
        }
        return queries;
    }

    private static boolean hasCoordinate(GeocodedAddress address) {
        return address.longitude() != null && !address.longitude().isBlank()
                && address.latitude() != null && !address.latitude().isBlank();
    }

    private static String clip(String query) {
        if (query.codePointCount(0, query.length()) <= MAX_ADDRESS_LENGTH) {
            return query;
        }
        return query.substring(0, query.offsetByCodePoints(0, MAX_ADDRESS_LENGTH)).strip();
    }
}
