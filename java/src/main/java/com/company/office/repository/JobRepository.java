package com.company.office.repository;

import com.company.office.database.DatabaseManager;
import com.company.office.model.JobEntity;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class JobRepository {
    private final DatabaseManager dbManager;

    public JobRepository(DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }

    public void save(JobEntity job) {
        String sql = """
            INSERT INTO jobs (id, document_id, job_type, status, progress, error_message, result_data, created_at, completed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                document_id = excluded.document_id,
                job_type = excluded.job_type,
                status = excluded.status,
                progress = excluded.progress,
                error_message = excluded.error_message,
                result_data = excluded.result_data,
                completed_at = excluded.completed_at;
        """;

        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, job.getId());
            ps.setString(2, job.getDocumentId());
            ps.setString(3, job.getJobType());
            ps.setString(4, job.getStatus());
            ps.setDouble(5, job.getProgress());
            ps.setString(6, job.getErrorMessage());
            ps.setString(7, job.getResultData());
            ps.setString(8, job.getCreatedAt() != null ? job.getCreatedAt().toString() : LocalDateTime.now().toString());
            ps.setString(9, job.getCompletedAt() != null ? job.getCompletedAt().toString() : null);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Error saving job: " + job.getId(), e);
        }
    }

    public Optional<JobEntity> findById(String id) {
        String sql = "SELECT * FROM jobs WHERE id = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error finding job by id: " + id, e);
        }
        return Optional.empty();
    }

    public List<JobEntity> findAll() {
        List<JobEntity> list = new ArrayList<>();
        String sql = "SELECT * FROM jobs ORDER BY created_at DESC";
        try (Connection conn = dbManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                list.add(mapRow(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error listing jobs", e);
        }
        return list;
    }

    private JobEntity mapRow(ResultSet rs) throws SQLException {
        JobEntity job = new JobEntity();
        job.setId(rs.getString("id"));
        job.setDocumentId(rs.getString("document_id"));
        job.setJobType(rs.getString("job_type"));
        job.setStatus(rs.getString("status"));
        job.setProgress(rs.getDouble("progress"));
        job.setErrorMessage(rs.getString("error_message"));
        job.setResultData(rs.getString("result_data"));
        String cAt = rs.getString("created_at");
        if (cAt != null) {
            try { job.setCreatedAt(LocalDateTime.parse(cAt.replace(" ", "T"))); } catch (Exception ignored) {}
        }
        String compAt = rs.getString("completed_at");
        if (compAt != null) {
            try { job.setCompletedAt(LocalDateTime.parse(compAt.replace(" ", "T"))); } catch (Exception ignored) {}
        }
        return job;
    }
}
