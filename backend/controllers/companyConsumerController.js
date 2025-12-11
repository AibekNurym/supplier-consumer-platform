import { sql } from "../config/db.js";
import { createNotification } from "../services/notificationService.js";

// Get pending consumer requests for a company
export const getPendingRequests = async (req, res) => {
    try {
        const companyId = req.user.company_id;

        const requests = await sql`
            SELECT 
                ccr.id,
                ccr.consumer_id,
                ccr.status,
                ccr.requested_at,
                ccr.responded_at,
                cu.first_name,
                cu.last_name,
                cu.email,
                cu.phone
            FROM company_consumer_requests ccr
            JOIN consumer_users cu ON ccr.consumer_id = cu.id
            WHERE ccr.company_id = ${companyId} AND ccr.status = 'pending'
            ORDER BY ccr.requested_at DESC
        `;

        res.json({
            success: true,
            data: requests
        });

    } catch (error) {
        console.error("Get pending requests error:", error);
        res.status(500).json({
            success: false,
            message: "Internal server error"
        });
    }
};

// Approve consumer access request
export const approveRequest = async (req, res) => {
    try {
        const { requestId } = req.params;
        const companyId = req.user.company_id;

        // Verify request exists and belongs to company
        const request = await sql`
            SELECT ccr.*, cu.first_name, cu.last_name, cu.email
            FROM company_consumer_requests ccr
            JOIN consumer_users cu ON ccr.consumer_id = cu.id
            WHERE ccr.id = ${requestId} AND ccr.company_id = ${companyId}
        `;

        if (request.length === 0) {
            return res.status(404).json({
                success: false,
                message: "Request not found"
            });
        }

        if (request[0].status !== 'pending') {
            return res.status(400).json({
                success: false,
                message: "Request is not pending"
            });
        }

        // Update request status
        await sql`
            UPDATE company_consumer_requests 
            SET status = 'approved', responded_at = NOW(), responded_by = ${req.user.id}
            WHERE id = ${requestId}
        `;

        // Grant access to consumer (upsert to reactivate if previously revoked)
        await sql`
            INSERT INTO consumer_company_access (consumer_id, company_id, granted_by, is_active, granted_at, revoked_at)
            VALUES (${request[0].consumer_id}, ${companyId}, ${req.user.id}, TRUE, NOW(), NULL)
            ON CONFLICT (consumer_id, company_id)
            DO UPDATE
            SET is_active = TRUE,
                granted_at = NOW(),
                granted_by = EXCLUDED.granted_by,
                revoked_at = NULL
        `;

        try {
            await createNotification({
                targetType: "consumer",
                consumerId: request[0].consumer_id,
                title: "Access request approved",
                body: "The company approved your access request. You can now view pricing and place orders.",
                actionType: "access:approved",
                actionPayload: {
                    requestId,
                    companyId,
                },
            });
        } catch (notificationError) {
            console.warn("Failed to create access approval notification:", notificationError.message);
        }

        res.json({
            success: true,
            message: "Access request approved successfully",
            data: {
                consumerName: `${request[0].first_name} ${request[0].last_name}`,
                consumerEmail: request[0].email
            }
        });

    } catch (error) {
        console.error("Approve request error:", error);
        res.status(500).json({
            success: false,
            message: "Internal server error"
        });
    }
};

// Reject consumer access request
export const rejectRequest = async (req, res) => {
    try {
        const { requestId } = req.params;
        const companyId = req.user.company_id;

        // Verify request exists and belongs to company
        const request = await sql`
            SELECT ccr.*, cu.first_name, cu.last_name, cu.email
            FROM company_consumer_requests ccr
            JOIN consumer_users cu ON ccr.consumer_id = cu.id
            WHERE ccr.id = ${requestId} AND ccr.company_id = ${companyId}
        `;

        if (request.length === 0) {
            return res.status(404).json({
                success: false,
                message: "Request not found"
            });
        }

        if (request[0].status !== 'pending') {
            return res.status(400).json({
                success: false,
                message: "Request is not pending"
            });
        }

        // Update request status
        await sql`
            UPDATE company_consumer_requests 
            SET status = 'rejected', responded_at = NOW(), responded_by = ${req.user.id}
            WHERE id = ${requestId}
        `;

        try {
            await createNotification({
                targetType: "consumer",
                consumerId: request[0].consumer_id,
                title: "Access request rejected",
                body: "The company rejected your access request.",
                actionType: "access:rejected",
                actionPayload: {
                    requestId,
                    companyId,
                },
            });
        } catch (notificationError) {
            console.warn("Failed to create access rejection notification:", notificationError.message);
        }

        res.json({
            success: true,
            message: "Access request rejected",
            data: {
                consumerName: `${request[0].first_name} ${request[0].last_name}`,
                consumerEmail: request[0].email
            }
        });

    } catch (error) {
        console.error("Reject request error:", error);
        res.status(500).json({
            success: false,
            message: "Internal server error"
        });
    }
};

