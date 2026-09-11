package com.supplierconsumer.product;

import com.supplierconsumer.repo.ProductRepository;
import com.supplierconsumer.security.Authorization;
import com.supplierconsumer.security.Principals;
import com.supplierconsumer.wire.ApiException;
import com.supplierconsumer.wire.ApiResponse;
import com.supplierconsumer.wire.JsNumbers;
import com.supplierconsumer.wire.WireError;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

/**
 * {@code /api/products} -- the supplier's own catalogue.
 *
 * <p>Request and response share the same snake_case keys. That is not incidental: the console
 * prefills its edit form directly from the read response and posts it back, so the two shapes have
 * to be interchangeable. It also rules out camelCasing this resource.
 *
 * <p>Prices go out as strings ({@code "1200.00"}), because that is how the driver rendered a
 * numeric column. The row mapper handles it; nothing here formats anything.
 *
 * <p>Path variables are declared as String rather than long on purpose. In the original a
 * non-numeric id reached Postgres, failed there, and surfaced as a 500 from the handler's catch.
 * Binding to a long would make Spring reject it with a 400 before the handler ran, which is a
 * different status for the same request.
 */
@RestController
@RequestMapping("/api/products")
@WireError(message = "Internal server error", detail = WireError.ErrorDetail.DEV_ONLY)
public class ProductController {

    private final ProductRepository products;
    private final Authorization authz;

    public ProductController(ProductRepository products, Authorization authz) {
        this.products = products;
        this.authz = authz;
    }

    @GetMapping
    public ApiResponse list(Principals.Company user, HttpServletRequest http) {
        authz.requirePermission(user, "products.read", http);
        long companyId = user.requireCompanyId(
                "User must be associated with a company to view products");

        return ApiResponse.ok().data(products.findAllForCompany(companyId));
    }

    @GetMapping("/{id}")
    public ApiResponse get(@PathVariable String id, Principals.Company user, HttpServletRequest http) {
        authz.requirePermission(user, "products.read", http);
        long companyId = user.requireCompanyId(
                "User must be associated with a company to view products");

        Map<String, Object> product = products.findForCompany(numericId(id), companyId)
                .orElseThrow(() -> ApiException.notFound("Product not found"));

        return ApiResponse.ok().data(product);
    }

    /** Note there is no {@code message} key on this response, unlike most creates. */
    @PostMapping
    public ResponseEntity<ApiResponse> create(@RequestBody(required = false) ProductRequest request,
                                              Principals.Company user, HttpServletRequest http) {
        authz.requirePermission(user, "products.create", http);
        ProductRequest body = request == null ? ProductRequest.empty() : request;

        if (isBlank(body.name()) || isBlank(body.image()) || body.price() == null
                || body.minimum_order_quantity() == null || body.available_quantity() == null) {
            throw ApiException.badRequest("All required fields are missing");
        }

        long companyId = user.requireCompanyId(
                "User must be associated with a company to create products");

        Map<String, Object> created = products.insert(body.name(), body.image(), body.price(),
                clampDiscount(body.discount_percentage(), BigDecimal.ZERO),
                clampLeadTime(body.lead_time_days(), 0),
                body.minimum_order_quantity(), body.available_quantity(), companyId);

        return ResponseEntity.status(201).body(ApiResponse.ok().data(created));
    }

