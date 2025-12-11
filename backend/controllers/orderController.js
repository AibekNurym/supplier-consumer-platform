import { sql } from "../config/db.js";
import { createNotification } from "../services/notificationService.js";
import { emitOrderUpdate } from "../realtime/events.js";

// Get pending orders for company
export const getPendingOrders = async (req, res) => {
    try {
        const companyId = req.user.company_id;

        const orders = await sql`
            SELECT 
                o.id,
                o.created_at,
                o.payment_method,
                o.delivery_method,
                o.delivery_address,
                o.total_amount,
                o.status,
                o.consumer_id,
                cu.first_name,
                cu.last_name,
                cu.email,
                cu.phone
            FROM consumer_orders o
            JOIN consumer_users cu ON o.consumer_id = cu.id
            WHERE o.company_id = ${companyId} AND o.status IN ('pending','accepted')
            ORDER BY o.created_at DESC
        `;

        // Get order items for each order
        for (const order of orders) {
            const items = await sql`
                SELECT 
                    product_id,
                    product_name,
                    quantity,
                    unit_price,
                    total_price
                FROM consumer_order_items
                WHERE order_id = ${order.id}
            `;
            order.items = items;
        }

        res.json({
            success: true,
            data: orders
        });

    } catch (error) {
        console.error("Get pending orders error:", error);
        res.status(500).json({
            success: false,
            message: "Internal server error"
        });
    }
};

// Get completed orders for company
export const getCompletedOrders = async (req, res) => {
    try {
        const companyId = req.user.company_id;

        const orders = await sql`
            SELECT 
                o.id,
                o.created_at,
                o.payment_method,
                o.delivery_method,
                o.delivery_address,
                o.total_amount,
                o.status,
                o.consumer_id,
                cu.first_name,
                cu.last_name,
                cu.email,
                cu.phone
            FROM consumer_orders o
            JOIN consumer_users cu ON o.consumer_id = cu.id
            WHERE o.company_id = ${companyId} AND o.status = 'completed'
            ORDER BY o.created_at DESC
        `;

        // Get order items for each order
        for (const order of orders) {
            const items = await sql`
                SELECT 
                    product_id,
                    product_name,
                    quantity,
                    unit_price,
                    total_price
                FROM consumer_order_items
                WHERE order_id = ${order.id}
            `;
            order.items = items;
        }

        res.json({
            success: true,
            data: orders
        });

    } catch (error) {
        console.error("Get completed orders error:", error);
        res.status(500).json({
            success: false,
            message: "Internal server error"
        });
    }
};

// Get consumer info for a specific order (company side)
export const getOrderCustomer = async (req, res) => {
    try {
        const { orderId } = req.params;
        const companyId = req.user.company_id;

        const order = await sql`
            SELECT 
                o.id,
                o.consumer_id,
                cu.first_name,
                cu.last_name,
                cu.email,
                cu.phone
            FROM consumer_orders o
            JOIN consumer_users cu ON o.consumer_id = cu.id
            WHERE o.id = ${orderId} AND o.company_id = ${companyId}
            LIMIT 1
        `;

        if (order.length === 0) {
            return res.status(404).json({
                success: false,
                message: "Order not found for this company"
            });
        }

        return res.json({
            success: true,
            data: order[0]
        });
    } catch (error) {
        console.error("Get order customer error:", error);
        res.status(500).json({
            success: false,
            message: "Internal server error"
        });
    }
};

