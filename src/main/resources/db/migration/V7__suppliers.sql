-- Suppliers, the categories they supply and their logins.
-- Tables: suppliers, supplier_category_mappings, supplier_login.

-- suppliers -----------------------------------------------------------------------------------------------------------

CREATE TABLE suppliers (
    id bigserial NOT NULL,
    name character varying(255) NOT NULL,
    trade_name character varying(255),
    email character varying(150) NOT NULL,
    phone_number character varying(20) NOT NULL,
    alternate_phone_number character varying(20),
    street text NOT NULL,
    landmark character varying(255),
    city character varying(100) NOT NULL,
    district character varying(100),
    state character varying(100) NOT NULL,
    country character varying(100) DEFAULT 'India'::character varying NOT NULL,
    pincode character varying(20) NOT NULL,
    google_maps_url text,
    gstin character varying(15),
    is_gst_verified boolean DEFAULT false NOT NULL,
    pan_number character varying(10),
    business_since integer,
    employee_count integer,
    offers_shipping boolean DEFAULT false NOT NULL,
    does_bulk_dealing boolean DEFAULT true NOT NULL,
    does_wholesale_dealing boolean DEFAULT true NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    created_by bigint,
    updated_by bigint,
    CONSTRAINT suppliers_pkey PRIMARY KEY (id),
    CONSTRAINT suppliers_gstin_key UNIQUE (gstin)
);

-- supplier_category_mappings ------------------------------------------------------------------------------------------

CREATE TABLE supplier_category_mappings (
    supplier_id bigint NOT NULL,
    category_id bigint NOT NULL,
    CONSTRAINT supplier_category_mappings_pkey PRIMARY KEY (supplier_id, category_id),
    CONSTRAINT supplier_category_mappings_category_id_fkey FOREIGN KEY (category_id) REFERENCES categories(id) ON DELETE CASCADE,
    CONSTRAINT supplier_category_mappings_supplier_id_fkey FOREIGN KEY (supplier_id) REFERENCES suppliers(id) ON DELETE CASCADE
);

CREATE INDEX idx_supplier_category_mappings_category ON supplier_category_mappings USING btree (category_id);

-- supplier_login ------------------------------------------------------------------------------------------------------

CREATE TABLE supplier_login (
    id bigserial NOT NULL,
    supplier_id bigint NOT NULL,
    user_id bigint NOT NULL,
    status character varying(20) NOT NULL,
    created_by bigint NOT NULL,
    created_at timestamp with time zone NOT NULL,
    decided_by bigint,
    decided_at timestamp with time zone,
    rejection_reason character varying(500),
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT chk_supplier_login_status CHECK ((status IN ('APPROVAL_PENDING', 'ACTIVE', 'REJECTED', 'DISABLED'))),
    CONSTRAINT supplier_login_pkey PRIMARY KEY (id),
    CONSTRAINT supplier_login_supplier_id_key UNIQUE (supplier_id),
    CONSTRAINT supplier_login_user_id_key UNIQUE (user_id),
    CONSTRAINT supplier_login_created_by_fkey FOREIGN KEY (created_by) REFERENCES users(id),
    CONSTRAINT supplier_login_decided_by_fkey FOREIGN KEY (decided_by) REFERENCES users(id),
    CONSTRAINT supplier_login_supplier_id_fkey FOREIGN KEY (supplier_id) REFERENCES suppliers(id),
    CONSTRAINT supplier_login_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE INDEX idx_supplier_login_status_created ON supplier_login USING btree (status, created_at);
