import { sql } from "../config/db.js";
import { insertChatMessage } from "../services/chatMessageService.js";
import { emitChatMessage, emitIssueUpdate } from "../realtime/events.js";
import { createNotification } from "../services/notificationService.js";
import { incrementUnreadCount } from "../services/chatUnreadService.js";

// Helper function: Update user's last_active timestamp (call this on API requests)
export const updateUserActivity = async (userId) => {
    try {
        await sql`
            UPDATE users 
            SET last_active = CURRENT_TIMESTAMP 
            WHERE id = ${userId}
        `;
    } catch (error) {
        console.error('Error updating user activity:', error);
    }
};

// Note: Auto-assignment removed. Issues start as 'reported' status.
// All company employees can see all issues, and Sales Reps can assign to managers if needed.

const createHttpError = (statusCode, message) => {
    const error = new Error(message);
    error.statusCode = statusCode;
    return error;
};

// Consumer reports an issue about an order
export const reportIssue = async (req, res) => {
    try {
        const consumerId = Number(req.consumer.id);
        const { orderId, title, description } = req.body;

        const trimmedTitle = typeof title === "string" ? title.trim() : "";
        const trimmedDescription = typeof description === "string" ? description.trim() : "";

        if (!orderId || !trimmedTitle || !trimmedDescription) {
            return res.status(400).json({
                success: false,
                message: "Order ID, title, and description are required"
            });
        }

        const sanitizedOrderId = Number(orderId);
        if (!Number.isFinite(sanitizedOrderId)) {
            return res.status(400).json({
                success: false,
                message: "Invalid order ID"
            });
        }

        // Verify the order belongs to this consumer
        const orderRows = await sql`
            SELECT 
                co.id,
                co.consumer_id,
                co.company_id,
                co.status as order_status
            FROM consumer_orders co
            WHERE co.id = ${sanitizedOrderId} AND co.consumer_id = ${consumerId}
            LIMIT 1
        `;

        if (orderRows.length === 0) {
            return res.status(404).json({
                success: false,
                message: "Order not found"
            });
        }

        const orderRecord = orderRows[0];
        const companyId = Number(orderRecord.company_id);

        // Ensure consumer has access to company (for chat and messaging)
        try {
            await sql`
                INSERT INTO consumer_company_access (consumer_id, company_id, granted_by, granted_at, is_active)
                VALUES (${consumerId}, ${companyId}, NULL, NOW(), true)
                ON CONFLICT (consumer_id, company_id) DO UPDATE
                SET is_active = true
            `;
        } catch (accessError) {
            console.log('Note: Could not upsert consumer_company_access (may already exist with different structure):', accessError.message);
        }

        const [issueRecord] = await sql`
            INSERT INTO order_issues (
                order_id,
                consumer_id,
                company_id,
                reported_by,
                assigned_to,
                assigned_to_role,
                status,
                title,
                description
            )
            VALUES (
                ${sanitizedOrderId},
                ${consumerId},
                ${companyId},
                ${consumerId},
                NULL,
                NULL,
                'reported',
                ${trimmedTitle},
                ${trimmedDescription}
            )
            RETURNING *
        `;

        const [consumerInfo] = await sql`
            SELECT first_name, last_name, email, phone
            FROM consumer_users
            WHERE id = ${consumerId}
        `;

        const [orderInfo] = await sql`
            SELECT 
                co.total_amount,
                co.payment_method,
                co.delivery_method,
                co.delivery_address,
                co.created_at,
                c.name AS company_name
            FROM consumer_orders co
            JOIN companies c ON co.company_id = c.id
            WHERE co.id = ${sanitizedOrderId}
            LIMIT 1
        `;

        const items = await sql`
            SELECT product_name, quantity, total_price
            FROM consumer_order_items
            WHERE order_id = ${sanitizedOrderId}
            ORDER BY id ASC
        `;

        const consumerName = `${consumerInfo?.first_name || ''} ${consumerInfo?.last_name || ''}`.trim() || 'Consumer';

        const itemsList = items.length > 0
            ? items.map(item => {
                const qty = Number(item.quantity) || 0;
                const total = Number(item.total_price || 0);
                const totalLine = Number.isFinite(total) ? ` = ₸${total.toFixed(2)}` : '';
                return `- ${item.product_name} x${qty}${totalLine}`;
            }).join('\n')
            : '- No order items found';

        const issueMessage =
            `⚠️ Order Issue Reported\n\n` +
            `Order #: ${sanitizedOrderId}\n` +
            (issueRecord?.id ? `Issue #: ${issueRecord.id}\n` : '') +
            `Consumer: ${consumerName}\n` +
            (consumerInfo?.email ? `Email: ${consumerInfo.email}\n` : '') +
            (consumerInfo?.phone ? `Phone: ${consumerInfo.phone}\n` : '') +
            `Title: ${trimmedTitle}\n` +
            `Description: ${trimmedDescription}\n` +
            (orderInfo?.total_amount ? `Order Total: ₸${Number(orderInfo.total_amount).toFixed(2)}\n` : '') +
            (orderInfo?.payment_method ? `Payment: ${orderInfo.payment_method}\n` : '') +
            (orderInfo?.delivery_method ? `Delivery: ${orderInfo.delivery_method}\n` : '') +
            (orderInfo?.delivery_address ? `Address: ${orderInfo.delivery_address}\n` : '') +
            `\nItems:\n${itemsList}`;

        console.log('Issue chat summary payload:', {
            consumerId,
            companyId,
            orderId: sanitizedOrderId,
            issueId: issueRecord?.id,
        });

        let chatMessage;
        try {
            chatMessage = await insertChatMessage(
                {
                    consumerId,
                    companyId,
                    senderType: 'consumer',
                    senderId: consumerId,
                    messageText: issueMessage,
                    messageType: 'issue_summary',
                },
                { db: sql }
            );
        } catch (chatError) {
            console.error('Failed to create issue summary chat message, rolling back issue:', chatError);
            await sql`DELETE FROM order_issues WHERE id = ${issueRecord.id}`;
            throw createHttpError(500, "Issue created but failed to add summary to chat");
        }

        if (!chatMessage) {
            await sql`DELETE FROM order_issues WHERE id = ${issueRecord.id}`;
            throw createHttpError(500, "Issue created but failed to add summary to chat");
        }

        res.status(201).json({
            success: true,
            message: "Issue reported successfully",
            data: issueRecord,
            chatMessage
        });
        emitChatMessage(chatMessage);
        await incrementUnreadCount({
            consumerId,
            companyId,
            senderType: "consumer",
        });
        emitIssueUpdate(issueRecord);
        try {
            await createNotification({
                targetType: "company",
                companyId,
                title: `New issue #${issueRecord.id || ""}`.trim(),
                body: `${consumerName} reported an issue for order #${sanitizedOrderId}`,
                actionType: "issue:new",
                actionPayload: {
                    issueId: issueRecord.id,
                    orderId: sanitizedOrderId,
                    consumerId,
                    companyId,
                },
            });
        } catch (notificationError) {
            console.warn("Failed to create issue notification:", notificationError.message);
        }
    } catch (error) {
        if (error?.statusCode) {
            return res.status(error.statusCode).json({
                success: false,
                message: error.message
            });
        }

        console.error("Error reporting issue:", error);
        res.status(500).json({
            success: false,
            message: "Internal server error"
        });
    }
};

