package com.kovil.domain.listing.service;

import com.kovil.domain.listing.dto.request.SavedAreaCreateRequest;
import com.kovil.domain.listing.dto.request.SavedAreaFilters;
import com.kovil.domain.listing.dto.response.SavedAreaResponse;
import com.kovil.domain.listing.entity.SavedArea;
import com.kovil.domain.listing.enums.FloorType;
import com.kovil.domain.listing.enums.ListingCategory;
import com.kovil.domain.listing.enums.RoomType;
import com.kovil.domain.listing.enums.SellingType;
import com.kovil.domain.listing.exception.ListingErrorCode;
import com.kovil.domain.listing.exception.ListingException;
import com.kovil.domain.listing.repository.SavedAreaRepository;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.repository.UserRepository;
import com.kovil.domain.user.service.UserQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import tools.jackson.databind.json.JsonMapper;
import org.mockito.ArgumentMatchers;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/*
 * 저장한 구역의 분기 격자.
 * ★ 20개 상한은 '센 뒤 넣는다' 구조라 잠금이 **첫 문장**이어야 한다 — 순서를 InOrder 로 박는다.
 *   잠금 앞에 일반 조회가 끼면 읽기 뷰가 잠금 전에 고정돼 동시 저장이 상한을 뚫는다(실 MySQL 경합은 SavedAreaLimitRaceMySqlTest).
 * ★ 필터는 진짜 JsonMapper 로 직렬화한다 — 목이면 저장한 JSON 을 다시 읽을 수 있는지 아무도 보지 않는다.
 */
class SavedAreaServiceTest {

    private static final String ME = "u-me";
    private static final Long ME_ID = 7L;
    private static final String SQUARE = "37.55:126.90,37.55:126.94,37.59:126.94,37.59:126.90";

    private UserRepository userRepository;
    private UserQueryService userQueryService;
    private SavedAreaRepository savedAreaRepository;
    private ListingRegionResolver regionResolver;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private SavedAreaService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        userQueryService = mock(UserQueryService.class);
        savedAreaRepository = mock(SavedAreaRepository.class);
        regionResolver = mock(ListingRegionResolver.class);
        service = new SavedAreaService(userRepository, userQueryService, savedAreaRepository, regionResolver, jsonMapper);
        given(userRepository.findIdByUuidForUpdate(ME)).willReturn(Optional.of(ME_ID));
        given(userQueryService.getActiveUser(ME)).willReturn(User.builder().id(ME_ID).uuid(ME).build());
        given(savedAreaRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
    }

    private static SavedAreaCreateRequest request(String name, String polygon, SavedAreaFilters filters) {
        return new SavedAreaCreateRequest(name, null, polygon, filters);
    }

    private static String codeOf(Throwable e) {
        return ((ListingException) e).getErrorCode().getCode();
    }

