package com.company.office.application;

import com.company.office.model.TemplateEntity;
import com.company.office.model.TemplateSchemaEntity;
import com.company.office.repository.TemplateSchemaRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

public class TemplateSchemaService {
    private final TemplateSchemaRepository repository;
    private final ObjectMapper mapper = new ObjectMapper();
    public TemplateSchemaService(TemplateSchemaRepository repository) { this.repository = repository; }

    public String fingerprint(TemplateEntity template) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(Path.of(template.getFilePath()))) {
                byte[] buffer = new byte[8192]; int n;
                while ((n = in.read(buffer)) >= 0) digest.update(buffer, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) { throw new RuntimeException("Cannot fingerprint template " + template.getId(), e); }
    }

    public TemplateSchemaEntity ensureVersionedSchema(TemplateEntity template) {
        String fingerprint = fingerprint(template);
        return repository.findLatest(template.getId(), fingerprint).orElseGet(() -> {
            String json = template.getSchemaJson() == null || template.getSchemaJson().isBlank()
                    ? "{}" : template.getSchemaJson();
            String status = schemaHasUnmapped(json) ? "NEEDS_REVIEW" : "AUTO_MAPPED";
            TemplateSchemaEntity schema = new TemplateSchemaEntity();
            schema.setId("schema-" + UUID.randomUUID().toString().substring(0, 12));
            schema.setTemplateId(template.getId()); schema.setFingerprint(fingerprint);
            schema.setVersion(repository.nextVersion(template.getId())); schema.setStatus(status);
            schema.setSchemaJson(json); schema.setCreatedAt(LocalDateTime.now());
            repository.save(schema); return schema;
        });
    }

    public TemplateSchemaEntity approve(TemplateEntity template, String schemaJson) {
        validateJson(schemaJson);
        TemplateSchemaEntity current = ensureVersionedSchema(template);
        current.setSchemaJson(schemaJson); current.setStatus("APPROVED");
        current.setApprovedAt(LocalDateTime.now()); repository.save(current); return current;
    }

    public Optional<TemplateSchemaEntity> activeFor(TemplateEntity template) {
        String fingerprint = fingerprint(template);
        Optional<TemplateSchemaEntity> approved = repository.findApproved(template.getId(), fingerprint);
        if (approved.isPresent()) return approved;
        Optional<TemplateSchemaEntity> latest = repository.findLatest(template.getId(), fingerprint);
        return latest.filter(s -> "AUTO_MAPPED".equals(s.getStatus()));
    }

    public Map<String, Object> parse(TemplateSchemaEntity schema) {
        try { return mapper.readValue(schema.getSchemaJson(), new TypeReference<>() {}); }
        catch (Exception e) { throw new IllegalArgumentException("Invalid template schema JSON", e); }
    }

    private boolean schemaHasUnmapped(String json) {
        try {
            Object value = mapper.readValue(json, Map.class).get("unmapped");
            return value instanceof Collection<?> c && !c.isEmpty();
        } catch (Exception ignored) { return true; }
    }
    private void validateJson(String json) {
        try { mapper.readTree(json); } catch (Exception e) { throw new IllegalArgumentException("Schema JSON is invalid", e); }
    }
}
