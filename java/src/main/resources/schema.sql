-- SQLite Schema for Office Automation System

CREATE TABLE IF NOT EXISTS settings (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS documents (
    id TEXT PRIMARY KEY,
    document_type TEXT NOT NULL DEFAULT 'generic',
    filename TEXT NOT NULL,
    source_path TEXT NOT NULL,
    file_type TEXT NOT NULL,
    file_size INTEGER DEFAULT 0,
    page_count INTEGER DEFAULT 1,
    status TEXT NOT NULL DEFAULT 'NEW', -- NEW, EXTRACTING, EXTRACTED, VALIDATED, NEEDS_REVIEW, EXPORTED, ERROR
    confidence REAL DEFAULT 1.0,
    raw_text TEXT,
    pages_json TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS document_fields (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    document_id TEXT NOT NULL,
    field_name TEXT NOT NULL,
    label TEXT,
    field_value TEXT,
    raw_value TEXT,
    data_type TEXT DEFAULT 'string',
    confidence REAL DEFAULT 1.0,
    validated INTEGER DEFAULT 0,
    validation_error TEXT,
    page_number INTEGER DEFAULT 1,
    source_bbox_json TEXT,
    FOREIGN KEY(document_id) REFERENCES documents(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS templates (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    template_type TEXT NOT NULL, -- excel, word
    file_path TEXT NOT NULL,
    description TEXT,
    schema_json TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS template_inspections (
    id TEXT PRIMARY KEY,
    template_id TEXT NOT NULL,
    fingerprint TEXT NOT NULL,
    inspection_json TEXT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(template_id) REFERENCES templates(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS template_schemas (
    id TEXT PRIMARY KEY,
    template_id TEXT NOT NULL,
    fingerprint TEXT NOT NULL,
    version INTEGER NOT NULL,
    status TEXT NOT NULL DEFAULT 'DRAFT',
    schema_json TEXT NOT NULL,
    approved_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(template_id) REFERENCES templates(id) ON DELETE CASCADE,
    UNIQUE(template_id, version)
);

CREATE TABLE IF NOT EXISTS mapping_runs (
    id TEXT PRIMARY KEY,
    document_id TEXT NOT NULL,
    template_id TEXT NOT NULL,
    schema_id TEXT,
    resolved_context_json TEXT,
    issues_json TEXT,
    status TEXT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS jobs (
    id TEXT PRIMARY KEY,
    document_id TEXT,
    job_type TEXT NOT NULL, -- EXTRACT, VALIDATE, EXPORT
    status TEXT NOT NULL DEFAULT 'PENDING', -- PENDING, RUNNING, COMPLETED, FAILED
    progress REAL DEFAULT 0.0,
    error_message TEXT,
    result_data TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_documents_status ON documents(status);
CREATE INDEX IF NOT EXISTS idx_doc_fields_doc_id ON document_fields(document_id);
CREATE INDEX IF NOT EXISTS idx_jobs_status ON jobs(status);
CREATE INDEX IF NOT EXISTS idx_template_inspections_fingerprint ON template_inspections(fingerprint);
CREATE INDEX IF NOT EXISTS idx_template_schemas_fingerprint ON template_schemas(fingerprint);