    private SavedArea saved() {
        ArgumentCaptor<SavedArea> captor = ArgumentCaptor.forClass(SavedArea.class);
        verify(savedAreaRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("사용자 행 잠금이 첫 문장이다 — 활성 확인 · 개수 세기 · 저장보다 먼저")
    void lock_comes_first() {
        service.save(ME, request("연세대 근처", SQUARE, null));

        InOrder order = inOrder(userRepository, userQueryService, savedAreaRepository);
        order.verify(userRepository).findIdByUuidForUpdate(ME);
        order.verify(userQueryService).getActiveUser(ME);
        order.verify(savedAreaRepository).countByUserId(ME_ID);
        order.verify(savedAreaRepository).save(any());
    }

    /*
     * ★ 클래스가 readOnly 라 쓰기 메서드의 선언이 빠지면 읽기 전용 트랜잭션에서 돈다 — 운영 MySQL 이 INSERT · DELETE 를 거절한다(500).
     *   단위 · H2 테스트로는 안 깨지므로(API 테스트는 바깥 트랜잭션에 합류한다) 선언을 고정한다.
     */
    @Test
    @DisplayName("저장 · 지우기 · 탈퇴 정리는 쓰기 트랜잭션이다 — 클래스의 readOnly 를 물려받지 않는다")
    void writes_run_in_writable_tx() throws Exception {
        for (Method m : new Method[]{
                SavedAreaService.class.getMethod("save", String.class, SavedAreaCreateRequest.class),
                SavedAreaService.class.getMethod("delete", String.class, String.class),
                SavedAreaService.class.getMethod("purgeOnWithdrawal", Long.class)}) {
            Transactional tx =
                    m.getAnnotation(Transactional.class);
            assertThat(tx).as(m.getName()).isNotNull();
            assertThat(tx.readOnly()).as(m.getName()).isFalse();
        }
    }

    @Test
    @DisplayName("19개면 저장하고, 20개면 409 SAVED_AREA_LIMIT_EXCEEDED · 저장하지 않는다")
    void limit_is_twenty() {
        given(savedAreaRepository.countByUserId(ME_ID)).willReturn(19L);
        service.save(ME, request("열아홉 번째", SQUARE, null));
        verify(savedAreaRepository).save(any());

        reset(savedAreaRepository);
        given(savedAreaRepository.countByUserId(ME_ID)).willReturn(20L);
        assertThatThrownBy(() -> service.save(ME, request("스물한 번째", SQUARE, null)))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("SAVED_AREA_LIMIT_EXCEEDED"));
        verify(savedAreaRepository, never()).save(any());
        assertThat(ListingErrorCode.SAVED_AREA_LIMIT_EXCEEDED.getHttpStatus().value()).isEqualTo(409);
    }

    @Test
    @DisplayName("이름은 앞뒤 공백(전각 포함)을 빼고 1~20자 — 빼면 비거나 21자면 400 INVALID_REQUEST")
    void name_is_stripped_and_bounded() {
        service.save(ME, request("　 " + "가".repeat(20) + " ", SQUARE, null));
        assertThat(saved().getName()).isEqualTo("가".repeat(20));

        for (String bad : new String[]{"　", "가".repeat(21)}) {
            assertThatThrownBy(() -> service.save(ME, request(bad, SQUARE, null)))
                    .as(bad)
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo("INVALID_REQUEST"));
        }
    }

