package com.company.office.application;

import com.company.office.model.CanonicalDocument;
import com.company.office.sidecar.IpcClient;
import com.company.office.sidecar.IpcRequest;
import com.company.office.sidecar.IpcResponse;
import com.company.office.sidecar.SidecarProcess;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class SidecarService {
    private final SidecarProcess sidecarProcess;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SidecarService(SidecarProcess sidecarProcess) {
        this.sidecarProcess = sidecarProcess;
    }

    public CompletableFuture<CanonicalDocument> extractDocumentAsync(String filePath, String documentType) {
        Map<String, Object> input = new HashMap<>();
        input.put("path", filePath);
        if (documentType != null) {
            input.put("document_type", documentType);
        }

        IpcRequest request = new IpcRequest("extract_document", input);
        return sidecarProcess.getIpcClient().sendAsync(request).thenApply(response -> {
            if (!response.isSuccess()) {
                throw new RuntimeException("Extraction failed: " + response.getError());
            }
            return objectMapper.convertValue(response.getData(), CanonicalDocument.class);
        });
    }

    public CompletableFuture<CanonicalDocument> validateDocumentAsync(CanonicalDocument doc, Map<String, Object> schema) {
        Map<String, Object> input = new HashMap<>();
        input.put("document", doc);
        if (schema != null) {
            input.put("schema", schema);
        }

        IpcRequest request = new IpcRequest("validate_document", input);
        return sidecarProcess.getIpcClient().sendAsync(request).thenApply(response -> {
            if (!response.isSuccess()) {
                throw new RuntimeException("Validation failed: " + response.getError());
            }
            return objectMapper.convertValue(response.getData(), CanonicalDocument.class);
        });
    }

    public CompletableFuture<Map<String, Object>> inspectTemplateAsync(String templatePath) {
        Map<String, Object> input = new HashMap<>();
        input.put("path", templatePath);

        IpcRequest request = new IpcRequest("inspect_template", input);
        return sidecarProcess.getIpcClient().sendAsync(request).thenApply(response -> {
            if (!response.isSuccess()) {
                throw new RuntimeException("Template inspection failed: " + response.getError());
            }
            return response.getData();
        });
    }

    public CompletableFuture<String> exportDocumentAsync(String templatePath, String outputPath, CanonicalDocument doc, Map<String, Object> schema) {
        Map<String, Object> input = new HashMap<>();
        input.put("template_path", templatePath);
        input.put("output_path", outputPath);
        input.put("document", doc);
        if (schema != null) {
            input.put("schema", schema);
        }

        IpcRequest request = new IpcRequest("export_document", input);
        return sidecarProcess.getIpcClient().sendAsync(request).thenApply(response -> {
            if (!response.isSuccess()) {
                throw new RuntimeException("Export failed: " + response.getError());
            }
            Map<String, Object> data = response.getData();
            return (String) data.get("output_path");
        });
    }

    public CompletableFuture<String> exportDirectAsync(String mode, String outputPath, CanonicalDocument doc) {
        Map<String, Object> input = new HashMap<>();
        input.put("mode", mode);
        input.put("output_path", outputPath);
        input.put("document", doc);

        IpcRequest request = new IpcRequest("export_document", input);
        return sidecarProcess.getIpcClient().sendAsync(request).thenApply(response -> {
            if (!response.isSuccess()) {
                throw new RuntimeException("Direct export failed: " + response.getError());
            }
            Map<String, Object> data = response.getData();
            return (String) data.get("output_path");
        });
    }
}
