package com.company.office.application;

import com.company.office.model.DocumentEntity;
import com.company.office.model.DocumentFieldEntity;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;

public class MappingService {
    private final ObjectMapper mapper = new ObjectMapper();

    public Map<String, Object> buildSourceContext(DocumentEntity document) {
        Map<String, Object> context = new LinkedHashMap<>();
        for (DocumentFieldEntity field : document.getFields()) context.put(field.getFieldName(), field.getFieldValue());
        context.put("raw_text", document.getRawText());
        context.put("document_type", document.getDocumentType());
        context.put("filename", document.getFilename());
        context.put("country_name", "CỘNG HÒA XÃ HỘI CHỦ NGHĨA VIỆT NAM");
        context.put("national_motto", "Độc lập - Tự do - Hạnh phúc");
        return context;
    }

    public Map<String, Object> resolve(DocumentEntity document, Map<String, Object> schema) {
        Map<String, Object> source = buildSourceContext(document);
        Map<String, Object> resolved = new LinkedHashMap<>();
        Object constants = schema.get("constants");
        if (constants instanceof Map<?, ?> map) map.forEach((k, v) -> resolved.put(String.valueOf(k), v));
        Object mappings = schema.get("mappings");
        if (mappings instanceof Map<?, ?> map) {
            map.forEach((target, path) -> {
                Object value = resolvePath(source, String.valueOf(path));
                if (value != null) resolved.put(String.valueOf(target), value);
            });
        }
        return resolved;
    }

    private Object resolvePath(Map<String, Object> source, String path) {
        String normalized = path.startsWith("fields.") ? path.substring(7) : path;
        Object current = source;
        for (String part : normalized.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) return null;
            current = map.get(part); if (current == null) return null;
        }
        return current;
    }
}
