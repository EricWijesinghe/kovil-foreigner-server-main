package com.kovil.domain.rentalrequest.service;

import com.kovil.domain.rentalrequest.dto.response.PartnerRentalRequestResponse;
import com.kovil.domain.rentalrequest.dto.response.PartnerRentalRequestSummaryResponse;
import com.kovil.domain.rentalrequest.dto.response.RentalRequestResponse;
import com.kovil.domain.rentalrequest.entity.RentalRequest;
import com.kovil.domain.rentalrequest.enums.RentalRequestStatus;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.repository.UserRepository;
import com.kovil.domain.verification.entity.ForeignerVerification;
import com.kovil.domain.verification.repository.ForeignerVerificationRepository;
import com.kovil.infra.crypto.Encryptor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/*
 * 파트너 화면의 임대 요청 조립 — 요약(잡기 전)과 전체(내 사무소 건).
 *
 * ★ 어느 모양을 줄지는 서비스가 정한다(내 사무소 건인가). 여기는 받은 대로 조립만 한다 — 요약을 부르면 연락처는 읽지도 않는다.
 * ★ 지역 · 질문지 답은 유학생 응답과 같은 조립(RentalRequestAssembler.contentsOf)을 쓴다. 유학생 응답을 통째로 감싸지 않는다 —
 *   거기에는 맡은 사무소 대표자의 휴대폰이 실린다.
 * ★ "인증됨"은 요청을 통과시킨 인증 건(verificationId)이 **지금** 유효한가다 — 승인 상태이고 만료 전.
 * ★ 페이지 단위로 한 번에 읽는다 — 행마다 신청자 · 인증을 부르면 20행이 40왕복이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PartnerRentalRequestAssembler {

    private final RentalRequestAssembler requestAssembler;
    private final UserRepository userRepository;
    private final ForeignerVerificationRepository verificationRepository;
    private final Encryptor encryptor;
    private final Clock clock;

    public PartnerRentalRequestSummaryResponse summary(RentalRequest request) {
        return summaries(List.of(request)).get(0);
    }

    public PartnerRentalRequestResponse full(RentalRequest request) {
        return fulls(List.of(request)).get(0);
    }

    public List<PartnerRentalRequestSummaryResponse> summaries(List<RentalRequest> requests) {
        if (requests.isEmpty()) {
            return List.of();
        }
        List<RentalRequestAssembler.Contents> contents = requestAssembler.contentsOf(requests);
        Map<Long, User> applicants = applicantsOf(requests);
        Set<Long> verified = effectiveVerificationsOf(requests);
        return IntStream.range(0, requests.size())
                .mapToObj(index -> {
                    RentalRequest request = requests.get(index);
                    RentalRequestAssembler.Contents body = contents.get(index);
                    return new PartnerRentalRequestSummaryResponse(
                            PartnerRentalRequestSummaryResponse.VIEW,
                            request.getUuid(), request.getRequestNumber(), request.getStatus().name(),
                            body.regions(), withoutTextValues(body.options()),
                            request.getMoveInDate(), Boolean.TRUE.equals(request.getMoveInFlexible()),
                            request.getLeaseMonths(),
                            request.getDepositMin(), request.getDepositMax(),
                            request.getMonthlyRentMin(), request.getMonthlyRentMax(),
                            request.getHouseholdSize(), request.getPreferredAreaNote(),
                            languageOf(applicants.get(request.getUserId())),
                            verified.contains(request.getVerificationId()),
                            request.getCreatedAt());
                })
                .toList();
    }

    public List<PartnerRentalRequestResponse> fulls(List<RentalRequest> requests) {
        if (requests.isEmpty()) {
            return List.of();
        }
        List<RentalRequestAssembler.Contents> contents = requestAssembler.contentsOf(requests);
        Map<Long, User> applicants = applicantsOf(requests);
        Set<Long> verified = effectiveVerificationsOf(requests);
        return IntStream.range(0, requests.size())
                .mapToObj(index -> {
                    RentalRequest request = requests.get(index);
                    RentalRequestAssembler.Contents body = contents.get(index);
                    User applicant = applicants.get(request.getUserId());
                    return new PartnerRentalRequestResponse(
                            PartnerRentalRequestResponse.VIEW,
                            request.getUuid(), request.getRequestNumber(), request.getStatus().name(),
                            body.regions(), body.options(),
                            request.getMoveInDate(), Boolean.TRUE.equals(request.getMoveInFlexible()),
                            request.getLeaseMonths(),
                            request.getDepositMin(), request.getDepositMax(),
                            request.getMonthlyRentMin(), request.getMonthlyRentMax(),
                            request.getHouseholdSize(), request.getPreferredAreaNote(),
                            languageOf(applicant),
                            verified.contains(request.getVerificationId()),
                            request.getCreatedAt(),
                            request.getClaimedAt(), request.getClosedAt(),
                            request.getOutcome() == null ? null : request.getOutcome().name(),
                            request.getRequestNote(),
                            new PartnerRentalRequestResponse.Applicant(
                                    applicant == null ? null : applicant.getName(),
                                    phoneOf(request, applicant)));
                })
                .toList();
    }

    // 요약은 승인된 모든 사무소에 나감. 글 답에는 연락처 · 실명이 들어갈 수 있어 값만 비우고 답 행은 남김.
    private static List<RentalRequestResponse.Option> withoutTextValues(List<RentalRequestResponse.Option> options) {
        return options.stream()
                .map(option -> option.textValue() == null ? option : new RentalRequestResponse.Option(
                        option.groupCode(), option.code(), option.name(), option.questionName(), option.answerName(),
                        option.questionText(), option.answerText(),
                        null, option.numberValue(), option.dateValue(), option.regionCode()))
                .toList();
    }

    private Map<Long, User> applicantsOf(List<RentalRequest> requests) {
        return userRepository.findAllById(requests.stream().map(RentalRequest::getUserId).distinct().toList())
                .stream().collect(Collectors.toMap(User::getId, Function.identity()));
    }

    // 요청을 통과시킨 인증 건 가운데 지금 유효한 것. 그 뒤에 새로 낸 신청(심사 중 · 반려)은 이 판정에 끼지 않는다.
    private Set<Long> effectiveVerificationsOf(List<RentalRequest> requests) {
        LocalDate today = LocalDate.now(clock);
        return verificationRepository.findAllById(requests.stream()
                        .map(RentalRequest::getVerificationId).filter(Objects::nonNull).distinct().toList())
                .stream()
                .filter(verification -> verification.isEffectiveOn(today))
                .map(ForeignerVerification::getId)
                .collect(Collectors.toSet());
    }

    private static String languageOf(User applicant) {
        return applicant == null ? null : applicant.getPreferredLanguage();
    }

    /*
     * 휴대폰은 진행 중일 때만 복호화한다 — 종료 · 취소된 요청을 위해 번호를 풀지 않는다(PO 확정 2026-10-07).
     * 신청자 계정이 ACTIVE 가 아니면 내지 않는다 — 맡은 사무소 대표자 휴대폰(ClaimedOfficeResolver)과 같은 조건이다.
     * 복호화가 실패해도 화면 전체가 죽으면 안 된다 — 그 번호만 비운다.
     */
    private String phoneOf(RentalRequest request, User applicant) {
        if (request.getStatus() != RentalRequestStatus.IN_PROGRESS
                || applicant == null || !applicant.isActive() || applicant.getPhone() == null) {
            return null;
        }
        try {
            return encryptor.decrypt(applicant.getPhone());
        } catch (RuntimeException e) {
            log.warn("[rental-request] 신청자 휴대폰 복호화 실패 request={}", request.getRequestNumber());
            return null;
        }
    }
}