// Get issues for a consumer
export const getConsumerIssues = async (req, res) => {
    try {
        const consumerId = req.consumer.id;

        const issues = await sql`
            SELECT 
                oi.*,
                oi.company_id,
                co.total_amount,
                co.status as order_status,
                co.created_at as order_created_at,
                c.name as company_name,
                u.first_name || ' ' || u.last_name as assigned_to_name,
                resolved_user.first_name || ' ' || resolved_user.last_name as resolved_by_name
            FROM order_issues oi
            JOIN consumer_orders co ON oi.order_id = co.id
            JOIN companies c ON oi.company_id = c.id
            LEFT JOIN users u ON oi.assigned_to = u.id
            LEFT JOIN users resolved_user ON oi.resolved_by = resolved_user.id
            WHERE oi.consumer_id = ${consumerId}
            ORDER BY oi.reported_at DESC
        `;

        res.json({
            success: true,
            data: issues
        });
    } catch (error) {
        console.error("Error fetching consumer issues:", error);
        res.status(500).json({
            success: false,
            message: "Internal server error"
        });
    }
};

// Get issues for a company (all employees can see all issues)
export const getCompanyIssues = async (req, res) => {
    try {
        const companyId = req.user.company_id;

        // All company employees can see all issues
        const issues = await sql`
            SELECT 
                oi.*,
                co.total_amount,
                co.status as order_status,
                co.created_at as order_created_at,
                oi.consumer_id,
                cu.first_name || ' ' || cu.last_name as consumer_name,
                cu.email as consumer_email,
                cu.phone as consumer_phone,
                u.first_name || ' ' || u.last_name as assigned_to_name,
                resolved_user.first_name || ' ' || resolved_user.last_name as resolved_by_name
            FROM order_issues oi
            JOIN consumer_orders co ON oi.order_id = co.id
            JOIN consumer_users cu ON oi.consumer_id = cu.id
            LEFT JOIN users u ON oi.assigned_to = u.id
            LEFT JOIN users resolved_user ON oi.resolved_by = resolved_user.id
            WHERE oi.company_id = ${companyId}
            ORDER BY 
                CASE oi.status
                    WHEN 'reported' THEN 1
                    WHEN 'assigned_to_manager' THEN 2
                    WHEN 'resolved' THEN 3
                    WHEN 'closed' THEN 4
                END,
                oi.reported_at DESC
        `;

        res.json({
            success: true,
            data: issues
        });
    } catch (error) {
        console.error("Error fetching company issues:", error);
        res.status(500).json({
            success: false,
            message: "Internal server error"
        });
    }
};

