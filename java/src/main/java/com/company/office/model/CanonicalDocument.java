package com.company.office.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class CanonicalDocument {
    @JsonProperty("id")
    private String id;

    @JsonProperty("document_type")
    private String documentType = "generic";

    @JsonProperty("metadata")
    private Map<String, Object> metadata = new HashMap<>();

    @JsonProperty("pages")
    private List<Map<String, Object>> pages = new ArrayList<>();

    @JsonProperty("fields")
    private Map<String, Object> fields = new HashMap<>();

    @JsonProperty("field_details")
    private Map<String, Object> fieldDetails = new HashMap<>();

    @JsonProperty("tables")
    private List<Map<String, Object>> tables = new ArrayList<>();

    @JsonProperty("confidence")
    private double confidence = 1.0;

    @JsonProperty("status")
    private String status = "NEW";

    @JsonProperty("raw_text")
    private String rawText;

    public CanonicalDocument() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getDocumentType() { return documentType != null ? documentType : "generic"; }
    public void setDocumentType(String documentType) { this.documentType = documentType; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

    public List<Map<String, Object>> getPages() { return pages; }
    public void setPages(List<Map<String, Object>> pages) { this.pages = pages; }

    public Map<String, Object> getFields() { return fields; }
    public void setFields(Map<String, Object> fields) { this.fields = fields; }

    public Map<String, Object> getFieldDetails() { return fieldDetails; }
    public void setFieldDetails(Map<String, Object> fieldDetails) { this.fieldDetails = fieldDetails; }

    public List<Map<String, Object>> getTables() { return tables; }
    public void setTables(List<Map<String, Object>> tables) { this.tables = tables; }

    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getRawText() { return rawText; }
    public void setRawText(String rawText) { this.rawText = rawText; }
}
