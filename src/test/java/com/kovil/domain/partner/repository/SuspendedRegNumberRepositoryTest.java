package com.kovil.domain.partner.repository;

import com.kovil.common.config.JpaAuditingConfig;
import com.kovil.domain.listing.entity.BrokerageOffice;
import com.kovil.domain.listing.repository.BrokerageOfficeRepository;
import com.kovil.domain.partner.dto.response.RegNoSuspensionView;
import com.kovil.domain.partner.service.RegNoSuspensionService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

/*
 * 정지 이력 — 남기고 읽기를 실제 DB 로 고정한다(서비스는 이 저장소와 사무소 저장소만 쓴다).
 * ★ 번호로 찾는다: 대표자가 떠나 번호가 풀리고 다른 사람이 같은 번호로 오면 사무소 · 사람으로는 이력이 이어지지 않는다.
 * ★ 그 사무소로도 찾는다: 정지됐던 사무소가 번호를 바꾸면 번호로는 자기 정지가 안 보인다.
 * ★ 상호는 저장하지 않고 그 사무소의 지금 상호를 읽는다.
 */
@DataJpaTest
@Import({JpaAuditingConfig.class, RegNoSuspensionService.class})
class SuspendedRegNumberRepositoryTest {

    private static final String REG_NO = "99999-2026-00002";
    private static final String OTHER_REG_NO = "99999-2026-00003";
    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 7, 9, 0);

    @Autowired RegNoSuspensionService service;
    @Autowired SuspendedRegNumberRepository repository;
    @Autowired BrokerageOfficeRepository officeRepository;
    @Autowired EntityManager entityManager;

    private BrokerageOffice office(String uuid, String name) {
        return officeRepository.saveAndFlush(BrokerageOffice.builder().uuid(uuid).regNo(REG_NO).name(name)
                .ceoName("홍길동").address("서울특별시 관악구").tel("02-000-0000").build());
    }

    /*
     * ★ 같은 번호의 정지는 사무소가 달라도(대표자가 떠나고 새 사람이 같은 번호로 온 경우) 한 이력으로 이어진다.
     *   복구된 정지도 남는다. 최근 정지가 먼저다. 사무소를 주지 않으면 다른 번호의 이력은 섞이지 않는다.
     */
    @Test
    @DisplayName("같은 번호의 정지는 사무소가 달라도 최근 순으로 이어지고, 상호는 그 사무소의 지금 값이다")
    void history_follows_the_number_across_offices() {
        BrokerageOffice left = office("o-left", "떠난공인중개사사무소");
        BrokerageOffice current = office("o-current", "지금공인중개사사무소");
        service.record(REG_NO, left.getId(), 11L, 1L, "허위 매물", AT.minusDays(200));
        service.record(REG_NO, current.getId(), 12L, 1L, "중개보수 초과", AT.minusDays(10));
        service.record(OTHER_REG_NO, current.getId(), 12L, 1L, "다른 번호", AT);
        entityManager.flush();
        entityManager.clear();

        List<RegNoSuspensionView> history = service.historyOf(List.of(REG_NO), null);

        assertThat(history).extracting(RegNoSuspensionView::officeUuid, RegNoSuspensionView::officeName,
                        RegNoSuspensionView::regNo, RegNoSuspensionView::reason, RegNoSuspensionView::suspendedAt)
                .containsExactly(
                        tuple("o-current", "지금공인중개사사무소", REG_NO, "중개보수 초과", AT.minusDays(10)),
                        tuple("o-left", "떠난공인중개사사무소", REG_NO, "허위 매물", AT.minusDays(200)));
        assertThat(service.historyOf(List.of("99999-2026-00099"), null)).isEmpty();
    }

    /*
     * ★ 정지됐던 사무소가 번호를 바꾼 뒤 — 새 번호로 심사해도 그 사무소 자신의 옛 정지가 따라온다(번호 OR 사무소).
     *   번호와 사무소 둘 다로 걸린 행은 한 번만 나온다. 정지 당시 번호가 그대로 실린다 — 화면이 "그때 번호"를 보여 준다.
     *   다른 사무소의 다른 번호 정지는 섞이지 않는다.
     */
    @Test
    @DisplayName("사무소로도 찾는다 — 번호를 바꾼 사무소의 옛 정지가 새 번호의 심사에 따라오고, 겹친 행은 한 번만 나온다")
    void history_follows_the_office_after_its_number_changed() {
        BrokerageOffice moved = office("o-moved", "옮긴공인중개사사무소");
        BrokerageOffice stranger = office("o-stranger", "남의공인중개사사무소");
        service.record(REG_NO, moved.getId(), 11L, 1L, "옛 번호 시절 정지", AT.minusDays(30));
        service.record(OTHER_REG_NO, moved.getId(), 11L, 1L, "새 번호로 다시 정지", AT.minusDays(1));
        service.record("99999-2026-00077", stranger.getId(), 13L, 1L, "남의 정지", AT);
        entityManager.flush();
        entityManager.clear();

        List<RegNoSuspensionView> history = service.historyOf(List.of(OTHER_REG_NO), moved.getId());

        assertThat(history).extracting(RegNoSuspensionView::officeUuid, RegNoSuspensionView::regNo,
                        RegNoSuspensionView::reason)
                .containsExactly(
                        tuple("o-moved", OTHER_REG_NO, "새 번호로 다시 정지"),
                        tuple("o-moved", REG_NO, "옛 번호 시절 정지"));
        // 번호가 하나도 없으면 묻지 않는다(IN () 를 만들지 않는다).
        assertThat(service.historyOf(List.of(), moved.getId())).isEmpty();
    }

    // 목록 한 페이지의 경고 표시 — 이 중 정지된 적이 있는 번호 · 사무소만, 여러 번 정지됐어도 한 번.
    @Test
    @DisplayName("목록용 표시는 정지된 적이 있는 번호 · 사무소를 중복 없이 주고, 한 줄의 경고는 그 줄의 번호 또는 사무소다")
    void marks_among_returns_distinct_numbers_and_offices() {
        BrokerageOffice o = office("o-1", "테스트공인중개사사무소");
        BrokerageOffice clean = office("o-2", "깨끗한공인중개사사무소");
        service.record(REG_NO, o.getId(), 11L, 1L, "사유", AT.minusDays(2));
        service.record(REG_NO, o.getId(), 11L, 1L, "사유", AT.minusDays(1));
        entityManager.flush();

        assertThat(repository.findRegNosIn(List.of(REG_NO, OTHER_REG_NO))).containsExactly(REG_NO);
        assertThat(repository.findOfficeIdsIn(List.of(o.getId(), clean.getId()))).containsExactly(o.getId());

        RegNoSuspensionService.SuspensionMarks marks = service.marksAmong(
                List.of(REG_NO, OTHER_REG_NO), List.of(o.getId(), clean.getId()));
        assertThat(marks.regNos()).containsExactly(REG_NO);
        assertThat(marks.officeIds()).containsExactly(o.getId());
        // 번호를 바꿔 이력 번호와 달라졌어도 그 사무소면 경고, 깨끗한 사무소의 깨끗한 번호면 경고 없음.
        assertThat(marks.any(o.getId(), OTHER_REG_NO)).isTrue();
        assertThat(marks.any(clean.getId(), REG_NO)).isTrue();
        assertThat(marks.any(clean.getId(), OTHER_REG_NO, null)).isFalse();

        RegNoSuspensionService.SuspensionMarks none = service.marksAmong(List.of(), List.of());
        assertThat(none.regNos()).isEmpty();
        assertThat(none.officeIds()).isEmpty();
    }
}
