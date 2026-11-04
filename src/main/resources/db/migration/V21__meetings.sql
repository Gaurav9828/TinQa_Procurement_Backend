-- ==========================================
-- V21: meetings
-- ==========================================
-- Online or face-to-face meetings, scheduled without approval by anyone with MEETING_SCHEDULE. Invitees may decline
-- within 24 hours of their invite; the organiser invites replacements. After the meeting the organiser marks it
-- completed and every attendee gives feedback once.
--
-- Emails (invite, update, reminder, cancellation, feedback request) are sent after the change committed, by a job:
-- each attendee row holds the email waiting for them (email_pending, with email_seq bumped whenever it changes, so a
-- send that raced a newer change never clears it) and the 24-hour reminder due (reminder_due_at).

CREATE SEQUENCE meeting_code_seq START WITH 1;

CREATE TABLE meeting
(
    id                   BIGSERIAL PRIMARY KEY,
    -- MTG-<year>-<number from meeting_code_seq>
    code                 VARCHAR(20)              NOT NULL,
    mode                 VARCHAR(20)              NOT NULL,
    title                VARCHAR(150)             NOT NULL,
    subject              VARCHAR(200),
    description          VARCHAR(4000),
    meeting_type         VARCHAR(30)              NOT NULL,
    client_name          VARCHAR(150),
    client_description   VARCHAR(2000),
    client_contact       VARCHAR(150),
    outreach_business_id BIGINT REFERENCES outreach_business (id),
    starts_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    ends_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    -- ONLINE: the Meet / Teams / Zoom link the organiser pasted
    join_url             VARCHAR(1000),
    -- IN_PERSON
    place_name           VARCHAR(150),
    address              VARCHAR(500),
    google_maps_url      VARCHAR(500),
    status               VARCHAR(20)              NOT NULL,
    cancel_reason        VARCHAR(1000),
    organizer_id         BIGINT                   NOT NULL REFERENCES users (id),
    -- The calendar (.ics) SEQUENCE: goes up whenever the time or place changes
    schedule_sequence    INTEGER                  NOT NULL DEFAULT 0,
    completed_at         TIMESTAMP WITH TIME ZONE,
    cancelled_at         TIMESTAMP WITH TIME ZONE,
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    version              BIGINT                   NOT NULL DEFAULT 0,
    CONSTRAINT uk_meeting_code UNIQUE (code),
    CONSTRAINT chk_meeting_mode CHECK (mode IN ('ONLINE', 'IN_PERSON')),
    CONSTRAINT chk_meeting_type CHECK (meeting_type IN ('CLIENT', 'BUSINESS_TO_BUSINESS', 'BUSINESS_TO_DEALER',
                                                        'CLIENT_TO_CLIENT', 'INTERNAL', 'OTHER')),
    CONSTRAINT chk_meeting_status CHECK (status IN ('SCHEDULED', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT chk_meeting_times CHECK (ends_at > starts_at AND ends_at <= starts_at + INTERVAL '12 hours'),
    CONSTRAINT chk_meeting_online CHECK (mode <> 'ONLINE' OR join_url IS NOT NULL),
    CONSTRAINT chk_meeting_in_person CHECK (mode <> 'IN_PERSON' OR (place_name IS NOT NULL AND address IS NOT NULL))
);

-- GET /v1/meetings: by status, soonest first (SCHEDULED) or latest first; and by date without a status
CREATE INDEX idx_meeting_status_starts ON meeting (status, starts_at, id);
CREATE INDEX idx_meeting_starts ON meeting (starts_at DESC, id DESC);
-- search: title, client or code contains the term
CREATE INDEX idx_meeting_title_trgm ON meeting USING gin (lower(title) gin_trgm_ops);
CREATE INDEX idx_meeting_client_trgm ON meeting USING gin (lower(client_name) gin_trgm_ops);
CREATE INDEX idx_meeting_code_trgm ON meeting USING gin (lower(code) gin_trgm_ops);

CREATE TABLE meeting_attendee
(
    id                    BIGSERIAL PRIMARY KEY,
    meeting_id            BIGINT                   NOT NULL REFERENCES meeting (id) ON DELETE CASCADE,
    employee_id           BIGINT                   NOT NULL REFERENCES employee (id),
    user_id               BIGINT                   NOT NULL REFERENCES users (id),
    -- The organiser attends too, and can't decline
    organizer             BOOLEAN                  NOT NULL DEFAULT FALSE,
    status                VARCHAR(20)              NOT NULL,
    invited_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    -- min(invited_at + 24 hours, starts_at); the organiser's is invited_at
    decline_deadline      TIMESTAMP WITH TIME ZONE NOT NULL,
    declined_at           TIMESTAMP WITH TIME ZONE,
    decline_reason        VARCHAR(1000),
    -- The attendee invited in place of this one (status REPLACED)
    replaced_by_id        BIGINT REFERENCES meeting_attendee (id),
    feedback_submitted_at TIMESTAMP WITH TIME ZONE,
    -- 24-hour reminder: when it is due (null once sent or no longer needed) and when it went out
    reminder_due_at       TIMESTAMP WITH TIME ZONE,
    reminder_sent_at      TIMESTAMP WITH TIME ZONE,
    -- Email outbox (see above)
    email_pending         VARCHAR(30),
    email_seq             INTEGER                  NOT NULL DEFAULT 0,
    email_attempts        INTEGER                  NOT NULL DEFAULT 0,
    email_claimed_until   TIMESTAMP WITH TIME ZONE,
    email_last_attempt_at TIMESTAMP WITH TIME ZONE,
    email_sent_at         TIMESTAMP WITH TIME ZONE,
    email_error           VARCHAR(500),
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_meeting_attendee_status CHECK (status IN ('INVITED', 'DECLINED', 'REPLACED')),
    CONSTRAINT chk_meeting_attendee_email CHECK (email_pending IN ('INVITE', 'UPDATED', 'REMINDER', 'CANCELLED',
                                                                   'FEEDBACK_REQUEST'))
);

-- One live invitation per employee per meeting
CREATE UNIQUE INDEX uk_meeting_attendee_employee ON meeting_attendee (meeting_id, employee_id) WHERE status <> 'REPLACED';
-- A meeting's attendees (detail, counts of a page of meetings)
CREATE INDEX idx_meeting_attendee_meeting ON meeting_attendee (meeting_id, id);
-- GET /v1/meetings/mine
CREATE INDEX idx_meeting_attendee_user ON meeting_attendee (user_id, meeting_id) WHERE status <> 'REPLACED';
-- The pending-feedback badge
CREATE INDEX idx_meeting_attendee_feedback_pending ON meeting_attendee (user_id)
    WHERE status = 'INVITED' AND feedback_submitted_at IS NULL;
-- The jobs: emails waiting and reminders due (both empty most of the time)
CREATE INDEX idx_meeting_attendee_email_pending ON meeting_attendee (id) WHERE email_pending IS NOT NULL;
CREATE INDEX idx_meeting_attendee_reminder_due ON meeting_attendee (reminder_due_at) WHERE reminder_due_at IS NOT NULL;

-- One attendee's feedback on a completed meeting, final once submitted
CREATE TABLE meeting_feedback
(
    id             BIGSERIAL PRIMARY KEY,
    meeting_id     BIGINT                   NOT NULL REFERENCES meeting (id) ON DELETE CASCADE,
    attendee_id    BIGINT                   NOT NULL REFERENCES meeting_attendee (id),
    agenda         VARCHAR(4000)            NOT NULL,
    feedback       VARCHAR(4000)            NOT NULL,
    decisions      VARCHAR(4000)            NOT NULL,
    deal_confirmed BOOLEAN                  NOT NULL DEFAULT FALSE,
    deal_notes     VARCHAR(2000),
    meeting_type   VARCHAR(30),
    rating         VARCHAR(20),
    submitted_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_meeting_feedback_attendee UNIQUE (attendee_id),
    CONSTRAINT chk_meeting_feedback_type CHECK (meeting_type IN ('CLIENT', 'BUSINESS_TO_BUSINESS', 'BUSINESS_TO_DEALER',
                                                                 'CLIENT_TO_CLIENT', 'INTERNAL', 'OTHER')),
    CONSTRAINT chk_meeting_feedback_rating CHECK (rating IN ('EXCELLENT', 'GOOD', 'AVERAGE', 'BAD', 'WORSE'))
);

CREATE INDEX idx_meeting_feedback_meeting ON meeting_feedback (meeting_id, id);

-- GET /v1/meetings/invitable-staff?search=: active employees by name
CREATE INDEX idx_employee_display_name_trgm ON employee USING gin (lower(display_name) gin_trgm_ops);
