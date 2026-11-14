package com.kovil.domain.partner.service;

import com.kovil.domain.file.service.FileDeletionService;
import com.kovil.domain.listing.entity.BrokerageOffice;
import com.kovil.domain.listing.repository.BrokerProfileRepository;
import com.kovil.domain.listing.repository.BrokerageOfficeRepository;
import com.kovil.domain.partner.entity.BrokerageOfficeChangeRequest;
import com.kovil.domain.partner.entity.PartnerDivision;
import com.kovil.domain.partner.enums.PartnerDivisionStatus;
import com.kovil.domain.partner.exception.PartnerException;
import com.kovil.domain.partner.repository.BrokerageOfficeChangeRequestRepository;
import com.kovil.domain.partner.repository.PartnerDivisionRepository;
import com.kovil.domain.rentalrequest.enums.RentalRequestReturnCause;
import com.kovil.domain.rentalrequest.service.RentalRequestPoolReturner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.kovil.domain.partner.exception.PartnerErrorCode.PARTNER_SUSPENDED_CANNOT_WITHDRAW;

/*
 * 파트너가 탈퇴할 때 부문 쪽에서 닫을 것 — 한 곳에서 한 번에 한다.
 *   ① 대기 중인 부문은 반려로 닫는다(제출 여부 무관, 심사자 없음) — 대기열에 승인할 수 없는 익명 행이 남지 않게.
 *   ② 사무소가 잡고 있던 등록번호를 놓는다 — 같은 번호로 새 사람이 가입 · 승인받을 수 있게(PO 확정 2026-10-06).
 *   ③ 등록증 사본을 폐기 표시한다 — 관계가 끝나면 서류는 남기지 않는다. 승인 기록은 부문 행에 남는다.
 *   ④ 대기 중인 사무소 변경 신청은 반려로 닫고(심사자 없음) 그 등록증도 폐기 표시한다 — 대기열에 처리할 수 없는 익명 행이
 *      남지 않게. 사무소 행을 잠그고 대기 건을 잠금 읽기로 찾는다 — 같은 순간의 변경 신청 제출과 줄을 세운다(제출과 같은 순서).
 *   ⑤ 사무소가 맡은 진행 중 임대 요청은 대기로 되돌리고 유학생에게 알린다(PO 확정 2026-10-06) — 맡을 사람이 사라졌다.
 *      사무소 행을 잠근 **뒤에** 한다(그 행이 같은 사무소의 잡기와 줄을 세운다 — RentalRequestPoolReturner).
 *
 * ★★ 사무소는 대표자 연결(broker_profiles)로 찾는다. 그래서 **사무소 이탈(BrokerOffboardingService.offboard)보다
 *    먼저** 불려야 한다 — 이탈이 연결을 지우고 나면 이 사람의 사무소를 찾을 길이 없어 번호도 등록증도 그대로 남는다.
 * ★ 파트너가 아니면 아무 일도 하지 않는다(부문도 연결도 없다) — 일반 회원 탈퇴 경로에서도 부른다.
 * ★ 폐기는 원장에 표시만 한다. 실제 이동 · 파기는 정리 배치가 한다 — 여기서 스토리지를 건드리면 탈퇴 트랜잭션이
 *   롤백돼도 파일은 이미 사라진다. 정리 배치는 파일 행을 지우지 않아 사무소의 license_file_id(FK)와 부딪히지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PartnerWithdrawalService {

    // 심사 이력에서 사람이 한 반려와 가르는 문구다 — 인증 서류를 탈퇴로 닫을 때와 같은 문구를 쓴다.
    static final String WITHDRAWN_REASON = "회원이 탈퇴했습니다.";

    private final PartnerDivisionRepository divisionRepository;
    private final BrokerProfileRepository profileRepository;
    private final BrokerageOfficeRepository officeRepository;
    private final BrokerageOfficeChangeRequestRepository changeRequestRepository;
    private final FileDeletionService fileDeletionService;
    private final RentalRequestPoolReturner poolReturner;
    private final Clock clock;

    /*
     * 부문이 정지된 동안은 탈퇴를 거절한다(PO 확정 2026-10-07). 탈퇴는 아래에서 등록번호를 놓는데, 정지된 사람이
     * 탈퇴하고 같은 번호로 다시 가입하면 정지가 씻기고, 떠난 사무소는 심사 화면에도 나오지 않는다.
     * ★ 탈퇴 경로의 **맨 앞**(무엇이든 바꾸기 전)에서 부른다 — 세션 취소선(Redis)은 롤백되지 않는다.
     */
    @Transactional(readOnly = true)
    public void assertWithdrawable(Long userId) {
        boolean suspended = divisionRepository.findStatusesForUpdate(userId).stream()
                .anyMatch(status -> PartnerDivisionStatus.SUSPENDED.name().equals(status));
        if (suspended) {
            throw new PartnerException(PARTNER_SUSPENDED_CANNOT_WITHDRAW);
        }
    }

    @Transactional
    public void purgeOnWithdrawal(Long userId) {
        LocalDateTime now = LocalDateTime.now(clock);
        List<PartnerDivision> pending = divisionRepository.findByUserIdOrderByIdAsc(userId).stream()
                .filter(PartnerDivision::isPending)
                .toList();
        pending.forEach(division -> division.closeOnWithdrawal(WITHDRAWN_REASON, now));

        Optional<BrokerageOffice> office = profileRepository.findByUserId(userId)
                .flatMap(profile -> officeRepository.findById(profile.getOfficeId()));
        if (office.isEmpty()) {
            return;
        }
        officeRepository.lockById(office.get().getId());
        int returned = poolReturner.returnHeldBy(office.get().getId(), RentalRequestReturnCause.REPRESENTATIVE_WITHDRAWN, now);
        Optional<BrokerageOfficeChangeRequest> pendingChange =
                changeRequestRepository.findPendingForUpdate(office.get().getId());
        pendingChange.ifPresent(change -> change.closeOnWithdrawal(WITHDRAWN_REASON, now));
        office.get().releaseRegNo();

        // 사무소의 등록증과, 대기 중이던 변경 신청의 등록증(사무소 것과 같은 파일이면 한 번만).
        List<Long> licenses = new ArrayList<>();
        if (office.get().getLicenseFileId() != null) {
            licenses.add(office.get().getLicenseFileId());
        }
        pendingChange.map(BrokerageOfficeChangeRequest::getLicenseFileId)
                .filter(fileId -> !licenses.contains(fileId))
                .ifPresent(licenses::add);
        int discarded = licenses.isEmpty() ? 0 : fileDeletionService.discardByIds(licenses);
        log.info("[partner] 탈퇴 정리 user={} 대기 반려={}건 변경 신청 반려={}건 번호 해제 office={} 등록증 폐기={}건 요청 대기 복귀={}건",
                userId, pending.size(), pendingChange.isPresent() ? 1 : 0, office.get().getId(), discarded, returned);
    }
}
