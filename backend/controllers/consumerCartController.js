import { sql } from "../config/db.js";
import { insertChatMessage } from "../services/chatMessageService.js";
import { emitChatMessage, emitOrderUpdate } from "../realtime/events.js";
import { createNotification } from "../services/notificationService.js";
import { incrementUnreadCount } from "../services/chatUnreadService.js";

// Get or create cart for a consumer-company combination
export const getCart = async (req, res) => {
    try {
        const consumerId = req.consumer.id;
        const { companyId } = req.params;

        // Get or create cart
        let cart = await sql`
            SELECT id FROM consumer_carts 
            WHERE consumer_id = ${consumerId} AND company_id = ${companyId}
        `;

        let cartId;
        if (cart.length === 0) {
            // Create new cart
            const newCart = await sql`
                INSERT INTO consumer_carts (consumer_id, company_id)
                VALUES (${consumerId}, ${companyId})
                RETURNING id
            `;
            cartId = newCart[0].id;
        } else {
            cartId = cart[0].id;
        }

        // Get cart items with product details
        const cartItems = await sql`
            SELECT 
                ci.id,
                ci.product_id,
                ci.quantity,
                ci.unit_price,
                p.name,
                p.image,
                p.minimum_order_quantity,
                p.available_quantity,
                p.lead_time_days
            FROM consumer_cart_items ci
            JOIN products p ON ci.product_id = p.id
            WHERE ci.cart_id = ${cartId}
            ORDER BY ci.created_at DESC
        `;

        // Calculate total
        let total = 0;
        cartItems.forEach(item => {
            total += parseFloat(item.unit_price) * item.quantity;
        });

        res.json({
            success: true,
            data: {
                cartId,
                items: cartItems,
                total: total.toFixed(2)
            }
        });

    } catch (error) {
        console.error("Get cart error:", error);
        res.status(500).json({
            success: false,
            message: "Internal server error"
        });
    }
};

// Add item to cart
export const addToCart = async (req, res) => {
    try {
        const consumerId = req.consumer.id;
        const { companyId, productId } = req.params;
        const { quantity } = req.body;

        // Get or create cart
        let cart = await sql`
            SELECT id FROM consumer_carts 
            WHERE consumer_id = ${consumerId} AND company_id = ${companyId}
        `;

        let cartId;
        if (cart.length === 0) {
            const newCart = await sql`
                INSERT INTO consumer_carts (consumer_id, company_id)
                VALUES (${consumerId}, ${companyId})
                RETURNING id
            `;
            cartId = newCart[0].id;
        } else {
            cartId = cart[0].id;
        }

        // Get product details to get current price with discount
        const product = await sql`
            SELECT 
                id, 
                name, 
                price, 
                discount_percentage,
                CASE 
                    WHEN discount_percentage > 0 THEN 
                        ROUND(price * (1 - discount_percentage / 100), 2)
                    ELSE 
                        price
                END as discounted_price,
                minimum_order_quantity, 
                available_quantity
            FROM products
            WHERE id = ${productId} AND company_id = ${companyId}
        `;

        if (product.length === 0) {
            return res.status(404).json({
                success: false,
                message: "Product not found"
            });
        }

        if (quantity > product[0].available_quantity) {
            return res.status(400).json({
                success: false,
                message: `Only ${product[0].available_quantity} units available`
            });
        }

        if (quantity < product[0].minimum_order_quantity) {
            return res.status(400).json({
                success: false,
                message: `Minimum order quantity is ${product[0].minimum_order_quantity}`
            });
        }

        // Check if item already exists in cart
        const existingItem = await sql`
            SELECT id, quantity FROM consumer_cart_items
            WHERE cart_id = ${cartId} AND product_id = ${productId}
        `;

        if (existingItem.length > 0) {
            // Update quantity
            const newQuantity = existingItem[0].quantity + quantity;
            
            if (newQuantity > product[0].available_quantity) {
                return res.status(400).json({
                    success: false,
                    message: `Cannot add more. Only ${product[0].available_quantity} units available`
                });
            }

            // Update quantity and price (in case discount changed)
            const unitPrice = product[0].discounted_price || product[0].price;
            await sql`
                UPDATE consumer_cart_items
                SET quantity = ${newQuantity}, unit_price = ${unitPrice}, updated_at = NOW()
                WHERE id = ${existingItem[0].id}
            `;
        } else {
            // Add new item with discounted price
            const unitPrice = product[0].discounted_price || product[0].price;
            await sql`
                INSERT INTO consumer_cart_items (cart_id, product_id, quantity, unit_price)
                VALUES (${cartId}, ${productId}, ${quantity}, ${unitPrice})
            `;
        }

        res.json({
            success: true,
            message: "Item added to cart successfully"
        });

    } catch (error) {
        console.error("Add to cart error:", error);
        res.status(500).json({
            success: false,
            message: "Internal server error"
        });
    }
};

