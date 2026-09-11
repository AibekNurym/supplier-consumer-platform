package com.supplierconsumer.consumercatalog;

import com.supplierconsumer.repo.CatalogRepository;
import com.supplierconsumer.repo.ConsumerAccessRepository;
import com.supplierconsumer.security.Principals;
import com.supplierconsumer.support.NotificationService;
import com.supplierconsumer.wire.ApiException;
import com.supplierconsumer.wire.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code /api/consumer/catalog} -- how a buyer finds suppliers and asks to buy from them.
 *
 * <p>The central rule is price masking: a buyer always sees what a supplier sells, but only sees
 * what it costs once their access has been approved. Both responses carry the same keys, so the
 * client renders one shape either way; without access the three price fields are simply null.
 */
@RestController
@RequestMapping("/api/consumer/catalog")
public class ConsumerCatalogController {

    private final CatalogRepository catalog;
    private final ConsumerAccessRepository access;
    private final NotificationService notifications;

    public ConsumerCatalogController(CatalogRepository catalog, ConsumerAccessRepository access,
                                     NotificationService notifications) {
        this.catalog = catalog;
        this.access = access;
        this.notifications = notifications;
    }

    /** Readable without signing in, which is how a prospective buyer browses before registering. */
    @GetMapping("/companies")
    public ApiResponse companies(Optional<Principals.Consumer> consumer) {
        return ApiResponse.ok().data(catalog.findCompanies(
                consumer.map(Principals.Consumer::id).orElse(null)));
    }

    @GetMapping("/companies-with-status")
    public ApiResponse companiesWithStatus(Principals.Consumer consumer) {
        return ApiResponse.ok().data(catalog.findCompaniesWithStatus(consumer.id()));
    }

    @GetMapping("/companies/{companyId}/products")
    public ApiResponse products(@PathVariable String companyId, Principals.Consumer consumer) {
        long id = numericId(companyId);

        // A block short-circuits before anything is disclosed, including the product list.
        if (access.isBlocked(consumer.id(), id)) {
            throw ApiException.forbidden("You have been blocked from this company.");
        }

        boolean hasAccess = access.hasActiveAccess(consumer.id(), id);
        List<Map<String, Object>> products = hasAccess
                ? catalog.findProductsWithPrices(id)
                : catalog.findProductsWithoutPrices(id);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("products", products);
        data.put("hasAccess", hasAccess);
        data.put("companyId", (int) id);
        data.put("isBlocked", false);

        return ApiResponse.ok().data(data);
    }

    /**
     * Asks a supplier for access.
     *
     * <p>The status code depends on which branch runs, not on the method: 201 when a request row
     * is created, 200 when an existing one is reopened or when there was nothing to do. A closed
     * request (cancelled, revoked, rejected or blocked) is reopened rather than duplicated, since
     * the buyer/supplier pair is unique; any other status is terminal for now and the caller is
     * simply told what it is.
     */
    @PostMapping("/companies/{companyId}/request-access")
    @Transactional
    public ResponseEntity<ApiResponse> requestAccess(@PathVariable String companyId,
                                                     Principals.Consumer consumer) {
        long id = numericId(companyId);

        String companyName = catalog.findActiveCompanyName(id)
                .orElseThrow(() -> ApiException.notFound("Company not found"));

        if (access.isBlocked(consumer.id(), id)) {
            throw ApiException.forbidden(
                    "Your access to this company has been blocked. You cannot request access.");
        }

        Optional<CatalogRepository.ExistingRequest> existing = catalog.findRequest(consumer.id(), id);
        CatalogRepository.ExistingRequest request = null;
        boolean reused = false;

        if (existing.isPresent()) {
            String status = existing.get().status();
            if (List.of("cancelled", "revoked", "rejected", "blocked").contains(status)) {
                request = catalog.reopenRequest(existing.get().id());
                reused = true;
            } else {
                // Nothing to do. Note createdAt is absent here rather than null: the original's
                // projection does not include the timestamp on this branch, so the key never
                // appears in the JSON at all.
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("requestId", existing.get().id());
                data.put("status", status);
                data.put("companyName", companyName);
                return ResponseEntity.ok(ApiResponse.ok()
                        .message("Access request already exists with status: " + status)
                        .data(data));
            }
        }

        if (access.hasActiveAccess(consumer.id(), id)) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("requestId", request != null ? request.id()
                    : existing.map(CatalogRepository.ExistingRequest::id).orElse(null));
            data.put("status", "approved");
            data.put("companyName", companyName);
            return ResponseEntity.ok(ApiResponse.ok()
                    .message("You already have active access to this company")
                    .data(data));
        }

        if (request == null) {
            request = catalog.createRequest(consumer.id(), id);
        }

        final long requestId = request.id();
        String consumerName = displayName(consumer);
        notifications.bestEffort("access request", () -> notifications.createForCompany(
                id, "New access request",
                consumerName + " requested access to your catalog.",
                "access:requested",
                Map.of("consumerId", consumer.id(), "companyId", id, "requestId", requestId)));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requestId", request.id());
        data.put("status", request.status());
        data.put("companyName", companyName);
        data.put("createdAt", request.requestedAt());

        return ResponseEntity.status(reused ? 200 : 201)
                .body(ApiResponse.ok().message("Access request sent successfully").data(data));
    }

    @GetMapping("/access-requests")
    public ApiResponse accessRequests(Principals.Consumer consumer) {
        return ApiResponse.ok().data(catalog.findRequestsForConsumer(consumer.id()));
    }

    @GetMapping("/accessible-companies")
    public ApiResponse accessibleCompanies(Principals.Consumer consumer) {
        return ApiResponse.ok().data(catalog.findAccessibleCompanies(consumer.id()));
    }

    private String displayName(Principals.Consumer consumer) {
        String name = ((consumer.firstName() == null ? "" : consumer.firstName()) + " "
                + (consumer.lastName() == null ? "" : consumer.lastName())).trim();
        if (!name.isEmpty()) {
            return name;
        }
        return consumer.email() == null ? "Consumer" : consumer.email();
    }

    private long numericId(String id) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            throw ApiException.notFound("Company not found");
        }
    }
}
