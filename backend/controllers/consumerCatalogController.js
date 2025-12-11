import { sql } from "../config/db.js";
import { createNotification } from "../services/notificationService.js";

// Get all companies (for consumer browsing)
export const getCompanies = async (req, res) => {
    try {
        const consumerId = req.consumer?.id || null;
        const companies = await sql`
            SELECT 
                c.id,
                c.name,
                c.description,
                c.created_at,
                COUNT(p.id) as product_count,
                CASE 
                    WHEN ${consumerId}::INT IS NOT NULL THEN COALESCE(blocks.is_blocked, false)
                    ELSE false
                END AS is_blocked
            FROM companies c
            LEFT JOIN products p ON c.id = p.company_id
            LEFT JOIN company_consumer_blocks blocks
              ON ${consumerId}::INT IS NOT NULL
             AND blocks.company_id = c.id
             AND blocks.consumer_id = ${consumerId}
             AND blocks.is_blocked = TRUE
            WHERE c.is_active = true
            GROUP BY c.id, c.name, c.description, c.created_at, blocks.is_blocked
            ORDER BY c.created_at DESC
        `;

        res.json({
            success: true,
            data: companies
        });

    } catch (error) {
        console.error("Get companies error:", error);
        res.status(500).json({
            success: false,
            message: "Internal server error"
        });
    }
};

// Get company products (without prices for consumers)
export const getCompanyProducts = async (req, res) => {
    try {
        const { companyId } = req.params;
        const consumerId = req.consumer?.id;

        console.log('=== GET COMPANY PRODUCTS DEBUG ===');
        console.log('Company ID:', companyId);
        console.log('Consumer ID:', consumerId);
        console.log('Consumer object:', req.consumer);

        // Check if consumer has access to this company
        let hasAccess = false;
        let isBlocked = false;
        if (consumerId) {
            const access = await sql`
                SELECT id FROM consumer_company_access 
                WHERE consumer_id = ${consumerId} AND company_id = ${companyId} AND is_active = true
            `;

            const blocked = await sql`
                SELECT id FROM company_consumer_blocks
                WHERE company_id = ${companyId}
                  AND consumer_id = ${consumerId}
                  AND is_blocked = TRUE
            `;

            if (blocked.length > 0) {
                isBlocked = true;
                return res.status(403).json({
                    success: false,
                    message: "You have been blocked from this company."
                });
            }
            console.log('Access query result:', access);
            hasAccess = access.length > 0;
        }
        
        console.log('Has access:', hasAccess);

        // Get products with or without prices based on access
        let products;
        if (hasAccess) {
            products = await sql`
                SELECT 
                    id,
                    name,
                    image,
                    price,
                    discount_percentage,
                    lead_time_days,
                    CASE 
                        WHEN discount_percentage > 0 THEN 
                            ROUND(price * (1 - discount_percentage / 100), 2)
                        ELSE 
                            price
                    END as discounted_price,
                    minimum_order_quantity,
                    available_quantity,
                    created_at
                FROM products 
                WHERE company_id = ${companyId}
                ORDER BY created_at DESC
            `;
        } else {
            products = await sql`
                SELECT 
                    id,
                    name,
                    image,
                    NULL as price,
                    NULL as discount_percentage,
                    NULL as discounted_price,
                    lead_time_days,
                    minimum_order_quantity,
                    available_quantity,
                    created_at
                FROM products 
                WHERE company_id = ${companyId}
                ORDER BY created_at DESC
            `;
        }

        res.json({
            success: true,
            data: {
                products,
                hasAccess,
                companyId: parseInt(companyId),
                isBlocked
            }
        });

    } catch (error) {
        console.error("Get company products error:", error);
        res.status(500).json({
            success: false,
            message: "Internal server error"
        });
    }
};