// Update cart item quantity
export const updateCartItem = async (req, res) => {
    try {
        const consumerId = req.consumer.id;
        const { itemId } = req.params;
        const { quantity } = req.body;

        // Get cart item
        const cartItem = await sql`
            SELECT ci.quantity, ci.product_id, p.available_quantity, p.minimum_order_quantity
            FROM consumer_cart_items ci
            JOIN consumer_carts c ON ci.cart_id = c.id
            JOIN products p ON ci.product_id = p.id
            WHERE ci.id = ${itemId} AND c.consumer_id = ${consumerId}
        `;

        if (cartItem.length === 0) {
            return res.status(404).json({
                success: false,
                message: "Cart item not found"
            });
        }

        if (quantity > cartItem[0].available_quantity) {
            return res.status(400).json({
                success: false,
                message: `Only ${cartItem[0].available_quantity} units available`
            });
        }

        if (quantity < cartItem[0].minimum_order_quantity) {
            return res.status(400).json({
                success: false,
                message: `Minimum order quantity is ${cartItem[0].minimum_order_quantity}`
            });
        }

        await sql`
            UPDATE consumer_cart_items
            SET quantity = ${quantity}, updated_at = NOW()
            WHERE id = ${itemId}
        `;

        res.json({
            success: true,
            message: "Cart item updated successfully"
        });

    } catch (error) {
        console.error("Update cart item error:", error);
        res.status(500).json({
            success: false,
            message: "Internal server error"
        });
    }
};

// Remove item from cart
export const removeFromCart = async (req, res) => {
    try {
        const consumerId = req.consumer.id;
        const { itemId } = req.params;

        // Verify item belongs to consumer's cart
        const cartItem = await sql`
            SELECT ci.id
            FROM consumer_cart_items ci
            JOIN consumer_carts c ON ci.cart_id = c.id
            WHERE ci.id = ${itemId} AND c.consumer_id = ${consumerId}
        `;

        if (cartItem.length === 0) {
            return res.status(404).json({
                success: false,
                message: "Cart item not found"
            });
        }

        await sql`
            DELETE FROM consumer_cart_items
            WHERE id = ${itemId}
        `;

        res.json({
            success: true,
            message: "Item removed from cart"
        });

    } catch (error) {
        console.error("Remove from cart error:", error);
        res.status(500).json({
            success: false,
            message: "Internal server error"
        });
    }
};

// Clear cart
export const clearCart = async (req, res) => {
    try {
        const consumerId = req.consumer.id;
        const { companyId } = req.params;

        // Get cart
        const cart = await sql`
            SELECT id FROM consumer_carts 
            WHERE consumer_id = ${consumerId} AND company_id = ${companyId}
        `;

        if (cart.length === 0) {
            return res.status(404).json({
                success: false,
                message: "Cart not found"
            });
        }

        // Delete all items
        await sql`
            DELETE FROM consumer_cart_items
            WHERE cart_id = ${cart[0].id}
        `;

        res.json({
            success: true,
            message: "Cart cleared successfully"
        });

    } catch (error) {
        console.error("Clear cart error:", error);
        res.status(500).json({
            success: false,
            message: "Internal server error"
        });
    }
};

