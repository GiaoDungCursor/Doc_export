package com.company.office.application;

import com.company.office.model.CanonicalDocument;
import com.company.office.model.DocumentEntity;
import com.company.office.model.DocumentFieldEntity;
import com.company.office.model.JobEntity;
import com.company.office.model.TemplateEntity;
import com.company.office.model.PreflightResult;
import com.company.office.model.MappingIssue;
import com.company.office.repository.DocumentRepository;
import com.company.office.repository.TemplateRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class ExportService {
    private static final Logger logger = LoggerFactory.getLogger(ExportService.class);

    private final SidecarService sidecarService;
    private final DocumentRepository documentRepository;
    private final TemplateRepository templateRepository;
    private final JobService jobService;
    private final String exportsDir;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TemplateSchemaService templateSchemaService;
    private final PreflightService preflightService;

    public ExportService(SidecarService sidecarService, DocumentRepository documentRepository,
                         TemplateRepository templateRepository, JobService jobService) {
        this(sidecarService, documentRepository, templateRepository, jobService, "app-data/exports");
    }

    public ExportService(SidecarService sidecarService, DocumentRepository documentRepository,
                         TemplateRepository templateRepository, JobService jobService, String exportsDir) {
        this.sidecarService = sidecarService;
        this.documentRepository = documentRepository;
        this.templateRepository = templateRepository;
        this.jobService = jobService;
        this.exportsDir = exportsDir;
        this.templateSchemaService = null;
        this.preflightService = null;
        new File(this.exportsDir).mkdirs();
    }

    public ExportService(SidecarService sidecarService, DocumentRepository documentRepository,
                         TemplateRepository templateRepository, JobService jobService, String exportsDir,
                         TemplateSchemaService templateSchemaService, PreflightService preflightService) {
        this.sidecarService = sidecarService; this.documentRepository = documentRepository;
        this.templateRepository = templateRepository; this.jobService = jobService;
        this.exportsDir = exportsDir; this.templateSchemaService = templateSchemaService;
        this.preflightService = preflightService; new File(this.exportsDir).mkdirs();
    }

    public CompletableFuture<String> exportDocument(String documentId, String templateId) {
        return exportDocument(documentId, templateId, null);
    }

    public CompletableFuture<String> exportDocument(String documentId, String templateId, String customTargetPath) {
        DocumentEntity doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + documentId));

        TemplateEntity tpl = templateRepository.findById(templateId)
                .orElseThrow(() -> new IllegalArgumentException("Template not found: " + templateId));

        if (preflightService != null) {
            PreflightResult preflight = preflightService.check(doc, tpl);
            if (!preflight.isCanRender()) {
                String errors = preflight.getIssues().stream()
                        .filter(i -> "ERROR".equals(i.severity())).map(MappingIssue::message)
                        .reduce((a, b) -> a + "; " + b).orElse("Preflight failed");
                throw new IllegalStateException(errors);
            }
        }

        JobEntity job = jobService.createJob(documentId, "EXPORT");
        jobService.updateProgress(job.getId(), "RUNNING", 0.2, null, null);

        String ext = "excel".equalsIgnoreCase(tpl.getTemplateType()) ? "xlsx" : "docx";
        String outPath;
        if (customTargetPath != null && !customTargetPath.trim().isEmpty()) {
            outPath = customTargetPath;
        } else {
            String outFilename = "export_" + doc.getId() + "_" + UUID.randomUUID().toString().substring(0, 6) + "." + ext;
            outPath = Path.of(exportsDir, outFilename).toAbsolutePath().toString();
        }

        CanonicalDocument canonicalDoc = buildCanonicalDocument(doc);

        Map<String, Object> schema = null;
        if (templateSchemaService != null) {
            schema = templateSchemaService.activeFor(tpl).map(templateSchemaService::parse).orElse(null);
        } else if (tpl.getSchemaJson() != null && !tpl.getSchemaJson().trim().isEmpty()) {
            try {
                schema = objectMapper.readValue(tpl.getSchemaJson(), new TypeReference<Map<String, Object>>() {});
            } catch (Exception ignored) {}
        }

        return sidecarService.exportDocumentAsync(tpl.getFilePath(), outPath, canonicalDoc, schema)
                .thenApply(outputPath -> {
                    jobService.updateProgress(job.getId(), "COMPLETED", 1.0, "{\"output_path\": \"" + outputPath.replace("\\", "\\\\") + "\"}", null);
                    doc.setStatus("EXPORTED");
                    documentRepository.save(doc);
                    return outputPath;
                })
                .exceptionally(ex -> {
                    logger.error("Export failed for document {} with template {}", documentId, templateId, ex);
                    jobService.updateProgress(job.getId(), "FAILED", 1.0, null, ex.getMessage());
                    throw new RuntimeException("Export failed: " + ex.getMessage(), ex);
                });
    }

    public CompletableFuture<String> exportFullDocument(String documentId, String format) {
        return exportFullDocument(documentId, format, null);
    }

    public CompletableFuture<String> exportFullDocument(String documentId, String format, String customTargetPath) {
        DocumentEntity doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + documentId));

        JobEntity job = jobService.createJob(documentId, "FULL_EXPORT");
        jobService.updateProgress(job.getId(), "RUNNING", 0.2, null, null);

        boolean isWord = "word".equalsIgnoreCase(format) || "docx".equalsIgnoreCase(format);
        String ext = isWord ? "docx" : "xlsx";
        String mode = isWord ? "full_word" : "full_excel";

        String outPath;
        if (customTargetPath != null && !customTargetPath.trim().isEmpty()) {
            outPath = customTargetPath;
        } else {
            String outFilename = "full_ocr_" + doc.getId() + "_" + UUID.randomUUID().toString().substring(0, 6) + "." + ext;
            outPath = Path.of(exportsDir, outFilename).toAbsolutePath().toString();
        }

        CanonicalDocument canonicalDoc = buildCanonicalDocument(doc);

        return sidecarService.exportDirectAsync(mode, outPath, canonicalDoc)
                .thenApply(outputPath -> {
                    jobService.updateProgress(job.getId(), "COMPLETED", 1.0, "{\"output_path\": \"" + outputPath.replace("\\", "\\\\") + "\"}", null);
                    doc.setStatus("EXPORTED");
                    documentRepository.save(doc);
                    return outputPath;
                })
                .exceptionally(ex -> {
                    logger.error("Direct full export failed for document {}", documentId, ex);
                    jobService.updateProgress(job.getId(), "FAILED", 1.0, null, ex.getMessage());
                    throw new RuntimeException("Direct export failed: " + ex.getMessage(), ex);
                });
    }

    private CanonicalDocument buildCanonicalDocument(DocumentEntity doc) {
        CanonicalDocument canonicalDoc = new CanonicalDocument();
        canonicalDoc.setId(doc.getId());
        canonicalDoc.setDocumentType(doc.getDocumentType());
        canonicalDoc.setRawText(doc.getRawText());

        Map<String, Object> fieldsMap = new HashMap<>();
        for (DocumentFieldEntity f : doc.getFields()) {
            fieldsMap.put(f.getFieldName(), f.getFieldValue());
        }
        canonicalDoc.setFields(fieldsMap);

        Map<String, Object> fieldDetails = new HashMap<>();
        for (DocumentFieldEntity field : doc.getFields()) {
            Map<String, Object> detail = new HashMap<>();
            detail.put("value", field.getFieldValue());
            detail.put("label", field.getLabel());
            detail.put("data_type", field.getDataType());
            detail.put("confidence", field.getConfidence());
            detail.put("validated", field.isValidated());
            detail.put("validation_error", field.getValidationError());
            fieldDetails.put(field.getFieldName(), detail);
        }
        canonicalDoc.setFieldDetails(fieldDetails);

        Map<String, Object> metaMap = new HashMap<>();
        metaMap.put("filename", doc.getFilename());
        metaMap.put("created_at", doc.getCreatedAt() != null ? doc.getCreatedAt().toString() : "");
        canonicalDoc.setMetadata(metaMap);

        if (doc.getPagesJson() != null && !doc.getPagesJson().trim().isEmpty()) {
            try {
                List<Map<String, Object>> pages = objectMapper.readValue(doc.getPagesJson(), new TypeReference<>() {});
                canonicalDoc.setPages(pages);
            } catch (Exception e) {
                logger.warn("Could not deserialize pagesJson for export", e);
            }
        }
        if (doc.getTablesJson() != null && !doc.getTablesJson().trim().isEmpty()) {
            try {
                List<Map<String, Object>> tables = objectMapper.readValue(doc.getTablesJson(), new TypeReference<>() {});
                canonicalDoc.setTables(tables);
            } catch (Exception e) {
                logger.warn("Could not deserialize tablesJson for export", e);
            }
        }

        return canonicalDoc;
    }
}
