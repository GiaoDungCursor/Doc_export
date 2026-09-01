package com.company.office.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class DocumentEntity {
    private String id;
    private String documentType;
    private String filename;
    private String sourcePath;
    private String fileType;
    private long fileSize;
    private int pageCount;
    private String status; // NEW, EXTRACTING, EXTRACTED, VALIDATED, NEEDS_REVIEW, EXPORTED, ERROR
    private double confidence;
    private String rawText;
    private String pagesJson;
    private String tablesJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private List<DocumentFieldEntity> fields = new ArrayList<>();

    public DocumentEntity() {}

    public DocumentEntity(String id, String documentType, String filename, String sourcePath, String fileType, long fileSize) {
        this.id = id;
        this.documentType = documentType;
        this.filename = filename;
        this.sourcePath = sourcePath;
        this.fileType = fileType;
        this.fileSize = fileSize;
        this.status = "NEW";
        this.confidence = 1.0;
        this.pageCount = 1;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getDocumentType() { return documentType; }
    public void setDocumentType(String documentType) { this.documentType = documentType; }

    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }

    public String getSourcePath() { return sourcePath; }
    public void setSourcePath(String sourcePath) { this.sourcePath = sourcePath; }

    public String getFileType() { return fileType; }
    public void setFileType(String fileType) { this.fileType = fileType; }

    public long getFileSize() { return fileSize; }
    public void setFileSize(long fileSize) { this.fileSize = fileSize; }

    public int getPageCount() { return pageCount; }
    public void setPageCount(int pageCount) { this.pageCount = pageCount; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }

    public String getRawText() { return rawText; }
    public void setRawText(String rawText) { this.rawText = rawText; }

    public String getPagesJson() { return pagesJson; }
    public void setPagesJson(String pagesJson) { this.pagesJson = pagesJson; }
    public String getTablesJson() { return tablesJson; }
    public void setTablesJson(String tablesJson) { this.tablesJson = tablesJson; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public List<DocumentFieldEntity> getFields() { return fields; }
    public void setFields(List<DocumentFieldEntity> fields) { this.fields = fields; }
}
