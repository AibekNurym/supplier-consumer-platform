import { sql } from "../config/db.js";

const resetDatabase = async () => {
    try {
        console.log("🗑️  Resetting database...");
        
        // Drop tables in reverse dependency order
        await sql`DROP TABLE IF EXISTS audit_log CASCADE`;
        console.log("✅ Dropped audit_log table");
        
        await sql`DROP TABLE IF EXISTS refresh_tokens CASCADE`;
        console.log("✅ Dropped refresh_tokens table");
        
        await sql`DROP TABLE IF EXISTS products CASCADE`;
        console.log("✅ Dropped products table");
        
        await sql`DROP TABLE IF EXISTS users CASCADE`;
        console.log("✅ Dropped users table");
        
        await sql`DROP TABLE IF EXISTS companies CASCADE`;
        console.log("✅ Dropped companies table");
        
        await sql`DROP TABLE IF EXISTS role_permissions CASCADE`;
        console.log("✅ Dropped role_permissions table");
        
        await sql`DROP TABLE IF EXISTS permissions CASCADE`;
        console.log("✅ Dropped permissions table");
        
        await sql`DROP TABLE IF EXISTS roles CASCADE`;
        console.log("✅ Dropped roles table");
        
        console.log("🎉 Database reset completed successfully!");
        console.log("You can now restart the server to create fresh tables with proper multi-tenant setup.");
        
    } catch (error) {
        console.error("❌ Database reset failed:", error);
        throw error;
    }
};

// Run the reset if this file is executed directly
if (import.meta.url === `file://${process.argv[1]}`) {
    resetDatabase()
        .then(() => {
            console.log("✅ Reset complete");
            process.exit(0);
        })
        .catch((error) => {
            console.error("❌ Reset failed:", error);
            process.exit(1);
        });
}

export { resetDatabase };