// Assign issue to a manager (by Sales Rep or Manager/Owner reassigning)
export const assignToManager = async (req, res) => {
    try {
        const { issueId } = req.params;
        const { managerId } = req.body;
        const companyId = req.user.company_id;
        const userId = req.user.id;

        // Verify issue belongs to company
        const issue = await sql`
            SELECT * FROM order_issues
            WHERE id = ${issueId} AND company_id = ${companyId}
        `;

        if (issue.length === 0) {
            return res.status(404).json({
                success: false,
                message: "Issue not found"
            });
        }

        // Verify manager exists and is a Manager or Owner
        const manager = await sql`
            SELECT u.id, r.name as role_name
            FROM users u
            JOIN roles r ON u.role_id = r.id
            WHERE u.id = ${managerId}
                AND u.company_id = ${companyId}
                AND u.is_active = true
                AND r.name IN ('Manager', 'Owner')
        `;

        if (manager.length === 0) {
            return res.status(400).json({
                success: false,
                message: "Invalid manager selected"
            });
        }

        // Update issue
        const updatedIssue = await sql`
            UPDATE order_issues
            SET 
                assigned_to = ${managerId},
                assigned_to_role = ${manager[0].role_name},
                status = 'assigned_to_manager',
                assigned_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ${issueId}
            RETURNING *
        `;

        res.json({
            success: true,
            message: "Issue assigned to manager",
            data: updatedIssue[0]
        });
        emitIssueUpdate(updatedIssue[0]);
    } catch (error) {
        console.error("Error assigning to manager:", error);
        res.status(500).json({
            success: false,
            message: "Internal server error"
        });
    }
};

