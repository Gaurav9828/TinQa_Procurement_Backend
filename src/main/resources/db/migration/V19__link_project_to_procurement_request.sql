ALTER TABLE procurement_request
    ADD COLUMN project_id BIGINT;

ALTER TABLE procurement_request
    ADD CONSTRAINT fk_procurement_request_project
        FOREIGN KEY (project_id)
            REFERENCES project(id);

CREATE INDEX idx_procurement_request_project_id
    ON procurement_request(project_id);