package com.company.office.model;

public class DocumentFieldEntity {
    private Long id;
    private String documentId;
    private String fieldName;
    private String label;
    private String fieldValue;
    private String rawValue;
    private String dataType; // string, number, date, boolean
    private double confidence;
    private boolean validated;
    private String validationError;
    private int pageNumber = 1;
    private String sourceBboxJson;

    public DocumentFieldEntity() {}

    public DocumentFieldEntity(String documentId, String fieldName, String fieldValue, String dataType, double confidence) {
        this.documentId = documentId;
        this.fieldName = fieldName;
        this.label = fieldName;
        this.fieldValue = fieldValue;
        this.dataType = dataType;
        this.confidence = confidence;
        this.validated = true;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getDocumentId() { return documentId; }
    public void setDocumentId(String documentId) { this.documentId = documentId; }

    public String getFieldName() { return fieldName; }
    public void setFieldName(String fieldName) { this.fieldName = fieldName; }

    public String getLabel() { return label != null ? label : fieldName; }
    public void setLabel(String label) { this.label = label; }

    public String getFieldValue() { return fieldValue; }
    public void setFieldValue(String fieldValue) { this.fieldValue = fieldValue; }

    public String getRawValue() { return rawValue; }
    public void setRawValue(String rawValue) { this.rawValue = rawValue; }

    public String getDataType() { return dataType; }
    public void setDataType(String dataType) { this.dataType = dataType; }

    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }

    public boolean isValidated() { return validated; }
    public void setValidated(boolean validated) { this.validated = validated; }

    public String getValidationError() { return validationError; }
    public void setValidationError(String validationError) { this.validationError = validationError; }

    public int getPageNumber() { return pageNumber; }
    public void setPageNumber(int pageNumber) { this.pageNumber = pageNumber; }

    public String getSourceBboxJson() { return sourceBboxJson; }
    public void setSourceBboxJson(String sourceBboxJson) { this.sourceBboxJson = sourceBboxJson; }
}
