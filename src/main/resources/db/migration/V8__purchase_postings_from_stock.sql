-- A dealer purchase is booked when goods are received into stock, for the units that passed testing, instead of
-- the whole order on delivery. Every change to what is booked for an order is one append-only row here, written in
-- the same transaction as the stock change; the Ecommerce company account reads them (/v1/internal/finance).
--
-- amount is signed: positive = more paid to the dealer (money out), negative = money back. The sum per order is what
-- the company account holds for it.
CREATE TABLE purchase_postings
(
    id                BIGSERIAL PRIMARY KEY,
    order_id          BIGINT                   NOT NULL REFERENCES orders (id),
    stock_id          BIGINT REFERENCES stock (id),
    reason            VARCHAR(30)              NOT NULL,
    units_passed      NUMERIC(14, 3)           NOT NULL,
    amount            NUMERIC(14, 2)           NOT NULL,
    occurred_on       DATE                     NOT NULL,
    -- Set for the rows that mirror postings the old rule (whole order on DELIVERED / REFUND_COMPLETED) already made:
    -- they keep that posting key, so the company account does not post them a second time
    legacy_history_id BIGINT UNIQUE REFERENCES order_status_history (id),
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_purchase_postings_created ON purchase_postings (created_at, id);
CREATE INDEX idx_purchase_postings_order ON purchase_postings (order_id);

-- 1. What the old rule already booked: the whole order total out on each DELIVERED, back on each REFUND_COMPLETED
INSERT INTO purchase_postings (order_id, stock_id, reason, units_passed, amount, occurred_on, legacy_history_id, created_at)
SELECT h.order_id,
       NULL,
       CASE h.to_status WHEN 'DELIVERED' THEN 'LEGACY_DELIVERED' ELSE 'LEGACY_REFUND' END,
       o.order_quantity,
       CASE h.to_status WHEN 'DELIVERED' THEN o.total_price ELSE -o.total_price END,
       COALESCE(h.actual_delivery, (h.changed_at AT TIME ZONE 'Asia/Kolkata')::date),
       h.id,
       h.changed_at
FROM order_status_history h
         JOIN orders o ON o.id = h.order_id
WHERE h.to_status IN ('DELIVERED', 'REFUND_COMPLETED')
  AND o.total_price > 0;

-- 2. Orders already received into stock: true up to the passed units (unit price x passed, plus shipment when any
-- unit passed; nothing for a rejected entry)
INSERT INTO purchase_postings (order_id, stock_id, reason, units_passed, amount, occurred_on, legacy_history_id, created_at)
SELECT t.order_id, t.stock_id, 'STOCK_TRUE_UP', t.units_passed, t.target - t.booked,
       (now() AT TIME ZONE 'Asia/Kolkata')::date, NULL, now()
FROM (SELECT o.id                                                           AS order_id,
             s.id                                                           AS stock_id,
             CASE WHEN s.approval_status = 'REJECTED' THEN 0 ELSE s.units_passed_test END AS units_passed,
             CASE
                 WHEN s.approval_status = 'REJECTED' OR s.units_passed_test <= 0 THEN 0
                 ELSE round(o.unit_price * s.units_passed_test, 2) + o.shipment_price
                 END                                                        AS target,
             COALESCE((SELECT SUM(p.amount) FROM purchase_postings p WHERE p.order_id = o.id), 0) AS booked
      FROM stock s
               JOIN orders o ON o.order_number = s.order_number) t
WHERE t.target <> t.booked;
