ALTER TABLE auction
    ADD COLUMN participation_mode VARCHAR(30) NOT NULL DEFAULT 'OPEN';

CREATE INDEX idx_auction_participation_mode
    ON auction(participation_mode);