// Accept order
export const acceptOrder = async (req, res) => {
    try {
        const { orderId } = req.params;
        const companyId = req.user.company_id;

        // Verify order exists and belongs to company
        const order = await sql`
            SELECT * FROM consumer_orders
            WHERE id = ${orderId} AND company_id = ${companyId}
        `;

        if (order.length === 0) {
            return res.status(404).json({
                success: false,
                message: "Order not found"
            });
        }

        const currentStatus = order[0].status;
        if (currentStatus !== 'pending' && currentStatus !== 'accepted') {
            return res.status(400).json({
                success: false,
                message: `Order cannot be accepted. Current status: ${currentStatus}`
            });
        }

        // If already accepted, just return success
        if (currentStatus === 'accepted') {
            return res.json({
                success: true,
                message: "Order already accepted",
                alreadyAccepted: true
            });
        }

        // Get order items and verify stock availability
        const orderItems = await sql`
            SELECT 
                oi.product_id,
                oi.quantity,
                p.name as product_name,
                COALESCE(p.available_quantity, 0) as available_quantity,
                p.id as product_id_verified
            FROM consumer_order_items oi
            JOIN products p ON oi.product_id = p.id
            WHERE oi.order_id = ${orderId} AND p.company_id = ${companyId}
        `;

        if (orderItems.length === 0) {
            // Check if order items exist at all
            const allOrderItems = await sql`
                SELECT * FROM consumer_order_items WHERE order_id = ${orderId}
            `;
            
            if (allOrderItems.length === 0) {
                return res.status(400).json({
                    success: false,
                    message: "Order has no items"
                });
            } else {
                return res.status(400).json({
                    success: false,
                    message: "Order items do not belong to this company"
                });
            }
        }

        // Check if all items have sufficient stock
        const insufficientStockItems = orderItems.filter(item => {
            const available = parseInt(item.available_quantity) || 0;
            const ordered = parseInt(item.quantity) || 0;
            return available < ordered;
        });

        if (insufficientStockItems.length > 0) {
            const itemNames = insufficientStockItems.map(item => 
                `${item.product_name} (available: ${item.available_quantity}, ordered: ${item.quantity})`
            ).join(', ');
            
            return res.status(400).json({
                success: false,
                message: `Insufficient stock for: ${itemNames}`,
                insufficientStock: insufficientStockItems
            });
        }

        // Update stock for each product (decrease available_quantity)
        for (const item of orderItems) {
            const quantityToDeduct = parseInt(item.quantity) || 0;
            const productId = parseInt(item.product_id);
            
            if (quantityToDeduct > 0 && productId) {
                try {
                    await sql`
                        UPDATE products
                        SET available_quantity = GREATEST(0, available_quantity - ${quantityToDeduct})
                        WHERE id = ${productId} AND company_id = ${companyId}
                    `;
                } catch (updateError) {
                    console.error(`Error updating product ${productId}:`, updateError);
                    throw updateError;
                }
            }
        }

        // Update order status
        await sql`
            UPDATE consumer_orders
            SET status = 'accepted'
            WHERE id = ${orderId}
        `;

        const [updatedOrder] = await sql`
            SELECT id, consumer_id, company_id, status, updated_at 
            FROM consumer_orders
            WHERE id = ${orderId}
        `;

        emitOrderUpdate(updatedOrder);

        try {
            await createNotification({
                targetType: "consumer",
                consumerId: order[0].consumer_id,
                title: `Order #${orderId} accepted`,
                body: "Your order has been accepted and is being processed.",
                actionType: "order:accepted",
                actionPayload: {
                    orderId,
                    companyId,
                },
            });
        } catch (notificationError) {
            console.warn("Failed to create order accepted notification:", notificationError.message);
        }

        res.json({
            success: true,
            message: "Order accepted and stock updated",
            stockUpdated: true
        });

    } catch (error) {
        console.error("Accept order error:", error);
        console.error("Error details:", {
            message: error.message,
            stack: error.stack,
            orderId: req.params.orderId,
            companyId: req.user?.company_id
        });
        res.status(500).json({
            success: false,
            message: "Internal server error",
            error: process.env.NODE_ENV === 'development' ? error.message : undefined
        });
    }
};

