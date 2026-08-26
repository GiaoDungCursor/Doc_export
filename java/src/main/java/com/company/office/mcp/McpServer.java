package com.company.office.mcp;

import com.company.office.application.*;
import com.company.office.model.DocumentEntity;
import com.company.office.model.JobEntity;
import com.company.office.model.TemplateEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public class McpServer {
    private static final Logger logger = LoggerFactory.getLogger(McpServer.class);

    private final DocumentService documentService;
    private final ExtractionService extractionService;
    private final ValidationService validationService;
    private final TemplateService templateService;
    private final ExportService exportService;
    private final JobService jobService;
    private final TemplateSchemaService templateSchemaService;
    private final PreflightService preflightService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public McpServer(DocumentService documentService,
                     ExtractionService extractionService,
                     ValidationService validationService,
                     TemplateService templateService,
                     ExportService exportService,
                     JobService jobService) {
        this.documentService = documentService;
        this.extractionService = extractionService;
        this.validationService = validationService;
        this.templateService = templateService;
        this.exportService = exportService;
        this.jobService = jobService;
        this.templateSchemaService = null;
        this.preflightService = null;
    }

    public McpServer(DocumentService documentService, ExtractionService extractionService,
                     ValidationService validationService, TemplateService templateService,
                     ExportService exportService, JobService jobService,
                     TemplateSchemaService templateSchemaService, PreflightService preflightService) {
        this.documentService = documentService; this.extractionService = extractionService;
        this.validationService = validationService; this.templateService = templateService;
        this.exportService = exportService; this.jobService = jobService;
        this.templateSchemaService = templateSchemaService; this.preflightService = preflightService;
    }

    public List<Map<String, Object>> getRegisteredTools() {
        List<Map<String, Object>> tools = new ArrayList<>();
        tools.add(createToolMeta("list_documents", "List all documents and their processing status in the system", Map.of()));
        tools.add(createToolMeta("read_document", "Get detailed metadata, structured fields, and raw text of a document", Map.of("document_id", "string (required)")));
        tools.add(createToolMeta("extract_document", "Trigger OCR and field extraction pipeline on a document", Map.of("document_id", "string (required)")));
        tools.add(createToolMeta("validate_document", "Validate extracted document data against schema or business rules", Map.of("document_id", "string (required)")));
        tools.add(createToolMeta("list_templates", "List available Excel and Word output templates", Map.of()));
        tools.add(createToolMeta("inspect_template", "Inspect placeholders and variable schema of a template", Map.of("template_id", "string (required)")));
        tools.add(createToolMeta("export_document", "Export structured document data into an Excel or Word template", Map.of("document_id", "string (required)", "template_id", "string (required)")));
        tools.add(createToolMeta("get_job_status", "Check the status and progress of an asynchronous job", Map.of("job_id", "string (required)")));
        tools.add(createToolMeta("approve_template_schema", "Approve a reviewed mapping schema version",
                Map.of("template_id", "string (required)", "schema_json", "string (required)")));
        tools.add(createToolMeta("preflight_export", "Validate fingerprint, schema and mappings before export",
                Map.of("document_id", "string (required)", "template_id", "string (required)")));
        return tools;
    }

    public CompletableFuture<Map<String, Object>> executeTool(String toolName, Map<String, Object> arguments) {
        logger.info("Executing MCP Tool: {} with args: {}", toolName, arguments);
        Map<String, Object> response = new HashMap<>();

        try {
            switch (toolName) {
                case "list_documents": {
                    List<DocumentEntity> docs = documentService.listDocuments();
                    response.put("success", true);
                    response.put("documents", docs);
                    return CompletableFuture.completedFuture(response);
                }
                case "read_document": {
                    String docId = (String) arguments.get("document_id");
                    Optional<DocumentEntity> opt = documentService.getDocument(docId);
                    if (opt.isPresent()) {
                        response.put("success", true);
                        response.put("document", opt.get());
                    } else {
                        response.put("success", false);
                        response.put("error", "Document not found: " + docId);
                    }
                    return CompletableFuture.completedFuture(response);
                }
                case "extract_document": {
                    String docId = (String) arguments.get("document_id");
                    return extractionService.extractDocument(docId)
                            .thenApply(doc -> {
                                response.put("success", true);
                                response.put("document", doc);
                                return response;
                            });
                }
                case "validate_document": {
                    String docId = (String) arguments.get("document_id");
                    String schema = (String) arguments.get("schema");
                    return validationService.validateDocument(docId, schema)
                            .thenApply(doc -> {
                                response.put("success", true);
                                response.put("document", doc);
                                return response;
                            });
                }
                case "list_templates": {
                    List<TemplateEntity> list = templateService.listTemplates();
                    response.put("success", true);
                    response.put("templates", list);
                    return CompletableFuture.completedFuture(response);
                }
                case "inspect_template": {
                    String tplId = (String) arguments.get("template_id");
                    Optional<TemplateEntity> opt = templateService.getTemplate(tplId);
                    if (opt.isPresent()) {
                        response.put("success", true);
                        response.put("template", opt.get());
                    } else {
                        response.put("success", false);
                        response.put("error", "Template not found: " + tplId);
                    }
                    return CompletableFuture.completedFuture(response);
                }
                case "export_document": {
                    String docId = (String) arguments.get("document_id");
                    String tplId = (String) arguments.get("template_id");
                    return exportService.exportDocument(docId, tplId)
                            .thenApply(outPath -> {
                                response.put("success", true);
                                response.put("output_path", outPath);
                                return response;
                            });
                }
                case "get_job_status": {
                    String jobId = (String) arguments.get("job_id");
                    Optional<JobEntity> opt = jobService.getJob(jobId);
                    if (opt.isPresent()) {
                        response.put("success", true);
                        response.put("job", opt.get());
                    } else {
                        response.put("success", false);
                        response.put("error", "Job not found: " + jobId);
                    }
                    return CompletableFuture.completedFuture(response);
                }
                case "approve_template_schema": {
                    if (templateSchemaService == null) throw new IllegalStateException("Template schema service unavailable");
                    String templateId = (String) arguments.get("template_id");
                    String schemaJson = (String) arguments.get("schema_json");
                    TemplateEntity template = templateService.getTemplate(templateId)
                            .orElseThrow(() -> new IllegalArgumentException("Template not found: " + templateId));
                    response.put("success", true);
                    response.put("schema", templateSchemaService.approve(template, schemaJson));
                    return CompletableFuture.completedFuture(response);
                }
                case "preflight_export": {
                    if (preflightService == null) throw new IllegalStateException("Preflight service unavailable");
                    String documentId = (String) arguments.get("document_id");
                    String templateId = (String) arguments.get("template_id");
                    DocumentEntity document = documentService.getDocument(documentId)
                            .orElseThrow(() -> new IllegalArgumentException("Document not found: " + documentId));
                    TemplateEntity template = templateService.getTemplate(templateId)
                            .orElseThrow(() -> new IllegalArgumentException("Template not found: " + templateId));
                    response.put("success", true);
                    response.put("preflight", preflightService.check(document, template));
                    return CompletableFuture.completedFuture(response);
                }
                default:
                    response.put("success", false);
                    response.put("error", "Unknown MCP tool: " + toolName);
                    return CompletableFuture.completedFuture(response);
            }
        } catch (Exception e) {
            logger.error("Error executing MCP tool: {}", toolName, e);
            response.put("success", false);
            response.put("error", e.getMessage());
            return CompletableFuture.completedFuture(response);
        }
    }

    private Map<String, Object> createToolMeta(String name, String description, Map<String, String> parameters) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("name", name);
        meta.put("description", description);
        meta.put("parameters", parameters);
        return meta;
    }
}
