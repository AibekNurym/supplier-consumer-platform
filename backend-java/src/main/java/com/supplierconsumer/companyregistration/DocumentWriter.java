package com.supplierconsumer.companyregistration;

import com.supplierconsumer.repo.DocumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * Stores one registration document inside its own savepoint.
 *
 * <p>The original stores each file in a try/catch and keeps going when one fails, so a bad
 * attachment never costs the applicant their registration. That behaviour cannot simply be carried
 * into a transaction: once a statement fails, Postgres marks the whole transaction aborted and
 * every later statement fails with {@code 25P02}, so catching the exception would leave the
 * registration doomed anyway.
 *
 * <p>{@code Propagation.NESTED} issues a real {@code SAVEPOINT}, so a failed file rolls back to it
 * and the surrounding transaction carries on. Two things this depends on: the method has to live
 * in a separate bean, because a self-invocation bypasses the proxy and silently gets no savepoint,
 * and the application has to use {@code DataSourceTransactionManager}, which supports nesting --
 * {@code JpaTransactionManager} does not. That is the main reason this project is on plain JDBC.
 */
@Service
public class DocumentWriter {

    private final DocumentRepository documents;

    public DocumentWriter(DocumentRepository documents) {
        this.documents = documents;
    }

    @Transactional(propagation = Propagation.NESTED)
    public long store(long companyId, MultipartFile file) throws IOException {
        byte[] data = file.getBytes();
        String originalName = file.getOriginalFilename() == null
                ? "document" : file.getOriginalFilename();

        // filename and originalname are written identically, as the original does -- the
        // distinction is a leftover from when files lived on disk under a generated name.
        return documents.insert(companyId, originalName, originalName,
                file.getContentType() == null ? "application/octet-stream" : file.getContentType(),
                (int) file.getSize(), data);
    }
}
