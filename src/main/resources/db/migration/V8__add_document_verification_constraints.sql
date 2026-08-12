ALTER TABLE workshop_document ADD CONSTRAINT fk_workshop_document_verified_by FOREIGN KEY (verified_by) REFERENCES users(id);

CREATE INDEX idx_workshop_document_verified_by ON workshop_document(verified_by);