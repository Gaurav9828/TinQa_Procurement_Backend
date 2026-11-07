-- Uploaded documents waiting for or past approval.
-- Tables: document.

-- document ------------------------------------------------------------------------------------------------------------

CREATE TABLE document (
    id bigserial NOT NULL,
    original_file_name character varying(255) NOT NULL,
    storage_key character varying(500) NOT NULL,
    content_type character varying(100) NOT NULL,
    file_size bigint NOT NULL,
    category character varying(50) NOT NULL,
    purpose character varying(50) NOT NULL,
    stage character varying(50) NOT NULL,
    status character varying(50) NOT NULL,
    type character varying(50),
    uploader_type character varying(50) NOT NULL,
    uploaded_by_user_id bigint NOT NULL,
    owner_id bigint,
    owner_type character varying(50),
    reference_type character varying(50) NOT NULL,
    reference_id bigint NOT NULL,
    deleted boolean DEFAULT false NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone,
    CONSTRAINT document_pkey PRIMARY KEY (id)
);

CREATE INDEX idx_doc_uploader ON document USING btree (uploader_type, uploaded_by_user_id);
CREATE INDEX idx_document_owner_id ON document USING btree (owner_id);
CREATE INDEX idx_document_reference ON document USING btree (reference_type, reference_id);
CREATE INDEX idx_document_status_created ON document USING btree (status, created_at);
