package com.kovil.domain.rentalrequest.service;

import com.kovil.common.dto.response.PageResponse;
import com.kovil.domain.listing.entity.BrokerageOffice;
import com.kovil.domain.listing.repository.BrokerageOfficeRepository;
import com.kovil.domain.rentalrequest.dto.response.AdminRentalRequestResponse;
import com.kovil.domain.rentalrequest.entity.RentalRequest;
import com.kovil.domain.rentalrequest.enums.RentalRequestStatus;
import com.kovil.domain.rentalrequest.exception.RentalRequestException;
import com.kovil.domain.rentalrequest.repository.RentalRequestRepository;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.kovil.domain.rentalrequest.exception.RentalRequestErrorCode.RENTAL_REQUEST_NOT_FOUND;

@Service
@RequiredArgsConstructor
public class AdminRentalRequestService {

    private final RentalRequestRepository requestRepository;
    private final RentalRequestAssembler requestAssembler;
    private final UserRepository userRepository;
    private final BrokerageOfficeRepository officeRepository;
    private final RentalRequestClaimHistory claimHistory;

    @Transactional(readOnly = true)
    public PageResponse<AdminRentalRequestResponse> search(RentalRequestStatus status, String officeUuid,
                                                           String query, Pageable pageable) {
        Long officeId = null;
        if (officeUuid != null && !officeUuid.isBlank()) {
            Optional<BrokerageOffice> office = officeRepository.findByUuid(officeUuid.strip());
            if (office.isEmpty()) {
                return PageResponse.from(Page.<AdminRentalRequestResponse>empty(pageable));
            }
            officeId = office.get().getId();
        }
        String normalized = query == null || query.isBlank() ? null : query.strip();
        Page<RentalRequest> page = requestRepository.searchForAdmin(status, officeId, normalized, pageable);
        return PageResponse.from(new PageImpl<>(responsesOf(page.getContent()), pageable, page.getTotalElements()));
    }

    @Transactional(readOnly = true)
    public AdminRentalRequestResponse get(String requestUuid) {
        RentalRequest request = requestRepository.findByUuid(requestUuid)
                .orElseThrow(() -> new RentalRequestException(RENTAL_REQUEST_NOT_FOUND));
        return responsesOf(List.of(request)).get(0).withHistory(claimHistory.historyOf(request.getId()));
    }

    private List<AdminRentalRequestResponse> responsesOf(List<RentalRequest> requests) {
        if (requests.isEmpty()) {
            return List.of();
        }
        List<RentalRequestAssembler.Contents> contents = requestAssembler.contentsOf(requests);
        Map<Long, User> applicants = userRepository.findAllById(
                        requests.stream().map(RentalRequest::getUserId).distinct().toList())
                .stream().collect(Collectors.toMap(User::getId, Function.identity()));
        List<Long> officeIds = requests.stream().map(RentalRequest::getOfficeId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, BrokerageOffice> offices = officeIds.isEmpty() ? Map.of()
                : officeRepository.findAllById(officeIds).stream()
                        .collect(Collectors.toMap(BrokerageOffice::getId, Function.identity()));

        return IntStream.range(0, requests.size())
                .mapToObj(index -> {
                    RentalRequest request = requests.get(index);
                    RentalRequestAssembler.Contents body = contents.get(index);
                    return new AdminRentalRequestResponse(
                            request.getUuid(), request.getRequestNumber(), request.getStatus().name(),
                            body.regions(), body.options(),
                            request.getMoveInDate(), Boolean.TRUE.equals(request.getMoveInFlexible()),
                            request.getLeaseMonths(),
                            request.getDepositMin(), request.getDepositMax(),
                            request.getMonthlyRentMin(), request.getMonthlyRentMax(),
                            request.getHouseholdSize(), request.getPreferredAreaNote(),
                            request.getRequestNote(), request.getClosedReason(),
                            request.getCreatedAt(), request.getClaimedAt(), request.getClosedAt(),
                            request.getOutcome() == null ? null : request.getOutcome().name(),
                            AdminRentalRequestResponse.Applicant.of(applicants.get(request.getUserId())),
                            request.getOfficeId() == null ? null
                                    : AdminRentalRequestResponse.Office.of(offices.get(request.getOfficeId())),
                            null);
                })
                .toList();
    }
}
