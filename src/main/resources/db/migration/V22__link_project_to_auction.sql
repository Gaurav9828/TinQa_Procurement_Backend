ALTER TABLE auction
    ADD COLUMN project_id BIGINT;

ALTER TABLE auction
    ADD CONSTRAINT fk_auction_project
        FOREIGN KEY (project_id)
            REFERENCES project(id);

CREATE INDEX idx_auction_project_id
    ON auction(project_id);