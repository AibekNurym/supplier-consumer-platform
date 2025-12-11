import { neon } from "@neondatabase/serverless";
import dotenv from "dotenv";

dotenv.config();

const {PGHOST, PGDATABASE, PGUSER, PGPASSWORD } = process.env;

// creates a SQL connection to the database using out ENV variables
export const sql = neon(
    `postgresql://${PGUSER}:${PGPASSWORD}@${PGHOST}/${PGDATABASE}?sslmode=require&channel_binding=require`
)

// this sql function we export is used as a tagged template literal, which allows us to write SQL queries in a safe way, preventing SQL injection attacks.

// 'postgresql://neondb_owner:npg_sD2IyPxfRip0@ep-floral-frost-adavh0n8-pooler.c-2.us-east-1.aws.neon.tech/neondb?sslmode=require&channel_binding=require'