package com.kovil.domain.rentalrequest.service;

import com.kovil.common.dto.response.PageResponse;
import com.kovil.common.util.BusinessNumberGenerator;
import com.kovil.common.util.BusinessNumberPrefix;
import com.kovil.domain.region.entity.Region;
import com.kovil.domain.rentalrequest.config.RentalRequestProperties;
import com.kovil.domain.rentalrequest.dto.request.RentalRequestSaveRequest;
import com.kovil.domain.rentalrequest.dto.response.RentalRequestResponse;
import com.kovil.domain.rentalrequest.entity.RentalRequest;
import com.kovil.domain.region.repository.RegionRepository;
import com.kovil.domain.rentalrequest.entity.RentalRequestOption;
import com.kovil.domain.rentalrequest.entity.RequestOptionTranslation;
import com.kovil.domain.rentalrequest.entity.RentalRequestRegion;
import com.kovil.domain.rentalrequest.entity.RequestOption;
import com.kovil.domain.rentalrequest.enums.RentalRequestStatus;
import com.kovil.domain.rentalrequest.exception.RentalRequestException;
import com.kovil.domain.rentalrequest.repository.RentalRequestOptionRepository;
import com.kovil.domain.rentalrequest.repository.RentalRequestRegionRepository;
import com.kovil.domain.rentalrequest.repository.RentalRequestRepository;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.repository.UserRepository;
import com.kovil.domain.user.service.UserQueryService;
import com.kovil.domain.verification.entity.ForeignerVerification;
import com.kovil.domain.verification.service.VerificationQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;

import java.util.*;
import java.time.LocalDateTime;

import static com.kovil.domain.rentalrequest.exception.RentalRequestErrorCode.BUDGET_RANGE_INVALID;
import static com.kovil.domain.rentalrequest.exception.RentalRequestErrorCode.MOVE_IN_DATE_IN_PAST;
import static com.kovil.domain.rentalrequest.exception.RentalRequestErrorCode.RENTAL_REQUEST_LIMIT_EXCEEDED;
import static com.kovil.domain.rentalrequest.exception.RentalRequestErrorCode.RENTAL_REQUEST_NOT_FOUND;
import static com.kovil.domain.rentalrequest.exception.RentalRequestErrorCode.VERIFICATION_REQUIRED;


@Slf4j
@Service
@RequiredArgsConstructor
public class RentalRequestService {

    private static final Set<RentalRequestStatus> OPEN_STATUSES =
            Set.of(RentalRequestStatus.SUBMITTED, RentalRequestStatus.IN_PROGRESS);
    private static final int NUMBER_RETRY = 5;

    private final UserQueryService userQueryService;
    private final UserRepository userRepository;
    private final VerificationQueryService verificationQueryService;
    // 답변 스냅샷 언어 — 중개사무소가 읽는 운영 언어(일반 사용자가 본 언어가 아님)
    private static final String SNAPSHOT_LANGUAGE = "ko";

    private final RentalRequestSelectionResolver selectionResolver;
    private final RequestAnswerResolver answerResolver;
    private final RequestOptionQueryService requestOptionQueryService;
    private final RegionRepository regionRepository;
    private final RentalRequestAssembler assembler;
    private final RentalRequestRepository requestRepository;
    private final RentalRequestRegionRepository requestRegionRepository;
    private final RentalRequestOptionRepository requestOptionRepository;
    private final BusinessNumberGenerator businessNumberGenerator;
    private final RentalRequestProperties properties;
    private final Clock clock;

