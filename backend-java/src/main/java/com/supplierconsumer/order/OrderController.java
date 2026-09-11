package com.supplierconsumer.order;

import com.supplierconsumer.repo.OrderRepository;
import com.supplierconsumer.security.Principals;
import com.supplierconsumer.wire.ApiException;
import com.supplierconsumer.wire.ApiResponse;
import com.supplierconsumer.wire.WireError;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code /api/orders} -- served to both identities, which is why the handlers do not share a
 * principal type: the supplier-facing routes take a company user, the buyer-facing ones a consumer.
 *
 * <p>Three responses here step outside the usual envelope and add a top-level flag of their own:
 * acceptance reports {@code stockUpdated} or {@code alreadyAccepted}, rejection reports
 * {@code stockRestored}, and a stock failure attaches the machine-readable
 * {@code insufficientStock} array alongside the human-readable message.
 */
@RestController
@RequestMapping("/api/orders")
@WireError(message = "Internal server error", detail = WireError.ErrorDetail.DEV_ONLY)
public class OrderController {

    private final OrderRepository orders;
    private final OrderService service;

    public OrderController(OrderRepository orders, OrderService service) {
        this.orders = orders;
        this.service = service;
    }

    /** Named "pending" but returns accepted orders too -- it is the active-work list. */
    @GetMapping("/pending")
    public ApiResponse pending(Principals.Company user) {
        long companyId = user.requireCompanyId("User must be associated with a company");
        return ApiResponse.ok().data(withItems(orders.findActiveForCompany(companyId)));
    }

    @GetMapping("/completed")
    public ApiResponse completed(Principals.Company user) {
        long companyId = user.requireCompanyId("User must be associated with a company");
        return ApiResponse.ok().data(withItems(orders.findCompletedForCompany(companyId)));
    }

    @GetMapping("/customer/{orderId}")
    public ApiResponse customer(@PathVariable String orderId, Principals.Company user) {
        long companyId = user.requireCompanyId("User must be associated with a company");
        Map<String, Object> row = orders.findCustomer(numericId(orderId), companyId)
                .orElseThrow(() -> ApiException.notFound("Order not found"));
        return ApiResponse.ok().data(row);
    }

    @PutMapping("/{orderId}/accept")
    public ApiResponse accept(@PathVariable String orderId, Principals.Company user) {
        long companyId = user.requireCompanyId("User must be associated with a company");

        return switch (service.accept(numericId(orderId), companyId)) {
            case OrderService.AcceptResult.Accepted ignored -> ApiResponse.ok()
                    .message("Order accepted and stock updated")
                    .put("stockUpdated", true);

            case OrderService.AcceptResult.AlreadyAccepted ignored -> ApiResponse.ok()
                    .message("Order already accepted")
                    .put("alreadyAccepted", true);

            case OrderService.AcceptResult.Insufficient shortfall -> throw ApiException
                    .badRequest("Insufficient stock for: " + shortfall.summary())
                    .with("insufficientStock", shortfall.rows());
        };
    }

    @PutMapping("/{orderId}/reject")
    public ApiResponse reject(@PathVariable String orderId, Principals.Company user) {
        long companyId = user.requireCompanyId("User must be associated with a company");
        OrderService.RejectResult result = service.reject(numericId(orderId), companyId);

        return ApiResponse.ok()
                .message(result.stockRestored() ? "Order rejected and stock restored" : "Order rejected")
                .put("stockRestored", result.stockRestored());
    }

    @GetMapping("/consumer")
    public ApiResponse consumerOrders(Principals.Consumer consumer) {
        return ApiResponse.ok().data(withItems(orders.findForConsumer(consumer.id())));
    }

    @PutMapping("/consumer/{orderId}/complete")
    public ApiResponse complete(@PathVariable String orderId, Principals.Consumer consumer) {
        service.complete(numericId(orderId), consumer.id());
        return ApiResponse.ok().message("Order completed and all issues resolved");
    }

    /**
     * Attaches line items to each order.
     *
     * <p>Fetched in a single statement rather than one query per order, which is what the original
     * does on every list endpoint. The resulting shape is identical.
     */
    private List<Map<String, Object>> withItems(List<Map<String, Object>> rows) {
        List<Long> ids = rows.stream()
                .map(row -> ((Number) row.get("id")).longValue())
                .toList();

        Map<Long, List<Map<String, Object>>> byOrder = new LinkedHashMap<>();
        for (Map<String, Object> item : orders.findItemsForOrders(ids)) {
            long orderId = ((Number) item.get("order_id")).longValue();
            Map<String, Object> line = new LinkedHashMap<>(item);
            line.remove("order_id");
            byOrder.computeIfAbsent(orderId, k -> new ArrayList<>()).add(line);
        }

        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> copy = new LinkedHashMap<>(row);
            copy.put("items", byOrder.getOrDefault(((Number) row.get("id")).longValue(), List.of()));
            out.add(copy);
        }
        return out;
    }

    private long numericId(String id) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            throw ApiException.notFound("Order not found");
        }
    }
}
