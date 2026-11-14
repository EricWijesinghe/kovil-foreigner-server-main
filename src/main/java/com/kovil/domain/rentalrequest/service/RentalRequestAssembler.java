package com.kovil.domain.rentalrequest.service;

import com.kovil.domain.region.entity.Region;
import com.kovil.domain.region.repository.RegionRepository;
import com.kovil.domain.rentalrequest.dto.response.RentalRequestResponse;
import com.kovil.domain.rentalrequest.entity.RentalRequest;
import com.kovil.domain.rentalrequest.entity.RentalRequestOption;
import com.kovil.domain.rentalrequest.entity.RentalRequestRegion;
import com.kovil.domain.rentalrequest.entity.RequestOption;
import com.kovil.domain.rentalrequest.entity.RequestOptionTranslation;
import com.kovil.domain.rentalrequest.enums.RentalRequestClaimEventType;
import com.kovil.domain.rentalrequest.repository.RentalRequestClaimEventRepository;
import com.kovil.domain.rentalrequest.repository.RentalRequestLastRelease;
import com.kovil.domain.rentalrequest.repository.RentalRequestOptionRepository;
import com.kovil.domain.rentalrequest.repository.RentalRequestRegionRepository;
import com.kovil.domain.rentalrequest.repository.RequestOptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.ArrayList;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;


@Component
@RequiredArgsConstructor
public class RentalRequestAssembler {

    private static final List<RentalRequestClaimEventType> RELEASE_EVENTS =
            List.of(RentalRequestClaimEventType.RELEASED, RentalRequestClaimEventType.RETURNED);

    private final RegionRepository regionRepository;
    private final RequestOptionRepository requestOptionRepository;
    private final RentalRequestRegionRepository requestRegionRepository;
    private final RentalRequestOptionRepository requestOptionLinkRepository;
    private final RequestOptionQueryService optionQueryService;
    private final ClaimedOfficeResolver claimedOfficeResolver;
    private final RentalRequestClaimEventRepository claimEventRepository;

    public RentalRequestResponse toResponse(RentalRequest request) {
        return toResponses(List.of(request)).get(0);
    }

    public List<RentalRequestResponse> toResponses(List<RentalRequest> requests) {
        if (requests.isEmpty()) {
            return List.of();
        }
        List<Contents> contents = contentsOf(requests);
        List<RentalRequestResponse.Office> offices = claimedOfficeResolver.officesOf(requests);
        Map<Long, LocalDateTime> lastReleases = lastReleasesOf(requests);
        return IntStream.range(0, requests.size())
                .mapToObj(index -> build(requests.get(index), contents.get(index), offices.get(index),
                        lastReleases.get(requests.get(index).getId())))
                .toList();
    }

    private Map<Long, LocalDateTime> lastReleasesOf(List<RentalRequest> requests) {
        List<Long> requestIds = requests.stream().map(RentalRequest::getId).toList();
        return claimEventRepository.findLastReleases(requestIds, RELEASE_EVENTS).stream()
                .collect(Collectors.toMap(RentalRequestLastRelease::requestId, RentalRequestLastRelease::releasedAt));
    }

    public record Contents(List<RentalRequestResponse.Region> regions, List<RentalRequestResponse.Option> options) {
    }

    public List<Contents> contentsOf(List<RentalRequest> requests) {
        if (requests.isEmpty()) {
            return List.of();
        }
        List<Long> requestIds = requests.stream().map(RentalRequest::getId).toList();
        String language = LocaleContextHolder.getLocale().getLanguage();

        Map<Long, List<RentalRequestRegion>> regionLinks = groupBy(
                requestRegionRepository.findByRentalRequestIdInOrderByIdAsc(requestIds),
                RentalRequestRegion::getRentalRequestId);
        Map<Long, List<RentalRequestOption>> optionLinks = groupBy(
                requestOptionLinkRepository.findByRentalRequestIdInOrderByIdAsc(requestIds),
                RentalRequestOption::getRentalRequestId);

        Map<Long, Region> regionsById = byId(
                regionRepository.findByIdIn(Stream.concat(
                                regionLinks.values().stream().flatMap(List::stream)
                                        .map(RentalRequestRegion::getRegionId),
                                optionLinks.values().stream().flatMap(List::stream)
                                        .map(RentalRequestOption::getRegionId)
                                        .filter(Objects::nonNull))
                        .distinct().toList()),
                Region::getId);
        Map<Long, RequestOption> optionsById = byId(
                requestOptionRepository.findByIdIn(optionLinks.values().stream()
                        .flatMap(List::stream).map(RentalRequestOption::getRequestOptionId)
                        .filter(Objects::nonNull).distinct().toList()),
                RequestOption::getId);
        Map<Long, RequestOption> groupsById = byId(
                requestOptionRepository.findByIdIn(optionsById.values().stream()
                        .map(RequestOption::getParentId).filter(Objects::nonNull).distinct().toList()),
                RequestOption::getId);
        Map<Long, RequestOptionTranslation> names = optionQueryService.resolveNames(language);

        return requests.stream()
                .map(request -> contents(language,
                        regionLinks.getOrDefault(request.getId(), List.of()), regionsById,
                        optionLinks.getOrDefault(request.getId(), List.of()), optionsById, groupsById, names))
                .toList();
    }

