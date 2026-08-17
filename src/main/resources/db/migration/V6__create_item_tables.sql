-- Drop old tables if replacing directly
DROP TABLE IF EXISTS dealer_category_mappings;
DROP TABLE IF EXISTS items;
DROP TABLE IF EXISTS item_categories;

-- 1. Create Generic Categories Table
CREATE TABLE categories
(
    id          BIGSERIAL PRIMARY KEY,
    type        VARCHAR(50)  NOT NULL, -- E.g., ITEM, DEALER, EMPLOYEE
    name        VARCHAR(100) NOT NULL,
    code        VARCHAR(50)  NOT NULL,
    description TEXT,
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    created_by  BIGINT,
    updated_by  BIGINT,

    -- Uniqueness enforced per Category Type
    CONSTRAINT uq_categories_type_name UNIQUE (type, name),
    CONSTRAINT uq_categories_type_code UNIQUE (type, code)
);

CREATE INDEX idx_categories_type ON categories (type);

-- 2. Items Table referencing Generic Categories
CREATE TABLE items
(
    id                  BIGSERIAL PRIMARY KEY,
    category_id         BIGINT         NOT NULL REFERENCES categories (id),
    name                VARCHAR(255)   NOT NULL,
    brand               VARCHAR(100),
    sku                 VARCHAR(100)   NOT NULL UNIQUE,
    unit_of_measure     VARCHAR(50)    NOT NULL,
    mrp                 NUMERIC(12, 2) NOT NULL,
    country_of_origin   VARCHAR(50)    NOT NULL,
    raw_materials_used  TEXT,
    warranty_months     INT,
    terms_and_condition TEXT,
    description         TEXT,
    attributes          JSONB,
    is_active           BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    created_by          BIGINT,
    updated_by          BIGINT
);

CREATE INDEX idx_items_category ON items (category_id);
CREATE INDEX idx_items_sku ON items (sku);

-- 3. Dealers Table
CREATE TABLE dealers
(
    id                     BIGSERIAL PRIMARY KEY,
    name                   VARCHAR(255) NOT NULL,
    trade_name             VARCHAR(255),
    email                  VARCHAR(150) NOT NULL,
    phone_number           VARCHAR(20)  NOT NULL,
    alternate_phone_number VARCHAR(20),
    street                 TEXT         NOT NULL,
    landmark               VARCHAR(255),
    city                   VARCHAR(100) NOT NULL,
    state                  VARCHAR(100) NOT NULL,
    country                VARCHAR(100) NOT NULL DEFAULT 'India',
    pincode                VARCHAR(20)  NOT NULL,
    google_maps_url        TEXT,
    gstin                  VARCHAR(15)  UNIQUE,
    is_gst_verified        BOOLEAN      NOT NULL DEFAULT FALSE,
    pan_number             VARCHAR(10),
    business_since         INT,
    employee_count         INT,
    offers_shipping        BOOLEAN      NOT NULL DEFAULT FALSE,
    does_bulk_dealing      BOOLEAN      NOT NULL DEFAULT TRUE,
    does_wholesale_dealing BOOLEAN      NOT NULL DEFAULT TRUE,
    is_active              BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at             TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at             TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    created_by             BIGINT,
    updated_by             BIGINT
);

CREATE INDEX idx_dealers_gstin ON dealers (gstin);
CREATE INDEX idx_dealers_email ON dealers (email);
CREATE INDEX idx_dealers_pincode ON dealers (pincode);

-- 4. Many-to-Many Mapping for Dealer to Generic Categories
CREATE TABLE dealer_category_mappings
(
    dealer_id   BIGINT NOT NULL REFERENCES dealers (id) ON DELETE CASCADE,
    category_id BIGINT NOT NULL REFERENCES categories (id) ON DELETE CASCADE,
    PRIMARY KEY (dealer_id, category_id)
);

CREATE INDEX idx_dealer_categories_dealer ON dealer_category_mappings (dealer_id);