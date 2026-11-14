package com.kovil.domain.verification.repository;

import com.kovil.domain.verification.entity.ResidentStatusDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ResidentStatusDocumentRepository extends JpaRepository<ResidentStatusDocument, Long> {

    // ★ 요구 문서는 언제나 여기서 읽는다(INV-03). 코드에 신분별 분기를 두지 않는다.
    // 순서는 이제 이 테이블이 정하지 않는다 — **문서 유형 축의 sortOrder가 단일 진실**이고,
    // 여기 sort_order 컬럼은 소비처가 없는 유산이다(드랍은 선택 부채로 기록).
    List<ResidentStatusDocument> findByResidentStatusId(Long residentStatusId);

    List<ResidentStatusDocument> findAllByOrderByResidentStatusIdAscIdAsc();

    boolean existsByResidentStatusIdAndDocumentTypeId(Long residentStatusId, Long documentTypeId);
}
