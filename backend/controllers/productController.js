import { sql } from "../config/db.js";
// CRUD operations for products
export const getProducts = async (req, res) => {
    try {
        const userCompanyId = req.user.company_id;
        
        if (!userCompanyId) {
            return res.status(403).json({ 
                success: false, 
                message: "User must be associated with a company to view products" 
            });
        }

        const products = await sql`
            SELECT * FROM products
            WHERE company_id = ${userCompanyId}
            ORDER BY created_at DESC
        `;

        console.log("fetched products for company", userCompanyId, products);
        res.status(200).json({ success: true, data: products });
    } catch (error) {
        console.log("Error in getProducts function", error);
        res.status(500).json({ success: false, message: "Internal server error" });
    }
};

export const createProduct = async (req, res) => {
    const { name, image, price, discount_percentage, lead_time_days, minimum_order_quantity, available_quantity } = req.body;

    if (!name || !image || !price || !minimum_order_quantity || !available_quantity) {
        return res.status(400).json({ success: false, message: "All required fields are missing" });
    }

    // Validate discount_percentage (0-100)
    const discount = discount_percentage !== undefined && discount_percentage !== null 
        ? Math.max(0, Math.min(100, parseFloat(discount_percentage) || 0))
        : 0;

    // Validate lead_time_days (0 or positive integer)
    const leadTime = lead_time_days !== undefined && lead_time_days !== null 
        ? Math.max(0, Math.floor(parseFloat(lead_time_days) || 0))
        : 0;

    const userCompanyId = req.user.company_id;
    
    if (!userCompanyId) {
        return res.status(403).json({ 
            success: false, 
            message: "User must be associated with a company to create products" 
        });
    }

    try {
        const newProduct = await sql`
            INSERT INTO products (name, image, price, discount_percentage, lead_time_days, minimum_order_quantity, available_quantity, company_id)
            VALUES (${name}, ${image}, ${price}, ${discount}, ${leadTime}, ${minimum_order_quantity}, ${available_quantity}, ${userCompanyId})
            RETURNING *
        `;
        
        res.status(201).json({ success: true, data: newProduct[0] });

    } catch (error) {
        console.log("Error in createProduct function", error);
        res.status(500).json({ success: false, message: "Internal server error" });
    }
};

export const getProduct = async (req, res) => { 
    const { id } = req.params;
    const userCompanyId = req.user.company_id;
    
    if (!userCompanyId) {
        return res.status(403).json({ 
            success: false, 
            message: "User must be associated with a company to view products" 
        });
    }

    try {
        const product = await sql`
            SELECT * FROM products 
            WHERE id = ${id} AND company_id = ${userCompanyId}
        `;

        if (product.length === 0) {
            return res.status(404).json({ success: false, message: "Product not found" });
        }

        res.status(200).json({ success: true, data: product[0] });
        
    } catch (error) {
        console.log("Error in getProduct function", error);
        res.status(500).json({ success: false, message: "Internal server error" });
    }
};

