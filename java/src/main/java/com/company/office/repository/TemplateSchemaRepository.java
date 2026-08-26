package com.company.office.repository;

import com.company.office.database.DatabaseManager;
import com.company.office.model.TemplateSchemaEntity;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class TemplateSchemaRepository {
    private final DatabaseManager db;
    public TemplateSchemaRepository(DatabaseManager db) { this.db = db; }

    public void save(TemplateSchemaEntity schema) {
        String sql = """
            INSERT INTO template_schemas
            (id, template_id, fingerprint, version, status, schema_json, approved_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET status=excluded.status, schema_json=excluded.schema_json,
              approved_at=excluded.approved_at, updated_at=excluded.updated_at
            """;
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            LocalDateTime now = LocalDateTime.now();
            ps.setString(1, schema.getId()); ps.setString(2, schema.getTemplateId());
            ps.setString(3, schema.getFingerprint()); ps.setInt(4, schema.getVersion());
            ps.setString(5, schema.getStatus()); ps.setString(6, schema.getSchemaJson());
            ps.setString(7, schema.getApprovedAt() == null ? null : schema.getApprovedAt().toString());
            ps.setString(8, schema.getCreatedAt() == null ? now.toString() : schema.getCreatedAt().toString());
            ps.setString(9, now.toString()); ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException("Error saving template schema", e); }
    }

    public Optional<TemplateSchemaEntity> findApproved(String templateId, String fingerprint) {
        String sql = "SELECT * FROM template_schemas WHERE template_id=? AND fingerprint=? AND status='APPROVED' ORDER BY version DESC LIMIT 1";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, templateId); ps.setString(2, fingerprint);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? Optional.of(map(rs)) : Optional.empty(); }
        } catch (SQLException e) { throw new RuntimeException("Error finding approved schema", e); }
    }

    public Optional<TemplateSchemaEntity> findLatest(String templateId, String fingerprint) {
        String sql = "SELECT * FROM template_schemas WHERE template_id=? AND fingerprint=? ORDER BY version DESC LIMIT 1";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, templateId); ps.setString(2, fingerprint);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? Optional.of(map(rs)) : Optional.empty(); }
        } catch (SQLException e) { throw new RuntimeException("Error finding latest schema", e); }
    }

    public int nextVersion(String templateId) {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT COALESCE(MAX(version),0)+1 FROM template_schemas WHERE template_id=?")) {
            ps.setString(1, templateId); try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 1; }
        } catch (SQLException e) { throw new RuntimeException("Error calculating schema version", e); }
    }

    public List<TemplateSchemaEntity> findByTemplate(String templateId) {
        List<TemplateSchemaEntity> out = new ArrayList<>();
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM template_schemas WHERE template_id=? ORDER BY version DESC")) {
            ps.setString(1, templateId); try (ResultSet rs = ps.executeQuery()) { while (rs.next()) out.add(map(rs)); }
        } catch (SQLException e) { throw new RuntimeException("Error listing schemas", e); }
        return out;
    }

    private TemplateSchemaEntity map(ResultSet rs) throws SQLException {
        TemplateSchemaEntity s = new TemplateSchemaEntity();
        s.setId(rs.getString("id")); s.setTemplateId(rs.getString("template_id"));
        s.setFingerprint(rs.getString("fingerprint")); s.setVersion(rs.getInt("version"));
        s.setStatus(rs.getString("status")); s.setSchemaJson(rs.getString("schema_json"));
        s.setApprovedAt(parse(rs.getString("approved_at"))); s.setCreatedAt(parse(rs.getString("created_at")));
        s.setUpdatedAt(parse(rs.getString("updated_at"))); return s;
    }
    private LocalDateTime parse(String value) {
        if (value == null) return null;
        try { return LocalDateTime.parse(value.replace(" ", "T")); } catch (Exception ignored) { return null; }
    }
}
