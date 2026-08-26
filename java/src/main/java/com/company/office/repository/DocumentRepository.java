package com.company.office.repository;

import com.company.office.database.DatabaseManager;
import com.company.office.model.DocumentEntity;
import com.company.office.model.DocumentFieldEntity;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class DocumentRepository {
    private final DatabaseManager dbManager;

    public DocumentRepository(DatabaseManager dbManager) {
        this.dbManager = dbManager;
        ensureColumnsExist();
    }

    private void ensureColumnsExist() {
        try (Connection conn = dbManager.getConnection();
             Statement stmt = conn.createStatement()) {
            try { stmt.execute("ALTER TABLE documents ADD COLUMN pages_json TEXT;"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE document_fields ADD COLUMN label TEXT;"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE document_fields ADD COLUMN page_number INTEGER DEFAULT 1;"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE document_fields ADD COLUMN source_bbox_json TEXT;"); } catch (Exception ignored) {}
        } catch (SQLException ignored) {}
    }

    public void save(DocumentEntity doc) {
        String sql = """
            INSERT INTO documents (id, document_type, filename, source_path, file_type, file_size, page_count, status, confidence, raw_text, pages_json, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                document_type = excluded.document_type,
                filename = excluded.filename,
                source_path = excluded.source_path,
                file_type = excluded.file_type,
                file_size = excluded.file_size,
                page_count = excluded.page_count,
                status = excluded.status,
                confidence = excluded.confidence,
                raw_text = excluded.raw_text,
                pages_json = excluded.pages_json,
                updated_at = CURRENT_TIMESTAMP;
        """;

        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, doc.getId());
            ps.setString(2, doc.getDocumentType() != null ? doc.getDocumentType() : "generic");
            ps.setString(3, doc.getFilename() != null ? doc.getFilename() : "unknown");
            ps.setString(4, doc.getSourcePath() != null ? doc.getSourcePath() : "");
            ps.setString(5, doc.getFileType() != null ? doc.getFileType() : "pdf");
            ps.setLong(6, doc.getFileSize());
            ps.setInt(7, doc.getPageCount() > 0 ? doc.getPageCount() : 1);
            ps.setString(8, doc.getStatus() != null ? doc.getStatus() : "NEW");
            ps.setDouble(9, doc.getConfidence());
            ps.setString(10, doc.getRawText());
            ps.setString(11, doc.getPagesJson());
            ps.setString(12, doc.getCreatedAt() != null ? doc.getCreatedAt().toString() : LocalDateTime.now().toString());
            ps.setString(13, LocalDateTime.now().toString());
            ps.executeUpdate();

            // Save fields
            saveFields(conn, doc.getId(), doc.getFields());
        } catch (SQLException e) {
            throw new RuntimeException("Error saving document: " + doc.getId(), e);
        }
    }

    public void saveFields(Connection conn, String documentId, List<DocumentFieldEntity> fields) throws SQLException {
        if (fields == null) return;
        try (PreparedStatement delPs = conn.prepareStatement("DELETE FROM document_fields WHERE document_id = ?")) {
            delPs.setString(1, documentId);
            delPs.executeUpdate();
        }

        String insertSql = """
            INSERT INTO document_fields (document_id, field_name, label, field_value, raw_value, data_type, confidence, validated, validation_error, page_number, source_bbox_json)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
        try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
            for (DocumentFieldEntity f : fields) {
                ps.setString(1, documentId);
                ps.setString(2, f.getFieldName() != null ? f.getFieldName() : "field");
                ps.setString(3, f.getLabel() != null ? f.getLabel() : f.getFieldName());
                ps.setString(4, f.getFieldValue());
                ps.setString(5, f.getRawValue());
                ps.setString(6, f.getDataType() != null ? f.getDataType() : "string");
                ps.setDouble(7, f.getConfidence());
                ps.setInt(8, f.isValidated() ? 1 : 0);
                ps.setString(9, f.getValidationError());
                ps.setInt(10, f.getPageNumber() > 0 ? f.getPageNumber() : 1);
                ps.setString(11, f.getSourceBboxJson());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    public Optional<DocumentEntity> findById(String id) {
        String sql = "SELECT * FROM documents WHERE id = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    DocumentEntity doc = mapRow(rs);
                    doc.setFields(loadFields(conn, doc.getId()));
                    return Optional.of(doc);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error finding document by id: " + id, e);
        }
        return Optional.empty();
    }

    public List<DocumentEntity> findAll() {
        List<DocumentEntity> list = new ArrayList<>();
        String sql = "SELECT * FROM documents ORDER BY created_at DESC";
        try (Connection conn = dbManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                DocumentEntity doc = mapRow(rs);
                doc.setFields(loadFields(conn, doc.getId()));
                list.add(doc);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error listing all documents", e);
        }
        return list;
    }

    public void delete(String id) {
        String sql = "DELETE FROM documents WHERE id = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Error deleting document: " + id, e);
        }
    }

    private List<DocumentFieldEntity> loadFields(Connection conn, String documentId) throws SQLException {
        List<DocumentFieldEntity> list = new ArrayList<>();
        String sql = "SELECT * FROM document_fields WHERE document_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, documentId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    DocumentFieldEntity f = new DocumentFieldEntity();
                    f.setId(rs.getLong("id"));
                    f.setDocumentId(rs.getString("document_id"));
                    f.setFieldName(rs.getString("field_name"));
                    try { f.setLabel(rs.getString("label")); } catch (Exception ignored) {}
                    f.setFieldValue(rs.getString("field_value"));
                    f.setRawValue(rs.getString("raw_value"));
                    f.setDataType(rs.getString("data_type"));
                    f.setConfidence(rs.getDouble("confidence"));
                    f.setValidated(rs.getInt("validated") == 1);
                    f.setValidationError(rs.getString("validation_error"));
                    try { f.setPageNumber(rs.getInt("page_number")); } catch (Exception ignored) {}
                    try { f.setSourceBboxJson(rs.getString("source_bbox_json")); } catch (Exception ignored) {}
                    list.add(f);
                }
            }
        }
        return list;
    }

    private DocumentEntity mapRow(ResultSet rs) throws SQLException {
        DocumentEntity doc = new DocumentEntity();
        doc.setId(rs.getString("id"));
        doc.setDocumentType(rs.getString("document_type"));
        doc.setFilename(rs.getString("filename"));
        doc.setSourcePath(rs.getString("source_path"));
        doc.setFileType(rs.getString("file_type"));
        doc.setFileSize(rs.getLong("file_size"));
        doc.setPageCount(rs.getInt("page_count"));
        doc.setStatus(rs.getString("status"));
        doc.setConfidence(rs.getDouble("confidence"));
        doc.setRawText(rs.getString("raw_text"));
        try { doc.setPagesJson(rs.getString("pages_json")); } catch (Exception ignored) {}
        String cAt = rs.getString("created_at");
        if (cAt != null) {
            try { doc.setCreatedAt(LocalDateTime.parse(cAt.replace(" ", "T"))); } catch (Exception ignored) {}
        }
        return doc;
    }
}
