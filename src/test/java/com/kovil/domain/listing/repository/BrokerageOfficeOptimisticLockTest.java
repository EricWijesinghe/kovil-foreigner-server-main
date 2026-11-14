package com.kovil.domain.listing.repository;

import com.kovil.common.config.JpaAuditingConfig;
import com.kovil.domain.listing.entity.BrokerageOffice;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/*
 * 사무소 행의 겹친 쓰기 — 늦은 쪽이 앞사람이 쓴 칸을 낡은 값으로 덮지 않는다.
 *
 * ★ 무엇이 문제였나 — JPA 는 @DynamicUpdate 가 없으면 UPDATE 에 전 칼럼을 싣는다. 이 행을 쓰는 곳이 넷인데
 *   (제출 · 대표번호 변경 · 승인의 번호 잡기 · 탈퇴의 번호 놓기) 대표번호 변경은 일부러 부문을 건드리지 않아
 *   부문의 @Version 이 줄을 세워 주지 못한다. 그래서 대표번호 변경이 사무소를 읽은 뒤 승인이 번호를 잡고 커밋하면,
 *   대표번호 변경의 커밋이 claimed_reg_no 를 NULL 로 되돌려 같은 번호로 두 번째 승인이 가능해졌다.
 *
 * ★ 실제 두 트랜잭션으로 겹친다 — 바깥 트랜잭션이 읽어 둔 채로 안쪽(REQUIRES_NEW, 다른 EntityManager)이 커밋하고,
 *   그다음 바깥이 커밋한다. 서비스 테스트는 리포지토리를 목킹하므로 이 충돌을 만들 수 없다.
 *   바깥의 실패가 ObjectOptimisticLockingFailureException 이어야 GlobalExceptionHandler 가 409 로 번역한다 —
 *   커밋 시점 실패가 다른 예외로 감싸여 나오면 500 이 나간다. 그래서 예외 타입까지 본다.
 * ★ 커밋하는 테스트라 테스트 트랜잭션을 끄고, 만든 행은 직접 지운다 — 같은 컨텍스트(같은 DB)를 쓰는
 *   다른 리포지토리 테스트가 행 수를 센다.
 */