// Get list of managers for assignment dropdown
export const getManagers = async (req, res) => {
    try {
        const companyId = req.user.company_id;

        const managers = await sql`
            SELECT 
                u.id,
                u.first_name,
                u.last_name,
                u.email,
                r.name as role_name
            FROM users u
            JOIN roles r ON u.role_id = r.id
            WHERE u.company_id = ${companyId}
                AND u.is_active = true
                AND r.name IN ('Manager', 'Owner')
            ORDER BY r.name, u.first_name, u.last_name
        `;

        res.json({
            success: true,
            data: managers
        });
    } catch (error) {
        console.error("Error fetching managers:", error);
        res.status(500).json({
            success: false,
            message: "Internal server error"
        });
    }
};

// Resolve an issue
export const resolveIssue = async (req, res) => {
    try {
        const { issueId } = req.params;
        const { resolutionNotes } = req.body;
        const companyId = req.user.company_id;
        const userId = req.user.id;

        // Verify issue belongs to company and is assigned to this user (or user is Manager/Owner)
        const issue = await sql`
            SELECT * FROM order_issues
            WHERE id = ${issueId} AND company_id = ${companyId}
        `;

        if (issue.length === 0) {
            return res.status(404).json({
                success: false,
                message: "Issue not found"
            });
        }

        const currentIssue = issue[0];

        // Any company employee can resolve issues (no assignment restriction)
        // This allows flexibility for collaborative problem-solving

        // Get order details to find consumer_id and company_id for chat message
        const orderDetails = await sql`
            SELECT co.consumer_id, co.company_id, oi.title, oi.description
            FROM order_issues oi
            JOIN consumer_orders co ON oi.order_id = co.id
            WHERE oi.id = ${issueId}
        `;

        if (orderDetails.length === 0) {
            return res.status(404).json({
                success: false,
                message: "Order not found for this issue"
            });
        }

        const { consumer_id, company_id, title: issueTitle, description: issueDescription } = orderDetails[0];

        // Update issue
        const updatedIssue = await sql`
            UPDATE order_issues
            SET 
                status = 'resolved',
                resolved_by = ${userId},
                resolution_notes = ${resolutionNotes || null},
                resolved_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ${issueId}
            RETURNING *
        `;

        // Send resolution message to chat
        try {
            const resolverName = await sql`
                SELECT first_name || ' ' || last_name as name
                FROM users
                WHERE id = ${userId}
            `;
            const resolverNameText = resolverName.length > 0 ? resolverName[0].name : 'Company representative';

            const resolutionMessage = resolutionNotes 
                ? `✅ Issue Resolved by ${resolverNameText}:\n\n"${issueTitle}"\n\nResolution: ${resolutionNotes}`
                : `✅ Issue Resolved by ${resolverNameText}:\n\n"${issueTitle}"\n\nThis issue has been resolved.`;

            const resolutionChatMessage = await insertChatMessage(
                {
                    consumerId: consumer_id,
                    companyId: company_id,
                    senderType: 'company',
                    senderId: userId,
                    messageText: resolutionMessage,
                    messageType: 'issue_resolution',
                },
                { db: sql }
            );
            console.log('Resolution message sent to chat:', resolutionChatMessage?.id);
            emitChatMessage(resolutionChatMessage);
            await incrementUnreadCount({
                consumerId: consumer_id,
                companyId: company_id,
                senderType: "company",
            });
        } catch (chatError) {
            console.error('Error sending resolution message to chat:', chatError);
            console.error('Chat error details:', {
                consumer_id,
                company_id,
                userId,
                error: chatError.message
            });
            // Don't fail the resolution if chat message fails
        }

        res.json({
            success: true,
            message: "Issue resolved successfully",
            data: updatedIssue[0]
        });
        emitIssueUpdate(updatedIssue[0]);
        try {
            await createNotification({
                targetType: "consumer",
                consumerId: issue.consumer_id,
                title: `Issue #${issueId} resolved`,
                body: "Your reported issue has been marked as resolved.",
                actionType: "issue:resolved",
                actionPayload: {
                    issueId,
                    orderId: issue.order_id,
                    companyId: issue.company_id,
                },
            });
        } catch (notificationError) {
            console.warn("Failed to create issue resolution notification:", notificationError.message);
        }
    } catch (error) {
        console.error("Error resolving issue:", error);
        res.status(500).json({
            success: false,
            message: "Internal server error"
        });
    }
};

