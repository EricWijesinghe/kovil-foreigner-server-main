package com.kovil.domain.verification.service;

import com.kovil.domain.file.service.FileDeletionService;
import com.kovil.domain.verification.entity.ForeignerVerification;
import com.kovil.domain.verification.entity.ForeignerVerificationDocument;
import com.kovil.domain.verification.repository.ForeignerVerificationDocumentRepository;
import com.kovil.domain.verification.repository.ForeignerVerificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

// 신분 증빙 사진의 파기 시점을 소유한다.
//
// ★ **심사 완료가 아니라 탈퇴 시점이다**(PO 확정 2026-09-23). 임대는 계약이고, 계약 기간 중
//   문제가 생기면 "이 사람이 누구였는지"를 증명할 근거가 필요하다. 심사가 끝났다고 바로 버리면
//   그 근거가 사라진다. 관계가 끝나는 순간(탈퇴)이 파기 시점이다.
//
// ⚠ **바깥 문구 두 개가 지금 사실과 다르다**(HUMAN_TODO 52):
//   ① 앱 화면(6개 언어): "확인이 끝나면 이미지는 폐기됩니다" — 시점이 다르다(심사 완료 ≠ 탈퇴).
//   ② legal-site/delete-account.html: "제출 서류는 지체 없이 파기합니다" — **결과가 다르다.**
//      여기서 버리는 것은 "파기"가 아니라 `retained/3y/` 격리다. 그 페이지의 "보관되는 정보"
//      표에도 인증 서류 줄이 없다. 구글 플레이가 계정 삭제 안내로 가리키는 공개 페이지라
//      사실과 어긋난 채로 두면 안 된다.
//
// 판정(승인·반려·심사자·시각)은 DB 에 남는다 — 사라지는 것은 이미지뿐이다.
@Slf4j
@Service
@RequiredArgsConstructor
public class VerificationPurgeService {

    private final ForeignerVerificationRepository verificationRepository;
    private final ForeignerVerificationDocumentRepository documentRepository;
    private final FileDeletionService fileDeletionService;
    private final Clock clock;

    /** 재제출이 인증 행을 새로 만들므로 그 사람의 **모든** 건을 훑는다. */
    @Transactional
    public void purgeOnWithdrawal(Long userId) {
        List<ForeignerVerification> verifications =
                verificationRepository.findByUserIdOrderByIdDesc(userId);

        /*
         * ★ 심사 대기 건은 여기서 닫는다(PO 확정 2026-09-29).
         *
         *   닫지 않으면 대기열에 그 건이 그대로 남는데, 바로 아래에서 서류를 파기 표시하므로
         *   **심사자는 증빙을 한 장도 열지 못한 채 승인 버튼을 보게 된다.** 심사할 대상이
         *   사라진 상태에서 심사가 가능한 것 자체가 결함이다.
         *
         *   심사자 자리는 비운다 — 사람이 판단한 반려가 아니다.
         */
        LocalDateTime now = LocalDateTime.now(clock);
        verifications.stream()
                .filter(ForeignerVerification::isPending)
                .forEach(pending -> pending.reject(null, "회원이 탈퇴했습니다.", now));

        List<Long> verificationIds = verifications.stream()
                .map(ForeignerVerification::getId)
                .toList();
        if (verificationIds.isEmpty()) {
            return;
        }

        List<Long> fileIds = documentRepository.findByVerificationIdIn(verificationIds).stream()
                .map(ForeignerVerificationDocument::getFileId)
                .toList();
        int discarded = fileDeletionService.discardByIds(fileIds);
        if (discarded > 0) {
            log.info("[verification] 탈퇴 파기 표시 user={} 서류={}건", userId, discarded);
        }
    }
}
