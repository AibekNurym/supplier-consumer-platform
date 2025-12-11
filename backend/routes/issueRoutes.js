import express from 'express';
import { authenticateToken } from '../middleware/auth.js';
import { authenticateConsumerToken } from '../middleware/consumerAuth.js';
import {
    reportIssue,
    getConsumerIssues,
    getCompanyIssues,
    assignToManager,
    getManagers,
    resolveIssue,
    getIssueDetails
} from '../controllers/issueController.js';

const router = express.Router();

// Consumer routes
router.post('/', authenticateConsumerToken, reportIssue);
router.get('/consumer', authenticateConsumerToken, getConsumerIssues);
router.get('/consumer/:issueId', authenticateConsumerToken, getIssueDetails);

// Company routes
router.get('/company', authenticateToken, getCompanyIssues);
router.get('/company/:issueId', authenticateToken, getIssueDetails);
router.get('/managers', authenticateToken, getManagers);
router.put('/:issueId/assign', authenticateToken, assignToManager); // Sales Reps can assign to managers
router.put('/:issueId/resolve', authenticateToken, resolveIssue);

export default router;

