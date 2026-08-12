ALTER TABLE auction
    ADD COLUMN scheduled_start_at TIMESTAMP;

ALTER TABLE auction
    ADD COLUMN scheduled_end_at TIMESTAMP;

ALTER TABLE auction
    ADD COLUMN actual_start_at TIMESTAMP;

ALTER TABLE auction
    ADD COLUMN actual_end_at TIMESTAMP;

CREATE INDEX idx_auction_scheduled_start_at
    ON auction(scheduled_start_at);

CREATE INDEX idx_auction_scheduled_end_at
    ON auction(scheduled_end_at);