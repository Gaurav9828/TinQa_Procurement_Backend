-- ==========================================
-- V17: client demos: the client's own phone and email, dated stays and travel, and the extra budget's reason
-- ==========================================
-- A client visited has its own phone and email (the hotel's front desk, the office's reception), besides its contact
-- person's. They tell two visits to the same client apart from visits to another one: the same client can't be
-- visited twice in a row on one day (make it one longer visit, or visit another client in between).
--
-- Stays and travel record the date as well as the time they start and end, since a night's stay or an overnight
-- journey ends on the next day (check-in 6:00 pm on 30 Oct, check-out 6:00 am on 31 Oct).
--
-- The proposed budget is the day-by-day plan's total (stays, meals and travel) plus an optional extra amount, which
-- must be justified with a reason and its breakup.

ALTER TABLE client_demo_visit
    ADD COLUMN client_phone VARCHAR(20),
    ADD COLUMN client_email VARCHAR(255);

-- Existing visits only knew the contact's phone and email
UPDATE client_demo_visit SET client_phone = contact_phone, client_email = contact_email;

ALTER TABLE client_demo_visit ALTER COLUMN client_phone SET NOT NULL;

ALTER TABLE client_demo_expense
    ADD COLUMN start_date DATE,
    ADD COLUMN end_date   DATE;

-- A timed stay so far started on its day and ended the next morning when check-out was not after check-in
UPDATE client_demo_expense e
SET start_date = d.start_date + (e.day_number - 1),
    end_date   = d.start_date + (e.day_number - 1) + CASE WHEN e.end_time <= e.start_time THEN 1 ELSE 0 END
FROM client_demo d
WHERE d.id = e.demo_id
  AND e.start_time IS NOT NULL
  AND e.day_number IS NOT NULL;

-- A stay or journey without its day can't be dated
UPDATE client_demo_expense SET start_time = NULL, end_time = NULL WHERE start_time IS NOT NULL AND start_date IS NULL;

ALTER TABLE client_demo_expense
    DROP CONSTRAINT chk_client_demo_expense_times,
    ADD CONSTRAINT chk_client_demo_expense_times CHECK (
        (start_time IS NULL) = (end_time IS NULL)
            AND (start_date IS NULL) = (start_time IS NULL)
            AND (end_date IS NULL) = (start_time IS NULL)
            AND (end_date IS NULL OR end_date + end_time > start_date + start_time));

ALTER TABLE client_demo
    ADD COLUMN extra_budget        NUMERIC(12, 2) NOT NULL DEFAULT 0,
    ADD COLUMN extra_budget_reason VARCHAR(1000);

-- What existing budgets hold beyond their planned stays, meals and travel
UPDATE client_demo d
SET extra_budget = GREATEST(d.proposed_budget - COALESCE((SELECT SUM(e.amount)
                                                          FROM client_demo_expense e
                                                          WHERE e.demo_id = d.id
                                                            AND e.kind IN ('STAY', 'FOOD', 'TRAVEL')), 0), 0);

UPDATE client_demo
SET extra_budget_reason = 'Budget set before the day-by-day breakdown was required.'
WHERE extra_budget > 0;

ALTER TABLE client_demo
    ADD CONSTRAINT chk_client_demo_extra_budget CHECK (extra_budget >= 0 AND (extra_budget = 0 OR extra_budget_reason IS NOT NULL));
