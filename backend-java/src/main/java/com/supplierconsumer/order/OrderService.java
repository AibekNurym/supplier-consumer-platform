package com.supplierconsumer.order;

import com.supplierconsumer.repo.OrderRepository;
import com.supplierconsumer.wire.ApiException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The order state machine: {@code pending → accepted → completed}, or
 * {@code pending|accepted → rejected}.
 *
 * <p>Every transition locks the order row first. That single lock does three things: it serialises
 * the transitions against each other, it makes the idempotent "already accepted" answer correct
 * rather than racy, and it means the {@code stockRestored} flag on a rejection reports what
 * actually happened instead of a guess.
 *
 * <p>Results are returned as a sealed type rather than shaped here, so the controller owns the
 * response quirks and the service owns the locking.
 */
@Service
public class OrderService {

    private final OrderRepository orders;
    private final ApplicationEventPublisher events;

    public OrderService(OrderRepository orders, ApplicationEventPublisher events) {
        this.orders = orders;
        this.events = events;
    }

    /**
     * Accepts an order and takes the stock.
     *
     * <p>The original reads stock, checks it, and decrements it in three separate steps, so two
     * acceptances can both pass the check and drive stock negative -- the {@code GREATEST(0, ...)}
     * in its update hides that rather than preventing it. Here the order row and then the product
     * rows are locked, and the sufficiency check is re-run against the locked values, so the
     * decision and the write cannot be separated by another transaction.
     *
     * <p>Products are locked in ascending id order. Two acceptances sharing products therefore take
     * their locks in the same sequence and one simply waits, instead of deadlocking.
     */
    @Transactional
    public AcceptResult accept(long orderId, long companyId) {
        OrderRepository.OrderRow order = orders.lockOrder(orderId)
                .orElseThrow(() -> ApiException.notFound("Order not found"));

        if (order.companyId() != companyId) {
            throw ApiException.notFound("Order not found");
        }
        if (!"pending".equals(order.status()) && !"accepted".equals(order.status())) {
            throw ApiException.badRequest(
                    "Order cannot be accepted. Current status: " + order.status());
        }
        if ("accepted".equals(order.status())) {
            return new AcceptResult.AlreadyAccepted();
        }

        List<OrderRepository.StockLine> lines = orders.findLinesWithStock(orderId, companyId);
        if (lines.isEmpty()) {
            throw ApiException.badRequest(orders.hasAnyItems(orderId)
                    ? "Order items do not belong to this company"
                    : "Order has no items");
        }

        Map<Long, Integer> locked = orders.lockStock(
                lines.stream().map(OrderRepository.StockLine::productId).toList());

        // Report shortfalls using the locked quantities, so the numbers in the error body are the
        // ones the decision was actually made on.
        List<Map<String, Object>> shortfall = lines.stream()
                .filter(line -> locked.getOrDefault(line.productId(), 0) < line.quantity())
                .map(line -> describe(line, locked.getOrDefault(line.productId(), 0)))
                .toList();

        if (!shortfall.isEmpty()) {
            return new AcceptResult.Insufficient(shortfall, lines.stream()
                    .filter(line -> locked.getOrDefault(line.productId(), 0) < line.quantity())
                    .map(line -> line.productName() + " (available: "
                            + locked.getOrDefault(line.productId(), 0)
                            + ", ordered: " + line.quantity() + ")")
                    .collect(Collectors.joining(", ")));
        }

        for (OrderRepository.StockLine line : lines) {
            orders.decrementStock(line.productId(), companyId, line.quantity());
        }
        orders.setStatus(orderId, "accepted");

        events.publishEvent(new OrderTransitioned("accepted", orderId, order.consumerId(),
                companyId, orders.findEventRow(orderId).orElse(Map.of())));

        return new AcceptResult.Accepted();
    }

    /** Rejects an order, giving stock back only if it had actually been taken. */
    @Transactional
    public RejectResult reject(long orderId, long companyId) {
        OrderRepository.OrderRow order = orders.lockOrder(orderId)
                .orElseThrow(() -> ApiException.notFound("Order not found"));

        if (order.companyId() != companyId) {
            throw ApiException.notFound("Order not found");
        }
        if (!"pending".equals(order.status()) && !"accepted".equals(order.status())) {
            throw ApiException.badRequest(
                    "Order cannot be rejected. Current status: " + order.status());
        }

        boolean restoreStock = "accepted".equals(order.status());
        if (restoreStock) {
            List<OrderRepository.StockLine> lines = orders.findLinesWithStock(orderId, companyId);
            orders.lockStock(lines.stream().map(OrderRepository.StockLine::productId).toList());
            for (OrderRepository.StockLine line : lines) {
                orders.restoreStock(line.productId(), companyId, line.quantity());
            }
        }
        orders.setStatus(orderId, "rejected");

        events.publishEvent(new OrderTransitioned("rejected", orderId, order.consumerId(),
                companyId, orders.findEventRow(orderId).orElse(Map.of())));

        return new RejectResult(restoreStock);
    }

    /** The buyer confirms delivery, which also closes any issue still open on the order. */
    @Transactional
    public void complete(long orderId, long consumerId) {
        OrderRepository.OrderRow order = orders.lockOrder(orderId)
                .orElseThrow(() -> ApiException.notFound("Order not found"));

        if (order.consumerId() != consumerId) {
            throw ApiException.notFound("Order not found");
        }
        if (!"accepted".equals(order.status())) {
            throw ApiException.badRequest("Order is not accepted");
        }

        orders.setStatus(orderId, "completed");
        orders.resolveOpenIssues(orderId);

        events.publishEvent(new OrderTransitioned("completed", orderId, consumerId,
                order.companyId(), orders.findEventRow(orderId).orElse(Map.of())));
    }

    private Map<String, Object> describe(OrderRepository.StockLine line, int available) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("product_id", line.productId());
        row.put("quantity", line.quantity());
        row.put("product_name", line.productName());
        row.put("available_quantity", available);
        return row;
    }

    /** What happened, for the controller to translate into the response the client expects. */
    public sealed interface AcceptResult {

        record Accepted() implements AcceptResult {
        }

        /** The order was already accepted; nothing was taken a second time. */
        record AlreadyAccepted() implements AcceptResult {
        }

        record Insufficient(List<Map<String, Object>> rows, String summary) implements AcceptResult {
        }
    }

    public record RejectResult(boolean stockRestored) {
    }

    public record OrderTransitioned(String transition, long orderId, long consumerId, long companyId,
                                    Map<String, Object> row) {
    }
}