// Reject order
export const rejectOrder = async (req, res) => {
    try {
        const { orderId } = req.params;
        const companyId = req.user.company_id;

        // Verify order exists and belongs to company
        const order = await sql`
            SELECT * FROM consumer_orders
            WHERE id = ${orderId} AND company_id = ${companyId}
        `;

        if (order.length === 0) {
            return res.status(404).json({
                success: false,
                message: "Order not found"
            });
        }

        const currentStatus = order[0].status;

        if (currentStatus !== 'pending' && currentStatus !== 'accepted') {
            return res.status(400).json({
                success: false,
                message: "Order cannot be rejected in its current state"
            });
        }

        // If order was previously accepted, restore stock
        if (currentStatus === 'accepted') {
            const orderItems = await sql`
                SELECT 
                    oi.product_id,
                    oi.quantity
                FROM consumer_order_items oi
                JOIN products p ON oi.product_id = p.id
                WHERE oi.order_id = ${orderId} AND p.company_id = ${companyId}
            `;

            // Restore stock for each product (increase available_quantity)
            for (const item of orderItems) {
                await sql`
                    UPDATE products
                    SET available_quantity = available_quantity + ${item.quantity},
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = ${item.product_id} AND company_id = ${companyId}
                `;
            }
        }

        // Update order status
        await sql`
            UPDATE consumer_orders
            SET status = 'rejected',
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ${orderId}
        `;

        const [updatedOrder] = await sql`
            SELECT id, consumer_id, company_id, status, updated_at 
            FROM consumer_orders
            WHERE id = ${orderId}
        `;

        emitOrderUpdate(updatedOrder);

        try {
            await createNotification({
                targetType: "consumer",
                consumerId: order[0].consumer_id,
                title: `Order #${orderId} rejected`,
                body: "Your order was rejected. Please check chat for details or contact the company.",
                actionType: "order:rejected",
                actionPayload: {
                    orderId,
                    companyId,
                },
            });
        } catch (notificationError) {
            console.warn("Failed to create order rejected notification:", notificationError.message);
        }

        res.json({
            success: true,
            message: currentStatus === 'accepted' 
                ? "Order rejected and stock restored" 
                : "Order rejected",
            stockRestored: currentStatus === 'accepted'
        });

    } catch (error) {
        console.error("Reject order error:", error);
        res.status(500).json({
            success: false,
            message: "Internal server error"
        });
    }
};

// Get consumer orders
export const getConsumerOrders = async (req, res) => {
    try {
        const consumerId = req.consumer.id;

        const orders = await sql`
            SELECT 
                o.id,
                o.created_at,
                o.payment_method,
                o.delivery_method,
                o.delivery_address,
                o.total_amount,
                o.status,
                o.company_id,
                c.name as company_name,
                EXISTS(
                    SELECT 1 FROM order_issues oi 
                    WHERE oi.order_id = o.id 
                    AND oi.status = 'resolved'
                ) as has_resolved_issue
            FROM consumer_orders o
            JOIN companies c ON o.company_id = c.id
            WHERE o.consumer_id = ${consumerId}
            ORDER BY o.created_at DESC
        `;

        // Get order items for each order
        for (const order of orders) {
            const items = await sql`
                SELECT 
                    product_id,
                    product_name,
                    quantity,
                    unit_price,
                    total_price
                FROM consumer_order_items
                WHERE order_id = ${order.id}
            `;
            order.items = items;
        }

        res.json({
            success: true,
            data: orders
        });

    } catch (error) {
        console.error("Get consumer orders error:", error);
        res.status(500).json({
            success: false,
            message: "Internal server error"
        });
    }
};

// Complete order
export const completeOrder = async (req, res) => {
    try {
        const { orderId } = req.params;
        const consumerId = req.consumer.id;

        // Verify order exists and belongs to consumer
        const order = await sql`
            SELECT * FROM consumer_orders
            WHERE id = ${orderId} AND consumer_id = ${consumerId}
        `;

        if (order.length === 0) {
            return res.status(404).json({
                success: false,
                message: "Order not found"
            });
        }

        if (order[0].status !== 'accepted') {
            return res.status(400).json({
                success: false,
                message: "Order is not accepted"
            });
        }

        // Update order status
        await sql`
            UPDATE consumer_orders
            SET status = 'completed'
            WHERE id = ${orderId}
        `;

        // Auto-resolve all unresolved issues for this order
        await sql`
            UPDATE order_issues
            SET 
                status = 'resolved',
                resolved_by = NULL, -- Auto-resolved by system
                resolution_notes = 'Order completed by consumer. All issues automatically resolved.',
                resolved_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP
            WHERE order_id = ${orderId}
            AND status != 'resolved'
        `;

        const [updatedOrder] = await sql`
            SELECT id, consumer_id, company_id, status, updated_at 
            FROM consumer_orders
            WHERE id = ${orderId}
        `;

        emitOrderUpdate(updatedOrder);

        try {
            await createNotification({
                targetType: "company",
                companyId: order[0].company_id,
                title: `Order #${orderId} completed`,
                body: "The consumer confirmed delivery for this order.",
                actionType: "order:completed",
                actionPayload: {
                    orderId,
                    consumerId,
                    companyId: order[0].company_id,
                },
            });
        } catch (notificationError) {
            console.warn("Failed to create order completed notification:", notificationError.message);
        }

        res.json({
            success: true,
            message: "Order completed and all issues resolved"
        });

    } catch (error) {
        console.error("Complete order error:", error);
        res.status(500).json({
            success: false,
            message: "Internal server error"
        });
    }
};

