import { runMigrations, createDefaultOwner, runSeeding } from "./lib/migrations.js";

async function main() {
    try {
        console.log("Running manual migrations...");
        await runMigrations();
        await createDefaultOwner();
        await runSeeding();
        console.log("All migrations completed successfully!");
        process.exit(0);
    } catch (error) {
        console.error("Migration failed:", error);
        process.exit(1);
    }
}

main();