    private Contents contents(
            String language,
            List<RentalRequestRegion> regionLinks, Map<Long, Region> regionsById,
            List<RentalRequestOption> optionLinks, Map<Long, RequestOption> optionsById,
            Map<Long, RequestOption> groupsById, Map<Long, RequestOptionTranslation> names) {

        List<RentalRequestResponse.Region> regions = regionLinks.stream()
                .map(link -> {
                    Region region = regionsById.get(link.getRegionId());
                    String snapshot = Locale.KOREAN.getLanguage().equals(language)
                            ? link.getRegionNameSnapshot() : link.getRegionNameSnapshotUser();
                    String name = snapshot != null ? snapshot
                            : region != null ? regionName(region, language) : null;
                    return new RentalRequestResponse.Region(
                            region != null ? region.getCode() : null, name);
                })
                .toList();

        List<RentalRequestResponse.Option> options = optionLinks.stream()
                .map(link -> {
                    RequestOption option = optionsById.get(link.getRequestOptionId());
                    RequestOption group = option == null || option.getParentId() == null
                            ? null : groupsById.get(option.getParentId());
                    return new RentalRequestResponse.Option(
                            group != null ? group.getCode() : null,
                            option != null ? option.getCode() : null,
                            displayName(link, option, language, names),
                            questionName(link, option, group, language, names),
                            answerName(link, option, language, names),
                            link.getQuestionTextSnapshot(),
                            link.getAnswerTextSnapshot(),
                            link.getTextValue(),
                            link.getNumberValue(),
                            link.getDateValue(),
                            link.getRegionId() == null ? null
                                    : regionCodeOf(regionsById.get(link.getRegionId())));
                })
                .toList();
        return new Contents(regions, options);
    }

    private RentalRequestResponse build(RentalRequest request, Contents contents, RentalRequestResponse.Office office,
                                        LocalDateTime lastReleasedAt) {
        return new RentalRequestResponse(
                request.getUuid(),
                request.getRequestNumber(),
                request.getStatus().name(),
                request.getStatus().isUserEditable(),
                contents.regions(),
                contents.options(),
                request.getMoveInDate(),
                Boolean.TRUE.equals(request.getMoveInFlexible()),
                request.getLeaseMonths(),
                request.getDepositMin(),
                request.getDepositMax(),
                request.getMonthlyRentMin(),
                request.getMonthlyRentMax(),
                request.getHouseholdSize(),
                request.getPreferredAreaNote(),
                request.getRequestNote(),
                request.getClosedReason(),
                request.getCreatedAt(),
                request.getClosedAt(),
                request.getClaimedAt(),
                lastReleasedAt,
                office);
    }

    private String displayName(RentalRequestOption link, RequestOption option,
                               String language, Map<Long, RequestOptionTranslation> names) {
        String snapshot = Locale.KOREAN.getLanguage().equals(language)
                ? firstNonNull(link.getAnswerTextSnapshot(), link.getQuestionTextSnapshot())
                : firstNonNull(link.getAnswerTextSnapshotUser(), link.getQuestionTextSnapshotUser());
        if (snapshot != null) {
            return snapshot;
        }
        if (option != null) {
            return optionQueryService.nameOf(option, names);
        }
        return firstNonNull(link.getAnswerTextSnapshot(), link.getQuestionTextSnapshot(),
                link.getAnswerTextSnapshotUser(), link.getQuestionTextSnapshotUser());
    }

    private String questionName(RentalRequestOption link, RequestOption option, RequestOption group,
                                String language, Map<Long, RequestOptionTranslation> names) {
        String snapshot = Locale.KOREAN.getLanguage().equals(language)
                ? link.getQuestionTextSnapshot() : link.getQuestionTextSnapshotUser();
        if (snapshot != null) {
            return snapshot;
        }

        RequestOption question = group != null ? group : option;
        return question == null ? null : optionQueryService.nameOf(question, names);
    }

    private String answerName(RentalRequestOption link, RequestOption option,
                              String language, Map<Long, RequestOptionTranslation> names) {
        String snapshot = Locale.KOREAN.getLanguage().equals(language)
                ? link.getAnswerTextSnapshot() : link.getAnswerTextSnapshotUser();
        if (snapshot != null) {
            return snapshot;
        }
        return option != null && !option.isGroup() ? optionQueryService.nameOf(option, names) : null;
    }

    private static String regionCodeOf(Region region) {
        return region == null ? null : region.getCode();
    }

    private static String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String regionName(Region region, String language) {
        return Locale.KOREAN.getLanguage().equals(language) ? region.getNameKo() : region.getNameEn();
    }

    private <T> Map<Long, List<T>> groupBy(List<T> rows, Function<T, Long> keyOf) {
        Map<Long, List<T>> grouped = new LinkedHashMap<>();
        rows.forEach(row -> grouped.computeIfAbsent(keyOf.apply(row), key -> new ArrayList<>()).add(row));
        return grouped;
    }

    private <T> Map<Long, T> byId(List<T> rows, Function<T, Long> keyOf) {
        Map<Long, T> indexed = new LinkedHashMap<>();
        rows.forEach(row -> indexed.put(keyOf.apply(row), row));
        return indexed;
    }
}