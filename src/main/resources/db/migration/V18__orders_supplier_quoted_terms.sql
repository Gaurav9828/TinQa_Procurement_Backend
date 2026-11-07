-- Area: orders (V8__orders.sql). What the supplier quoted, so they see what TinQa changed before accepting an order.
-- The supplier's own word on each term: their quotation as they raised it, updated by each change of theirs that staff
-- approved. Staff edits never touch it. NULL when staff prepared the quotation on the supplier's behalf.
-- Keys (all text): itemId, itemName, orderQuantity, unitType, unitPrice, shipmentPrice, supplyType, gstRate,
-- expectedDelivery, advancePercent, midtermPercent, expectedPaymentMethod. See order/change/SupplierQuotedTerms.java.
-- Read only with the order it belongs to, so no index.
ALTER TABLE orders ADD COLUMN supplier_quoted_terms jsonb;