// Get all consumer requests for a company (approved, rejected, pending)
export const getAllRequests = async (req, res) => {
    try {
        const companyId = req.user.company_id;

        const requests = await sql`
            SELECT 
                ccr.id,
                ccr.consumer_id,
                ccr.status,
                ccr.requested_at,
                ccr.responded_at,
                cu.first_name,
                cu.last_name,
                cu.email,
                cu.phone,
                u.first_name as reviewed_by_first_name,
                u.last_name as reviewed_by_last_name
            FROM company_consumer_requests ccr
            JOIN consumer_users cu ON ccr.consumer_id = cu.id
            LEFT JOIN users u ON ccr.responded_by = u.id
            WHERE ccr.company_id = ${companyId}
            ORDER BY ccr.requested_at DESC
        `;

        res.json({
            success: true,
            data: requests
        });

    } catch (error) {
        console.error("Get all requests error:", error);
        res.status(500).json({
            success: false,
            message: "Internal server error"
        });
    }
};

// Get consumers with access to company
export const getCompanyConsumers = async (req, res) => {
    try {
        const companyId = req.user.company_id;

        const consumers = await sql`
            SELECT 
                cca.id,
                cca.consumer_id,
                cca.granted_at AS access_granted_at,
                cca.is_active,
                COALESCE(blocks.is_blocked, false) AS is_blocked,
                blocks.blocked_at,
                cu.first_name,
                cu.last_name,
                cu.email,
                cu.phone,
                u.first_name AS granted_by_first_name,
                u.last_name AS granted_by_last_name
            FROM consumer_company_access cca
            JOIN consumer_users cu ON cca.consumer_id = cu.id
            LEFT JOIN users u ON cca.granted_by = u.id
            LEFT JOIN company_consumer_blocks blocks
              ON blocks.company_id = cca.company_id
             AND blocks.consumer_id = cca.consumer_id
             AND blocks.is_blocked = TRUE
            WHERE cca.company_id = ${companyId}
              AND cca.is_active = TRUE
            ORDER BY cca.granted_at DESC
        `;

        res.json({
            success: true,
            data: consumers
        });

    } catch (error) {
        console.error("Get company consumers error:", error);
        res.status(500).json({
            success: false,
            message: "Internal server error"
        });
    }
};

// Revoke consumer access
export const revokeAccess = async (req, res) => {
    try {
        const { accessId } = req.params;
        const companyId = req.user.company_id;

        // Verify access exists and belongs to company
        const access = await sql`
            SELECT cca.*, cu.first_name, cu.last_name, cu.email
            FROM consumer_company_access cca
            JOIN consumer_users cu ON cca.consumer_id = cu.id
            WHERE cca.id = ${accessId} AND cca.company_id = ${companyId}
        `;

        if (access.length === 0) {
            return res.status(404).json({
                success: false,
                message: "Access not found"
            });
        }

        // Revoke access
        await sql`
            UPDATE consumer_company_access 
            SET is_active = FALSE, revoked_at = NOW()
            WHERE id = ${accessId}
        `;

        await sql`
            UPDATE company_consumer_requests
            SET status = 'revoked',
                responded_at = NOW(),
                responded_by = ${req.user.id}
            WHERE company_id = ${companyId}
              AND consumer_id = ${access[0].consumer_id}
        `;

        try {
            await createNotification({
                targetType: "consumer",
                consumerId: access[0].consumer_id,
                title: "Access removed",
                body: "The company removed your access to their catalog.",
                actionType: "access:revoked",
                actionPayload: {
                    companyId,
                    consumerId: access[0].consumer_id,
                },
            });
        } catch (notificationError) {
            console.warn("Failed to create access revoke notification:", notificationError.message);
        }

        res.json({
            success: true,
            message: "Consumer access revoked successfully",
            data: {
                consumerName: `${access[0].first_name} ${access[0].last_name}`,
                consumerEmail: access[0].email
            }
        });

    } catch (error) {
        console.error("Revoke access error:", error);
        res.status(500).json({
            success: false,
            message: "Internal server error"
        });
    }
};