// Checkout (create order from cart)
export const checkout = async (req, res) => {
    try {
        const consumerId = req.consumer.id;
        const { companyId } = req.params;
        const { payment_method, delivery_method, delivery_address, delivery_coordinates } = req.body;

        // Get cart
        const cart = await sql`
            SELECT id FROM consumer_carts 
            WHERE consumer_id = ${consumerId} AND company_id = ${companyId}
        `;

        if (cart.length === 0) {
            return res.status(404).json({
                success: false,
                message: "Cart not found"
            });
        }

        const cartId = cart[0].id;

        // Get cart items
        const cartItems = await sql`
            SELECT 
                ci.product_id,
                ci.quantity,
                ci.unit_price,
                p.name as product_name
            FROM consumer_cart_items ci
            JOIN products p ON ci.product_id = p.id
            WHERE ci.cart_id = ${cartId}
        `;

        if (cartItems.length === 0) {
            return res.status(400).json({
                success: false,
                message: "Cart is empty"
            });
        }

        // Calculate total amount
        let totalAmount = 0;
        cartItems.forEach(item => {
            totalAmount += parseFloat(item.unit_price) * item.quantity;
        });

        // Create order
        const order = await sql`
            INSERT INTO consumer_orders (
                consumer_id,
                company_id,
                payment_method,
                delivery_method,
                delivery_address,
                delivery_coordinates,
                total_amount,
                status
            )
            VALUES (
                ${consumerId},
                ${companyId},
                ${payment_method},
                ${delivery_method},
                ${delivery_address || null},
                ${delivery_coordinates || null},
                ${totalAmount},
                'pending'
            )
            RETURNING id
        `;

        const orderId = order[0].id;

        // Create order items
        for (const item of cartItems) {
            await sql`
                INSERT INTO consumer_order_items (
                    order_id,
                    product_id,
                    product_name,
                    quantity,
                    unit_price,
                    total_price
                )
                VALUES (
                    ${orderId},
                    ${item.product_id},
                    ${item.product_name},
                    ${item.quantity},
                    ${item.unit_price},
                    ${parseFloat(item.unit_price) * item.quantity}
                )
            `;
        }

        // Clear cart
        await sql`
            DELETE FROM consumer_cart_items
            WHERE cart_id = ${cartId}
        `;

        // Ensure consumer has access to company (for chat and messaging)
        // This allows consumers to message companies about their orders even if they haven't explicitly requested access
        try {
            const existingAccess = await sql`
                SELECT id FROM consumer_company_access 
                WHERE consumer_id = ${consumerId} AND company_id = ${companyId}
            `;
            
            if (existingAccess.length === 0) {
                // Create access record automatically when order is placed
                await sql`
                    INSERT INTO consumer_company_access (consumer_id, company_id, granted_by, granted_at)
                    VALUES (${consumerId}, ${companyId}, NULL, NOW())
                    ON CONFLICT (consumer_id, company_id) DO NOTHING
                `;
            }
        } catch (accessError) {
            console.log('Note: Could not create access record (may already exist or table structure differs):', accessError.message);
        }

        // Get order details for chat message
        const orderDetails = await sql`
            SELECT 
                o.*,
                cu.first_name,
                cu.last_name,
                cu.email,
                cu.phone
            FROM consumer_orders o
            JOIN consumer_users cu ON o.consumer_id = cu.id
            WHERE o.id = ${orderId}
        `;

        // Get consumer details
        const consumer = await sql`
            SELECT first_name, last_name FROM consumer_users WHERE id = ${consumerId}
        `;

        // Format order message
        const itemsList = cartItems.map(item => 
            `- ${item.product_name} x${item.quantity} = ₸${(parseFloat(item.unit_price) * item.quantity).toFixed(2)}`
        ).join('\n');

        const orderMessage = `📦 New Order #${orderId}\n\n` +
            `Customer: ${orderDetails[0].first_name} ${orderDetails[0].last_name}\n` +
            `Email: ${orderDetails[0].email}\n` +
            (orderDetails[0].phone ? `Phone: ${orderDetails[0].phone}\n` : '') +
            `Payment: ${payment_method}\n` +
            `Delivery: ${delivery_method}\n` +
            (delivery_address ? `Address: ${delivery_address}\n` : '') +
            `\nItems:\n${itemsList}\n` +
            `\nTotal: ₸${totalAmount.toFixed(2)}`;

        // Send order message to company in chat
        try {
            const chatMessage = await insertChatMessage(
                {
                    consumerId,
                    companyId,
                    senderType: 'consumer',
                    senderId: consumerId,
                    messageText: orderMessage,
                    messageType: 'text',
                },
                { db: sql }
            );

            if (chatMessage) {
                emitChatMessage(chatMessage);
                await incrementUnreadCount({
                    consumerId,
                    companyId,
                    senderType: "consumer",
                });
                console.log('Order message sent to chat:', chatMessage.id);
            }
        } catch (chatError) {
            console.error('Error sending order message to chat:', chatError);
            console.error('Order chat error details:', {
                consumer_id: consumerId,
                company_id: companyId,
                order_id: orderId,
                error: chatError.message,
                stack: chatError.stack
            });
            // Don't fail the order if chat message fails
        }

        try {
            emitOrderUpdate({
                ...(orderDetails?.[0] || {}),
                id: orderId,
                consumer_id: consumerId,
                company_id: companyId,
                status: "pending",
                total_amount: totalAmount,
            });
        } catch (emitError) {
            console.warn("Failed to emit order update event:", emitError.message);
        }

        try {
            const customerName = `${orderDetails[0]?.first_name || ""} ${orderDetails[0]?.last_name || ""}`.trim() || "Customer";
            await createNotification({
                targetType: "company",
                companyId,
                title: `New order #${orderId}`,
                body: `${customerName} placed an order totaling ₸${totalAmount.toFixed(2)}`,
                actionType: "order:new",
                actionPayload: {
                    orderId,
                    consumerId,
                    companyId,
                },
            });
        } catch (notificationError) {
            console.warn("Failed to create order notification:", notificationError.message);
        }

        res.json({
            success: true,
            message: "Order placed successfully",
            data: {
                orderId: orderId,
                totalAmount: totalAmount.toFixed(2)
            }
        });

    } catch (error) {
        console.error("Checkout error:", error);
        res.status(500).json({
            success: false,
            message: "Internal server error"
        });
    }
};

