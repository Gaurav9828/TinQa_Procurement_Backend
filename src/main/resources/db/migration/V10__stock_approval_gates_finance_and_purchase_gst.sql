-- ==========================================
-- V10: finance follows Admin L2 approval of stock, and dealer purchases carry their GST (input tax)
-- ==========================================
-- 1. Nothing is bought from a dealer until Admin L2 approves the stock entry. What is booked for an order is the value
--    of the units L2 last approved (approved_units_passed), never of units only recorded. A change to an approved entry
--    (units added, counts corrected) waits for L2 too: until then the money stays at the approved value and units
--    added are held back from use.
-- 2. Each purchase posting also carries the GST on the value it books, from the quotation's GST breakup
--    (orders.tax_breakup): CGST + SGST or IGST at the quoted rate, cess in proportion. The company account records it
--    as input tax (input tax credit) in its GST register.

-- ------------------------------------------
-- Stock: the counts Admin L2 approved
-- ------------------------------------------
ALTER TABLE stock
    ADD COLUMN approved_units_passed  NUMERIC(14, 3),
    ADD COLUMN approved_defected_units NUMERIC(14, 3),
    ADD CONSTRAINT chk_stock_approved_counts CHECK (
        (approved_units_passed IS NULL) = (approved_defected_units IS NULL)
        AND (approved_units_passed IS NULL OR (approved_units_passed >= 0 AND approved_defected_units >= 0)));

-- Entries already approved were approved with the counts they hold now
UPDATE stock
SET approved_units_passed  = units_passed_test,
    approved_defected_units = defected_units
WHERE approval_status = 'APPROVED';

-- ------------------------------------------
-- Purchase postings: GST on the booked value
-- ------------------------------------------
-- Signed like amount: the GST of the value this row adds (or, when negative, takes back)
ALTER TABLE purchase_postings
    ADD COLUMN cgst        NUMERIC(14, 2) NOT NULL DEFAULT 0,
    ADD COLUMN sgst        NUMERIC(14, 2) NOT NULL DEFAULT 0,
    ADD COLUMN igst        NUMERIC(14, 2) NOT NULL DEFAULT 0,
    ADD COLUMN cess        NUMERIC(14, 2) NOT NULL DEFAULT 0,
    -- The quotation's GST terms when the row was written (null: no GST breakup on the quotation)
    ADD COLUMN gst_rate    NUMERIC(5, 2),
    ADD COLUMN supply_type VARCHAR(12),
    ADD COLUMN hsn_sac     VARCHAR(8),
    ADD CONSTRAINT chk_purchase_posting_supply_type CHECK (supply_type IS NULL OR supply_type IN ('INTRA_STATE', 'INTER_STATE')),
    ADD CONSTRAINT chk_purchase_posting_gst_split CHECK (
        (supply_type IS NULL AND cgst = 0 AND sgst = 0 AND igst = 0)
        OR (supply_type = 'INTRA_STATE' AND igst = 0 AND cgst = sgst)
        OR (supply_type = 'INTER_STATE' AND cgst = 0 AND sgst = 0));