export const blockConsumer = async (req, res) => {
    try {
        const { consumerId } = req.params;
        const companyId = req.user.company_id;

        const consumer = await sql`
            SELECT id, first_name, last_name, email
            FROM consumer_users
            WHERE id = ${consumerId}
        `;

        if (consumer.length === 0) {
            return res.status(404).json({
                success: false,
                message: "Consumer not found"
            });
        }

        await sql`
            INSERT INTO company_consumer_blocks (company_id, consumer_id, blocked_by, is_blocked, blocked_at, unblocked_by, unblocked_at)
            VALUES (${companyId}, ${consumerId}, ${req.user.id}, TRUE, CURRENT_TIMESTAMP, NULL, NULL)
            ON CONFLICT (company_id, consumer_id)
            DO UPDATE
            SET is_blocked = TRUE,
                blocked_by = EXCLUDED.blocked_by,
                blocked_at = CURRENT_TIMESTAMP,
                unblocked_by = NULL,
                unblocked_at = NULL
        `;

        await sql`
            UPDATE consumer_company_access
            SET is_active = FALSE, revoked_at = NOW()
            WHERE company_id = ${companyId}
              AND consumer_id = ${consumerId}
              AND is_active = TRUE
        `;

        await sql`
            UPDATE company_consumer_requests
            SET status = 'blocked',
                responded_at = NOW(),
                responded_by = ${req.user.id}
            WHERE company_id = ${companyId}
              AND consumer_id = ${consumerId}
        `;

        try {
            await createNotification({
                targetType: "consumer",
                consumerId: Number(consumerId),
                title: "Access blocked",
                body: "The company has blocked your access. You can no longer request access or send messages.",
                actionType: "access:blocked",
                actionPayload: {
                    companyId,
                    consumerId: Number(consumerId),
                },
            });
        } catch (notificationError) {
            console.warn("Failed to create block notification:", notificationError.message);
        }

        res.json({
            success: true,
            message: "Consumer has been blocked",
            data: {
                consumerName: `${consumer[0].first_name} ${consumer[0].last_name}`,
                consumerEmail: consumer[0].email
            }
        });
    } catch (error) {
        console.error("Block consumer error:", error);
        res.status(500).json({
            success: false,
            message: "Internal server error"
        });
    }
};

export const unblockConsumer = async (req, res) => {
    try {
        const { consumerId } = req.params;
        const companyId = req.user.company_id;

        const unblocked = await sql`
            UPDATE company_consumer_blocks
            SET is_blocked = FALSE,
                unblocked_at = CURRENT_TIMESTAMP,
                unblocked_by = ${req.user.id}
            WHERE company_id = ${companyId}
              AND consumer_id = ${consumerId}
              AND is_blocked = TRUE
            RETURNING *
        `;

        if (unblocked.length === 0) {
            return res.status(404).json({
                success: false,
                message: "Consumer is not currently blocked"
            });
        }

        await sql`
            UPDATE company_consumer_requests
            SET status = 'pending',
                responded_at = NULL,
                responded_by = NULL
            WHERE company_id = ${companyId}
              AND consumer_id = ${consumerId}
        `;

        try {
            await createNotification({
                targetType: "consumer",
                consumerId: Number(consumerId),
                title: "Access unblocked",
                body: "The company has removed your block. You may request access again.",
                actionType: "access:unblocked",
                actionPayload: {
                    companyId,
                    consumerId: Number(consumerId),
                },
            });
        } catch (notificationError) {
            console.warn("Failed to create unblock notification:", notificationError.message);
        }

        res.json({
            success: true,
            message: "Consumer has been unblocked"
        });
    } catch (error) {
        console.error("Unblock consumer error:", error);
        res.status(500).json({
            success: false,
            message: "Internal server error"
        });
    }
};

export const getBlockedConsumers = async (req, res) => {
    try {
        const companyId = req.user.company_id;

        const blocked = await sql`
            SELECT 
                b.id,
                b.consumer_id,
                b.blocked_at,
                cu.first_name,
                cu.last_name,
                cu.email,
                cu.phone,
                ub.first_name AS blocked_by_first_name,
                ub.last_name AS blocked_by_last_name
            FROM company_consumer_blocks b
            JOIN consumer_users cu ON b.consumer_id = cu.id
            LEFT JOIN users ub ON b.blocked_by = ub.id
            WHERE b.company_id = ${companyId}
              AND b.is_blocked = TRUE
            ORDER BY b.blocked_at DESC
        `;

        res.json({
            success: true,
            data: blocked
        });
    } catch (error) {
        console.error("Get blocked consumers error:", error);
        res.status(500).json({
            success: false,
            message: "Internal server error"
        });
    }
};

export const cancelConsumerRequest = async (req, res) => {
    try {
        const { requestId } = req.params;
        const companyId = req.user.company_id;

        const request = await sql`
            SELECT *
            FROM company_consumer_requests
            WHERE id = ${requestId} AND company_id = ${companyId}
        `;

        if (request.length === 0) {
            return res.status(404).json({
                success: false,
                message: "Request not found"
            });
        }

        await sql`
            UPDATE consumer_company_access
            SET is_active = FALSE, revoked_at = NOW()
            WHERE company_id = ${companyId}
              AND consumer_id = ${request[0].consumer_id}
              AND is_active = TRUE
        `;

        await sql`
            UPDATE company_consumer_requests
            SET status = 'cancelled',
                responded_at = NOW(),
                responded_by = ${req.user.id}
            WHERE id = ${requestId}
        `;

        try {
            await createNotification({
                targetType: "consumer",
                consumerId: request[0].consumer_id,
                title: "Access cancelled",
                body: "The company has cancelled your access request.",
                actionType: "access:cancelled",
                actionPayload: {
                    companyId,
                    requestId,
                },
            });
        } catch (notificationError) {
            console.warn("Failed to create cancel notification:", notificationError.message);
        }

        res.json({
            success: true,
            message: "Request cancelled successfully"
        });
    } catch (error) {
        console.error("Cancel request error:", error);
        res.status(500).json({
            success: false,
            message: "Internal server error"
        });
    }
};
