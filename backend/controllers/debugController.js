import { sql } from "../config/db.js";

export const createCompaniesTable = async (req, res) => {
    try {
        // Create companies table
        await sql`
            CREATE TABLE IF NOT EXISTS companies (
                id SERIAL PRIMARY KEY,
                name VARCHAR(255) NOT NULL,
                description TEXT,
                owner_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                is_active BOOLEAN DEFAULT true,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        `;
        
        // Add company_id column to users table if it doesn't exist
        await sql`
            ALTER TABLE users 
            ADD COLUMN IF NOT EXISTS company_id INTEGER REFERENCES companies(id) ON DELETE SET NULL
        `;
        
        // Add company_id column to products table if it doesn't exist
        await sql`
            ALTER TABLE products 
            ADD COLUMN IF NOT EXISTS company_id INTEGER REFERENCES companies(id) ON DELETE CASCADE
        `;
        
        res.json({ success: true, message: "Companies table and columns created successfully" });
    } catch (error) {
        console.error("Error creating companies table:", error);
        res.status(500).json({ success: false, message: error.message });
    }
};

export const testCompanyCreation = async (req, res) => {
    try {
        const { firstName, lastName } = req.body;
        
        const companyName = `${firstName} ${lastName} Company`;
        const companyDescription = `Company owned by ${firstName} ${lastName}`;
        
        // Test creating a company
        const newCompany = await sql`
            INSERT INTO companies (name, description, owner_id)
            VALUES (${companyName}, ${companyDescription}, 1)
            RETURNING id
        `;
        
        res.json({ success: true, companyId: newCompany[0].id });
    } catch (error) {
        console.error("Error creating company:", error);
        res.status(500).json({ success: false, message: error.message });
    }
};
