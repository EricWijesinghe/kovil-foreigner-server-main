package com.kovil.domain.listing;

import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.user.repository.UserRepository;
import com.kovil.infra.jwt.JwtTokenProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * ★★ 비로그인 경계 (PO 확정 2026-10-05, 문의는 2026-11-03 번복): **열람은 열고, 문의 · 찜 · 번역은 닫는다.**
 *
 *    이 경계는 `SecurityConfig` 의 **문자열 패턴 하나**가 지킨다. 와일드카드를 한 글자
 *    넓히는 것만으로 찜과 번역이 함께 열리는데, 그건 컴파일도 되고 다른 테스트도 전부 통과한다.
 *    그래서 **양쪽을 다 박는다** — 열려야 할 것이 닫히는 것도, 닫혀야 할 것이 열리는 것도.
 *
 * ★ 없는 uuid 를 쓴다. 404 가 돌아왔다는 것은 **보안 필터를 지나 컨트롤러까지 갔다**는 뜻이고,
 *   401 이면 문 앞에서 막힌 것이다. 픽스처 없이 경계만 가른다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ListingAccessBoundaryTest {

    private static final String UNKNOWN = "no-such-listing-uuid";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository userRepository;

    @Autowired
    JwtTokenProvider jwtTokenProvider;

    @Test
    @DisplayName("비로그인도 목록을 본다 — 유입이 여기서 일어난다")
    void anonymous_can_browse_list() throws Exception {
        mockMvc.perform(get("/api/v1/listings").param("page", "0").param("size", "20"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("비로그인도 지도를 본다")
    void anonymous_can_browse_map() throws Exception {
        mockMvc.perform(get("/api/v1/listings/map")
                        .param("swLat", "37.0").param("swLng", "126.0")
                        .param("neLat", "38.0").param("neLng", "128.0"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("비로그인도 상세에 들어간다 — 401 이 아니라 404 여야 한다")
    void anonymous_can_open_detail() throws Exception {
        mockMvc.perform(get("/api/v1/listings/" + UNKNOWN))
                .andExpect(status().isNotFound());
    }

    /*
     * ★ 문의(중개사 번호)는 로그인한 사람만 — 가입 유도(PO 2026-11-03, 10-05 의 '비로그인 문의 열기'를 뒤집음).
     *   앱만 막으면 API 로 번호를 그대로 받는다. 로그인하면 문을 지나 컨트롤러가 404 로 답한다.
     */
    @Test
    @DisplayName("비로그인 문의는 401 — 로그인하면 문을 지난다")
    void inquiry_requires_login() throws Exception {
        mockMvc.perform(post("/api/v1/listings/" + UNKNOWN + "/inquiries"))
                .andExpect(status().isUnauthorized());

        User viewer = userRepository.save(User.builder()
                .uuid("listing-boundary-inquirer").status(UserStatus.ACTIVE).role(UserRole.USER)
                .preferredLanguage("ko").build());
        mockMvc.perform(post("/api/v1/listings/" + UNKNOWN + "/inquiries")
                        .header("Authorization", "Bearer " + jwtTokenProvider.issueAccessToken(viewer.getUuid(), UserRole.USER)))
                .andExpect(status().isNotFound());
    }

    // 주변시설은 상세가 공개라서 같이 연다 — 두 세그먼트 경로라 따로 열었다.
    @Test
    @DisplayName("비로그인도 주변시설을 본다 — 401 이 아니라 404 여야 한다")
    void anonymous_can_open_nearby() throws Exception {
        mockMvc.perform(get("/api/v1/listings/" + UNKNOWN + "/nearby"))
                .andExpect(status().isNotFound());
    }

    // ★ 주변시설 한 줄을 열면서 두 세그먼트 GET 전체를 연 것이 아니다 — 패턴을 /** 로 넓히면 여기서 걸린다.
    @Test
    @DisplayName("주변시설 말고 다른 두 세그먼트 GET 은 여전히 닫혀 있다")
    void other_two_segment_get_stays_closed() throws Exception {
        mockMvc.perform(get("/api/v1/listings/" + UNKNOWN + "/favorite"))
                .andExpect(status().isUnauthorized());
    }

    // 대학교 레이어는 비로그인 공개다(G-6) — 정확한 경로 하나만 열었다. 404 · 400 이 아니라 200 이면 문을 지나 컨트롤러가 답한 것이다.
    @Test
    @DisplayName("비로그인도 대학교 레이어를 본다 — 영역이 빠지면 401 이 아니라 400")
    void anonymous_can_see_universities() throws Exception {
        mockMvc.perform(get("/api/v1/universities")
                        .param("swLat", "37.0").param("swLng", "126.0").param("neLat", "38.0").param("neLng", "128.0"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/universities").param("swLat", "37.0"))
                .andExpect(status().isBadRequest());
    }

    // ★ 한 경로를 열면서 그 아래를 연 것이 아니다 — 패턴을 /** 로 넓히면 여기서 걸린다.
    @Test
    @DisplayName("대학교 경로 아래 · 다른 메서드는 여전히 닫혀 있다")
    void university_subpaths_stay_closed() throws Exception {
        mockMvc.perform(get("/api/v1/universities/x")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/universities")).andExpect(status().isUnauthorized());
    }

    // 지하철역 레이어도 비로그인 공개다(G-6) — 정확한 경로 하나만 열었다.
    @Test
    @DisplayName("비로그인도 지하철역 레이어를 본다 — 영역이 빠지면 401 이 아니라 400")
    void anonymous_can_see_subway_stations() throws Exception {
        mockMvc.perform(get("/api/v1/transit/subway-stations")
                        .param("swLat", "37.0").param("swLng", "126.0").param("neLat", "38.0").param("neLng", "128.0"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/transit/subway-stations").param("swLat", "37.0"))
                .andExpect(status().isBadRequest());
    }

    // ★ 역 경로 하나를 열면서 /transit/** 를 연 것이 아니다 — 버스 정류장 경로를 열 때 이 줄의 마지막 단언을 그 경로의 공개 단언으로 바꾼다.
    @Test
    @DisplayName("지하철역 경로 아래 · 다른 메서드 · 다른 교통 경로는 여전히 닫혀 있다")
    void subway_station_subpaths_stay_closed() throws Exception {
        mockMvc.perform(get("/api/v1/transit/subway-stations/x")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/transit/subway-stations")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/transit/bus-stops")).andExpect(status().isUnauthorized());
    }

    // 장소 검색도 비로그인 공개다(G-6) — 첫 화면 검색창이 로그인 전에 쓰인다.
    @Test
    @DisplayName("비로그인도 장소를 검색한다 — 검색어가 없어도 401 이 아니라 200")
    void anonymous_can_search_places() throws Exception {
        mockMvc.perform(get("/api/v1/search/places").param("query", "신촌"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/search/places"))
                .andExpect(status().isOk());
    }

    // ★ 장소 검색 경로 하나를 열면서 /search/** 를 연 것이 아니다 — 다른 검색 경로를 열 때 마지막 단언을 그 경로의 공개 단언으로 바꾼다.
    @Test
    @DisplayName("장소 검색 경로 아래 · 다른 메서드 · 다른 검색 경로는 여전히 닫혀 있다")
    void place_search_subpaths_stay_closed() throws Exception {
        mockMvc.perform(get("/api/v1/search/places/x")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/search/places")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/search/listings")).andExpect(status().isUnauthorized());
    }

    // 신고는 로그인해야 한다 — 비로그인 신고는 경쟁 사무소의 허위 신고 남발 통로다. 공개 POST 목록에 넣지 않았다.
    @Test
    @DisplayName("신고는 로그인해야 한다")
    void report_requires_login() throws Exception {
        mockMvc.perform(post("/api/v1/listings/" + UNKNOWN + "/reports")
                        .contentType("application/json").content("{\"reasonCode\":\"FAKE_LISTING\"}"))
                .andExpect(status().isUnauthorized());
    }

    // 찜은 사람에게 붙는 데이터다 — 비로그인에게는 붙일 곳이 없다.
    @Test
    @DisplayName("찜은 로그인해야 한다")
    void favorite_requires_login() throws Exception {
        mockMvc.perform(post("/api/v1/listings/" + UNKNOWN + "/favorite"))
                .andExpect(status().isUnauthorized());
    }

    /*
     * ★★ 번역은 **호출당 과금**이다. 비로그인에 열면 한도의 축이 사람에서 IP 로 떨어져,
     *    기숙사·쉐어하우스 Wi-Fi 뒤의 학생들이 한 통의 한도를 나눠 쓰게 된다 —
     *    그리고 공격자는 IP 를 바꿔 가며 그대로 통과한다.
     */
    @Test
    @DisplayName("번역은 로그인해야 한다 — 호출당 과금이라 사람 단위로 세야 한다")
    void translate_requires_login() throws Exception {
        mockMvc.perform(post("/api/v1/listings/" + UNKNOWN + "/description/translate")
                        .param("language", "en"))
                .andExpect(status().isUnauthorized());
    }
}
