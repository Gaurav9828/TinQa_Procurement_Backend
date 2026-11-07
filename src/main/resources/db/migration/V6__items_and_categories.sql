-- Items we buy, their categories and warranties.
-- Tables: categories, items, item_warranties, item_category_mappings.

-- categories ----------------------------------------------------------------------------------------------------------

CREATE TABLE categories (
    id bigserial NOT NULL,
    type character varying(50) NOT NULL,
    name character varying(100) NOT NULL,
    code character varying(50) NOT NULL,
    description text,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    created_by bigint,
    updated_by bigint,
    CONSTRAINT categories_pkey PRIMARY KEY (id),
    CONSTRAINT uq_categories_type_code UNIQUE (type, code),
    CONSTRAINT uq_categories_type_name UNIQUE (type, name)
);

-- items ---------------------------------------------------------------------------------------------------------------

CREATE TABLE items (
    id bigserial NOT NULL,
    name character varying(255) NOT NULL,
    brand character varying(100),
    sku character varying(100) NOT NULL,
    unit_of_measure character varying(50) NOT NULL,
    mrp numeric(12,2) NOT NULL,
    country_of_origin character varying(50) NOT NULL,
    raw_materials_used text,
    terms_and_condition text,
    description text,
    attributes jsonb,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    created_by bigint,
    updated_by bigint,
    CONSTRAINT items_pkey PRIMARY KEY (id),
    CONSTRAINT items_sku_key UNIQUE (sku)
);

-- item_warranties -----------------------------------------------------------------------------------------------------

CREATE TABLE item_warranties (
    id bigserial NOT NULL,
    item_id bigint NOT NULL,
    warranty_type character varying(30) NOT NULL,
    title character varying(255) NOT NULL,
    duration_value integer NOT NULL,
    duration_unit character varying(10) NOT NULL,
    provider character varying(255),
    coverage text,
    exclusions text,
    terms_and_conditions text,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    created_by bigint,
    updated_by bigint,
    CONSTRAINT chk_item_warranties_duration_positive CHECK ((duration_value > 0)),
    CONSTRAINT item_warranties_pkey PRIMARY KEY (id),
    CONSTRAINT item_warranties_item_id_fkey FOREIGN KEY (item_id) REFERENCES items(id) ON DELETE CASCADE
);

CREATE INDEX idx_item_warranties_item ON item_warranties USING btree (item_id);

-- item_category_mappings ----------------------------------------------------------------------------------------------

CREATE TABLE item_category_mappings (
    item_id bigint NOT NULL,
    category_id bigint NOT NULL,
    CONSTRAINT item_category_mappings_pkey PRIMARY KEY (item_id, category_id),
    CONSTRAINT item_category_mappings_category_id_fkey FOREIGN KEY (category_id) REFERENCES categories(id) ON DELETE CASCADE,
    CONSTRAINT item_category_mappings_item_id_fkey FOREIGN KEY (item_id) REFERENCES items(id) ON DELETE CASCADE
);

CREATE INDEX idx_item_category_mappings_category ON item_category_mappings USING btree (category_id);
