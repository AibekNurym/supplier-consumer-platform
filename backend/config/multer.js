import multer from 'multer';
import path from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

// Configure storage
const storage = multer.diskStorage({
  destination: function (req, file, cb) {
    cb(null, path.join(__dirname, '../uploads/chat'));
  },
  filename: function (req, file, cb) {
    // Generate unique filename while preserving original name
    const uniqueSuffix = Date.now() + '-' + Math.round(Math.random() * 1E9);
    // Get the base name without extension and the extension
    const ext = path.extname(file.originalname);
    const baseName = path.basename(file.originalname, ext);
    // Preserve original name with unique suffix
    cb(null, baseName + '-' + uniqueSuffix + ext);
  }
});

// Configure file filter
const fileFilter = (req, file, cb) => {
  console.log('Multer fileFilter - file.mimetype:', file.mimetype);
  console.log('Multer fileFilter - file.originalname:', file.originalname);
  
  // Allow images, audio, documents, CSV, SVG, and other common file types
  const allowedTypes = /jpeg|jpg|png|gif|webp|svg|mp3|mp4|m4a|wav|ogg|pdf|doc|docx|csv|xlsx|xls|txt|zip|octet-stream/;
  const allowedMimeTypes = /^image\/|^audio\/(mp3|mp4|m4a|mpeg|wav|ogg)|^video\/|^application\/(pdf|msword|vnd\.openxmlformats|vnd\.ms-excel|csv|xml|octet-stream)|^text\/(csv|plain|xml)/;
  
  const extname = allowedTypes.test(path.extname(file.originalname).toLowerCase());
  const mimetype = allowedMimeTypes.test(file.mimetype);

  if (mimetype || extname) {
    return cb(null, true);
  } else {
    console.log('File rejected - mimetype:', file.mimetype, 'originalname:', file.originalname);
    cb(new Error('Invalid file type. Only images, audio, videos, and documents are allowed!'));
  }
};

// Initialize multer
export const upload = multer({ 
  storage: storage,
  limits: { fileSize: 100 * 1024 * 1024 }, // 100MB limit for videos
  fileFilter: fileFilter
});

// Helper to determine message type from file
export const getMessageTypeFromFile = (file) => {
  const ext = path.extname(file.originalname).toLowerCase();
  if (['.jpg', '.jpeg', '.png', '.gif', '.webp', '.svg'].includes(ext)) {
    return 'image';
  } else if (['.mp3', '.wav', '.ogg', '.mp4', '.m4a'].includes(ext)) {
    return 'audio';
  } else {
    return 'file';
  }
};

