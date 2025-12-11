import { sql } from "../config/db.js";

const checkDatabase = async () => {
    try {
        console.log("🔍 Checking database tables...");
        
        // Check if tables exist
        const tables = await sql`
            SELECT table_name 
            FROM information_schema.tables 
            WHERE table_schema = 'public'
            ORDER BY table_name
        `;
        
        console.log("📋 Existing tables:");
        tables.forEach(table => {
            console.log(`  - ${table.table_name}`);
        });
        
        // Check if roles exist
        const roles = await sql`SELECT * FROM roles`;
        console.log(`\n👥 Roles (${roles.length}):`);
        roles.forEach(role => {
            console.log(`  - ${role.name}: ${role.description}`);
        });
        
        // Check if users exist
        const users = await sql`SELECT email, first_name, last_name, role_id FROM users`;
        console.log(`\n👤 Users (${users.length}):`);
        users.forEach(user => {
            console.log(`  - ${user.email}: ${user.first_name} ${user.last_name} (role_id: ${user.role_id})`);
        });
        
        // Check if companies exist
        const companies = await sql`SELECT id, name, owner_id FROM companies`;
        console.log(`\n🏢 Companies (${companies.length}):`);
        companies.forEach(company => {
            console.log(`  - ${company.name} (owner_id: ${company.owner_id})`);
        });
        
    } catch (error) {
        console.error("❌ Database check failed:", error);
        throw error;
    }
};

// Run the check if this file is executed directly
if (import.meta.url === `file://${process.argv[1]}`) {
    checkDatabase()
        .then(() => {
            console.log("✅ Database check complete");
            process.exit(0);
        })
        .catch((error) => {
            console.error("❌ Database check failed:", error);
            process.exit(1);
        });
}

export { checkDatabase };








