-- Client outreach (businesses, contacts) and meetings with attendees and feedback.
-- Tables: outreach_business, outreach_contact, meeting, meeting_attendee, meeting_feedback.

-- outreach_business ---------------------------------------------------------------------------------------------------

CREATE TABLE outreach_business (
    id bigserial NOT NULL,
    name character varying(200) NOT NULL,
    business_type character varying(30) NOT NULL,
    city character varying(100),
    address character varying(500),
    google_maps_url character varying(500),
    phone character varying(20),
    email character varying(255),
    contact_person character varying(150),
    notes character varying(2000),
    contact_count integer DEFAULT 0 NOT NULL,
    demo_request_count integer DEFAULT 0 NOT NULL,
    latest_contact_id bigint,
    last_contact_at timestamp with time zone,
    last_contact_by bigint,
    latest_interest character varying(20),
    latest_rating character varying(20),
    next_follow_up_at timestamp with time zone,
    created_by bigint NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT chk_outreach_business_type CHECK ((business_type IN ('HOTEL', 'HOME_STAY', 'CORPORATE_OFFICE', 'HOSPITAL', 'CLINIC', 'HOME_VISIT', 'SHOPPING_MALL', 'RESTAURANT', 'PUBLIC_PLACE', 'OTHER'))),
    CONSTRAINT outreach_business_pkey PRIMARY KEY (id),
    CONSTRAINT outreach_business_created_by_fkey FOREIGN KEY (created_by) REFERENCES users(id),
    CONSTRAINT outreach_business_last_contact_by_fkey FOREIGN KEY (last_contact_by) REFERENCES users(id)
);

CREATE INDEX idx_outreach_business_created ON outreach_business USING btree (created_at);
CREATE INDEX idx_outreach_business_email_trgm ON outreach_business USING gin (lower((email)::text) gin_trgm_ops);
CREATE INDEX idx_outreach_business_follow_up ON outreach_business USING btree (next_follow_up_at) WHERE (next_follow_up_at IS NOT NULL);
CREATE INDEX idx_outreach_business_interest ON outreach_business USING btree (latest_interest, last_contact_at DESC NULLS LAST, id DESC);
CREATE INDEX idx_outreach_business_last_contact ON outreach_business USING btree (last_contact_at DESC NULLS LAST, id DESC);
CREATE INDEX idx_outreach_business_name_trgm ON outreach_business USING gin (lower((name)::text) gin_trgm_ops);
CREATE INDEX idx_outreach_business_phone_trgm ON outreach_business USING gin (phone gin_trgm_ops);
CREATE INDEX idx_outreach_business_type ON outreach_business USING btree (business_type, last_contact_at DESC NULLS LAST, id DESC);
CREATE UNIQUE INDEX uk_outreach_business_email ON outreach_business USING btree (lower((email)::text)) WHERE (email IS NOT NULL);
CREATE UNIQUE INDEX uk_outreach_business_phone ON outreach_business USING btree (phone) WHERE (phone IS NOT NULL);

-- outreach_contact ----------------------------------------------------------------------------------------------------

CREATE TABLE outreach_contact (
    id bigserial NOT NULL,
    business_id bigint NOT NULL,
    contacted_at timestamp with time zone NOT NULL,
    channel character varying(20) NOT NULL,
    contact_person character varying(150),
    contact_phone character varying(20),
    summary character varying(4000) NOT NULL,
    response character varying(2000),
    rating character varying(20) NOT NULL,
    interest character varying(20) NOT NULL,
    next_action character varying(30) NOT NULL,
    follow_up_at timestamp with time zone,
    follow_up_reminded_at timestamp with time zone,
    logged_by bigint NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT chk_outreach_contact_channel CHECK ((channel IN ('CALL', 'EMAIL', 'WHATSAPP', 'IN_PERSON', 'OTHER'))),
    CONSTRAINT chk_outreach_contact_follow_up CHECK ((((next_action)::text = 'NONE'::text) OR (follow_up_at IS NOT NULL))),
    CONSTRAINT chk_outreach_contact_interest CHECK ((interest IN ('INTERESTED', 'MAYBE', 'LATER', 'NOT_INTERESTED'))),
    CONSTRAINT chk_outreach_contact_next_action CHECK ((next_action IN ('NONE', 'CALL_BACK', 'DEMO_REQUESTED', 'MEETING_REQUESTED'))),
    CONSTRAINT chk_outreach_contact_rating CHECK ((rating IN ('EXCELLENT', 'GOOD', 'AVERAGE', 'BAD', 'WORSE'))),
    CONSTRAINT outreach_contact_pkey PRIMARY KEY (id),
    CONSTRAINT outreach_contact_business_id_fkey FOREIGN KEY (business_id) REFERENCES outreach_business(id),
    CONSTRAINT outreach_contact_logged_by_fkey FOREIGN KEY (logged_by) REFERENCES users(id)
);

