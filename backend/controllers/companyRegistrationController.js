import { hashPassword } from "../lib/auth.js";
import { sql } from "../config/db.js";
import multer from "multer";

// Configure multer to store files in memory (we'll save to database)
const storage = multer.memoryStorage();

const fileFilter = (req, file, cb) => {
  // Allow PDF, PNG, JPG, JPEG
  const allowedTypes = /pdf|png|jpg|jpeg/;
  const extname = allowedTypes.test(file.originalname.toLowerCase());
  const mimetype = file.mimetype === 'application/pdf' ||
                   file.mimetype === 'image/png' ||
                   file.mimetype === 'image/jpeg' ||
                   file.mimetype === 'image/jpg';

  if (mimetype || extname) {
    return cb(null, true);
  } else {
    cb(new Error('Invalid file type. Only PDF, PNG, and JPG files are allowed!'));
  }
};

export const uploadDocuments = multer({
  storage: storage,
  limits: { fileSize: 10 * 1024 * 1024 }, // 10MB limit
  fileFilter: fileFilter
});

/**
 * Register a new company (Owner registration)
 */
export const registerCompany = async (req, res) => {
    try {
        console.log('=== COMPANY REGISTRATION DEBUG ===');
        console.log('req.body:', req.body);
        console.log('req.files:', req.files ? req.files.map(f => ({ name: f.originalname, size: f.size, hasBuffer: !!f.buffer })) : 'No files');
        console.log('Files count:', req.files?.length || 0);
        
        const { companyName, email, password, firstName, lastName, phone } = req.body;

        // Validation
        if (!companyName || !email || !password || !firstName || !lastName) {
            return res.status(400).json({
                success: false,
                message: "Company name, email, password, first name, and last name are required"
            });
        }

        // Check if user already exists
        const existingUser = await sql`
            SELECT id FROM users WHERE email = ${email}
        `;

        if (existingUser.length > 0) {
            return res.status(409).json({
                success: false,
                message: "User with this email already exists"
            });
        }

        // Check if company name already exists
        const existingCompany = await sql`
            SELECT id FROM companies WHERE name = ${companyName}
        `;

        if (existingCompany.length > 0) {
            return res.status(409).json({
                success: false,
                message: "Company with this name already exists"
            });
        }

        // Get Owner role
        const role = await sql`
            SELECT id, name FROM roles WHERE name = 'Owner'
        `;

        if (role.length === 0) {
            return res.status(500).json({
                success: false,
                message: "Owner role not found in database"
            });
        }

        // Hash password
        const passwordHash = await hashPassword(password);

        // Create user (Owner)
        const newUser = await sql`
            INSERT INTO users (email, password_hash, first_name, last_name, phone, role_id, created_by)
            VALUES (${email}, ${passwordHash}, ${firstName}, ${lastName}, ${phone || null}, ${role[0].id}, null)
            RETURNING id, email, first_name, last_name, phone, role_id, created_at
        `;

        // Create company with pending status
        const newCompany = await sql`
            INSERT INTO companies (name, description, owner_id, status)
            VALUES (${companyName}, ${`Company registered by ${firstName} ${lastName}`}, ${newUser[0].id}, 'pending')
            RETURNING id, name, status, created_at
        `;

        // Process and store uploaded documents in database
        const documentIds = [];
        if (req.files && req.files.length > 0) {
            console.log('Processing files:', req.files.length);
            for (const file of req.files) {
                try {
                    if (!file.buffer) {
                        console.error('File buffer is missing for:', file.originalname);
                        throw new Error(`File buffer is missing for ${file.originalname}. Make sure multer is configured with memoryStorage.`);
                    }
                    
                    console.log('Storing file:', file.originalname, 'Size:', file.size, 'Buffer length:', file.buffer.length);
                    
                    // Ensure buffer is a Buffer object for PostgreSQL BYTEA
                    const fileBuffer = Buffer.isBuffer(file.buffer) ? file.buffer : Buffer.from(file.buffer);
                    
                    // Store file in database
                    const document = await sql`
                        INSERT INTO company_documents (company_id, filename, originalname, mimetype, size, file_data)
                        VALUES (${newCompany[0].id}, ${file.originalname}, ${file.originalname}, ${file.mimetype}, ${file.size}, ${fileBuffer})
                        RETURNING id, originalname, mimetype, size
                    `;
                    console.log('✅ Document stored with ID:', document[0].id);
                    documentIds.push(document[0].id);
                } catch (fileError) {
                    console.error('❌ Error storing file:', fileError);
                    console.error('Error details:', {
                        message: fileError.message,
                        stack: fileError.stack,
                        fileName: file.originalname
                    });
                    // Don't throw - continue with other files, but log the error
                    // We'll still create the company even if file upload fails
                }
            }
        } else {
            console.log('⚠️ No files received in req.files');
        }

        // Store document metadata in business_documents JSONB field for easy access
        if (documentIds.length > 0) {
            const documents = await sql`
                SELECT id, originalname, mimetype, size
                FROM company_documents
                WHERE company_id = ${newCompany[0].id}
            `;
            
            console.log('Documents to store in JSONB:', documents);
            
            await sql`
                UPDATE companies
                SET business_documents = ${JSON.stringify(documents)}::jsonb
                WHERE id = ${newCompany[0].id}
            `;
        }

        // Update the user with company_id
        await sql`
            UPDATE users 
            SET company_id = ${newCompany[0].id}
            WHERE id = ${newUser[0].id}
        `;

        res.status(201).json({
            success: true,
            message: "Company registration submitted successfully. Please wait for admin approval.",
            data: {
                company: {
                    id: newCompany[0].id,
                    name: newCompany[0].name,
                    status: newCompany[0].status
                },
                user: {
                    id: newUser[0].id,
                    email: newUser[0].email,
                    firstName: newUser[0].first_name,
                    lastName: newUser[0].last_name
                }
            }
        });

    } catch (error) {
        console.error("Company registration error:", error);
        res.status(500).json({
            success: false,
            message: "Internal server error during company registration"
        });
    }
};