@DataJpaTest
@Import(JpaAuditingConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BrokerageOfficeOptimisticLockTest {

    private static final String REG_NO = "99999-2026-00011";
    private static final String TEL = "02-000-0000";
    private static final String NEW_TEL = "02-000-0001";

    @Autowired BrokerageOfficeRepository officeRepository;
    @Autowired PlatformTransactionManager transactionManager;

    private TransactionTemplate outer;
    private TransactionTemplate inner;
    private final List<Long> created = new ArrayList<>();

    @BeforeEach
    void setUp() {
        outer = new TransactionTemplate(transactionManager);
        inner = new TransactionTemplate(transactionManager);
        inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @AfterEach
    void cleanUp() {
        outer.executeWithoutResult(status -> officeRepository.deleteAllById(created));
    }

    private Long office(String claimedRegNo) {
        BrokerageOffice saved = outer.execute(status -> officeRepository.save(BrokerageOffice.builder()
                .uuid(UUID.randomUUID().toString()).regNo(REG_NO).claimedRegNo(claimedRegNo)
                .name("테스트공인중개사사무소").ceoName("홍길동").address("서울특별시 관악구").tel(TEL)
                .build()));
        created.add(saved.getId());
        return saved.getId();
    }

    private BrokerageOffice reload(Long id) {
        return outer.execute(status -> officeRepository.findById(id).orElseThrow());
    }

    @Test
    @DisplayName("승인이 번호를 잡고 커밋한 뒤의 대표번호 변경은 충돌이다 — 잡은 번호가 NULL 로 돌아가지 않는다")
    void tel_change_after_approval_conflicts_and_claim_survives() {
        Long id = office(null);

        assertThatThrownBy(() -> outer.executeWithoutResult(status -> {
            BrokerageOffice telView = officeRepository.findById(id).orElseThrow();
            inner.executeWithoutResult(s -> officeRepository.findById(id).orElseThrow().claimRegNo());
            telView.changeTel(NEW_TEL);
        })).isInstanceOf(ObjectOptimisticLockingFailureException.class);

        BrokerageOffice row = reload(id);
        assertThat(row.getClaimedRegNo()).isEqualTo(REG_NO);
        assertThat(row.getTel()).isEqualTo(TEL);
    }

    // 반대 순서 — 승인의 flush 가 옛 대표번호를 되써 넣으면 파트너가 200 을 받은 변경이 조용히 사라진다.
    @Test
    @DisplayName("대표번호 변경이 커밋된 뒤의 승인 flush 는 충돌이다 — 바뀐 대표번호가 되돌아가지 않는다")
    void approval_after_tel_change_conflicts_and_tel_survives() {
        Long id = office(null);

        assertThatThrownBy(() -> outer.executeWithoutResult(status -> {
            BrokerageOffice approveView = officeRepository.findById(id).orElseThrow();
            inner.executeWithoutResult(s -> officeRepository.findById(id).orElseThrow().changeTel(NEW_TEL));
            approveView.claimRegNo();
            officeRepository.flush();
        })).isInstanceOf(ObjectOptimisticLockingFailureException.class);

        BrokerageOffice row = reload(id);
        assertThat(row.getTel()).isEqualTo(NEW_TEL);
        assertThat(row.getClaimedRegNo()).isNull();
    }

    // 재제출이 바꾼 서류(번호 · 상호 · 등록증)를 대표번호 변경이 옛 값으로 되써 넣으면, 대기 중인 신청이 옛 서류 · 폐기된 등록증을 가리킨다.
    @Test
    @DisplayName("재제출이 커밋된 뒤의 대표번호 변경은 충돌이다 — 새로 낸 서류가 남는다")
    void tel_change_after_resubmit_conflicts_and_new_documents_survive() {
        Long id = office(null);

        assertThatThrownBy(() -> outer.executeWithoutResult(status -> {
            BrokerageOffice telView = officeRepository.findById(id).orElseThrow();
            inner.executeWithoutResult(s -> officeRepository.findById(id).orElseThrow()
                    .resubmit("99999-2026-00012", "새상호공인중개사사무소", "홍길동", "서울특별시 종로구", TEL, null, 902L));
            telView.changeTel(NEW_TEL);
        })).isInstanceOf(ObjectOptimisticLockingFailureException.class);

        BrokerageOffice row = reload(id);
        assertThat(row.getRegNo()).isEqualTo("99999-2026-00012");
        assertThat(row.getName()).isEqualTo("새상호공인중개사사무소");
        assertThat(row.getLicenseFileId()).isEqualTo(902L);
        assertThat(row.getTel()).isEqualTo(TEL);
    }

    // 탈퇴가 놓은 번호를 낡은 대표번호 변경이 되살리면, 대표자 없는 사무소가 번호를 영구히 잡는다(놓을 경로가 없다).
    @Test
    @DisplayName("탈퇴가 번호를 놓고 커밋한 뒤의 대표번호 변경은 충돌이다 — 놓은 번호가 되살아나지 않는다")
    void tel_change_after_release_conflicts_and_release_survives() {
        Long id = office(REG_NO);

        assertThatThrownBy(() -> outer.executeWithoutResult(status -> {
            BrokerageOffice telView = officeRepository.findById(id).orElseThrow();
            inner.executeWithoutResult(s -> officeRepository.findById(id).orElseThrow().releaseRegNo());
            telView.changeTel(NEW_TEL);
        })).isInstanceOf(ObjectOptimisticLockingFailureException.class);

        assertThat(reload(id).getClaimedRegNo()).isNull();
    }

    /*
     * 변경 승인이 번호를 옮기고 상호를 바꾼 뒤의 낡은 대표번호 변경 — 통과하면 옛 번호 · 옛 상호가 되살아나
     * 옮긴 번호를 다시 잡고(같은 번호의 다른 승인이 막히거나 유니크가 터진다) 승인된 값이 사라진다.
     */
    @Test
    @DisplayName("변경 승인이 커밋된 뒤의 대표번호 변경은 충돌이다 — 옮긴 번호와 바꾼 값이 남는다")
    void tel_change_after_change_approval_conflicts() {
        Long id = office(REG_NO);

        assertThatThrownBy(() -> outer.executeWithoutResult(status -> {
            BrokerageOffice telView = officeRepository.findById(id).orElseThrow();
            inner.executeWithoutResult(s -> officeRepository.findById(id).orElseThrow()
                    .applyApprovedChange("99999-2026-00013", "새상호공인중개사사무소", "홍길동", "서울특별시 종로구",
                            null, 903L));
            telView.changeTel(NEW_TEL);
        })).isInstanceOf(ObjectOptimisticLockingFailureException.class);

        BrokerageOffice row = reload(id);
        assertThat(row.getRegNo()).isEqualTo("99999-2026-00013");
        assertThat(row.getClaimedRegNo()).isEqualTo("99999-2026-00013");
        assertThat(row.getName()).isEqualTo("새상호공인중개사사무소");
        assertThat(row.getTel()).isEqualTo(TEL);
    }

    // 반대 순서 — 변경 승인의 flush 가 옛 대표번호를 되써 넣으면 파트너가 200 을 받은 대표번호 변경이 조용히 사라진다.
    @Test
    @DisplayName("대표번호 변경이 커밋된 뒤의 변경 승인 flush 는 충돌이다 — 바뀐 대표번호와 옛 번호가 그대로다")
    void change_approval_after_tel_change_conflicts() {
        Long id = office(REG_NO);

        assertThatThrownBy(() -> outer.executeWithoutResult(status -> {
            BrokerageOffice approveView = officeRepository.findById(id).orElseThrow();
            inner.executeWithoutResult(s -> officeRepository.findById(id).orElseThrow().changeTel(NEW_TEL));
            approveView.applyApprovedChange("99999-2026-00013", "새상호공인중개사사무소", "홍길동", "서울특별시 종로구",
                    null, 903L);
            officeRepository.flush();
        })).isInstanceOf(ObjectOptimisticLockingFailureException.class);

        BrokerageOffice row = reload(id);
        assertThat(row.getTel()).isEqualTo(NEW_TEL);
        assertThat(row.getRegNo()).isEqualTo(REG_NO);
        assertThat(row.getClaimedRegNo()).isEqualTo(REG_NO);
    }

    // 겹치지 않으면 아무 일도 없어야 한다 — 잠금이 순차 갱신까지 막으면 대표번호 변경이 늘 409 가 된다.
    @Test
    @DisplayName("겹치지 않는 순차 갱신은 그대로 지나간다")
    void sequential_updates_pass() {
        Long id = office(null);

        outer.executeWithoutResult(status -> officeRepository.findById(id).orElseThrow().claimRegNo());
        outer.executeWithoutResult(status -> officeRepository.findById(id).orElseThrow().changeTel(NEW_TEL));

        BrokerageOffice row = reload(id);
        assertThat(row.getClaimedRegNo()).isEqualTo(REG_NO);
        assertThat(row.getTel()).isEqualTo(NEW_TEL);
        assertThat(row.getVersion()).isEqualTo(2L);
    }
}
