package com.kovil.domain.listing.repository;

import com.kovil.common.config.JpaAuditingConfig;
import com.kovil.domain.listing.entity.ListingMaintenanceItem;
import com.kovil.domain.listing.enums.MaintenanceItemCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// 목록 카드의 "+ α" 판정 쿼리. 매물마다 묻지 않고, 실비 비목이 있는 매물 id 만 한 번에 뽑는다.
@DataJpaTest
@Import(JpaAuditingConfig.class)
class ListingMaintenanceItemQueryTest {

    @Autowired ListingMaintenanceItemRepository itemRepository;

    @Test
    @DisplayName("실비 비목이 하나라도 있는 매물만, 한 번씩, 넘긴 매물 안에서만 돌려준다")
    void returns_listings_with_any_actual_cost_item() {
        item(1L, MaintenanceItemCode.ELECTRIC, true);
        item(1L, MaintenanceItemCode.WATER, true);
        item(1L, MaintenanceItemCode.COMMON, false);
        item(2L, MaintenanceItemCode.ELECTRIC, false);
        item(3L, MaintenanceItemCode.WATER, true);

        List<Long> ids = itemRepository.findListingIdsWithActualCost(List.of(1L, 2L, 4L));

        assertThat(ids).containsExactly(1L);
    }

    private void item(Long listingId, MaintenanceItemCode code, boolean actualCost) {
        itemRepository.saveAndFlush(ListingMaintenanceItem.builder()
                .listingId(listingId).itemCode(code).actualCost(actualCost)
                .cost(actualCost ? null : 10_000L).build());
    }
}