// Request access to company
export const requestCompanyAccess = async (req, res) => {
    try {
        const { companyId } = req.params;
        const consumerId = req.consumer.id;

        // Check if company exists and is active
        const company = await sql`
            SELECT id, name FROM companies 
            WHERE id = ${companyId} AND is_active = true
        `;

        if (company.length === 0) {
            return res.status(404).json({
                success: false,
                message: "Company not found"
            });
        }

        const blocked = await sql`
            SELECT id FROM company_consumer_blocks
            WHERE company_id = ${companyId}
              AND consumer_id = ${consumerId}
              AND is_blocked = TRUE
        `;

        if (blocked.length > 0) {
            return res.status(403).json({
                success: false,
                message: "Your access to this company has been blocked. You cannot request access."
            });
        }

        // Check if request already exists
        const existingRequest = await sql`
            SELECT id, status FROM company_consumer_requests 
            WHERE consumer_id = ${consumerId} AND company_id = ${companyId}
        `;

        let requestRecord = null;
        let reusedRequest = false;

        if (existingRequest.length > 0) {
            const status = existingRequest[0].status;
            const reusableStatuses = ['cancelled', 'revoked', 'rejected', 'blocked'];

            if (reusableStatuses.includes(status)) {
                const [updatedRequest] = await sql`
                    UPDATE company_consumer_requests
                    SET status = 'pending',
                        requested_at = NOW(),
                        responded_at = NULL,
                        responded_by = NULL
                    WHERE id = ${existingRequest[0].id}
                    RETURNING id, status, requested_at
                `;
                requestRecord = updatedRequest;
                reusedRequest = true;
            } else {
                return res.status(200).json({
                    success: true,
                    message: `Access request already exists with status: ${status}`,
                    data: {
                        requestId: existingRequest[0].id,
                        status,
                        companyName: company[0].name,
                        createdAt: existingRequest[0].requested_at
                    }
                });
            }
        }

        // Check if consumer already has access
        const existingAccess = await sql`
            SELECT id FROM consumer_company_access 
            WHERE consumer_id = ${consumerId} 
              AND company_id = ${companyId}
              AND is_active = TRUE
        `;

        if (existingAccess.length > 0) {
            return res.status(200).json({
                success: true,
                message: "You already have active access to this company",
                data: {
                    requestId: requestRecord?.id ?? existingRequest[0]?.id ?? null,
                    status: 'approved',
                    companyName: company[0].name
                }
            });
        }

        if (!requestRecord) {
            const [newRequest] = await sql`
                INSERT INTO company_consumer_requests (consumer_id, company_id, status)
                VALUES (${consumerId}, ${companyId}, 'pending')
                RETURNING id, status, requested_at
            `;
            requestRecord = newRequest;
        }

        try {
            const consumerName = `${req.consumer?.firstName || ""} ${req.consumer?.lastName || ""}`.trim() || req.consumer?.email || "Consumer";
            await createNotification({
                targetType: "company",
                companyId: Number(companyId),
                title: "New access request",
                body: `${consumerName} requested access to your catalog.`,
                actionType: "access:requested",
                actionPayload: {
                    consumerId,
                    companyId: Number(companyId),
                    requestId: requestRecord.id,
                },
            });
        } catch (notificationError) {
            console.warn("Failed to create access request notification:", notificationError.message);
        }

        res.status(reusedRequest ? 200 : 201).json({
            success: true,
            message: "Access request sent successfully",
            data: {
                requestId: requestRecord.id,
                status: requestRecord.status,
                companyName: company[0].name,
                createdAt: requestRecord.requested_at
            }
        });

    } catch (error) {
        console.error("Request company access error:", error);
        res.status(500).json({
            success: false,
            message: "Internal server error"
        });
    }
};

// Get consumer's access requests
export const getAccessRequests = async (req, res) => {
    try {
        const consumerId = req.consumer.id;

        const requests = await sql`
            SELECT 
                ccr.id,
                ccr.status,
                ccr.created_at,
                ccr.updated_at,
                c.name as company_name,
                c.description as company_description
            FROM company_consumer_requests ccr
            JOIN companies c ON ccr.company_id = c.id
            WHERE ccr.consumer_id = ${consumerId}
            ORDER BY ccr.created_at DESC
        `;

        res.json({
            success: true,
            data: requests
        });

    } catch (error) {
        console.error("Get access requests error:", error);
        res.status(500).json({
            success: false,
            message: "Internal server error"
        });
    }
};

// Get consumer's accessible companies
export const getAccessibleCompanies = async (req, res) => {
    try {
        const consumerId = req.consumer.id;

        const companies = await sql`
            SELECT 
                c.id,
                c.name,
                c.description,
                cca.granted_at as access_granted_at,
                COALESCE(blocks.is_blocked, false) AS is_blocked
            FROM consumer_company_access cca
            JOIN companies c ON cca.company_id = c.id
            LEFT JOIN company_consumer_blocks blocks
              ON blocks.company_id = c.id
             AND blocks.consumer_id = ${consumerId}
             AND blocks.is_blocked = TRUE
            WHERE cca.consumer_id = ${consumerId} AND cca.is_active = true AND c.is_active = true
            ORDER BY cca.granted_at DESC
        `;

        res.json({
            success: true,
            data: companies
        });

    } catch (error) {
        console.error("Get accessible companies error:", error);
        res.status(500).json({
            success: false,
            message: "Internal server error"
        });
    }
};

// Get consumer request status for all companies
export const getConsumerRequestStatus = async (req, res) => {
    try {
        const consumerId = req.consumer.id;

        // Get all companies with their request status and access status
        const companies = await sql`
            SELECT 
                c.id,
                c.name,
                c.description,
                c.created_at,
                COUNT(p.id) as product_count,
                ccr.status as request_status,
                ccr.requested_at,
                ccr.responded_at,
                cca.is_active as has_access,
                COALESCE(blocks.is_blocked, false) AS is_blocked
            FROM companies c
            LEFT JOIN products p ON c.id = p.company_id
            LEFT JOIN company_consumer_requests ccr ON c.id = ccr.company_id AND ccr.consumer_id = ${consumerId}
            LEFT JOIN consumer_company_access cca ON c.id = cca.company_id AND cca.consumer_id = ${consumerId}
            LEFT JOIN company_consumer_blocks blocks
              ON blocks.company_id = c.id
             AND blocks.consumer_id = ${consumerId}
             AND blocks.is_blocked = TRUE
            WHERE c.is_active = true
            GROUP BY c.id, c.name, c.description, c.created_at, ccr.status, ccr.requested_at, ccr.responded_at, cca.is_active, blocks.is_blocked
            ORDER BY c.created_at DESC
        `;

        res.json({
            success: true,
            data: companies
        });

    } catch (error) {
        console.error("Get consumer request status error:", error);
        res.status(500).json({
            success: false,
            message: "Internal server error"
        });
    }
};
