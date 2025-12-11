import { sql } from "../config/db.js";

/**
 * Download a company document by ID
 */
export const downloadDocument = async (req, res) => {
    try {
        const { documentId } = req.params;
        
        // Get document from database
        const document = await sql`
            SELECT 
                cd.id,
                cd.filename,
                cd.originalname,
                cd.mimetype,
                cd.size,
                cd.file_data,
                cd.company_id,
                c.status
            FROM company_documents cd
            JOIN companies c ON cd.company_id = c.id
            WHERE cd.id = ${documentId}
        `;
        
        if (document.length === 0) {
            return res.status(404).json({
                success: false,
                message: "Document not found"
            });
        }
        
        const doc = document[0];
        
        // Set appropriate headers for file download
        res.setHeader('Content-Type', doc.mimetype);
        res.setHeader('Content-Disposition', `attachment; filename="${encodeURIComponent(doc.originalname)}"`);
        res.setHeader('Content-Length', doc.size);
        res.setHeader('Cache-Control', 'no-cache');
        
        // Send the file data (BYTEA column returns a Buffer in Node.js)
        // Convert to Buffer if needed
        const fileBuffer = Buffer.isBuffer(doc.file_data) ? doc.file_data : Buffer.from(doc.file_data);
        res.send(fileBuffer);
        
    } catch (error) {
        console.error("Download document error:", error);
        res.status(500).json({
            success: false,
            message: "Internal server error"
        });
    }
};

/**
 * Get document metadata (for admin dashboard)
 */
export const getDocumentInfo = async (req, res) => {
    try {
        const { documentId } = req.params;
        
        const document = await sql`
            SELECT id, filename, originalname, mimetype, size, created_at
            FROM company_documents
            WHERE id = ${documentId}
        `;
        
        if (document.length === 0) {
            return res.status(404).json({
                success: false,
                message: "Document not found"
            });
        }
        
        res.json({
            success: true,
            data: document[0]
        });
        
    } catch (error) {
        console.error("Get document info error:", error);
        res.status(500).json({
            success: false,
            message: "Internal server error"
        });
    }
};

