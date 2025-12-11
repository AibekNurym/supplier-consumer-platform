import express from 'express';
import dotenv from 'dotenv';
import { 
  getChatMessages, 
  sendMessage, 
  markMessagesAsRead, 
  getConversations 
} from '../controllers/chatController.js';
import { authenticateToken } from '../middleware/auth.js';
import { authenticateConsumerToken } from '../middleware/consumerAuth.js';
import { upload, getMessageTypeFromFile } from '../config/multer.js';
import { sql } from '../config/db.js';

// Ensure dotenv is loaded
dotenv.config();

const router = express.Router();

// Get JWT_SECRET with fallback
const JWT_SECRET = process.env.JWT_SECRET || 'your-super-secret-jwt-key-change-in-production';

// Middleware to authenticate both consumer and company users
const authenticateUser = async (req, res, next) => {
  try {
    console.log('=== CHAT AUTH MIDDLEWARE DEBUG ===');
    // Check if Authorization header exists
    const authHeader = req.headers.authorization;
    console.log('Authorization header:', authHeader ? authHeader.substring(0, 20) + '...' : 'NO HEADER');
    
    if (!authHeader || !authHeader.startsWith('Bearer ')) {
      console.log('Missing authorization header');
      return res.status(401).json({ success: false, message: 'Missing authorization header' });
    }
    
    const token = authHeader.split(' ')[1];
    
    // Try to decode as company token first
    try {
      const jwt = await import('jsonwebtoken');
      console.log('About to verify token with JWT_SECRET:', JWT_SECRET ? 'SET' : 'NOT SET');
      const decoded = jwt.default.verify(token, JWT_SECRET);
      console.log('Token decoded:', decoded);
      
      // Check if this is a consumer token (has consumer-related fields) or company token
      if (decoded.consumerId || decoded.consumer_id) {
        console.log('This is a consumer token');
        // It's a consumer token
        const consumerId = decoded.consumerId || decoded.consumer_id;
        console.log('Consumer ID:', consumerId);
        // Fetch consumer details from database
        const consumerUsers = await sql`SELECT * FROM consumer_users WHERE id = ${consumerId}`;
        console.log('Consumer lookup result:', consumerUsers);
        if (consumerUsers.length === 0) {
          console.log('Consumer not found');
          return res.status(401).json({ success: false, message: 'Consumer not found' });
        }
        const consumer = consumerUsers[0];
        req.user = { 
          ...decoded, 
          type: 'consumer', 
          id: consumerId, 
          consumer_id: consumerId,
          email: consumer.email,
          firstName: consumer.first_name,
          lastName: consumer.last_name
        };
        console.log('Set req.user:', req.user);
        next();
      } else if (decoded.user_id || decoded.userId) {
        console.log('This is a company token');
        // It's a company token
        const userId = decoded.user_id || decoded.userId;
        // Fetch company user details from database
        const companyUsers = await sql`
          SELECT u.*, r.name as role_name 
          FROM users u
          JOIN roles r ON u.role_id = r.id
          WHERE u.id = ${userId} AND u.is_active = true
        `;
        if (companyUsers.length === 0) {
          return res.status(401).json({ success: false, message: 'User not found' });
        }
        const companyUser = companyUsers[0];
        req.user = { 
          ...decoded, 
          type: 'company', 
          id: userId, 
          user_id: userId,
          email: companyUser.email,
          firstName: companyUser.first_name,
          lastName: companyUser.last_name,
          company_id: companyUser.company_id,
          roleName: companyUser.role_name
        };
        console.log('Set req.user for company:', req.user);
        next();
      } else {
        console.log('Invalid token - no consumer or user id');
        return res.status(401).json({ success: false, message: 'Invalid token' });
      }
    } catch (error) {
      console.log('JWT verification error:', error.message);
      return res.status(401).json({ success: false, message: 'Invalid token', error: error.message });
    }
  } catch (error) {
    console.log('Chat auth middleware error:', error.message);
    return res.status(401).json({ success: false, message: 'Authentication failed', error: error.message });
  }
};

// Get list of conversations
router.get('/conversations', authenticateUser, getConversations);

// Get chat messages between consumer and company
router.get('/messages/:consumerId/:companyId', authenticateUser, getChatMessages);

// Send a message (with file upload support)
const handleSendMessage = async (req, res) => {
  try {
    console.log('=== HANDLE SEND MESSAGE DEBUG ===');
    console.log('req.body:', req.body);
    console.log('req.file:', req.file);
    
    // If a file was uploaded, prepare the attachment data
    let message_type = 'text';
    let attachment_url = null;
    let attachment_name = null;
    
    if (req.file) {
      message_type = getMessageTypeFromFile(req.file);
      attachment_url = `/uploads/chat/${req.file.filename}`;
      attachment_name = req.file.originalname;
      console.log('File uploaded:', attachment_name, attachment_url);
    }
    
    // Add the attachment data to the request body
    req.body.message_type = message_type;
    req.body.attachment_url = attachment_url;
    req.body.attachment_name = attachment_name;

    // Normalize optional reply_to_message_id if provided
    if (req.body.reply_to_message_id === '' || req.body.reply_to_message_id === undefined) {
      req.body.reply_to_message_id = null;
    }
    
    console.log('Calling sendMessage with req.body:', req.body);
    
    // Call the existing sendMessage controller
    return await sendMessage(req, res);
  } catch (error) {
    console.error('Error in handleSendMessage:', error);
    return res.status(500).json({ success: false, message: 'Failed to send message', error: error.message });
  }
};

router.post('/messages/:consumerId/:companyId', authenticateUser, upload.single('attachment'), handleSendMessage);

// Mark messages as read
router.put('/messages/:consumerId/:companyId/read', authenticateUser, markMessagesAsRead);

export default router;

