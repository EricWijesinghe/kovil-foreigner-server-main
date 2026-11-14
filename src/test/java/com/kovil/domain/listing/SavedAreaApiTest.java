package com.kovil.domain.listing;

import com.kovil.domain.listing.entity.SavedArea;
import com.kovil.domain.listing.repository.SavedAreaRepository;
import com.kovil.domain.listing.service.SavedAreaService;
import com.kovil.domain.region.entity.Region;
import com.kovil.domain.region.repository.RegionRepository;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.user.repository.UserRepository;
import com.kovil.infra.jwt.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * 저장한 구역을 **진짜 보안 필터 · 진짜 검증 · 진짜 직렬화 · 진짜 쿼리**로 태운다(H2).
 * ★ 응답의 필터 칸 이름이 목록 API 파라미터와 같아야 앱이 그대로 다시 싣는다 — 이름을 하나씩 박는다.
 * ★ 남의 구역은 없는 것과 같은 404 다 — 지워지지도 않아야 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SavedAreaApiTest {

    private static final String PATH = "/api/v1/me/saved-areas";
    private static final String SQUARE = "37.55:126.90,37.55:126.94,37.59:126.94,37.59:126.90";

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired UserRepository userRepository;
    @Autowired SavedAreaRepository savedAreaRepository;
    @Autowired RegionRepository regionRepository;
    @Autowired SavedAreaService savedAreaService;

    private User me;
    private User other;
    private String bearer;
    private String otherBearer;

    @BeforeEach
    void setUp() {
        // 테스트 DB 에는 지역 시드가 없다 — 필터의 지역 코드는 열린 시도 아래의 열린 시군구여야 저장된다.
        Long seoul = regionRepository.saveAndFlush(Region.builder().code("11").nameKo("서울특별시").nameEn("Seoul").build()).getId();
        regionRepository.saveAndFlush(Region.builder().parentId(seoul).code("11410").nameKo("서대문구").nameEn("Seodaemun-gu").build());
        me = user("sa-api-me");
        other = user("sa-api-other");
        bearer = "Bearer " + jwtTokenProvider.issueAccessToken(me.getUuid(), UserRole.USER);
        otherBearer = "Bearer " + jwtTokenProvider.issueAccessToken(other.getUuid(), UserRole.USER);
    }

    private User user(String uuid) {
        return userRepository.save(User.builder().uuid(uuid).status(UserStatus.ACTIVE).role(UserRole.USER)
                .preferredLanguage("ko").build());
    }

    private ResultActions save(String token, String json) throws Exception {
        return mockMvc.perform(post(PATH).header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static String body(String name, String polygon) {
        return "{\"name\":\"" + name + "\",\"polygon\":\"" + polygon + "\"}";
    }

    @Test
    @DisplayName("비로그인은 목록 · 저장 · 지우기 모두 401")
    void anonymous_is_unauthorized() throws Exception {
        mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body("구역", SQUARE)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete(PATH + "/no-such")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("저장 → 응답에 정규화한 구역 · 같은 이름의 필터 · 앞뒤 공백 뺀 이름과 동 이름")
    void save_returns_normalized_area_and_filters() throws Exception {
        String json = """
                {"name":"  연세대 근처 ","placeLabel":" 창천동 ",
                 "polygon":"37.55:126.90,37.55:126.90,37.55:126.94,37.59:126.94,37.59:126.90,37.55:126.90",
                 "filters":{"category":"ONE_TWO_ROOM","sellingTypes":["MONTHLY_RENT","MONTHLY_RENT"],
                            "roomTypes":["ONE_ROOM","ONE_ROOM"],"floorTypes":["GROUND","GROUND"],
                            "depositMin":1000000,"rentMax":600000,"areaMin":20.5,"canParking":true,
                            "residentRegistration":"POSSIBLE","regionCode":"11410"}}
                """;
        save(bearer, json)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.savedAreaUuid").isNotEmpty())
                .andExpect(jsonPath("$.data.name").value("연세대 근처"))
                .andExpect(jsonPath("$.data.placeLabel").value("창천동"))
                .andExpect(jsonPath("$.data.polygon").value(SQUARE))
                .andExpect(jsonPath("$.data.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.data.filters.category").value("ONE_TWO_ROOM"))
                .andExpect(jsonPath("$.data.filters.sellingTypes").value(contains("MONTHLY_RENT")))
                .andExpect(jsonPath("$.data.filters.roomTypes").value(contains("ONE_ROOM")))
                .andExpect(jsonPath("$.data.filters.floorTypes").value(contains("GROUND")))
                .andExpect(jsonPath("$.data.filters.depositMin").value(1_000_000))
                .andExpect(jsonPath("$.data.filters.rentMax").value(600_000))
                .andExpect(jsonPath("$.data.filters.areaMin").value(20.5))
                .andExpect(jsonPath("$.data.filters.canParking").value(true))
                .andExpect(jsonPath("$.data.filters.residentRegistration").value("POSSIBLE"))
                .andExpect(jsonPath("$.data.filters.regionCode").value("11410"));

        // 목록에서 다시 읽어도 같다 — DB 의 JSON 을 되돌린다.
        mockMvc.perform(get(PATH).header("Authorization", bearer))
                .andExpect(jsonPath("$.data[0].polygon").value(SQUARE))
                .andExpect(jsonPath("$.data[0].filters.regionCode").value("11410"))
                .andExpect(jsonPath("$.data[0].filters.sellingTypes").value(contains("MONTHLY_RENT")));
    }

    @Test
    @DisplayName("목록은 내 것만 최근 순 — 남의 구역은 안 보인다")
    void list_is_mine_and_recent_first() throws Exception {
        save(bearer, body("처음", SQUARE)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.filters").isMap());
        save(bearer, body("나중", SQUARE)).andExpect(status().isOk());
        save(otherBearer, body("남의 것", SQUARE)).andExpect(status().isOk());

        mockMvc.perform(get(PATH).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].name").value(contains("나중", "처음")));
    }

    @Test
    @DisplayName("이름 · 동 이름 · 구역 · 필터 검증 — 400 과 그 코드")
    void validation() throws Exception {
        save(bearer, body("   ", SQUARE)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
        save(bearer, body("가".repeat(21), SQUARE)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
        save(bearer, "{\"name\":\"구역\",\"placeLabel\":\"" + "가".repeat(41) + "\",\"polygon\":\"" + SQUARE + "\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
        save(bearer, "{\"name\":\"구역\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("LISTING_POLYGON_INVALID"));
        save(bearer, body("구역", "37.55:126.90,37.59:126.94,37.55:126.94,37.59:126.90")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("LISTING_POLYGON_INVALID"));
        save(bearer, "{\"name\":\"구역\",\"polygon\":\"" + SQUARE + "\",\"filters\":{\"regionCode\":\"99999\"}}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("LISTING_REGION_INVALID"));
        save(bearer, "{\"name\":\"구역\",\"polygon\":\"" + SQUARE + "\",\"filters\":{\"areaMin\":1e-999999999}}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));

        save(bearer, "{\"name\":\"구역\",\"polygon\":\"" + SQUARE + "\",\"filters\":{\"regionCode\":\"" + "1".repeat(11) + "\"}}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
        save(bearer, "{\"name\":\"구역\",\"polygon\":\"" + SQUARE + "\",\"filters\":{\"areaMax\":100000001}}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
        // 이름 칸이 아예 없으면 서비스의 strip() 이 NPE 로 500 이 된다 — @NotBlank 가 앞에서 400 으로 막는다.
        save(bearer, "{\"polygon\":\"" + SQUARE + "\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));

        assertThat(savedAreaRepository.countByUserId(me.getId())).isZero();
    }

    // 지역 확인은 공백을 뺀 값으로 통과한다 — 원래 값을 저장하면 공백 수천 개가 JSON 칸을 넘겨 500 이 났다.
    @Test
    @DisplayName("지역 코드 앞뒤 공백은 빼고 저장한다 — 공백 2,000개를 덧붙여도 200, 응답은 11410")
    void padded_region_code_is_trimmed() throws Exception {
        save(bearer, "{\"name\":\"구역\",\"polygon\":\"" + SQUARE + "\",\"filters\":{\"regionCode\":\"11410" + " ".repeat(2_000) + "\"}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.filters.regionCode").value("11410"));
    }

    @Test
    @DisplayName("20개까지 — 21번째는 409 SAVED_AREA_LIMIT_EXCEEDED, 남의 구역은 내 개수에 들지 않는다")
    void limit_is_per_user() throws Exception {
        IntStream.range(0, 19).forEach(i -> stored(me, "구역 " + i));
        IntStream.range(0, 20).forEach(i -> stored(other, "남의 구역 " + i));

        save(bearer, body("스무 번째", SQUARE)).andExpect(status().isOk());
        save(bearer, body("스물한 번째", SQUARE))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SAVED_AREA_LIMIT_EXCEEDED"));

        assertThat(savedAreaRepository.countByUserId(me.getId())).isEqualTo(20);
    }

    @Test
    @DisplayName("지우기 — 내 것은 사라지고, 남의 것 · 없는 것은 404 SAVED_AREA_NOT_FOUND(남의 것은 그대로 남는다)")
    void delete_only_mine() throws Exception {
        String mine = stored(me, "내 구역").getUuid();
        String theirs = stored(other, "남의 구역").getUuid();

        mockMvc.perform(delete(PATH + "/" + theirs).header("Authorization", bearer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("SAVED_AREA_NOT_FOUND"));
        mockMvc.perform(delete(PATH + "/no-such").header("Authorization", bearer))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete(PATH + "/" + mine).header("Authorization", bearer))
                .andExpect(status().isOk());
        mockMvc.perform(delete(PATH + "/" + mine).header("Authorization", bearer))
                .andExpect(status().isNotFound());

        assertThat(savedAreaRepository.countByUserId(me.getId())).isZero();
        assertThat(savedAreaRepository.countByUserId(other.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("탈퇴 정리는 그 사람의 구역만 지운다")
    void purge_removes_only_that_user() {
        stored(me, "내 구역");
        stored(other, "남의 구역");

        savedAreaRepository.deleteAllByUserId(me.getId());

        assertThat(savedAreaRepository.countByUserId(me.getId())).isZero();
        assertThat(savedAreaRepository.countByUserId(other.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("경계 허용 — 이름 20자 · 동 이름 40자는 그대로 저장된다")
    void max_length_name_and_place_label_are_accepted() throws Exception {
        save(bearer, "{\"name\":\"" + "가".repeat(20) + "\",\"placeLabel\":\"" + "가".repeat(40) + "\",\"polygon\":\"" + SQUARE + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("가".repeat(20)))
                .andExpect(jsonPath("$.data.placeLabel").value("가".repeat(40)));
    }

    @Test
    @DisplayName("나머지 필터 칸도 목록 API 파라미터 이름 그대로 — 저장 응답 · 목록 응답 모두")
    void remaining_filter_names_match_list_api_params() throws Exception {
        String json = """
                {"name":"구역","polygon":"%s",
                 "filters":{"depositMax":5000000,"rentMin":100000,"areaMax":33.3,"shortLease":true}}
                """.formatted(SQUARE);
        save(bearer, json)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.filters.depositMax").value(5_000_000))
                .andExpect(jsonPath("$.data.filters.rentMin").value(100_000))
                .andExpect(jsonPath("$.data.filters.areaMax").value(33.3))
                .andExpect(jsonPath("$.data.filters.shortLease").value(true));

        mockMvc.perform(get(PATH).header("Authorization", bearer))
                .andExpect(jsonPath("$.data[0].filters.depositMax").value(5_000_000))
                .andExpect(jsonPath("$.data[0].filters.rentMin").value(100_000))
                .andExpect(jsonPath("$.data[0].filters.areaMax").value(33.3))
                .andExpect(jsonPath("$.data[0].filters.shortLease").value(true));
    }

    /*
     * ★ 탈퇴는 계정 상태를 먼저 바꾼 뒤 구역을 지운다. 정리 DELETE 가 컨텍스트를 비우기(clear) 전에 밀어 넣지(flush) 않으면
     *   앞서 바꾼 탈퇴 표시가 조용히 버려진다 — saved_areas 쿼리는 users 의 대기 변경을 스스로 flush 하지 않는다.
     */
    @Test
    @DisplayName("탈퇴 정리는 앞서 바뀐 계정 상태를 버리지 않는다 — 지우기 전에 밀어 넣고 컨텍스트를 비운다")
    void purge_flushes_pending_changes_before_clearing() {
        stored(me, "내 구역");
        other.withdraw(LocalDateTime.now());

        savedAreaService.purgeOnWithdrawal(me.getId());

        assertThat(savedAreaRepository.countByUserId(me.getId())).isZero();
        User back = userRepository.findById(other.getId()).orElseThrow();
        assertThat(back).isNotSameAs(other);
        assertThat(back.getStatus()).isEqualTo(UserStatus.WITHDRAWN);
        assertThat(back.getDeletedAt()).isNotNull();
    }

    private SavedArea stored(User owner, String name) {
        return savedAreaRepository.saveAndFlush(SavedArea.builder()
                .userId(owner.getId()).name(name).polygon(SQUARE).filters("{}").build());
    }
}