CREATE INDEX idx_outreach_contact_business ON outreach_contact USING btree (business_id, contacted_at DESC, id DESC);
CREATE INDEX idx_outreach_contact_contacted ON outreach_contact USING btree (contacted_at DESC, id DESC);
CREATE INDEX idx_outreach_contact_follow_up_due ON outreach_contact USING btree (follow_up_at) WHERE ((follow_up_at IS NOT NULL) AND (follow_up_reminded_at IS NULL));
CREATE INDEX idx_outreach_contact_logged_by ON outreach_contact USING btree (logged_by, contacted_at DESC, id DESC);

-- meeting -------------------------------------------------------------------------------------------------------------

CREATE TABLE meeting (
    id bigserial NOT NULL,
    code character varying(20) NOT NULL,
    mode character varying(20) NOT NULL,
    title character varying(150) NOT NULL,
    subject character varying(200),
    description character varying(4000),
    meeting_type character varying(30) NOT NULL,
    client_name character varying(150),
    client_description character varying(2000),
    client_contact character varying(150),
    outreach_business_id bigint,
    starts_at timestamp with time zone NOT NULL,
    ends_at timestamp with time zone NOT NULL,
    join_url character varying(1000),
    place_name character varying(150),
    address character varying(500),
    google_maps_url character varying(500),
    status character varying(20) NOT NULL,
    cancel_reason character varying(1000),
    organizer_id bigint NOT NULL,
    schedule_sequence integer DEFAULT 0 NOT NULL,
    completed_at timestamp with time zone,
    cancelled_at timestamp with time zone,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT chk_meeting_in_person CHECK ((((mode)::text <> 'IN_PERSON'::text) OR ((place_name IS NOT NULL) AND (address IS NOT NULL)))),
    CONSTRAINT chk_meeting_mode CHECK ((mode IN ('ONLINE', 'IN_PERSON'))),
    CONSTRAINT chk_meeting_online CHECK ((((mode)::text <> 'ONLINE'::text) OR (join_url IS NOT NULL))),
    CONSTRAINT chk_meeting_status CHECK ((status IN ('SCHEDULED', 'COMPLETED', 'CANCELLED'))),
    CONSTRAINT chk_meeting_times CHECK (((ends_at > starts_at) AND (ends_at <= (starts_at + '12:00:00'::interval)))),
    CONSTRAINT chk_meeting_type CHECK (((meeting_type)::text = ANY (ARRAY[('CLIENT'::character varying)::text, ('BUSINESS_TO_BUSINESS'::character varying)::text, ('BUSINESS_TO_SUPPLIER'::character varying)::text, ('CLIENT_TO_CLIENT'::character varying)::text, ('INTERNAL'::character varying)::text, ('OTHER'::character varying)::text]))),
    CONSTRAINT meeting_pkey PRIMARY KEY (id),
    CONSTRAINT uk_meeting_code UNIQUE (code),
    CONSTRAINT meeting_organizer_id_fkey FOREIGN KEY (organizer_id) REFERENCES users(id),
    CONSTRAINT meeting_outreach_business_id_fkey FOREIGN KEY (outreach_business_id) REFERENCES outreach_business(id)
);

CREATE SEQUENCE meeting_code_seq;

CREATE INDEX idx_meeting_client_trgm ON meeting USING gin (lower((client_name)::text) gin_trgm_ops);
CREATE INDEX idx_meeting_code_trgm ON meeting USING gin (lower((code)::text) gin_trgm_ops);
CREATE INDEX idx_meeting_starts ON meeting USING btree (starts_at DESC, id DESC);
CREATE INDEX idx_meeting_status_starts ON meeting USING btree (status, starts_at, id);
CREATE INDEX idx_meeting_title_trgm ON meeting USING gin (lower((title)::text) gin_trgm_ops);

-- meeting_attendee ----------------------------------------------------------------------------------------------------

