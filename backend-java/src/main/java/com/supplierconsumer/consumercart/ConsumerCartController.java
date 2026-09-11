package com.supplierconsumer.consumercart;

import com.supplierconsumer.security.Principals;
import com.supplierconsumer.wire.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * {@code /api/consumer/cart} -- one cart per buyer per supplier.
 *
 * <p>Both reading the cart and adding to it will create the cart row if it does not exist, so a
 * GET here has a write side effect. That is how the original behaves and the client relies on it:
 * it never creates a cart explicitly.
 *
 * <p>Checkout returns 200 rather than 201 despite creating an order, which is also preserved.
 */
@RestController
@RequestMapping("/api/consumer/cart")
public class ConsumerCartController {

    private final CartService cart;

    public ConsumerCartController(CartService cart) {
        this.cart = cart;
    }

    @GetMapping("/company/{companyId}")
    public ApiResponse get(@PathVariable String companyId, Principals.Consumer consumer) {
        return ApiResponse.ok().data(cart.view(consumer.id(), companyId));
    }

    @PostMapping("/company/{companyId}/product/{productId}/add")
    public ApiResponse add(@PathVariable String companyId, @PathVariable String productId,
                           @RequestBody(required = false) QuantityRequest request,
                           Principals.Consumer consumer) {
        cart.add(consumer.id(), companyId, productId, quantityOf(request));
        return ApiResponse.ok().message("Item added to cart successfully");
    }

    @PutMapping("/item/{itemId}")
    public ApiResponse updateItem(@PathVariable String itemId,
                                  @RequestBody(required = false) QuantityRequest request,
                                  Principals.Consumer consumer) {
        cart.updateItem(consumer.id(), itemId, quantityOf(request));
        return ApiResponse.ok().message("Cart item updated successfully");
    }

    @DeleteMapping("/item/{itemId}")
    public ApiResponse removeItem(@PathVariable String itemId, Principals.Consumer consumer) {
        cart.removeItem(consumer.id(), itemId);
        return ApiResponse.ok().message("Item removed from cart");
    }

    @DeleteMapping("/company/{companyId}/clear")
    public ApiResponse clear(@PathVariable String companyId, Principals.Consumer consumer) {
        cart.clear(consumer.id(), companyId);
        return ApiResponse.ok().message("Cart cleared successfully");
    }

    @PostMapping("/company/{companyId}/checkout")
    public ApiResponse checkout(@PathVariable String companyId,
                                @RequestBody(required = false) CheckoutRequest request,
                                Principals.Consumer consumer) {
        Map<String, Object> result = cart.checkout(consumer, companyId,
                request == null ? CheckoutRequest.empty() : request);
        return ApiResponse.ok().message("Order placed successfully").data(result);
    }

    private Integer quantityOf(QuantityRequest request) {
        return request == null ? null : request.quantity();
    }

    public record QuantityRequest(Integer quantity) {
    }

    /** snake_case, because that is what the client sends. */
    public record CheckoutRequest(String payment_method, String delivery_method,
                                  String delivery_address, String delivery_coordinates) {
        static CheckoutRequest empty() {
            return new CheckoutRequest(null, null, null, null);
        }
    }
}
