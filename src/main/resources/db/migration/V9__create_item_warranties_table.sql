-- 1. Item Warranties Table (an item can carry multiple warranties)
CREATE TABLE item_warranties
(
    id                   BIGSERIAL PRIMARY KEY,
    item_id              BIGINT       NOT NULL REFERENCES items (id) ON DELETE CASCADE,
    warranty_type        VARCHAR(30)  NOT NULL, -- MANUFACTURER, SELLER, EXTENDED, REPLACEMENT, SERVICE, PARTS, LIMITED
    title                VARCHAR(255) NOT NULL,
    duration_value       INT          NOT NULL,
    duration_unit        VARCHAR(10)  NOT NULL, -- DAYS, MONTHS, YEARS
    provider             VARCHAR(255),          -- Who honours the warranty (brand, manufacturer, dealer, etc.)
    coverage             TEXT,
    exclusions           TEXT,
    terms_and_conditions TEXT,
    is_active            BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    created_by           BIGINT,
    updated_by           BIGINT,

    CONSTRAINT chk_item_warranties_duration_positive CHECK (duration_value > 0)
);

CREATE INDEX idx_item_warranties_item ON item_warranties (item_id);

-- 2. Carry existing single-value warranties over as MANUFACTURER warranties
INSERT INTO item_warranties (item_id, warranty_type, title, duration_value, duration_unit,
                             created_at, updated_at, created_by, updated_by)
SELECT id,
       'MANUFACTURER',
       warranty_months || ' Month Manufacturer Warranty',
       warranty_months,
       'MONTHS',
       CURRENT_TIMESTAMP,
       CURRENT_TIMESTAMP,
       updated_by,
       updated_by
FROM items
WHERE warranty_months IS NOT NULL
  AND warranty_months > 0;

-- 3. Warranty details now live in item_warranties
ALTER TABLE items
    DROP COLUMN IF EXISTS warranty_months;
