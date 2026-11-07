-- Area: supplier payments (V8__orders.sql). A cash payment to a supplier counts only once the supplier confirms, in
-- their portal, that they received it: after Level 2 confirms it, it waits in AWAITING_SUPPLIER.

ALTER TABLE supplier_payment DROP CONSTRAINT supplier_payment_status_check;
ALTER TABLE supplier_payment ADD CONSTRAINT supplier_payment_status_check
    CHECK ((status IN ('PENDING_APPROVAL', 'AWAITING_SUPPLIER', 'APPROVED', 'REJECTED', 'WITHDRAWN')));

ALTER TABLE supplier_payment
    ADD COLUMN supplier_responded_at timestamp with time zone,
    ADD COLUMN supplier_response_reason character varying(500);

-- The supplier portal's "cash payment to confirm" flag per order (SupplierPaymentRepository.countAwaitingSupplier)
CREATE INDEX idx_supplier_payment_awaiting_supplier ON supplier_payment USING btree (order_id)
    WHERE ((status)::text = 'AWAITING_SUPPLIER'::text);