    /** A merge patch: any field left out keeps the value the product already has. */
    @PutMapping("/{id}")
    @Transactional
    public ApiResponse update(@PathVariable String id,
                              @RequestBody(required = false) ProductRequest request,
                              Principals.Company user, HttpServletRequest http) {
        authz.requirePermission(user, "products.update", http);
        ProductRequest body = request == null ? ProductRequest.empty() : request;

        long companyId = user.requireCompanyId(
                "User must be associated with a company to update products");
        long productId = numericId(id);

        Map<String, Object> existing = products.findForCompany(productId, companyId)
                .orElseThrow(() -> ApiException.notFound("Product not found"));

        boolean hasUpdates = body.name() != null || body.image() != null || body.price() != null
                || body.discount_percentage() != null || body.lead_time_days() != null
                || body.minimum_order_quantity() != null || body.available_quantity() != null;

        if (!hasUpdates) {
            throw ApiException.badRequest("No fields to update");
        }

        Map<String, Object> updated = products.update(productId, companyId,
                body.name() != null ? body.name() : (String) existing.get("name"),
                body.image() != null ? body.image() : (String) existing.get("image"),
                body.price() != null ? body.price() : decimalOf(existing.get("price")),
                clampDiscount(body.discount_percentage(), decimalOf(existing.get("discount_percentage"))),
                clampLeadTime(body.lead_time_days(), intOf(existing.get("lead_time_days"))),
                body.minimum_order_quantity() != null
                        ? body.minimum_order_quantity() : intOf(existing.get("minimum_order_quantity")),
                body.available_quantity() != null
                        ? body.available_quantity() : intOf(existing.get("available_quantity")))
                .orElseThrow(() -> ApiException.notFound("Product not found"));

        return ApiResponse.ok().data(updated);
    }

    /** The one response in the API that puts {@code data} before {@code message}. */
    @DeleteMapping("/{id}")
    @Transactional
    public ApiResponse delete(@PathVariable String id, Principals.Company user, HttpServletRequest http) {
        authz.requirePermission(user, "products.delete", http);
        long companyId = user.requireCompanyId(
                "User must be associated with a company to delete products");
        long productId = numericId(id);

        products.findForCompany(productId, companyId)
                .orElseThrow(() -> ApiException.notFound("Product not found"));

        int activeOrders = products.countActiveOrders(productId);
        if (activeOrders > 0) {
            throw ApiException.badRequest("Cannot delete product. It is currently in "
                    + activeOrders + " active order(s). Please complete or cancel these orders first.");
        }

        Map<String, Object> deleted;
        try {
            deleted = products.delete(productId, companyId)
                    .orElseThrow(() -> ApiException.notFound("Product not found"));
        } catch (DataIntegrityViolationException e) {
            // A reference the active-order check does not cover, e.g. a chat message linking to
            // the product. The original detects this by string-matching the driver's error text.
            throw ApiException.badRequest("Cannot delete product. It is referenced in existing "
                    + "orders. Please contact support if you need to delete this product.");
        }

        return ApiResponse.ok().data(deleted).message("Product deleted successfully");
    }

    /**
     * Mirrors the original's clamping: a discount is pinned to 0-100 and anything unparseable
     * becomes 0, rather than being rejected.
     */
    private BigDecimal clampDiscount(BigDecimal supplied, BigDecimal fallback) {
        if (supplied == null) {
            return fallback;
        }
        return supplied.max(BigDecimal.ZERO).min(BigDecimal.valueOf(100));
    }

    private int clampLeadTime(Integer supplied, int fallback) {
        return supplied == null ? fallback : Math.max(0, supplied);
    }

    /**
     * A non-numeric id used to reach the database and fail there, producing a 500. Preserved by
     * throwing something the handler's own error policy turns into the same 500.
     */
    private long numericId(String id) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid input syntax for type integer: \"" + id + "\"");
        }
    }

    private static BigDecimal decimalOf(Object value) {
        return value == null ? null : new BigDecimal(value.toString());
    }

    private static int intOf(Object value) {
        return JsNumbers.parseInt(value);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * The request body, in the same snake_case the responses use, because the console posts back
     * what it was given.
     */
    public record ProductRequest(
            String name,
            String image,
            BigDecimal price,
            BigDecimal discount_percentage,
            Integer lead_time_days,
            Integer minimum_order_quantity,
            Integer available_quantity) {

        static ProductRequest empty() {
            return new ProductRequest(null, null, null, null, null, null, null);
        }
    }
}
