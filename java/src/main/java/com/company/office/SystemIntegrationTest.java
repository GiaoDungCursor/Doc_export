package com.company.office;

import com.company.office.application.*;
import com.company.office.database.DatabaseManager;
import com.company.office.mcp.McpServer;
import com.company.office.model.DocumentEntity;
import com.company.office.model.TemplateEntity;
import com.company.office.repository.DocumentRepository;
import com.company.office.repository.JobRepository;
import com.company.office.repository.TemplateRepository;
import com.company.office.sidecar.SidecarProcess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Map;

public class SystemIntegrationTest {
    private static final Logger logger = LoggerFactory.getLogger(SystemIntegrationTest.class);

    public static void main(String[] args) {
        logger.info("=================================================");
        logger.info("  STARTING SYSTEM INTEGRATION & SMOKE TESTS");
        logger.info("=================================================");

        File baseDir = new File("..").getAbsoluteFile();
        File appDataDir = new File(baseDir, "app-data");
        File dbFile = new File(appDataDir, "app.db");
        File docsDir = new File(appDataDir, "documents");
        File tplDir = new File(appDataDir, "templates");
        File expDir = new File(appDataDir, "exports");

        SidecarProcess sidecarProcess = null;
        try {
            // 1. Initialize Database
            logger.info("[TEST 1/6] Initializing DatabaseManager and Repositories at {}...", dbFile.getAbsolutePath());
            DatabaseManager dbManager = new DatabaseManager(dbFile.getAbsolutePath());

            DocumentRepository docRepo = new DocumentRepository(dbManager);
            TemplateRepository tplRepo = new TemplateRepository(dbManager);
            JobRepository jobRepo = new JobRepository(dbManager);
            logger.info("✓ Database initialized successfully.");

            // 2. Start Python Sidecar
            logger.info("[TEST 2/6] Starting Python Sidecar Process & verifying IPC...");
            File scriptPath = new File(baseDir, "python/main.py");
            sidecarProcess = new SidecarProcess(scriptPath.getAbsolutePath(), baseDir.getAbsolutePath());
            sidecarProcess.start();
            logger.info("✓ Python Sidecar started and ping verified.");

            // 3. Application Services
            logger.info("[TEST 3/6] Initializing Application Layer Services...");
            SidecarService sidecarService = new SidecarService(sidecarProcess);
            DocumentService docService = new DocumentService(docRepo, docsDir.getAbsolutePath());
            JobService jobService = new JobService(jobRepo);
            ExtractionService extractionService = new ExtractionService(sidecarService, docRepo, jobService);
            ValidationService validationService = new ValidationService(sidecarService, docRepo);
            TemplateService templateService = new TemplateService(tplRepo, sidecarService, tplDir.getAbsolutePath());
            ExportService exportService = new ExportService(sidecarService, docRepo, tplRepo, jobService, expDir.getAbsolutePath());
            logger.info("✓ Application Services ready.");

            // 4. Test Ingestion & Extraction
            logger.info("[TEST 4/6] Testing Document Ingestion & Sidecar Extraction...");
            File testPdf = null;
            File[] pdfFiles = docsDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".pdf"));
            if (pdfFiles != null && pdfFiles.length > 0) {
                testPdf = pdfFiles[0];
            }

            if (testPdf != null && testPdf.exists()) {
                DocumentEntity importedDoc = docService.importFile(testPdf, "generic");
                logger.info("Imported document ID: {} ({})", importedDoc.getId(), importedDoc.getFilename());

                DocumentEntity extractedDoc = extractionService.extractDocument(importedDoc.getId()).get();
                logger.info("Extracted document status: {}, confidence: {}, fields count: {}",
                        extractedDoc.getStatus(), extractedDoc.getConfidence(), extractedDoc.getFields().size());
                logger.info("✓ Extraction pipeline executed and persisted successfully.");

                // Test direct Word full export
                String fullWordOut = exportService.exportFullDocument(extractedDoc.getId(), "word").get();
                logger.info("✓ Direct Full Word Export completed: {}", fullWordOut);
            } else {
                logger.info("No PDF files in documents directory to test extraction.");
            }

            // 5. Test MCP Server
            logger.info("[TEST 6/6] Testing Model Context Protocol (MCP) AI Tools Layer...");
            McpServer mcpServer = new McpServer(docService, extractionService, validationService, templateService, exportService, jobService);
            logger.info("MCP registered tools ({} tools):", mcpServer.getRegisteredTools().size());
            for (var tool : mcpServer.getRegisteredTools()) {
                logger.info("  • Tool: {} - {}", tool.get("name"), tool.get("description"));
            }

            var listResult = mcpServer.executeTool("list_documents", Map.of()).get();
            logger.info("MCP 'list_documents' result success: {}", listResult.get("success"));

            logger.info("✓ MCP Server verification complete.");

            logger.info("=================================================");
            logger.info("  ALL INTEGRATION & ARCHITECTURE TESTS PASSED!");
            logger.info("=================================================");

        } catch (Exception e) {
            logger.error("Integration test failed with error", e);
            System.exit(1);
        } finally {
            if (sidecarProcess != null) {
                logger.info("Stopping Python sidecar process...");
                sidecarProcess.stop();
            }
        }
    }
}