export const updateProduct = async (req, res) => { 
    const { id } = req.params;
    const { name, image, price, discount_percentage, lead_time_days, minimum_order_quantity, available_quantity } = req.body;
    const userCompanyId = req.user.company_id;
    
    if (!userCompanyId) {
        return res.status(403).json({ 
            success: false, 
            message: "User must be associated with a company to update products" 
        });
    }

    // Validate discount_percentage (0-100)
    const discount = discount_percentage !== undefined && discount_percentage !== null 
        ? Math.max(0, Math.min(100, parseFloat(discount_percentage) || 0))
        : undefined;

    // Validate lead_time_days (0 or positive integer)
    const leadTime = lead_time_days !== undefined && lead_time_days !== null 
        ? Math.max(0, Math.floor(parseFloat(lead_time_days) || 0))
        : undefined;

    try {
        // First, get the existing product
        const existingProduct = await sql`
            SELECT * FROM products
            WHERE id = ${id} AND company_id = ${userCompanyId}
        `;

        if (existingProduct.length === 0) {
            return res.status(404).json({ success: false, message: "Product not found" });
        }

        // Check if any fields are being updated
        const hasUpdates = name !== undefined || image !== undefined || price !== undefined || 
                          discount !== undefined || leadTime !== undefined || 
                          minimum_order_quantity !== undefined || available_quantity !== undefined;

        if (!hasUpdates) {
            return res.status(400).json({ success: false, message: "No fields to update" });
        }

        // Build update query - use existing values if field is not provided
        const updateProduct = await sql`
            UPDATE products
            SET 
                name = ${name !== undefined ? name : existingProduct[0].name},
                image = ${image !== undefined ? image : existingProduct[0].image},
                price = ${price !== undefined ? price : existingProduct[0].price},
                discount_percentage = ${discount !== undefined ? discount : existingProduct[0].discount_percentage},
                lead_time_days = ${leadTime !== undefined ? leadTime : existingProduct[0].lead_time_days},
                minimum_order_quantity = ${minimum_order_quantity !== undefined ? minimum_order_quantity : existingProduct[0].minimum_order_quantity},
                available_quantity = ${available_quantity !== undefined ? available_quantity : existingProduct[0].available_quantity}
            WHERE id = ${id} AND company_id = ${userCompanyId}
            RETURNING *
        `;

        if(updateProduct.length === 0) {
            return res.status(404).json({ success: false, message: "Product not found" });
        }

        res.status(200).json({ success: true, data: updateProduct[0] });

    } catch (error) {
        console.error("Error in updateProduct function", error);
        console.error("Error details:", {
            message: error.message,
            stack: error.stack,
            productId: id,
            companyId: userCompanyId,
            body: req.body
        });
        res.status(500).json({ 
            success: false, 
            message: "Internal server error",
            error: process.env.NODE_ENV === 'development' ? error.message : undefined
        });
    }
};

export const deleteProduct = async (req, res) => {
    const { id } = req.params;
    const userCompanyId = req.user.company_id;
    
    if (!userCompanyId) {
        return res.status(403).json({ 
            success: false, 
            message: "User must be associated with a company to delete products" 
        });
    }

    try {
        // First check if product exists and belongs to the company
        const existingProduct = await sql`
            SELECT * FROM products 
            WHERE id = ${id} AND company_id = ${userCompanyId}
        `;

        if(existingProduct.length === 0) {
            return res.status(404).json({ success: false, message: "Product not found" });
        }

        // Check if product is in any active orders
        const activeOrders = await sql`
            SELECT oi.id, o.id as order_id, o.status
            FROM consumer_order_items oi
            JOIN consumer_orders o ON oi.order_id = o.id
            WHERE oi.product_id = ${id} 
            AND o.status IN ('pending', 'accepted', 'in_progress')
        `;

        if(activeOrders.length > 0) {
            return res.status(400).json({ 
                success: false, 
                message: `Cannot delete product. It is currently in ${activeOrders.length} active order(s). Please complete or cancel these orders first.` 
            });
        }

        // Delete the product
        const deletedProduct = await sql`
            DELETE FROM products 
            WHERE id = ${id} AND company_id = ${userCompanyId} 
            RETURNING *
        `;

        if(deletedProduct.length === 0) {
            return res.status(404).json({ success: false, message: "Product not found" });
        }

        res.status(200).json({ success: true, data: deletedProduct[0], message: "Product deleted successfully" });
    } catch (error) {
        console.error("Error in deleteProduct function", error);
        console.error("Error details:", {
            message: error.message,
            stack: error.stack,
            productId: id,
            companyId: userCompanyId
        });
        
        // Check if it's a foreign key constraint error
        if (error.message && error.message.includes('foreign key')) {
            return res.status(400).json({ 
                success: false, 
                message: "Cannot delete product. It is referenced in existing orders. Please contact support if you need to delete this product." 
            });
        }
        
        res.status(500).json({ 
            success: false, 
            message: "Internal server error",
            error: process.env.NODE_ENV === 'development' ? error.message : undefined
        });
    }
};
 //
