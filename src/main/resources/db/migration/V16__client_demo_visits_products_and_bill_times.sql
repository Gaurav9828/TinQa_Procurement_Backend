-- ==========================================
-- V16: client demos visit several clients, list the products shown, and time their stays
-- ==========================================
-- One demo trip may visit several clients (e.g. three hotels over two days), each in its own time slot; slots on the
-- same day never overlap. Business clients (hotels, home stays, offices, hospitals, clinics, restaurants) may carry
-- their legal name and GSTIN. The demo also lists which storefront products are shown and how many units. A stay bill
-- records its check-in and check-out time.
--
-- client_demo keeps the first visit's client (earliest day and time) in its own columns, so lists, the Approvals Hub
-- and the e-commerce finance feed read the demo without the visits.

CREATE TABLE client_demo_visit
(
    id                    BIGSERIAL PRIMARY KEY,
    demo_id               BIGINT       NOT NULL REFERENCES client_demo (id) ON DELETE CASCADE,
    client_category       VARCHAR(30)  NOT NULL,
    -- What kind of place it is when the category is OTHER
    client_category_other VARCHAR(100),
    client_name           VARCHAR(150) NOT NULL,
    -- Registered name and GSTIN of a business client; both optional
    legal_name            VARCHAR(150),
    gstin                 VARCHAR(15),
    contact_person        VARCHAR(100),
    contact_phone         VARCHAR(20)  NOT NULL,
    contact_email         VARCHAR(255),
    address_line          VARCHAR(300) NOT NULL,
    city                  VARCHAR(100) NOT NULL,
    state                 VARCHAR(100),
    pincode               VARCHAR(10),
    map_link              VARCHAR(500),
    -- Day of the trip (1 = the demo's start date) and the expected time at the client
    day_number            INTEGER      NOT NULL,
    start_time            TIME         NOT NULL,
    end_time              TIME         NOT NULL,
    CONSTRAINT chk_client_demo_visit_category CHECK (client_category IN ('HOTEL', 'HOME_STAY', 'CORPORATE_OFFICE', 'HOSPITAL',
                                                                          'CLINIC', 'HOME_VISIT', 'SHOPPING_MALL', 'RESTAURANT',
                                                                          'PUBLIC_PLACE', 'OTHER')),
    CONSTRAINT chk_client_demo_visit_day CHECK (day_number BETWEEN 1 AND 60),
    CONSTRAINT chk_client_demo_visit_slot CHECK (end_time > start_time)
);

CREATE INDEX idx_client_demo_visit_demo ON client_demo_visit (demo_id, day_number, start_time);

-- Every existing demo visited its one client on day 1, from its start time for an hour (to the end of the day at most)
INSERT INTO client_demo_visit (demo_id, client_category, client_category_other, client_name, contact_person, contact_phone,
                               contact_email, address_line, city, state, pincode, map_link, day_number, start_time, end_time)
SELECT id, client_category, client_category_other, client_name, contact_person, contact_phone, contact_email, address_line,
       city, state, pincode, map_link, 1, start_time,
       CASE WHEN start_time >= TIME '23:00' THEN TIME '23:59' ELSE start_time + INTERVAL '1 hour' END
FROM client_demo;

-- The storefront products shown on the demo (the e-commerce product id and its title when chosen) and how many units
CREATE TABLE client_demo_product
(
    demo_id      BIGINT       NOT NULL REFERENCES client_demo (id) ON DELETE CASCADE,
    product_id   BIGINT       NOT NULL,
    product_name VARCHAR(200) NOT NULL,
    quantity     INTEGER      NOT NULL,
    PRIMARY KEY (demo_id, product_id),
    CONSTRAINT chk_client_demo_product_quantity CHECK (quantity BETWEEN 1 AND 1000)
);

-- Check-in and check-out of a stay; check-out may be the next morning
ALTER TABLE client_demo_expense
    ADD COLUMN start_time TIME,
    ADD COLUMN end_time   TIME,
    ADD CONSTRAINT chk_client_demo_expense_times CHECK ((start_time IS NULL) = (end_time IS NULL));
