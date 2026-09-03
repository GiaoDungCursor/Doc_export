package com.company.office.ui;

import com.company.office.application.*;
import com.company.office.database.DatabaseManager;
import com.company.office.mcp.McpServer;
import com.company.office.repository.*;
import com.company.office.sidecar.SidecarProcess;
import com.company.office.sidecar.HttpMcpProcess;

import java.io.File;

public class AppContext {
    private static AppContext instance;

    private final DatabaseManager databaseManager;
    private final DocumentRepository documentRepository;
    private final TemplateRepository templateRepository;
    private final TemplateSchemaRepository templateSchemaRepository;
    private final JobRepository jobRepository;
    private final SettingRepository settingRepository;

    private final SidecarProcess sidecarProcess;
    private final HttpMcpProcess httpMcpProcess;
    private final SidecarService sidecarService;
    private final DocumentService documentService;
    private final ExtractionService extractionService;
    private final ValidationService validationService;
    private final TemplateService templateService;
    private final TemplateSchemaService templateSchemaService;
    private final MappingService mappingService;
    private final PreflightService preflightService;
    private final ExportService exportService;
    private final JobService jobService;
    private final McpServer mcpServer;
    private final McpService mcpService;

    private static File resolvePath(String relativePath) {
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath != null && !appPath.isBlank()) {
            File packaged = new File(new File(appPath).getParentFile(), "app" + File.separator + relativePath);
            if (packaged.exists()) return packaged.getAbsoluteFile();
        }
        File fParent = new File("..", relativePath);
        if (new File(fParent, "templates").exists()) {
            return fParent.getAbsoluteFile();
        }
        File fCurrent = new File(relativePath);
        if (fCurrent.exists()) {
            return fCurrent.getAbsoluteFile();
        }
        return fParent.getAbsoluteFile();
    }

    private AppContext() {
        File appDataDir = resolvePath("app-data");
        File dbFile = new File(appDataDir, "app.db");
        File docsDir = new File(appDataDir, "documents");
        File tplDir = new File(appDataDir, "templates");
        File expDir = new File(appDataDir, "exports");

        this.databaseManager = new DatabaseManager(dbFile.getAbsolutePath());
        this.documentRepository = new DocumentRepository(databaseManager);
        this.templateRepository = new TemplateRepository(databaseManager);
        this.templateSchemaRepository = new TemplateSchemaRepository(databaseManager);
        this.jobRepository = new JobRepository(databaseManager);
        this.settingRepository = new SettingRepository(databaseManager);

        this.sidecarProcess = new SidecarProcess("python", "python/main.py");
        int savedMcpPort = settingRepository.get("mcp.http.port")
                .map(AppContext::parseMcpPort).orElse(HttpMcpProcess.DEFAULT_PORT);
        this.httpMcpProcess = new HttpMcpProcess(savedMcpPort);
        this.sidecarService = new SidecarService(sidecarProcess);

        this.documentService = new DocumentService(documentRepository, docsDir.getAbsolutePath());
        this.jobService = new JobService(jobRepository);
        this.extractionService = new ExtractionService(sidecarService, documentRepository, jobService);
        this.validationService = new ValidationService(sidecarService, documentRepository);
        this.templateService = new TemplateService(templateRepository, sidecarService, tplDir.getAbsolutePath());
        this.templateSchemaService = new TemplateSchemaService(templateSchemaRepository);
        this.mappingService = new MappingService();
        this.preflightService = new PreflightService(templateSchemaService, mappingService);
        this.exportService = new ExportService(sidecarService, documentRepository, templateRepository, jobService,
                expDir.getAbsolutePath(), templateSchemaService, preflightService);

        this.mcpServer = new McpServer(
                documentService,
                extractionService,
                validationService,
                templateService,
                exportService,
                jobService,
                templateSchemaService,
                preflightService
        );
        this.mcpService = new McpService(mcpServer);
    }

    public static synchronized AppContext getInstance() {
        if (instance == null) {
            instance = new AppContext();
        }
        return instance;
    }

    public void init() {
        // Start Python sidecar process
        sidecarProcess.start();
        httpMcpProcess.start();
        // Seed default templates if database is empty
        seedDefaults();
    }

    public void shutdown() {
        sidecarProcess.stop();
        httpMcpProcess.stop();
    }

    private void seedDefaults() {
        try {
            templateService.syncStorageCatalog().join();
            if (templateRepository.findAll().isEmpty()) {
                File appDataDir = resolvePath("app-data");
                File tplXlsx = new File(appDataDir, "templates/invoice_template.xlsx");
                if (tplXlsx.exists()) {
                    templateService.importTemplate(tplXlsx, "Mẫu Hóa đơn Tổng hợp (.xlsx)", "Mẫu biểu chuẩn xuất Excel cho hóa đơn");
                }
                File tplDocx = new File(appDataDir, "templates/report_template.docx");
                if (tplDocx.exists()) {
                    templateService.importTemplate(tplDocx, "Biên bản Báo cáo Chứng từ (.docx)", "Mẫu biểu Word xác nhận chứng từ");
                }
            }
        } catch (Exception ignored) {}
    }

    public DatabaseManager getDatabaseManager() { return databaseManager; }
    public DocumentRepository getDocumentRepository() { return documentRepository; }
    public TemplateRepository getTemplateRepository() { return templateRepository; }
    public TemplateSchemaRepository getTemplateSchemaRepository() { return templateSchemaRepository; }
    public JobRepository getJobRepository() { return jobRepository; }
    public SettingRepository getSettingRepository() { return settingRepository; }
    public SidecarProcess getSidecarProcess() { return sidecarProcess; }
    public HttpMcpProcess getHttpMcpProcess() { return httpMcpProcess; }
    public SidecarService getSidecarService() { return sidecarService; }
    public DocumentService getDocumentService() { return documentService; }
    public ExtractionService getExtractionService() { return extractionService; }
    public ValidationService getValidationService() { return validationService; }
    public TemplateService getTemplateService() { return templateService; }
    public TemplateSchemaService getTemplateSchemaService() { return templateSchemaService; }
    public MappingService getMappingService() { return mappingService; }
    public PreflightService getPreflightService() { return preflightService; }
    public ExportService getExportService() { return exportService; }
    public JobService getJobService() { return jobService; }
    public McpServer getMcpServer() { return mcpServer; }
    public McpService getMcpService() { return mcpService; }

    private static int parseMcpPort(String value) {
        try {
            int port = Integer.parseInt(value);
            return port >= 1024 && port <= 65535 ? port : HttpMcpProcess.DEFAULT_PORT;
        } catch (Exception ignored) {
            return HttpMcpProcess.DEFAULT_PORT;
        }
    }
}
