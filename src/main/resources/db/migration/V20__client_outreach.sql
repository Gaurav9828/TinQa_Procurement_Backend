-- ==========================================
-- V20: client outreach (call log)
-- ==========================================
-- A business is a property or company we pitch to; a contact is one call, email, WhatsApp message or visit with it,
-- with what was said, how it went, the interest shown and when to follow up. The business row keeps a summary of its
-- contacts (count, demo requests, latest contact, interest, rating and follow-up), rewritten in one statement by the
-- service whenever a contact is logged or edited, so the business list sorts and filters on indexed columns.

CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE outreach_business
(
    id                 BIGSERIAL PRIMARY KEY,
    name               VARCHAR(200)             NOT NULL,
    -- Same codes as client demos' client category (ClientCategory)
    business_type      VARCHAR(30)              NOT NULL,
    city               VARCHAR(100),
    address            VARCHAR(500),
    google_maps_url    VARCHAR(500),
    -- 10 digits for Indian numbers, else +<country code><number>
    phone              VARCHAR(20),
    email              VARCHAR(255),
    contact_person     VARCHAR(150),
    notes              VARCHAR(2000),
    -- Summary of the contacts (see above)
    contact_count      INTEGER                  NOT NULL DEFAULT 0,
    demo_request_count INTEGER                  NOT NULL DEFAULT 0,
    latest_contact_id  BIGINT,
    last_contact_at    TIMESTAMP WITH TIME ZONE,
    last_contact_by    BIGINT REFERENCES users (id),
    latest_interest    VARCHAR(20),
    latest_rating      VARCHAR(20),
    -- The latest contact's follow-up, when it asks for one
    next_follow_up_at  TIMESTAMP WITH TIME ZONE,
    created_by         BIGINT                   NOT NULL REFERENCES users (id),
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    version            BIGINT                   NOT NULL DEFAULT 0,
    CONSTRAINT chk_outreach_business_type CHECK (business_type IN ('HOTEL', 'HOME_STAY', 'CORPORATE_OFFICE', 'HOSPITAL',
                                                                    'CLINIC', 'HOME_VISIT', 'SHOPPING_MALL', 'RESTAURANT',
                                                                    'PUBLIC_PLACE', 'OTHER'))
);

-- "We already contacted Radisson Blu": one business per phone number and per email
CREATE UNIQUE INDEX uk_outreach_business_phone ON outreach_business (phone) WHERE phone IS NOT NULL;
CREATE UNIQUE INDEX uk_outreach_business_email ON outreach_business (lower(email)) WHERE email IS NOT NULL;
-- GET /v1/outreach/businesses: latest contact first, optionally by type or latest interest
CREATE INDEX idx_outreach_business_last_contact ON outreach_business (last_contact_at DESC NULLS LAST, id DESC);
CREATE INDEX idx_outreach_business_type ON outreach_business (business_type, last_contact_at DESC NULLS LAST, id DESC);
CREATE INDEX idx_outreach_business_interest ON outreach_business (latest_interest, last_contact_at DESC NULLS LAST, id DESC);
-- followUpDue=true, the follow-up list and the summary's followUpsDue
CREATE INDEX idx_outreach_business_follow_up ON outreach_business (next_follow_up_at) WHERE next_follow_up_at IS NOT NULL;
-- search: name, phone or email contains the term
CREATE INDEX idx_outreach_business_name_trgm ON outreach_business USING gin (lower(name) gin_trgm_ops);
CREATE INDEX idx_outreach_business_phone_trgm ON outreach_business USING gin (phone gin_trgm_ops);
CREATE INDEX idx_outreach_business_email_trgm ON outreach_business USING gin (lower(email) gin_trgm_ops);
-- newBusinesses in the summary
CREATE INDEX idx_outreach_business_created ON outreach_business (created_at);

CREATE TABLE outreach_contact
(
    id                   BIGSERIAL PRIMARY KEY,
    business_id          BIGINT                   NOT NULL REFERENCES outreach_business (id),
    contacted_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    channel              VARCHAR(20)              NOT NULL,
    contact_person       VARCHAR(150),
    contact_phone        VARCHAR(20),
    summary              VARCHAR(4000)            NOT NULL,
    response             VARCHAR(2000),
    rating               VARCHAR(20)              NOT NULL,
    interest             VARCHAR(20)              NOT NULL,
    next_action          VARCHAR(30)              NOT NULL,
    follow_up_at         TIMESTAMP WITH TIME ZONE,
    -- When the 09:00 follow-up reminder went out (or was no longer needed because a later contact superseded this one)
    follow_up_reminded_at TIMESTAMP WITH TIME ZONE,
    logged_by            BIGINT                   NOT NULL REFERENCES users (id),
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    version              BIGINT                   NOT NULL DEFAULT 0,
    CONSTRAINT chk_outreach_contact_channel CHECK (channel IN ('CALL', 'EMAIL', 'WHATSAPP', 'IN_PERSON', 'OTHER')),
    CONSTRAINT chk_outreach_contact_rating CHECK (rating IN ('EXCELLENT', 'GOOD', 'AVERAGE', 'BAD', 'WORSE')),
    CONSTRAINT chk_outreach_contact_interest CHECK (interest IN ('INTERESTED', 'MAYBE', 'LATER', 'NOT_INTERESTED')),
    CONSTRAINT chk_outreach_contact_next_action CHECK (next_action IN ('NONE', 'CALL_BACK', 'DEMO_REQUESTED', 'MEETING_REQUESTED')),
    CONSTRAINT chk_outreach_contact_follow_up CHECK (next_action = 'NONE' OR follow_up_at IS NOT NULL)
);

ALTER TABLE outreach_business
    ADD CONSTRAINT fk_outreach_business_latest_contact FOREIGN KEY (latest_contact_id) REFERENCES outreach_contact (id);

-- A business's contacts, newest first (and the summary rewrite)
CREATE INDEX idx_outreach_contact_business ON outreach_contact (business_id, contacted_at DESC, id DESC);
-- GET /v1/outreach/contacts and the summary: by date, optionally by who logged it
CREATE INDEX idx_outreach_contact_contacted ON outreach_contact (contacted_at DESC, id DESC);
CREATE INDEX idx_outreach_contact_logged_by ON outreach_contact (logged_by, contacted_at DESC, id DESC);
-- The 09:00 follow-up reminder job: only follow-ups not yet reminded
CREATE INDEX idx_outreach_contact_follow_up_due ON outreach_contact (follow_up_at)
    WHERE follow_up_at IS NOT NULL AND follow_up_reminded_at IS NULL;