CREATE TABLE meeting_attendee (
    id bigserial NOT NULL,
    meeting_id bigint NOT NULL,
    employee_id bigint NOT NULL,
    user_id bigint NOT NULL,
    organizer boolean DEFAULT false NOT NULL,
    status character varying(20) NOT NULL,
    invited_at timestamp with time zone NOT NULL,
    decline_deadline timestamp with time zone NOT NULL,
    declined_at timestamp with time zone,
    decline_reason character varying(1000),
    replaced_by_id bigint,
    feedback_submitted_at timestamp with time zone,
    reminder_due_at timestamp with time zone,
    reminder_sent_at timestamp with time zone,
    email_pending character varying(30),
    email_seq integer DEFAULT 0 NOT NULL,
    email_attempts integer DEFAULT 0 NOT NULL,
    email_claimed_until timestamp with time zone,
    email_last_attempt_at timestamp with time zone,
    email_sent_at timestamp with time zone,
    email_error character varying(500),
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT chk_meeting_attendee_email CHECK ((email_pending IN ('INVITE', 'UPDATED', 'REMINDER', 'CANCELLED', 'FEEDBACK_REQUEST'))),
    CONSTRAINT chk_meeting_attendee_status CHECK ((status IN ('INVITED', 'DECLINED', 'REPLACED'))),
    CONSTRAINT meeting_attendee_pkey PRIMARY KEY (id),
    CONSTRAINT meeting_attendee_employee_id_fkey FOREIGN KEY (employee_id) REFERENCES employee(id),
    CONSTRAINT meeting_attendee_meeting_id_fkey FOREIGN KEY (meeting_id) REFERENCES meeting(id) ON DELETE CASCADE,
    CONSTRAINT meeting_attendee_replaced_by_id_fkey FOREIGN KEY (replaced_by_id) REFERENCES meeting_attendee(id),
    CONSTRAINT meeting_attendee_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE INDEX idx_meeting_attendee_email_pending ON meeting_attendee USING btree (id) WHERE (email_pending IS NOT NULL);
CREATE INDEX idx_meeting_attendee_employee_live ON meeting_attendee USING btree (employee_id, meeting_id) WHERE ((status)::text = 'INVITED'::text);
CREATE INDEX idx_meeting_attendee_feedback_pending ON meeting_attendee USING btree (user_id) WHERE (((status)::text = 'INVITED'::text) AND (feedback_submitted_at IS NULL));
CREATE INDEX idx_meeting_attendee_meeting ON meeting_attendee USING btree (meeting_id, id);
CREATE INDEX idx_meeting_attendee_reminder_due ON meeting_attendee USING btree (reminder_due_at) WHERE (reminder_due_at IS NOT NULL);
CREATE INDEX idx_meeting_attendee_user ON meeting_attendee USING btree (user_id, meeting_id) WHERE ((status)::text <> 'REPLACED'::text);
CREATE UNIQUE INDEX uk_meeting_attendee_employee ON meeting_attendee USING btree (meeting_id, employee_id) WHERE ((status)::text <> 'REPLACED'::text);

-- meeting_feedback ----------------------------------------------------------------------------------------------------

CREATE TABLE meeting_feedback (
    id bigserial NOT NULL,
    meeting_id bigint NOT NULL,
    attendee_id bigint NOT NULL,
    agenda character varying(4000) NOT NULL,
    feedback character varying(4000) NOT NULL,
    decisions character varying(4000) NOT NULL,
    deal_confirmed boolean DEFAULT false NOT NULL,
    deal_notes character varying(2000),
    meeting_type character varying(30),
    rating character varying(20),
    submitted_at timestamp with time zone NOT NULL,
    CONSTRAINT chk_meeting_feedback_rating CHECK ((rating IN ('EXCELLENT', 'GOOD', 'AVERAGE', 'BAD', 'WORSE'))),
    CONSTRAINT chk_meeting_feedback_type CHECK (((meeting_type)::text = ANY (ARRAY[('CLIENT'::character varying)::text, ('BUSINESS_TO_BUSINESS'::character varying)::text, ('BUSINESS_TO_SUPPLIER'::character varying)::text, ('CLIENT_TO_CLIENT'::character varying)::text, ('INTERNAL'::character varying)::text, ('OTHER'::character varying)::text]))),
    CONSTRAINT meeting_feedback_pkey PRIMARY KEY (id),
    CONSTRAINT uk_meeting_feedback_attendee UNIQUE (attendee_id),
    CONSTRAINT meeting_feedback_attendee_id_fkey FOREIGN KEY (attendee_id) REFERENCES meeting_attendee(id),
    CONSTRAINT meeting_feedback_meeting_id_fkey FOREIGN KEY (meeting_id) REFERENCES meeting(id) ON DELETE CASCADE
);

CREATE INDEX idx_meeting_feedback_meeting ON meeting_feedback USING btree (meeting_id, id);

-- Foreign keys between tables of this file that point at each other -------------------------------------------------

ALTER TABLE outreach_business
    ADD CONSTRAINT fk_outreach_business_latest_contact FOREIGN KEY (latest_contact_id) REFERENCES outreach_contact(id);
