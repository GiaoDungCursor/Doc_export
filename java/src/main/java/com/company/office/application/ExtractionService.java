package com.company.office.application;

import com.company.office.model.CanonicalDocument;
import com.company.office.model.DocumentEntity;
import com.company.office.model.DocumentFieldEntity;
import com.company.office.model.JobEntity;
import com.company.office.repository.DocumentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class ExtractionService {
    private static final Logger logger = LoggerFactory.getLogger(ExtractionService.class);

    private final SidecarService sidecarService;
    private final DocumentRepository documentRepository;
    private final JobService jobService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ExtractionService(SidecarService sidecarService, DocumentRepository documentRepository, JobService jobService) {
        this.sidecarService = sidecarService;
        this.documentRepository = documentRepository;
        this.jobService = jobService;
    }

    public CompletableFuture<DocumentEntity> extractDocument(String documentId) {
        DocumentEntity doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + documentId));

        JobEntity job = jobService.createJob(documentId, "EXTRACT");
        jobService.updateProgress(job.getId(), "RUNNING", 0.1, null, null);

        doc.setStatus("EXTRACTING");
        documentRepository.save(doc);

        return sidecarService.extractDocumentAsync(doc.getSourcePath(), doc.getDocumentType())
                .thenApply(canonicalDoc -> {
                    try {
                        doc.setDocumentType(canonicalDoc.getDocumentType());
                        doc.setConfidence(canonicalDoc.getConfidence());
                        doc.setStatus(canonicalDoc.getStatus());
                        doc.setRawText(canonicalDoc.getRawText());

                        // Save pages json
                        if (canonicalDoc.getPages() != null && !canonicalDoc.getPages().isEmpty()) {
                            doc.setPagesJson(objectMapper.writeValueAsString(canonicalDoc.getPages()));
                            doc.setPageCount(canonicalDoc.getPages().size());
                        }
                        if (canonicalDoc.getTables() != null) {
                            doc.setTablesJson(objectMapper.writeValueAsString(canonicalDoc.getTables()));
                        }

                        List<DocumentFieldEntity> fieldEntities = new ArrayList<>();
                        Map<String, Object> fieldDetails = canonicalDoc.getFieldDetails();

                        if (fieldDetails != null && !fieldDetails.isEmpty()) {
                            for (Map.Entry<String, Object> entry : fieldDetails.entrySet()) {
                                if (entry.getValue() instanceof Map<?, ?> map) {
                                    DocumentFieldEntity f = new DocumentFieldEntity();
                                    f.setDocumentId(doc.getId());
                                    f.setFieldName(entry.getKey());
                                    f.setLabel(map.get("label") != null ? String.valueOf(map.get("label")) : entry.getKey());
                                    f.setFieldValue(map.get("value") != null ? String.valueOf(map.get("value")) : null);
                                    f.setRawValue(map.get("raw_value") != null ? String.valueOf(map.get("raw_value")) : null);
                                    f.setDataType(map.get("data_type") != null ? String.valueOf(map.get("data_type")) : "string");
                                    Object conf = map.get("confidence");
                                    f.setConfidence(conf instanceof Number ? ((Number) conf).doubleValue() : 1.0);
                                    f.setValidated(Boolean.TRUE.equals(map.get("validated")));
                                    f.setValidationError((String) map.get("validation_error"));

                                    if (map.get("page_number") instanceof Number num) {
                                        f.setPageNumber(num.intValue());
                                    }
                                    if (map.get("source_bbox") != null) {
                                        f.setSourceBboxJson(objectMapper.writeValueAsString(map.get("source_bbox")));
                                    }
                                    fieldEntities.add(f);
                                }
                            }
                        } else if (canonicalDoc.getFields() != null) {
                            for (Map.Entry<String, Object> entry : canonicalDoc.getFields().entrySet()) {
                                DocumentFieldEntity f = new DocumentFieldEntity();
                                f.setDocumentId(doc.getId());
                                f.setFieldName(entry.getKey());
                                f.setLabel(entry.getKey());
                                f.setFieldValue(entry.getValue() != null ? String.valueOf(entry.getValue()) : null);
                                f.setDataType("string");
                                f.setConfidence(1.0);
                                f.setValidated(true);
                                fieldEntities.add(f);
                            }
                        }

                        doc.setFields(fieldEntities);
                        documentRepository.save(doc);

                        String resultJson = objectMapper.writeValueAsString(canonicalDoc);
                        jobService.updateProgress(job.getId(), "COMPLETED", 1.0, resultJson, null);

                        return doc;
                    } catch (Exception e) {
                        logger.error("Error processing extraction result", e);
                        jobService.updateProgress(job.getId(), "FAILED", 1.0, null, e.getMessage());
                        doc.setStatus("ERROR");
                        documentRepository.save(doc);
                        throw new RuntimeException("Error processing extraction response", e);
                    }
                })
                .exceptionally(ex -> {
                    logger.error("Extraction failed for document: {}", documentId, ex);
                    jobService.updateProgress(job.getId(), "FAILED", 1.0, null, ex.getMessage());
                    doc.setStatus("ERROR");
                    documentRepository.save(doc);
                    throw new RuntimeException("Extraction pipeline error: " + ex.getMessage(), ex);
                });
    }
}
