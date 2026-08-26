package com.company.office.application;

import com.company.office.model.CanonicalDocument;
import com.company.office.model.DocumentEntity;
import com.company.office.model.DocumentFieldEntity;
import com.company.office.repository.DocumentRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class ValidationService {
    private final SidecarService sidecarService;
    private final DocumentRepository documentRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ValidationService(SidecarService sidecarService, DocumentRepository documentRepository) {
        this.sidecarService = sidecarService;
        this.documentRepository = documentRepository;
    }

    public CompletableFuture<DocumentEntity> validateDocument(String documentId, String schemaJson) {
        DocumentEntity doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + documentId));

        CanonicalDocument canonicalDoc = new CanonicalDocument();
        canonicalDoc.setId(doc.getId());
        canonicalDoc.setDocumentType(doc.getDocumentType());
        canonicalDoc.setStatus(doc.getStatus());
        canonicalDoc.setConfidence(doc.getConfidence());

        Map<String, Object> fieldsMap = new HashMap<>();
        for (DocumentFieldEntity f : doc.getFields()) {
            fieldsMap.put(f.getFieldName(), f.getFieldValue());
        }
        canonicalDoc.setFields(fieldsMap);

        Map<String, Object> schema = null;
        if (schemaJson != null && !schemaJson.trim().isEmpty()) {
            try {
                schema = objectMapper.readValue(schemaJson, new TypeReference<Map<String, Object>>() {});
            } catch (Exception ignored) {}
        }

        return sidecarService.validateDocumentAsync(canonicalDoc, schema)
                .thenApply(validatedDoc -> {
                    doc.setStatus(validatedDoc.getStatus());
                    doc.setConfidence(validatedDoc.getConfidence());
                    documentRepository.save(doc);
                    return doc;
                });
    }

    public void updateFieldManualReview(String documentId, String fieldName, String newValue) {
        DocumentEntity doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + documentId));

        boolean found = false;
        for (DocumentFieldEntity f : doc.getFields()) {
            if (f.getFieldName().equalsIgnoreCase(fieldName)) {
                f.setFieldValue(newValue);
                f.setValidated(true);
                f.setValidationError(null);
                found = true;
                break;
            }
        }

        if (!found) {
            DocumentFieldEntity newField = new DocumentFieldEntity(documentId, fieldName, newValue, "string", 1.0);
            newField.setValidated(true);
            doc.getFields().add(newField);
        }

        // Check if all fields are now validated
        boolean allValid = doc.getFields().stream().allMatch(DocumentFieldEntity::isValidated);
        if (allValid) {
            doc.setStatus("VALIDATED");
        }

        documentRepository.save(doc);
    }
}