    @Transactional
    public RentalRequestResponse create(String userUuid, RentalRequestSaveRequest request) {
        // 맨 첫 줄 고정. 같은 유저의 연타 제출을 한 줄로 세운다.
        // 앞에 다른 조회가 있으면 아래 count 가 옛 데이터를 봐서 상한이 뚫린다.
        userRepository.findIdByUuidForUpdate(userUuid);

        User user = userQueryService.getActiveUser(userUuid);

        // 현재 효력있는 외국인 인증 조회 (만료된 인증은 인증이 아님)
        ForeignerVerification verification = verificationQueryService.findEffective(user.getId())
                .orElseThrow(() -> new RentalRequestException(VERIFICATION_REQUIRED));

        /* 같은 사람의 열린 요청이 여러 건이면 사무소가 무엇을 맡을지 헷갈림.
         * 위 락킹이 첫 읽기라 이 count 는 경쟁 제출의 커밋을 본다. count 에 FOR UPDATE 를
         * 걸면 안 된다 — 빈 결과의 갭락이 무관한 유저끼리 데드락을 만든다(리포지토리 주석).
         */
        if (requestRepository.countByUserIdAndStatusIn(user.getId(), OPEN_STATUSES)
                >= properties.maxActivePerUser()) {
            throw new RentalRequestException(RENTAL_REQUEST_LIMIT_EXCEEDED);
        }

        validateConditions(request, false);
        List<Region> regions = selectionResolver.resolveRegions(request.regionCodes());
        List<RequestOption> options = selectionResolver.resolveOptions(request.optionCodes());
        List<RequestAnswerResolver.ResolvedAnswer> answers =
                answerResolver.resolve(options, request.optionAnswers());

        RentalRequest created = requestRepository.save(RentalRequest.builder()
                .requestNumber(nextRequestNumber())
                .userId(user.getId())
                //통과 근거를 고정한다. 이후 이 인증이 만료돼도 이력은 근거를 잃지 않는다.
                .verificationId(verification.getId())
                .status(RentalRequestStatus.SUBMITTED)
                .moveInDate(request.moveInDate())
                .moveInFlexible(Boolean.TRUE.equals(request.moveInFlexible()))
                .leaseMonths(request.leaseMonths())
                .depositMin(request.depositMin())
                .depositMax(request.depositMax())
                .monthlyRentMin(request.monthlyRentMin())
                .monthlyRentMax(request.monthlyRentMax())
                .householdSize(request.householdSize())
                .preferredAreaNote(trimToNull(request.preferredAreaNote()))
                .requestNote(trimToNull(request.requestNote()))
                .build());

        replaceSelections(created.getId(), regions, answers);

        log.info("[rental-request] 생성 number={} user={}", created.getRequestNumber(), user.getId());
        return assembler.toResponse(created);
    }

    // 등록과 같은 본문으로 통째로 덮어쓴다. 부분 패치로 두면 "보증금 하한 없음"과
    // "보증금 하한 안 바꿈"을 구분할 수 없다 — 둘 다 null이기 때문이다.
    @Transactional
    public RentalRequestResponse update(String userUuid, String requestUuid, RentalRequestSaveRequest request) {
        User user = userQueryService.getActiveUser(userUuid);
        RentalRequest target = getOwned(user, requestUuid);
        target.requireUserEditable();

        // 이월 면제(AUDIT_2026_08_26): 입주일이 이미 지난 구(pre-V14) 요청은 FE가 저장값을
        // 그대로 되보낸다(안 보내면 데이터가 지워지는 통째 덮어쓰기 계약). 그 값에 과거 검사를
        // 다시 물리면 **어떤 필드도 수정할 수 없는** 요청이 된다 — 저장된 값 그대로면 통과시키고,
        // 새로 바꾸는 값에만 과거 금지를 잰다(저장된 답을 소급 무효화하지 않는다는 V16 원칙과 동일).
        boolean moveInDateUnchanged = Objects.equals(request.moveInDate(), target.getMoveInDate());
        validateConditions(request, moveInDateUnchanged);
        List<Region> regions = selectionResolver.resolveRegions(request.regionCodes());
        List<RequestOption> options = selectionResolver.resolveOptions(request.optionCodes());
        // 수정도 **현재 규칙**으로 검증한다 — 관리자가 그 사이 켠 필수 질문은 여기서 비로소 물린다.
        List<RequestAnswerResolver.ResolvedAnswer> answers =
                answerResolver.resolve(options, request.optionAnswers());

        target.updateConditions(
                request.moveInDate(), Boolean.TRUE.equals(request.moveInFlexible()), request.leaseMonths(),
                request.depositMin(), request.depositMax(),
                request.monthlyRentMin(), request.monthlyRentMax(),
                request.householdSize(),
                trimToNull(request.preferredAreaNote()), trimToNull(request.requestNote()));

        replaceSelections(target.getId(), regions, answers);

        log.info("[rental-request] 수정 number={} user={}", target.getRequestNumber(), user.getId());
        return assembler.toResponse(target);
    }

    @Transactional
    public RentalRequestResponse cancel(String userUuid, String requestUuid, String reason) {
        User user = userQueryService.getActiveUser(userUuid);
        RentalRequest target = getOwned(user, requestUuid);

        target.cancel(trimToNull(reason), LocalDateTime.now(clock));

        log.info("[rental-request] 취소 number={} user={}", target.getRequestNumber(), user.getId());
        return assembler.toResponse(target);
    }

