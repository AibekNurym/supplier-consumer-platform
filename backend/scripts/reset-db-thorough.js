import { sql } from "../config/db.js";

const resetDatabase = async () => {
    try {
        console.log("🗑️  Performing thorough database reset...");
        
        // Get all tables
        const tables = await sql`
            SELECT table_name 
            FROM information_schema.tables 
            WHERE table_schema = 'public'
            ORDER BY table_name
        `;
        
        console.log("📋 Found tables:", tables.map(t => t.table_name));
        
        // Drop all tables
        for (const table of tables) {
            await sql.unsafe(`DROP TABLE IF EXISTS "${table.table_name}" CASCADE`);
            console.log(`✅ Dropped ${table.table_name} table`);
        }
        
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








