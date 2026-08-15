CREATE TABLE item_categories (
                                 id BIGSERIAL PRIMARY KEY,
                                 name VARCHAR(100) NOT NULL UNIQUE,
                                 code VARCHAR(50) NOT NULL UNIQUE,
                                 description TEXT,
                                 is_active BOOLEAN NOT NULL DEFAULT TRUE,
                                 created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
                                 updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
                                 created_by BIGINT,
                                 updated_by BIGINT
);

CREATE TABLE items (
                       id BIGSERIAL PRIMARY KEY,
                       category_id BIGINT NOT NULL REFERENCES item_categories(id),
                       name VARCHAR(255) NOT NULL,
                       brand VARCHAR(100),
                       sku VARCHAR(100) NOT NULL UNIQUE,
                       unit_of_measure VARCHAR(50) NOT NULL, -- e.g., PCS, KG, SQ_FT, METERS
                       mrp NUMERIC(12, 2) NOT NULL,
                       description TEXT,
                       attributes JSONB, -- Dynamic attributes (e.g. {"voltage": "5V", "material": "Granite"})
                       is_active BOOLEAN NOT NULL DEFAULT TRUE,
                       created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
                       updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
                       created_by BIGINT,
                       updated_by BIGINT
);

CREATE INDEX idx_items_category ON items(category_id);
CREATE INDEX idx_items_sku ON items(sku);