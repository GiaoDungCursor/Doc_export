package com.company.office.repository;

import com.company.office.database.DatabaseManager;
import com.company.office.model.TemplateEntity;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class TemplateRepository {
    private final DatabaseManager dbManager;

    public TemplateRepository(DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }

    public void save(TemplateEntity tpl) {
        String sql = """
            INSERT INTO templates (id, name, template_type, file_path, description, schema_json, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                name = excluded.name,
                template_type = excluded.template_type,
                file_path = excluded.file_path,
                description = excluded.description,
                schema_json = excluded.schema_json,
                updated_at = CURRENT_TIMESTAMP;
        """;

        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tpl.getId());
            ps.setString(2, tpl.getName());
            ps.setString(3, tpl.getTemplateType());
            ps.setString(4, tpl.getFilePath());
            ps.setString(5, tpl.getDescription());
            ps.setString(6, tpl.getSchemaJson());
            ps.setString(7, tpl.getCreatedAt() != null ? tpl.getCreatedAt().toString() : LocalDateTime.now().toString());
            ps.setString(8, LocalDateTime.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Error saving template: " + tpl.getId(), e);
        }
    }

    public Optional<TemplateEntity> findById(String id) {
        String sql = "SELECT * FROM templates WHERE id = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error finding template by id: " + id, e);
        }
        return Optional.empty();
    }

    public List<TemplateEntity> findAll() {
        List<TemplateEntity> list = new ArrayList<>();
        String sql = "SELECT * FROM templates ORDER BY created_at DESC";
        try (Connection conn = dbManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                list.add(mapRow(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error listing templates", e);
        }
        return list;
    }

    public void delete(String id) {
        String sql = "DELETE FROM templates WHERE id = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Error deleting template: " + id, e);
        }
    }

    private TemplateEntity mapRow(ResultSet rs) throws SQLException {
        TemplateEntity tpl = new TemplateEntity();
        tpl.setId(rs.getString("id"));
        tpl.setName(rs.getString("name"));
        tpl.setTemplateType(rs.getString("template_type"));
        tpl.setFilePath(rs.getString("file_path"));
        tpl.setDescription(rs.getString("description"));
        tpl.setSchemaJson(rs.getString("schema_json"));
        String cAt = rs.getString("created_at");
        if (cAt != null) {
            try { tpl.setCreatedAt(LocalDateTime.parse(cAt.replace(" ", "T"))); } catch (Exception ignored) {}
        }
        return tpl;
    }
}
