package com.supplierconsumer.companyconsumer;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.supplierconsumer.repo.ConsumerAccessRepository;
import com.supplierconsumer.security.Authorization;
import com.supplierconsumer.security.Principals;
import com.supplierconsumer.support.NotificationService;
import com.supplierconsumer.wire.ApiException;
import com.supplierconsumer.wire.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * {@code /api/company/consumers} -- who is allowed to buy from this supplier.
 *
 * <p>Restricted to Owners and Managers, and every query is scoped by company.
 *
 * <p>Three tables cooperate, all keyed on the same buyer/supplier pair: the request, the access
 * grant, and the block list. They are not redundant -- the grant is what actually decides what a
 * buyer can see, the request is the paper trail, and the block overrides both. Most operations
 * here touch more than one, which is why they are transactional.
 *
 * <p>Notifications are raised after the transaction and never allowed to fail the operation,
 * matching the original, where each is wrapped in a catch that only logs.
 */
@RestController
@RequestMapping("/api/company/consumers")
public class CompanyConsumerController {

    private final ConsumerAccessRepository access;
    private final NotificationService notifications;
    private final Authorization authz;

    public CompanyConsumerController(ConsumerAccessRepository access,
                                     NotificationService notifications, Authorization authz) {
        this.access = access;
        this.notifications = notifications;
        this.authz = authz;
    }

    @GetMapping("/requests/pending")
    public ApiResponse pendingRequests(Principals.Company user, HttpServletRequest http) {
        long companyId = guard(user, http);
        return ApiResponse.ok().data(access.findPendingRequests(companyId));
    }

    @GetMapping("/requests")
    public ApiResponse allRequests(Principals.Company user, HttpServletRequest http) {
        long companyId = guard(user, http);
        return ApiResponse.ok().data(access.findAllRequests(companyId));
    }

    @PostMapping("/requests/{requestId}/approve")
    @Transactional
    public ApiResponse approve(@PathVariable String requestId, Principals.Company user,
                               HttpServletRequest http) {
        long companyId = guard(user, http);
        var request = requireRequest(requestId, companyId);

        if (!"pending".equals(request.status())) {
            throw ApiException.badRequest("Request is not pending");
        }

        access.setRequestStatus(request.id(), "approved", user.id());
        access.grantAccess(request.consumerId(), companyId, user.id());

        notifications.bestEffort("access approved", () -> notifications.createForConsumer(
                request.consumerId(), "Access approved",
                "Your access request has been approved.", "access:approved",
                Map.of("companyId", companyId)));

        return ApiResponse.ok()
                .message("Access request approved successfully")
                .data(new ConsumerSummary(request.fullName(), request.email()));
    }

    @PostMapping("/requests/{requestId}/reject")
    @Transactional
    public ApiResponse reject(@PathVariable String requestId, Principals.Company user,
                              HttpServletRequest http) {
        long companyId = guard(user, http);
        var request = requireRequest(requestId, companyId);

        if (!"pending".equals(request.status())) {
            throw ApiException.badRequest("Request is not pending");
        }

        access.setRequestStatus(request.id(), "rejected", user.id());

        notifications.bestEffort("access rejected", () -> notifications.createForConsumer(
                request.consumerId(), "Access rejected",
                "Your access request has been rejected.", "access:rejected",
                Map.of("companyId", companyId)));

        return ApiResponse.ok()
                .message("Access request rejected")
                .data(new ConsumerSummary(request.fullName(), request.email()));
    }

    /** No {@code data} on this one, unlike approve and reject. */
    @PostMapping("/requests/{requestId}/cancel")
    @Transactional
    public ApiResponse cancel(@PathVariable String requestId, Principals.Company user,
                              HttpServletRequest http) {
        long companyId = guard(user, http);
        var request = requireRequest(requestId, companyId);

        access.revokeAccessForConsumer(companyId, request.consumerId());
        access.setRequestStatusForConsumer(companyId, request.consumerId(), "cancelled", user.id());

        notifications.bestEffort("access cancelled", () -> notifications.createForConsumer(
                request.consumerId(), "Access cancelled",
                "Your access has been cancelled.", "access:cancelled",
                Map.of("companyId", companyId)));

        return ApiResponse.ok().message("Request cancelled successfully");
    }