-- ------------------------------------------
-- True-up: what is booked = the approved value (and its GST)
-- ------------------------------------------
-- Same rule as PurchasePostingRecorder: value = unit price x approved passed units + shipment (nothing when none passed,
-- the entry is not approved, or it was rejected); GST at the quoted rate, the quoted amounts when the value is the quoted
-- taxable value. Orders delivered before stock was recorded, and entries still awaiting L2, are taken back to nothing.
WITH targets AS (
    SELECT o.id AS order_id,
           s.id AS stock_id,
           COALESCE(s.approved_units_passed, 0) AS units,
           CASE
               WHEN s.id IS NULL OR s.approval_status <> 'APPROVED' OR COALESCE(s.approved_units_passed, 0) <= 0 THEN 0
               ELSE round(o.unit_price * s.approved_units_passed, 2) + COALESCE(o.shipment_price, 0)
               END AS taxable,
           o.tax_breakup AS breakup
    FROM orders o
             -- At most one stock entry per order (stock.order_number is unique)
             LEFT JOIN stock s ON s.order_number = o.order_number
    WHERE EXISTS (SELECT 1 FROM purchase_postings p WHERE p.order_id = o.id)
       OR s.id IS NOT NULL),
     taxed AS (
         SELECT t.order_id,
                t.stock_id,
                t.units,
                t.taxable,
                CASE WHEN t.breakup ? 'gstRate' AND (t.breakup ->> 'gstRate') <> '' THEN (t.breakup ->> 'gstRate')::numeric END AS rate,
                CASE WHEN t.breakup ? 'gstRate' AND (t.breakup ->> 'gstRate') <> '' THEN t.breakup ->> 'supplyType' END AS supply_type,
                NULLIF(t.breakup ->> 'hsnSac', '') AS hsn_sac,
                NULLIF(t.breakup ->> 'taxableValue', '')::numeric AS quoted_taxable,
                COALESCE(NULLIF(t.breakup ->> 'cgst', '')::numeric, 0) AS quoted_cgst,
                COALESCE(NULLIF(t.breakup ->> 'sgst', '')::numeric, 0) AS quoted_sgst,
                COALESCE(NULLIF(t.breakup ->> 'igst', '')::numeric, 0) AS quoted_igst,
                COALESCE(NULLIF(t.breakup ->> 'cess', '')::numeric, 0) AS quoted_cess
         FROM targets t),
     gst AS (
         SELECT x.*,
                CASE
                    WHEN x.rate IS NULL OR x.taxable = 0 THEN 0
                    WHEN x.supply_type <> 'INTRA_STATE' THEN 0
                    WHEN x.quoted_taxable = x.taxable THEN x.quoted_cgst
                    ELSE round(x.taxable * x.rate / 200, 2) END AS cgst,
                CASE
                    WHEN x.rate IS NULL OR x.taxable = 0 THEN 0
                    WHEN x.supply_type <> 'INTER_STATE' THEN 0
                    WHEN x.quoted_taxable = x.taxable THEN x.quoted_igst
                    ELSE round(x.taxable * x.rate / 100, 2) END AS igst,
                CASE
                    WHEN x.rate IS NULL OR x.taxable = 0 OR COALESCE(x.quoted_taxable, 0) = 0 THEN 0
                    WHEN x.quoted_taxable = x.taxable THEN x.quoted_cess
                    ELSE round(x.quoted_cess * x.taxable / x.quoted_taxable, 2) END AS cess
         FROM taxed x),
     booked AS (
         SELECT p.order_id,
                COALESCE(SUM(p.amount), 0) AS amount,
                COALESCE(SUM(p.cgst), 0)   AS cgst,
                COALESCE(SUM(p.sgst), 0)   AS sgst,
                COALESCE(SUM(p.igst), 0)   AS igst,
                COALESCE(SUM(p.cess), 0)   AS cess
         FROM purchase_postings p
         GROUP BY p.order_id)
INSERT INTO purchase_postings (order_id, stock_id, reason, units_passed, amount, occurred_on, legacy_history_id, created_at,
                               cgst, sgst, igst, cess, gst_rate, supply_type, hsn_sac)
SELECT g.order_id,
       g.stock_id,
       'APPROVAL_TRUE_UP',
       g.units,
       g.taxable - COALESCE(b.amount, 0),
       (now() AT TIME ZONE 'Asia/Kolkata')::date,
       NULL,
       now(),
       g.cgst - COALESCE(b.cgst, 0),
       -- SGST is always equal to CGST (both half the rate)
       g.cgst - COALESCE(b.sgst, 0),
       g.igst - COALESCE(b.igst, 0),
       g.cess - COALESCE(b.cess, 0),
       g.rate,
       CASE WHEN g.rate IS NULL THEN NULL ELSE g.supply_type END,
       g.hsn_sac
FROM gst g
         LEFT JOIN booked b ON b.order_id = g.order_id
WHERE g.taxable <> COALESCE(b.amount, 0)
   OR g.cgst <> COALESCE(b.cgst, 0)
   OR g.cgst <> COALESCE(b.sgst, 0)
   OR g.igst <> COALESCE(b.igst, 0)
   OR g.cess <> COALESCE(b.cess, 0);
