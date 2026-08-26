package com.company.office.model;

import java.time.LocalDateTime;

public class TemplateEntity {
    private String id;
    private String name;
    private String templateType; // excel, word
    private String filePath;
    private String description;
    private String schemaJson; // JSON holding field mapping rules & placeholders
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public TemplateEntity() {}

    public TemplateEntity(String id, String name, String templateType, String filePath, String description) {
        this.id = id;
        this.name = name;
        this.templateType = templateType;
        this.filePath = filePath;
        this.description = description;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getTemplateType() { return templateType; }
    public void setTemplateType(String templateType) { this.templateType = templateType; }

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getSchemaJson() { return schemaJson; }
    public void setSchemaJson(String schemaJson) { this.schemaJson = schemaJson; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