    @Test
    @DisplayName("구역이 없거나 틀리면 400 LISTING_POLYGON_INVALID — 저장하지 않는다")
    void polygon_is_required_and_validated() {
        for (String bad : new String[]{null, "", "37.55:126.90,37.59:126.94,37.55:126.94,37.59:126.90"}) {
            assertThatThrownBy(() -> service.save(ME, request("구역", bad, null)))
                    .as(String.valueOf(bad))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo("LISTING_POLYGON_INVALID"));
        }
        verify(savedAreaRepository, never()).save(any());
    }

    @Test
    @DisplayName("구역은 정규화해 저장한다 — 같은 점 연속 · 닫는 점을 뺀 꼭짓점")
    void polygon_is_normalized() {
        SavedAreaResponse response = service.save(ME, request("구역",
                "37.55:126.90,37.55:126.90,37.55:126.94,37.59:126.94,37.59:126.90,37.55:126.90", null));

        assertThat(saved().getPolygon()).isEqualTo(SQUARE);
        assertThat(response.polygon()).isEqualTo(SQUARE);
    }

    @Test
    @DisplayName("필터는 JSON 으로 저장하고 다시 읽으면 같은 값이다 — 겹친 목록 값은 하나로")
    void filters_round_trip() {
        given(regionResolver.resolveIdOrNull("11410")).willReturn(38L);
        SavedAreaFilters filters = new SavedAreaFilters(ListingCategory.ONE_TWO_ROOM,
                List.of(SellingType.MONTHLY_RENT, SellingType.MONTHLY_RENT),
                List.of(RoomType.ONE_ROOM, RoomType.ONE_ROOM), List.of(FloorType.GROUND, FloorType.GROUND),
                1_000_000L, 5_000_000L, null, 600_000L, new BigDecimal("20.5"), null,
                true, null, null, "11410");

        service.save(ME, request("필터 있음", SQUARE, filters));

        SavedAreaFilters back = jsonMapper.readValue(saved().getFilters(), SavedAreaFilters.class);
        assertThat(back.sellingTypes()).containsExactly(SellingType.MONTHLY_RENT);
        assertThat(back.roomTypes()).containsExactly(RoomType.ONE_ROOM);
        assertThat(back.floorTypes()).containsExactly(FloorType.GROUND);
        assertThat(back).isEqualTo(filters);
        assertThat(back.areaMin()).isEqualByComparingTo("20.5");
    }

    // 기대값을 NONE 상수가 아니라 리터럴로 둔다 — 상수가 바뀌어도 '필터 없음 = 모든 칸 null' 이 지켜지는지 본다.
    @Test
    @DisplayName("필터를 안 보내면 모든 칸이 null 인 빈 필터로 저장하고, 응답도 null 이 아닌 그 빈 필터다")
    void no_filters_is_empty() {
        SavedAreaResponse response = service.save(ME, request("필터 없음", SQUARE, null));

        SavedAreaFilters allNull = new SavedAreaFilters(null, null, null, null, null, null, null, null,
                null, null, null, null, null, null);
        assertThat(jsonMapper.readValue(saved().getFilters(), SavedAreaFilters.class)).isEqualTo(allNull);
        assertThat(response.filters()).isEqualTo(allNull);
    }

    // 지금 필드 범위로는 HTTP 로 닿지 않는 방어 분기다 — 필터 칸이 늘어 칸 폭(2,000자)을 넘길 때 500 대신 400 이 나도록 경계를 박는다.
    @Test
    @DisplayName("필터 JSON 은 2,000자까지 저장하고 2,001자면 400 INVALID_REQUEST — 저장하지 않는다")
    void filters_json_is_bounded_at_2000() {
        given(regionResolver.resolveIdOrNull(anyString())).willReturn(1L);
        int base = jsonMapper.writeValueAsString(regionOnly("1")).length();
        String fits = "1".repeat(2_000 - base + 1);

        service.save(ME, request("경계", SQUARE, regionOnly(fits)));
        assertThat(saved().getFilters()).hasSize(2_000);

        reset(savedAreaRepository);
        assertThatThrownBy(() -> service.save(ME, request("넘침", SQUARE, regionOnly(fits + "1"))))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("INVALID_REQUEST"));
        verify(savedAreaRepository, never()).save(any());
    }

    private static SavedAreaFilters regionOnly(String code) {
        return new SavedAreaFilters(null, null, null, null, null, null, null, null,
                null, null, null, null, null, code);
    }

    // 목록 API 가 거절할 필터를 저장하면 저장한 구역을 열 때마다 400 이 난다.
    @Test
    @DisplayName("목록 API 가 거절할 필터는 저장도 거절한다 — 닫힌 지역 · 면적 지수 표기")
    void unusable_filters_are_rejected() {
        given(regionResolver.resolveIdOrNull("99999"))
                .willThrow(new ListingException(ListingErrorCode.LISTING_REGION_INVALID));
        SavedAreaFilters closedRegion = new SavedAreaFilters(null, null, null, null, null, null, null, null,
                null, null, null, null, null, "99999");
        SavedAreaFilters hugeScale = new SavedAreaFilters(null, null, null, null, null, null, null, null,
                new BigDecimal("1e-999999999"), null, null, null, null, null);

        assertThatThrownBy(() -> service.save(ME, request("닫힌 지역", SQUARE, closedRegion)))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("LISTING_REGION_INVALID"));
        assertThatThrownBy(() -> service.save(ME, request("면적", SQUARE, hugeScale)))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("INVALID_REQUEST"));
        SavedAreaFilters hugeMax = new SavedAreaFilters(null, null, null, null, null, null, null, null,
                null, new BigDecimal("100000001"), null, null, null, null);
        assertThatThrownBy(() -> service.save(ME, request("면적 상한", SQUARE, hugeMax)))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("INVALID_REQUEST"));
        verify(savedAreaRepository, never()).save(any());
    }

    @Test
    @DisplayName("동 이름은 앞뒤 공백(전각 포함)을 빼고, 비면 null")
    void place_label_is_stripped() {
        service.save(ME, new SavedAreaCreateRequest("구역", "  창천동 ", SQUARE, null));
        assertThat(saved().getPlaceLabel()).isEqualTo("창천동");

        reset(savedAreaRepository);
        given(savedAreaRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        service.save(ME, new SavedAreaCreateRequest("구역", "   ", SQUARE, null));
        assertThat(saved().getPlaceLabel()).isNull();

        reset(savedAreaRepository);
        given(savedAreaRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        service.save(ME, new SavedAreaCreateRequest("구역", "\u3000 창천동 \u3000", SQUARE, null));
        assertThat(saved().getPlaceLabel()).isEqualTo("창천동");
    }

    @Test
    @DisplayName("내 구역만 지운다 — 0건이면(없거나 남의 것) 404 SAVED_AREA_NOT_FOUND")
    void delete_only_mine() {
        given(savedAreaRepository.deleteByUuidAndUserId("mine", ME_ID)).willReturn(1);
        given(savedAreaRepository.deleteByUuidAndUserId("theirs", ME_ID)).willReturn(0);

        service.delete(ME, "mine");
        assertThatThrownBy(() -> service.delete(ME, "theirs"))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("SAVED_AREA_NOT_FOUND"));
        verify(savedAreaRepository, never()).deleteByUuidAndUserId(anyString(), ArgumentMatchers.longThat(id -> !id.equals(ME_ID)));
    }

    @Test
    @DisplayName("목록은 최근 순 쿼리로 내 것만 읽고, 저장한 필터를 되돌린다")
    void find_mine() {
        SavedArea area = SavedArea.builder().id(1L).uuid("a-1").userId(ME_ID).name("구역")
                .polygon(SQUARE).filters(jsonMapper.writeValueAsString(SavedAreaFilters.NONE)).build();
        given(savedAreaRepository.findByUserIdOrderByCreatedAtDescIdDesc(ME_ID)).willReturn(List.of(area));

        List<SavedAreaResponse> mine = service.findMine(ME);

        assertThat(mine).extracting(SavedAreaResponse::savedAreaUuid).containsExactly("a-1");
        assertThat(mine.get(0).filters()).isEqualTo(SavedAreaFilters.NONE);
        verify(savedAreaRepository, never()).findAll();
    }

    /*
     * ★ 개수를 먼저 세고 0 이면 건너뛰면 안 된다 — 탈퇴 트랜잭션은 사용자 행을 잠그기 전에 이미 일반 읽기를 해서
     *   REPEATABLE READ 스냅샷이 고정돼 있다. 그 사이 다른 기기에서 저장한 첫 구역은 그 스냅샷의 COUNT 에 안 보여 탈퇴 뒤에도 남는다.
     *   DELETE 는 잠금 읽기라 최신 행을 본다 — 개수와 상관없이 늘 지운다.
     */
    @Test
    @DisplayName("탈퇴 정리 — 개수를 보지 않고 늘 그 사람 것만 지운다(스냅샷에 안 보이는 방금 저장한 구역까지)")
    void purge_on_withdrawal() {
        service.purgeOnWithdrawal(ME_ID);
        verify(savedAreaRepository).deleteAllByUserId(ME_ID);

        reset(savedAreaRepository);
        given(savedAreaRepository.countByUserId(8L)).willReturn(0L);
        service.purgeOnWithdrawal(8L);
        verify(savedAreaRepository).deleteAllByUserId(8L);
        verify(savedAreaRepository, never()).countByUserId(anyLong());
    }

    @Test
    @DisplayName("지역 코드는 앞뒤 공백을 빼고 저장하고, 비면 null — 확인한 값과 저장한 값이 같다")
    void region_code_is_normalized() {
        given(regionResolver.resolveIdOrNull("11410")).willReturn(38L);
        SavedAreaFilters padded = new SavedAreaFilters(null, null, null, null, null, null, null, null,
                null, null, null, null, null, "11410" + " ".repeat(2_000));

        service.save(ME, request("지역", SQUARE, padded));

        SavedAreaFilters back = jsonMapper.readValue(saved().getFilters(), SavedAreaFilters.class);
        assertThat(back.regionCode()).isEqualTo("11410");
        assertThat(new SavedAreaFilters(null, null, null, null, null, null, null, null,
                null, null, null, null, null, "   ").regionCode()).isNull();
    }
}
