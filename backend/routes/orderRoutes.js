import express from 'express';
import { authenticateToken } from '../middleware/auth.js';
import { authenticateConsumerToken } from '../middleware/consumerAuth.js';
import {
    getPendingOrders,
    getCompletedOrders,
    getOrderCustomer,
    acceptOrder,
    rejectOrder,
    getConsumerOrders,
    completeOrder
} from '../controllers/orderController.js';

const router = express.Router();

// Company routes
router.get('/pending', authenticateToken, getPendingOrders);
router.get('/completed', authenticateToken, getCompletedOrders);
router.get('/customer/:orderId', authenticateToken, getOrderCustomer);
router.put('/:orderId/accept', authenticateToken, acceptOrder);
router.put('/:orderId/reject', authenticateToken, rejectOrder);

// Consumer routes
router.get('/consumer', authenticateConsumerToken, getConsumerOrders);
router.put('/consumer/:orderId/complete', authenticateConsumerToken, completeOrder);

export default router;