    /*
     * 탈퇴하면 열린 문의를 접는다
     *
     *   새 상태를 만들지 않고 CANCELED 를 쓴다 — 이 상태의 뜻이 이미 "유저가 접는 것"이고,
     *   탈퇴는 유저가 스스로 관계를 끊는 것이라 뜻이 그대로 맞는다. 사유를 남기므로
     *   "왜 끝났나"도 사라지지 않는다(사무소가 접는 CLOSED 와 갈리는 이유가 그 한 줄이다).
     *
     *   진행 중이던 요청도 취소로 닫는다 — 맡은 사무소는 지우지 않아 그 사무소의 맡은 요청 목록에 "취소됨"으로 남고,
     *   대기 요청은 사무소들의 대기 목록에서 빠진다.
     */
    @Transactional
    public void cancelOpenOnWithdrawal(Long userId) {
        LocalDateTime now = LocalDateTime.now(clock);
        List<RentalRequest> open = requestRepository.findByUserIdAndStatusIn(
                userId, List.of(RentalRequestStatus.SUBMITTED, RentalRequestStatus.IN_PROGRESS));
        open.forEach(request -> request.cancel("회원이 탈퇴했습니다.", now));
        if (!open.isEmpty()) {
            log.info("[rental-request] 탈퇴 취소 user={} {}건", userId, open.size());
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<RentalRequestResponse> findMine(String userUuid, Pageable pageable) {
        User user = userQueryService.getActiveUser(userUuid);
        var page = requestRepository.findByUserIdOrderByIdDesc(user.getId(), pageable);
        // 페이지 단위로 한 번에 조립한다 — 항목마다 map을 돌리면 지역·옵션 조회가 N배가 된다.
        return PageResponse.from(new PageImpl<>(
                assembler.toResponses(page.getContent()), pageable, page.getTotalElements()));
    }

    @Transactional(readOnly = true)
    public RentalRequestResponse getMine(String userUuid, String requestUuid) {
        User user = userQueryService.getActiveUser(userUuid);
        return assembler.toResponse(getOwned(user, requestUuid));
    }

    // 남의 요청은 "권한 없음"이 아니라 "없음"으로 답한다 — uuid의 존재 여부를 흘리지 않는다.
    private RentalRequest getOwned(User user, String requestUuid) {
        return requestRepository.findByUuid(requestUuid)
                .filter(request -> request.getUserId().equals(user.getId()))
                .orElseThrow(() -> new RentalRequestException(RENTAL_REQUEST_NOT_FOUND));
    }

    private void validateConditions(RentalRequestSaveRequest request, boolean moveInDateCarriedOver) {
        // V14 — 입주일은 레거시 축이라 비울 수 있다. **새로 들어오는** 값만 과거 금지를 잰다 —
        // 저장값 그대로의 이월(수정 화면의 통째 되보내기)은 면제다.
        if (!moveInDateCarriedOver
                && request.moveInDate() != null && request.moveInDate().isBefore(LocalDate.now(clock))) {
            throw new RentalRequestException(MOVE_IN_DATE_IN_PAST);
        }
        requireOrdered(request.depositMin(), request.depositMax());
        requireOrdered(request.monthlyRentMin(), request.monthlyRentMax());
    }

    // 하한이 상한보다 크면 어떤 매물도 걸리지 않는다 — 사무소가 손도 못 대는 요청이 된다.
    private void requireOrdered(Long min, Long max) {
        if (min != null && max != null && min > max) {
            throw new RentalRequestException(BUDGET_RANGE_INVALID);
        }
    }

    // 지역·옵션은 통째로 갈아끼우는 값이라 독립 수명이 없다(V1 주석).
    //
    // ★ 삭제 뒤 반드시 flush한다. Hibernate는 한 플러시 안에서 **INSERT를 DELETE보다 먼저** 내보내므로,
    // 같은 지역을 그대로 둔 채 수정하면 아직 지워지지 않은 행과 유니크 키가 충돌해 500이 된다.
    // 실측에서 잡았다 — 지역을 바꾸지 않고 예산만 고치는, 가장 흔한 수정이 깨져 있었다.
    private void replaceSelections(Long requestId, List<Region> regions,
                                   List<RequestAnswerResolver.ResolvedAnswer> answers) {
        requestRegionRepository.deleteByRentalRequestId(requestId);
        requestOptionRepository.deleteByRentalRequestId(requestId);
        requestRegionRepository.flush();
        requestOptionRepository.flush();

        // ★ 스냅샷 두 벌로 표시를 완결한다(PO 확정 2026-08-26) — 운영 언어(ko)와 제출자가 본 언어.
        //   제출된 요청은 그 시점의 박제본이다. 관리자가 마스터를 어떻게 바꾸든(문구·숨김·
        //   구조·삭제) 여기 박힌 문구가 그대로 나온다. 이 완결이 있어야 구조 잠금을
        //   "열린 요청 기준"으로 좁힐 수 있다 — 종착 요청의 표시가 마스터에 기대지 않으므로.
        String userLanguage = LocaleContextHolder.getLocale().getLanguage();
        Map<Long, RequestOptionTranslation> koNames = requestOptionQueryService.resolveNames(SNAPSHOT_LANGUAGE);
        Map<Long, RequestOptionTranslation> userNames = SNAPSHOT_LANGUAGE.equals(userLanguage)
                ? koNames : requestOptionQueryService.resolveNames(userLanguage);

        requestRegionRepository.saveAll(regions.stream()
                .map(region -> RentalRequestRegion.builder()
                        .rentalRequestId(requestId).regionId(region.getId())
                        .regionNameSnapshot(region.getNameKo())
                        .regionNameSnapshotUser(regionNameFor(region, userLanguage))
                        .build())
                .toList());

        // ★ ADDRESS 답변의 "고른 값"은 옵션이 아니라 **지역**이다 — 지역명도 관리자가 고치는
        //   마스터라(행정구역 개편·오타 수정) 옵션과 똑같이 박제해야 한다.
        Map<Long, Region> answerRegions = answerRegionsOf(answers);
        // 질문 행: 항목 답이면 부모를 읽어와야 문구·코드 폴백이 가능하다(질문 행 답이면 자기 자신).
        Map<Long, RequestOption> questionsById = questionRowsOf(answers);
        requestOptionRepository.saveAll(answers.stream()
                .map(answer -> {
                    RequestOption option = answer.option();
                    RequestOption question = option.isGroup()
                            ? option : questionsById.get(option.getParentId());
                    Region answerRegion = answer.regionId() == null
                            ? null : answerRegions.get(answer.regionId());
                    return RentalRequestOption.builder()
                            .rentalRequestId(requestId)
                            .requestOptionId(option.getId())
                            .textValue(answer.textValue())
                            .numberValue(answer.numberValue())
                            .dateValue(answer.dateValue())
                            .regionId(answer.regionId())
                            .questionTextSnapshot(labelOf(koNames, question))
                            .questionTextSnapshotUser(labelOf(userNames, question))
                            .answerTextSnapshot(answerLabel(option, answerRegion, koNames, SNAPSHOT_LANGUAGE))
                            .answerTextSnapshotUser(answerLabel(option, answerRegion, userNames, userLanguage))
                            .build();
                })
                .toList());
    }

    // ADDRESS 답에 걸린 지역 행들 — 한 번에 읽는다(답마다 조회하지 않는다).
    private Map<Long, Region> answerRegionsOf(List<RequestAnswerResolver.ResolvedAnswer> answers) {
        List<Long> regionIds = answers.stream()
                .map(RequestAnswerResolver.ResolvedAnswer::regionId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (regionIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Region> rows = new HashMap<>();
        regionRepository.findAllById(regionIds).forEach(region -> rows.put(region.getId(), region));
        return rows;
    }

    // 항목 답의 부모(질문) 행들 — 질문 문구 스냅샷과 코드 폴백에 필요하다.
    private Map<Long, RequestOption> questionRowsOf(List<RequestAnswerResolver.ResolvedAnswer> answers) {
        List<Long> parentIds = answers.stream()
                .map(answer -> answer.option().getParentId())
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (parentIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, RequestOption> rows = new HashMap<>();
        requestOptionQueryService.findRows(parentIds).forEach(row -> rows.put(row.getId(), row));
        return rows;
    }

    /**
     * 답의 문구 박제 — 선택지는 라벨, ADDRESS 는 지역명, 직접 입력은 값 컬럼이 정본이라 null.
     */
    private String answerLabel(RequestOption option, Region answerRegion,
                               Map<Long, RequestOptionTranslation> names, String language) {
        if (answerRegion != null) {
            return regionNameFor(answerRegion, language);
        }
        return option.isGroup() ? null : labelOf(names, option);
    }

    // 번역 행이 없으면 코드를 남긴다 — 빈 칸보다 코드가 낫다(무엇인지 추적할 수 있다).
    private String labelOf(Map<Long, RequestOptionTranslation> names, RequestOption option) {
        if (option == null) {
            return null;
        }
        RequestOptionTranslation translation = names.get(option.getId());
        return translation == null ? option.getCode() : translation.getName();
    }

    // 지역명은 번역 테이블 없이 ko/en 인라인이다 — 한국어만 국문, 나머지는 영문(전역 관례).
    private String regionNameFor(Region region, String language) {
        return SNAPSHOT_LANGUAGE.equals(language) ? region.getNameKo() : region.getNameEn();
    }

    // RAND6이라 충돌은 사실상 없지만, 났을 때 500으로 죽는 것보다 다시 뽑는 편이 낫다.
    private String nextRequestNumber() {
        for (int attempt = 0; attempt < NUMBER_RETRY; attempt++) {
            String candidate = businessNumberGenerator.generate(
                    BusinessNumberPrefix.RENTAL_REQUEST, LocalDate.now(clock));
            if (!requestRepository.existsByRequestNumber(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("업무번호 발급 실패 — " + NUMBER_RETRY + "회 연속 충돌");
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
