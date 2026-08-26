package com.company.office.model;

import java.time.LocalDateTime;

public class JobEntity {
    private String id;
    private String documentId;
    private String jobType; // EXTRACT, VALIDATE, EXPORT
    private String status;  // PENDING, RUNNING, COMPLETED, FAILED
    private double progress;
    private String errorMessage;
    private String resultData; // JSON data
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;

    public JobEntity() {}

    public JobEntity(String id, String documentId, String jobType) {
        this.id = id;
        this.documentId = documentId;
        this.jobType = jobType;
        this.status = "PENDING";
        this.progress = 0.0;
        this.createdAt = LocalDateTime.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getDocumentId() { return documentId; }
    public void setDocumentId(String documentId) { this.documentId = documentId; }

    public String getJobType() { return jobType; }
    public void setJobType(String jobType) { this.jobType = jobType; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public double getProgress() { return progress; }
    public void setProgress(double progress) { this.progress = progress; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public String getResultData() { return resultData; }
    public void setResultData(String resultData) { this.resultData = resultData; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
}