    @GetMapping("/consumers")
    public ApiResponse consumers(Principals.Company user, HttpServletRequest http) {
        long companyId = guard(user, http);
        return ApiResponse.ok().data(access.findCompanyConsumers(companyId));
    }

    @GetMapping("/consumers/blocked")
    public ApiResponse blocked(Principals.Company user, HttpServletRequest http) {
        long companyId = guard(user, http);
        return ApiResponse.ok().data(access.findBlockedConsumers(companyId));
    }

    /** Takes the access row's id, not the consumer's -- the two are easy to confuse. */
    @PostMapping("/consumers/{accessId}/revoke")
    @Transactional
    public ApiResponse revoke(@PathVariable String accessId, Principals.Company user,
                              HttpServletRequest http) {
        long companyId = guard(user, http);
        var row = access.findAccess(numericId(accessId, "Access not found"), companyId)
                .orElseThrow(() -> ApiException.notFound("Access not found"));

        access.revokeAccessById(row.id());
        access.setRequestStatusForConsumer(companyId, row.consumerId(), "revoked", user.id());

        notifications.bestEffort("access revoked", () -> notifications.createForConsumer(
                row.consumerId(), "Access revoked",
                "Your access has been revoked.", "access:revoked",
                Map.of("companyId", companyId)));

        return ApiResponse.ok()
                .message("Consumer access revoked successfully")
                .data(new ConsumerSummary(row.fullName(), row.email()));
    }

    /** Takes the consumer's id, unlike revoke. Writes to all three tables. */
    @PostMapping("/consumers/{consumerId}/block")
    @Transactional
    public ApiResponse block(@PathVariable String consumerId, Principals.Company user,
                             HttpServletRequest http) {
        long companyId = guard(user, http);
        long id = numericId(consumerId, "Consumer not found");

        var consumer = access.findConsumer(id)
                .orElseThrow(() -> ApiException.notFound("Consumer not found"));

        access.block(companyId, id, user.id());
        access.revokeAccessForConsumer(companyId, id);
        access.setRequestStatusForConsumer(companyId, id, "blocked", user.id());

        notifications.bestEffort("consumer blocked", () -> notifications.createForConsumer(
                id, "Access blocked", "You have been blocked by this company.",
                "access:blocked", Map.of("companyId", companyId)));

        return ApiResponse.ok()
                .message("Consumer has been blocked")
                .data(new ConsumerSummary(consumer.fullName(), consumer.email()));
    }

    /**
     * Unblocking does not restore access -- it returns the buyer to the pending queue, so a
     * company still has to approve them again.
     */
    @PostMapping("/consumers/{consumerId}/unblock")
    @Transactional
    public ApiResponse unblock(@PathVariable String consumerId, Principals.Company user,
                               HttpServletRequest http) {
        long companyId = guard(user, http);
        long id = numericId(consumerId, "Consumer not found");

        if (access.unblock(companyId, id, user.id()) == 0) {
            throw ApiException.notFound("Consumer is not currently blocked");
        }
        access.resetRequestToPending(companyId, id);

        notifications.bestEffort("consumer unblocked", () -> notifications.createForConsumer(
                id, "Access unblocked", "You have been unblocked by this company.",
                "access:unblocked", Map.of("companyId", companyId)));

        return ApiResponse.ok().message("Consumer has been unblocked");
    }

    private long guard(Principals.Company user, HttpServletRequest http) {
        authz.requireRoles(user, http, "Owner", "Manager");
        return user.requireCompanyId("User must be associated with a company");
    }

    private ConsumerAccessRepository.RequestRow requireRequest(String requestId, long companyId) {
        return access.findRequest(numericId(requestId, "Request not found"), companyId)
                .orElseThrow(() -> ApiException.notFound("Request not found"));
    }

    private long numericId(String id, String notFoundMessage) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            throw ApiException.notFound(notFoundMessage);
        }
    }

    /** A synthesised pair, not a database row -- the console shows the name in a confirmation. */
    @JsonPropertyOrder({"consumerName", "consumerEmail"})
    public record ConsumerSummary(
            @JsonProperty("consumerName") String consumerName,
            @JsonProperty("consumerEmail") String consumerEmail) {
    }
}
