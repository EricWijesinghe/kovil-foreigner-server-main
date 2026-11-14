package com.kovil.domain.listing.repository;

import com.kovil.domain.listing.entity.ListingDescriptionTranslation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ListingDescriptionTranslationRepository
        extends JpaRepository<ListingDescriptionTranslation, Long> {

    /*
     * ★★ 캐시 히트의 **유일한 질문**이다. 셋 중 하나라도 빠지면 틀린 번역이 나간다 —
     *     특히 sourceVersion 을 빼면 중개사가 설명을 고쳐도 옛 번역이 계속 나가서
     *     유학생이 **존재하지 않는 조건**을 읽는다.
     */
    Optional<ListingDescriptionTranslation> findByListingIdAndLanguageCodeAndSourceVersion(
            Long listingId, String languageCode, int sourceVersion);

    /*
     * ★★ **쓸 수 없게 된 캐시 행을 치운다.** 비켜 가는 것만으로는 부족하다 —
     *    유니크 키 `uq_ldt(listing_id, language_code, source_version)` 때문에 그 자리를 비우지
     *    않으면 **새 번역을 저장할 수 없고**, 저장 실패 복구 경로가 **낡은 행을 다시 집어** 돌려준다.
     *    그러면 과금만 매번 일어나고 결과는 버려진다(적대적 리뷰 2차 2026-10-06).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            "DELETE FROM ListingDescriptionTranslation t "
                    + "WHERE t.listingId = :listingId AND t.languageCode = :languageCode "
                    + "AND t.sourceVersion = :sourceVersion")
    int deleteOne(@Param("listingId") Long listingId,
                  @Param("languageCode") String languageCode,
                  @Param("sourceVersion") int sourceVersion);
}