// Get a single issue with full details
export const getIssueDetails = async (req, res) => {
    try {
        const { issueId } = req.params;
        const user = req.user;

        let issue;

        if (user.type === 'consumer') {
            issue = await sql`
                SELECT 
                    oi.*,
                    co.total_amount,
                    co.payment_method,
                    co.delivery_method,
                    co.delivery_address,
                    co.status as order_status,
                    co.created_at as order_created_at,
                    c.name as company_name,
                    u.first_name || ' ' || u.last_name as assigned_to_name,
                    resolved_user.first_name || ' ' || resolved_user.last_name as resolved_by_name,
                    ARRAY_AGG(
                        JSONB_BUILD_OBJECT(
                            'product_id', coi.product_id,
                            'product_name', coi.product_name,
                            'quantity', coi.quantity,
                            'unit_price', coi.unit_price,
                            'total_price', coi.total_price
                        )
                    ) AS order_items
                FROM order_issues oi
                JOIN consumer_orders co ON oi.order_id = co.id
                JOIN consumer_order_items coi ON co.id = coi.order_id
                JOIN companies c ON oi.company_id = c.id
                LEFT JOIN users u ON oi.assigned_to = u.id
                LEFT JOIN users resolved_user ON oi.resolved_by = resolved_user.id
                WHERE oi.id = ${issueId} AND oi.consumer_id = ${user.id}
                GROUP BY oi.id, co.id, c.id, u.id, resolved_user.id
            `;
        } else {
            issue = await sql`
                SELECT 
                    oi.*,
                    co.total_amount,
                    co.payment_method,
                    co.delivery_method,
                    co.delivery_address,
                    co.status as order_status,
                    co.created_at as order_created_at,
                    cu.first_name || ' ' || cu.last_name as consumer_name,
                    cu.email as consumer_email,
                    cu.phone as consumer_phone,
                    u.first_name || ' ' || u.last_name as assigned_to_name,
                    resolved_user.first_name || ' ' || resolved_user.last_name as resolved_by_name,
                    ARRAY_AGG(
                        JSONB_BUILD_OBJECT(
                            'product_id', coi.product_id,
                            'product_name', coi.product_name,
                            'quantity', coi.quantity,
                            'unit_price', coi.unit_price,
                            'total_price', coi.total_price
                        )
                    ) AS order_items
                FROM order_issues oi
                JOIN consumer_orders co ON oi.order_id = co.id
                JOIN consumer_order_items coi ON co.id = coi.order_id
                JOIN consumer_users cu ON oi.consumer_id = cu.id
                JOIN companies c ON oi.company_id = c.id
                LEFT JOIN users u ON oi.assigned_to = u.id
                LEFT JOIN users resolved_user ON oi.resolved_by = resolved_user.id
                WHERE oi.id = ${issueId} AND oi.company_id = ${user.company_id}
                GROUP BY oi.id, co.id, cu.id, c.id, u.id, resolved_user.id
            `;
        }

        if (issue.length === 0) {
            return res.status(404).json({
                success: false,
                message: "Issue not found"
            });
        }

        res.json({
            success: true,
            data: issue[0]
        });
    } catch (error) {
        console.error("Error fetching issue details:", error);
        res.status(500).json({
            success: false,
            message: "Internal server error"
        });
    }
};

