package com.supplierconsumer.repo;

import com.supplierconsumer.wire.PgJson;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Registration documents, stored as {@code BYTEA} inside {@code company_documents}.
 *
 * <p>The blob is only ever selected by the download endpoint. Every other read uses a metadata
 * projection, since {@code SELECT *} here drags whole PDFs through the connection.
 */
@Repository
public class DocumentRepository {

    private final JdbcClient db;
    private final PgJson pgJson;

    public DocumentRepository(JdbcClient db, PgJson pgJson) {
        this.db = db;
        this.pgJson = pgJson;
    }

    public long insert(long companyId, String filename, String originalName, String mimeType,
                       int size, byte[] data) {
        KeyHolder keys = new GeneratedKeyHolder();
        db.sql("""
                        INSERT INTO company_documents (company_id, filename, originalname, mimetype,
                                                       size, file_data)
                        VALUES (:companyId, :filename, :originalName, :mimeType, :size, :data)
                        """)
                .param("companyId", companyId)
                .param("filename", filename)
                .param("originalName", originalName)
                .param("mimeType", mimeType)
                .param("size", size)
                .param("data", data)
                .update(keys, "id");
        return ((Number) keys.getKeys().get("id")).longValue();
    }

    /** Metadata for every document of one company, which is what gets copied into the JSONB column. */
    public List<Map<String, Object>> findMetadataForCompany(long companyId) {
        return db.sql("""
                        SELECT id, originalname, mimetype, size
                        FROM company_documents
                        WHERE company_id = :companyId
                        ORDER BY id
                        """)
                .param("companyId", companyId)
                .query(pgJson.rowMapper())
                .list();
    }

    public Optional<Map<String, Object>> findInfo(long documentId) {
        return db.sql("""
                        SELECT id, filename, originalname, mimetype, size, created_at
                        FROM company_documents
                        WHERE id = :id
                        """)
                .param("id", documentId)
                .query(pgJson.rowMapper())
                .optional();
    }

    /** The only query that reads the blob. */
    public Optional<StoredDocument> findForDownload(long documentId) {
        return db.sql("""
                        SELECT cd.id, cd.originalname, cd.mimetype, cd.size, cd.file_data
                        FROM company_documents cd
                        JOIN companies c ON cd.company_id = c.id
                        WHERE cd.id = :id
                        """)
                .param("id", documentId)
                .query((rs, n) -> new StoredDocument(
                        rs.getLong("id"), rs.getString("originalname"), rs.getString("mimetype"),
                        rs.getInt("size"), rs.getBytes("file_data")))
                .optional();
    }

    /**
     * Resolves a legacy document entry, which carries a filename but no id, by matching the
     * original filename within the company.
     */
    public Optional<Long> findIdByOriginalName(long companyId, String originalName) {
        return db.sql("""
                        SELECT id FROM company_documents
                        WHERE company_id = :companyId AND originalname = :originalName
                        LIMIT 1
                        """)
                .param("companyId", companyId)
                .param("originalName", originalName)
                .query(Long.class)
                .optional();
    }

    public record StoredDocument(long id, String originalName, String mimeType, int size, byte[] data) {
    }
}
