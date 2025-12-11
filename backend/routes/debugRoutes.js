import express from "express";
import { createCompaniesTable, testCompanyCreation } from "../controllers/debugController.js";

const router = express.Router();

// Debug route to create companies table
router.post("/create-companies-table", createCompaniesTable);

// Debug route to test company creation
router.post("/test-company-creation", testCompanyCreation);

export default router;
