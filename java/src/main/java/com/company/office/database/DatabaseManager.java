package com.company.office.database;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public class DatabaseManager {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseManager.class);
    private static final String DEFAULT_DB_PATH = "app-data/app.db";
    private final String dbUrl;

    public DatabaseManager() {
        this(DEFAULT_DB_PATH);
    }

    public DatabaseManager(String dbFilePath) {
        File dbFile = new File(dbFilePath);
        if (dbFile.getParentFile() != null && !dbFile.getParentFile().exists()) {
            dbFile.getParentFile().mkdirs();
        }
        this.dbUrl = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        initializeDatabase();
    }

    public Connection getConnection() throws SQLException {
        Connection conn = DriverManager.getConnection(dbUrl);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON;");
            stmt.execute("PRAGMA journal_mode = WAL;");
        }
        return conn;
    }

    private void initializeDatabase() {
        try (Connection conn = getConnection()) {
            logger.info("Initializing SQLite database at {}", dbUrl);
            InputStream schemaStream = getClass().getResourceAsStream("/schema.sql");
            if (schemaStream == null) {
                logger.warn("schema.sql not found in resources, creating default tables via code");
                createDefaultTables(conn);
                return;
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(schemaStream, StandardCharsets.UTF_8));
                 Statement stmt = conn.createStatement()) {
                StringBuilder sql = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.trim().startsWith("--") || line.trim().isEmpty()) {
                        continue;
                    }
                    sql.append(line).append("\n");
                    if (line.trim().endsWith(";")) {
                        stmt.execute(sql.toString());
                        sql.setLength(0);
                    }
                }
                if (!sql.toString().trim().isEmpty()) {
                    stmt.execute(sql.toString());
                }
            }
            logger.info("Database schema initialized successfully.");
        } catch (Exception e) {
            logger.error("Failed to initialize database", e);
            throw new RuntimeException("Database initialization error", e);
        }
    }

    private void createDefaultTables(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP);");
            stmt.execute("CREATE TABLE IF NOT EXISTS documents (id TEXT PRIMARY KEY, document_type TEXT, filename TEXT, source_path TEXT, file_type TEXT, file_size INTEGER, page_count INTEGER, status TEXT, confidence REAL, raw_text TEXT, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP);");
            stmt.execute("CREATE TABLE IF NOT EXISTS document_fields (id INTEGER PRIMARY KEY AUTOINCREMENT, document_id TEXT, field_name TEXT, field_value TEXT, raw_value TEXT, data_type TEXT, confidence REAL, validated INTEGER, validation_error TEXT);");
            stmt.execute("CREATE TABLE IF NOT EXISTS templates (id TEXT PRIMARY KEY, name TEXT, template_type TEXT, file_path TEXT, description TEXT, schema_json TEXT, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP);");
            stmt.execute("CREATE TABLE IF NOT EXISTS jobs (id TEXT PRIMARY KEY, document_id TEXT, job_type TEXT, status TEXT, progress REAL, error_message TEXT, result_data TEXT, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, completed_at TIMESTAMP);");
        }
    }
}
