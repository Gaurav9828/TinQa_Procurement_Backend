-- ==========================================
-- V22: who is already in a meeting at a time
-- ==========================================
-- Nobody can be in two meetings at once. Scheduling, changing the time of, or inviting someone to a meeting looks up
-- the live invitations (status INVITED, the organiser's included) of the people concerned, and GET /v1/meetings/busy
-- shows the organiser the same on the clock. The meeting side is read by primary key and filtered by its time.
CREATE INDEX idx_meeting_attendee_employee_live ON meeting_attendee (employee_id, meeting_id) WHERE status = 'INVITED';
