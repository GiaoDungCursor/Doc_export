package com.company.office.application;

import com.company.office.model.DocumentEntity;
import com.company.office.model.DocumentFieldEntity;
import com.company.office.repository.DocumentRepository;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class DocumentService {
    private final DocumentRepository documentRepository;
    private final String documentsStorageDir;

    public DocumentService(DocumentRepository documentRepository) {
        this(documentRepository, "app-data/documents");
    }

    public DocumentService(DocumentRepository documentRepository, String storageDir) {
        this.documentRepository = documentRepository;
        this.documentsStorageDir = storageDir;
        new File(this.documentsStorageDir).mkdirs();
    }

    public DocumentEntity importFile(File sourceFile, String documentType) throws IOException {
        if (!sourceFile.exists()) {
            throw new IllegalArgumentException("Source file does not exist: " + sourceFile.getAbsolutePath());
        }

        String docId = "doc-" + UUID.randomUUID().toString().substring(0, 8);
        String originalFilename = sourceFile.getName();
        String extension = "";
        int dotIndex = originalFilename.lastIndexOf('.');
        if (dotIndex > 0) {
            extension = originalFilename.substring(dotIndex + 1).toLowerCase();
        }

        // Store file with safe ASCII ID on disk to prevent Windows charset mismatches
        String safeStorageName = extension.isEmpty() ? docId : docId + "." + extension;
        Path targetPath = Path.of(documentsStorageDir, safeStorageName).toAbsolutePath();
        Files.copy(sourceFile.toPath(), targetPath, StandardCopyOption.REPLACE_EXISTING);

        DocumentEntity doc = new DocumentEntity(
            docId,
            documentType != null ? documentType : "generic",
            originalFilename,
            targetPath.toString(),
            extension,
            sourceFile.length()
        );

        documentRepository.save(doc);
        return doc;
    }

    public Optional<DocumentEntity> getDocument(String id) {
        return documentRepository.findById(id);
    }

    public List<DocumentEntity> listDocuments() {
        return documentRepository.findAll();
    }

    public void updateDocument(DocumentEntity doc) {
        documentRepository.save(doc);
    }

    public void deleteDocument(String id) {
        Optional<DocumentEntity> opt = documentRepository.findById(id);
        if (opt.isPresent()) {
            DocumentEntity doc = opt.get();
            try {
                if (doc.getSourcePath() != null) {
                    Files.deleteIfExists(Path.of(doc.getSourcePath()));
                }
            } catch (Exception ignored) {}
            documentRepository.delete(id);
        }
    }
}
