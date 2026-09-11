package com.supplierconsumer.consumercart;

import com.supplierconsumer.repo.CartRepository;
import com.supplierconsumer.repo.ConsumerAccessRepository;
import com.supplierconsumer.repo.OrderRepository;
import com.supplierconsumer.security.Principals;
import com.supplierconsumer.wire.ApiException;
import com.supplierconsumer.wire.JsNumbers;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class CartService {

    private final CartRepository carts;
    private final OrderRepository orders;
    private final ConsumerAccessRepository access;
    private final ApplicationEventPublisher events;

    public CartService(CartRepository carts, OrderRepository orders,
                       ConsumerAccessRepository access, ApplicationEventPublisher events) {
        this.carts = carts;
        this.orders = orders;
        this.access = access;
        this.events = events;
    }

    @Transactional
    public Map<String, Object> view(long consumerId, String companyId) {
        long company = numericId(companyId, "Cart not found");
        long cartId = carts.findCartId(consumerId, company)
                .orElseGet(() -> carts.createCart(consumerId, company));

        List<Map<String, Object>> items = carts.findItems(cartId);

        // Accumulated as a double and then formatted, matching the original's arithmetic exactly.
        double total = 0d;
        for (Map<String, Object> item : items) {
            total += JsNumbers.parseFloat(item.get("unit_price")) * JsNumbers.parseInt(item.get("quantity"));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("cartId", cartId);
        data.put("items", items);
        data.put("total", JsNumbers.toFixed2(total));
        return data;
    }

    /**
     * Adds to the cart, or merges into the existing line.
     *
     * <p>The merge path re-reads the product's current price, so adding the same item twice
     * re-prices the whole line. Changing the quantity through the update endpoint does not. That
     * means the price a buyer pays depends on which route they took, which is surprising but is
     * how the original behaves.
     */
    @Transactional
    public void add(long consumerId, String companyId, String productId, Integer quantity) {
        long company = numericId(companyId, "Product not found");
        long product = numericId(productId, "Product not found");
        int requested = quantity == null ? 0 : quantity;

        long cartId = carts.findCartId(consumerId, company)
                .orElseGet(() -> carts.createCart(consumerId, company));

        CartRepository.CartProduct row = carts.findProduct(product, company)
                .orElseThrow(() -> ApiException.notFound("Product not found"));

        if (requested > row.availableQuantity()) {
            throw ApiException.badRequest("Only " + row.availableQuantity() + " units available");
        }
        if (requested < row.minimumOrderQuantity()) {
            throw ApiException.badRequest("Minimum order quantity is " + row.minimumOrderQuantity());
        }

        var existing = carts.findItem(cartId, product);
        if (existing.isPresent()) {
            int merged = existing.get().quantity() + requested;
            if (merged > row.availableQuantity()) {
                throw ApiException.badRequest(
                        "Cannot add more. Only " + row.availableQuantity() + " units available");
            }
            carts.updateItemQuantityAndPrice(existing.get().id(), merged, row.effectivePrice());
        } else {
            carts.insertItem(cartId, product, requested, row.effectivePrice());
        }
    }

    @Transactional
    public void updateItem(long consumerId, String itemId, Integer quantity) {
        long id = numericId(itemId, "Cart item not found");
        int requested = quantity == null ? 0 : quantity;

        CartRepository.OwnedItem item = carts.findOwnedItem(id, consumerId)
                .orElseThrow(() -> ApiException.notFound("Cart item not found"));

        if (requested > item.availableQuantity()) {
            throw ApiException.badRequest("Only " + item.availableQuantity() + " units available");
        }
        if (requested < item.minimumOrderQuantity()) {
            throw ApiException.badRequest(
                    "Minimum order quantity is " + item.minimumOrderQuantity());
        }

        carts.updateItemQuantity(id, requested);
    }

    @Transactional
    public void removeItem(long consumerId, String itemId) {
        long id = numericId(itemId, "Cart item not found");
        if (carts.deleteItem(id, consumerId) == 0) {
            throw ApiException.notFound("Cart item not found");
        }
    }

    @Transactional
    public void clear(long consumerId, String companyId) {
        long company = numericId(companyId, "Cart not found");
        long cartId = carts.findCartId(consumerId, company)
                .orElseThrow(() -> ApiException.notFound("Cart not found"));
        carts.clearItems(cartId);
    }

    /**
     * Turns the cart into an order.
     *
     * <p>Everything that must hold together does: the order, its lines, emptying the cart, and the
     * access grant that lets the buyer discuss the order afterwards. In the original these are
     * separate statements, so a failure partway leaves a half-written order beside a cart that
     * still looks full.
     *
     * <p>The cart row is locked first, so the same cart cannot be checked out twice at once.
     *
     * <p>The chat summary, the unread counter and the notification are deliberately outside: they
     * are announcements about an order that already exists, and the original explicitly declines
     * to fail the order when the chat post fails.
     */
    @Transactional
    public Map<String, Object> checkout(Principals.Consumer consumer, String companyId,
                                        ConsumerCartController.CheckoutRequest request) {
        long company = numericId(companyId, "Cart not found");

        long cartId = carts.lockCart(consumer.id(), company)
                .orElseThrow(() -> ApiException.notFound("Cart not found"));

        List<CartRepository.CheckoutItem> items = carts.findItemsForCheckout(cartId);
        if (items.isEmpty()) {
            throw ApiException.badRequest("Cart is empty");
        }

        double total = 0d;
        for (CartRepository.CheckoutItem item : items) {
            total += JsNumbers.parseFloat(item.unitPrice()) * item.quantity();
        }

        long orderId = orders.insertOrder(consumer.id(), company, request.payment_method(),
                request.delivery_method(), request.delivery_address(),
                request.delivery_coordinates(), JsNumbers.forStorage(total));

        List<String> lines = new ArrayList<>();
        for (CartRepository.CheckoutItem item : items) {
            double lineTotal = JsNumbers.parseFloat(item.unitPrice()) * item.quantity();
            orders.insertOrderItem(orderId, item.productId(), item.productName(), item.quantity(),
                    item.unitPrice(), JsNumbers.forStorage(lineTotal));
            lines.add("- " + item.productName() + " x" + item.quantity()
                    + " = ₸" + JsNumbers.toFixed2(lineTotal));
        }

        carts.clearItems(cartId);

        // Placing an order grants access on its own, so a buyer can always talk to a supplier
        // about an order even if they never formally requested access.
        access.grantAccessIfAbsent(consumer.id(), company);

        events.publishEvent(new OrderPlaced(orderId, consumer.id(), company,
                buildSummary(orderId, consumer, request, lines, total),
                displayName(consumer), JsNumbers.toFixed2(total)));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", orderId);
        data.put("totalAmount", JsNumbers.toFixed2(total));
        return data;
    }

    /** The plain-text order summary posted into the conversation. */
    private String buildSummary(long orderId, Principals.Consumer consumer,
                                ConsumerCartController.CheckoutRequest request,
                                List<String> lines, double total) {
        StringBuilder message = new StringBuilder();
        message.append("📦 New Order #").append(orderId).append("\n\n");
        message.append("Customer: ").append(consumer.firstName()).append(' ')
                .append(consumer.lastName()).append('\n');
        message.append("Email: ").append(consumer.email()).append('\n');
        if (consumer.phone() != null && !consumer.phone().isBlank()) {
            message.append("Phone: ").append(consumer.phone()).append('\n');
        }
        message.append("Payment: ").append(request.payment_method()).append('\n');
        message.append("Delivery: ").append(request.delivery_method()).append('\n');
        if (request.delivery_address() != null && !request.delivery_address().isBlank()) {
            message.append("Address: ").append(request.delivery_address()).append('\n');
        }
        message.append("\nItems:\n").append(String.join("\n", lines)).append('\n');
        message.append("\nTotal: ₸").append(JsNumbers.toFixed2(total));
        return message.toString();
    }

    private String displayName(Principals.Consumer consumer) {
        String name = ((consumer.firstName() == null ? "" : consumer.firstName()) + " "
                + (consumer.lastName() == null ? "" : consumer.lastName())).trim();
        return name.isEmpty() ? "Customer" : name;
    }

    private long numericId(String id, String notFoundMessage) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            throw ApiException.notFound(notFoundMessage);
        }
    }

    /** Raised after the order commits, so the announcements cannot roll it back. */
    public record OrderPlaced(long orderId, long consumerId, long companyId, String chatSummary,
                              String customerName, String totalFormatted) {
    }
}
