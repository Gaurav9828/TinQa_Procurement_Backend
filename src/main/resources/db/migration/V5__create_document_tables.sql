-- Centralized File & Document Service
CREATE TABLE document (
                          id BIGSERIAL PRIMARY KEY,
                          original_file_name VARCHAR(255) NOT NULL,
                          storage_key VARCHAR(500) NOT NULL,
                          content_type VARCHAR(100) NOT NULL,
                          file_size BIGINT NOT NULL,
                          category VARCHAR(50) NOT NULL,
                          purpose VARCHAR(50) NOT NULL,
                          stage VARCHAR(50) NOT NULL,
                          status VARCHAR(50) NOT NULL,
                          type VARCHAR(50),                        -- <--- Added 'type' column
                          uploader_type VARCHAR(50) NOT NULL,       -- <--- Renamed from uploaded_by_type
                          uploaded_by_user_id BIGINT NOT NULL,     -- <--- Renamed from uploaded_by
                          owner_id BIGINT,
                          owner_type VARCHAR(50),
                          reference_type VARCHAR(50) NOT NULL,
                          reference_id BIGINT NOT NULL,
                          deleted BOOLEAN NOT NULL DEFAULT FALSE,
                          created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                          updated_at TIMESTAMP
);

CREATE INDEX idx_document_uploaded_by ON document(uploaded_by_user_id);
CREATE INDEX idx_document_owner ON document(owner_type, owner_id);
CREATE INDEX idx_document_reference ON document(reference_type, reference_id);
CREATE INDEX idx_document_category ON document(category);
CREATE INDEX idx_document_purpose ON document(purpose);
CREATE INDEX idx_document_stage ON document(stage);
CREATE INDEX idx_document_status ON document(status);