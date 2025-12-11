import { sql } from "../config/db.js";
import { hashPassword } from "../lib/auth.js";

export const seedDatabase = async () => {
    try {
        console.log("🌱 Starting database seeding...");

        // Check if users already exist
        const existingUsers = await sql`SELECT COUNT(*) as count FROM users`;
        if (existingUsers[0].count > 0) {
            console.log("Users already exist, skipping seed data");
            return;
        }

        // Get role IDs
        const roles = await sql`SELECT id, name FROM roles`;
        const roleMap = {};
        roles.forEach(role => {
            roleMap[role.name] = role.id;
        });

        // Create sample users
        const sampleUsers = [
            {
                email: "admin@company.com",
                password: "admin123",
                firstName: "System",
                lastName: "Administrator",
                phone: "+1-555-0001",
                roleName: "Owner"
            },
            {
                email: "manager@company.com",
                password: "manager123",
                firstName: "John",
                lastName: "Manager",
                phone: "+1-555-0002",
                roleName: "Manager"
            },
            {
                email: "sales@company.com",
                password: "sales123",
                firstName: "Jane",
                lastName: "Sales",
                phone: "+1-555-0003",
                roleName: "Sales Representative"
            },
            {
                email: "alice@company.com",
                password: "alice123",
                firstName: "Alice",
                lastName: "Johnson",
                phone: "+1-555-0004",
                roleName: "Sales Representative"
            },
            {
                email: "bob@company.com",
                password: "bob123",
                firstName: "Bob",
                lastName: "Smith",
                phone: "+1-555-0005",
                roleName: "Manager"
            }
        ];

        // Create users
        for (const userData of sampleUsers) {
            const passwordHash = await hashPassword(userData.password);
            
            await sql`
                INSERT INTO users (email, password_hash, first_name, last_name, phone, role_id, created_by)
                VALUES (
                    ${userData.email}, 
                    ${passwordHash}, 
                    ${userData.firstName}, 
                    ${userData.lastName}, 
                    ${userData.phone}, 
                    ${roleMap[userData.roleName]}, 
                    ${roleMap["Owner"]} -- Created by Owner
                )
            `;
        }

        // Create some sample products
        const sampleProducts = [
            {
                name: "Premium Widget A",
                image: "https://via.placeholder.com/300x200/4F46E5/FFFFFF?text=Widget+A",
                price: 29.99,
                minimum_order_quantity: 10,
                available_quantity: 100
            },
            {
                name: "Standard Widget B",
                image: "https://via.placeholder.com/300x200/059669/FFFFFF?text=Widget+B",
                price: 19.99,
                minimum_order_quantity: 5,
                available_quantity: 250
            },
            {
                name: "Deluxe Widget C",
                image: "https://via.placeholder.com/300x200/DC2626/FFFFFF?text=Widget+C",
                price: 49.99,
                minimum_order_quantity: 15,
                available_quantity: 75
            },
            {
                name: "Economy Widget D",
                image: "https://via.placeholder.com/300x200/7C3AED/FFFFFF?text=Widget+D",
                price: 12.99,
                minimum_order_quantity: 20,
                available_quantity: 500
            }
        ];

        for (const product of sampleProducts) {
            await sql`
                INSERT INTO products (name, image, price, minimum_order_quantity, available_quantity)
                VALUES (
                    ${product.name}, 
                    ${product.image}, 
                    ${product.price}, 
                    ${product.minimum_order_quantity}, 
                    ${product.available_quantity}
                )
            `;
        }

        console.log("✅ Database seeded successfully!");
        console.log("\n📋 Sample Users Created:");
        console.log("┌─────────────────────────┬─────────────┬─────────────────┐");
        console.log("│ Email                    │ Password    │ Role            │");
        console.log("├─────────────────────────┼─────────────┼─────────────────┤");
        sampleUsers.forEach(user => {
            console.log(`│ ${user.email.padEnd(23)} │ ${user.password.padEnd(11)} │ ${user.roleName.padEnd(15)} │`);
        });
        console.log("└─────────────────────────┴─────────────┴─────────────────┘");
        
        console.log("\n🎯 Quick Start:");
        console.log("1. Start your backend server: npm run dev");
        console.log("2. Start your frontend: cd frontend && npm run dev");
        console.log("3. Login with any of the sample accounts above");
        console.log("4. Explore the role-based dashboards!");

    } catch (error) {
        console.error("❌ Seeding failed:", error);
        throw error;
    }
};

// Run seeding if this file is executed directly
if (import.meta.url === `file://${process.argv[1]}`) {
    seedDatabase()
        .then(() => {
            console.log("Seeding completed");
            process.exit(0);
        })
        .catch((error) => {
            console.error("Seeding failed:", error);
            process.exit(1);
        });
}









