package com.company.office.application;

import com.company.office.model.*;

import java.io.File;
import java.util.*;

public class PreflightService {
    private final TemplateSchemaService schemaService;
    private final MappingService mappingService;
    public PreflightService(TemplateSchemaService schemaService, MappingService mappingService) {
        this.schemaService = schemaService; this.mappingService = mappingService;
    }

    public PreflightResult check(DocumentEntity document, TemplateEntity template) {
        PreflightResult result = new PreflightResult();
        List<MappingIssue> issues = new ArrayList<>();
        if (!new File(template.getFilePath()).isFile())
            issues.add(new MappingIssue("ERROR", "TEMPLATE_MISSING", null, "Không tìm thấy file template"));

        Optional<TemplateSchemaEntity> schemaOpt = schemaService.activeFor(template);
        if (schemaOpt.isEmpty()) {
            TemplateSchemaEntity latest = schemaService.ensureVersionedSchema(template);
            issues.add(new MappingIssue("ERROR", "SCHEMA_NOT_APPROVED", null,
                    "Schema đang ở trạng thái " + latest.getStatus() + "; cần kiểm tra và phê duyệt"));
        } else {
            Map<String, Object> schema = schemaService.parse(schemaOpt.get());
            result.setResolvedContext(mappingService.resolve(document, schema));
            Object unmapped = schema.get("unmapped");
            if (unmapped instanceof Collection<?> values && !values.isEmpty())
                issues.add(new MappingIssue("ERROR", "UNMAPPED_FIELDS", null, "Còn placeholder chưa được mapping: " + values));
            Object placeholders = schema.get("placeholders");
            if (placeholders instanceof Collection<?> values) {
                for (Object target : values) {
                    if (!result.getResolvedContext().containsKey(String.valueOf(target)))
                        issues.add(new MappingIssue("WARNING", "EMPTY_VALUE", String.valueOf(target), "Không có dữ liệu để điền"));
                }
            }
        }
        result.setIssues(issues);
        result.setCanRender(issues.stream().noneMatch(i -> "ERROR".equals(i.severity())));
        return result;
    }
}
