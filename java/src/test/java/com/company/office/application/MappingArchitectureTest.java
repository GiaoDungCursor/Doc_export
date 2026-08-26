package com.company.office.application;

import com.company.office.database.DatabaseManager;
import com.company.office.model.*;
import com.company.office.repository.TemplateSchemaRepository;
import com.company.office.repository.TemplateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MappingArchitectureTest {
    @TempDir Path temp;

    @Test
    void versionedSchemaIsResolvedByFingerprintAndMappingIsDeterministic() throws Exception {
        DatabaseManager db = new DatabaseManager(temp.resolve("test.db").toString());
        TemplateSchemaRepository repository = new TemplateSchemaRepository(db);
        TemplateSchemaService schemas = new TemplateSchemaService(repository);
        Path file = temp.resolve("cong_van.docx");
        Files.writeString(file, "template-v1");

        TemplateEntity template = new TemplateEntity("tpl-test", "Công văn", "word", file.toString(), "test");
        template.setSchemaJson("""
            {"placeholders":["document_number","recipient"],
             "mappings":{"document_number":"document_number","recipient":"recipient"},
             "unmapped":[]}
             """);
        new TemplateRepository(db).save(template);
        TemplateSchemaEntity schema = schemas.ensureVersionedSchema(template);
        assertEquals("AUTO_MAPPED", schema.getStatus());
        assertEquals(1, schema.getVersion());
        assertTrue(schemas.activeFor(template).isPresent());

        DocumentEntity document = new DocumentEntity();
        document.setFilename("input.pdf"); document.setDocumentType("official");
        DocumentFieldEntity number = new DocumentFieldEntity();
        number.setFieldName("document_number"); number.setFieldValue("12/BGDĐT");
        DocumentFieldEntity recipient = new DocumentFieldEntity();
        recipient.setFieldName("recipient"); recipient.setFieldValue("Các Sở GDĐT");
        document.setFields(java.util.List.of(number, recipient));

        MappingService mapping = new MappingService();
        Map<String, Object> resolved = mapping.resolve(document, schemas.parse(schema));
        assertEquals("12/BGDĐT", resolved.get("document_number"));
        assertEquals("Các Sở GDĐT", resolved.get("recipient"));

        Files.writeString(file, "template-v2");
        TemplateSchemaEntity changed = schemas.ensureVersionedSchema(template);
        assertEquals(2, changed.getVersion());
        assertNotEquals(schema.getFingerprint(), changed.getFingerprint());
    }
}
