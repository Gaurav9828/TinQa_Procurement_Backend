-- Items can belong to several categories, and dealers are assigned the same (item) categories, so a dealer only
-- quotes for items in a category they deal in.

-- 1. Items: one category column becomes a many-to-many mapping
CREATE TABLE item_category_mappings
(
    item_id     BIGINT NOT NULL REFERENCES items (id) ON DELETE CASCADE,
    category_id BIGINT NOT NULL REFERENCES categories (id) ON DELETE CASCADE,
    PRIMARY KEY (item_id, category_id)
);

CREATE INDEX idx_item_category_mappings_category ON item_category_mappings (category_id);

INSERT INTO item_category_mappings (item_id, category_id)
SELECT id, category_id
FROM items;

ALTER TABLE items DROP COLUMN category_id;

-- 2. Dealers: their own DEALER categories give way to item categories. A dealer category is replaced by the item
-- category with the same code (or else the same name, ignoring case); one with no match becomes an item category.
CREATE TEMPORARY TABLE dealer_category_remap AS
SELECT dc.id AS old_id,
       COALESCE(
               (SELECT ic.id FROM categories ic WHERE ic.type = 'ITEM' AND ic.code = dc.code),
               (SELECT ic.id FROM categories ic WHERE ic.type = 'ITEM' AND lower(ic.name) = lower(dc.name)
                ORDER BY ic.id LIMIT 1)
       )     AS new_id
FROM categories dc
WHERE dc.type = 'DEALER';

INSERT INTO dealer_category_mappings (dealer_id, category_id)
SELECT DISTINCT m.dealer_id, r.new_id
FROM dealer_category_mappings m
         JOIN dealer_category_remap r ON r.old_id = m.category_id
WHERE r.new_id IS NOT NULL
ON CONFLICT DO NOTHING;

-- Also removes their dealer mappings (ON DELETE CASCADE)
DELETE FROM categories c
    USING dealer_category_remap r
WHERE c.id = r.old_id
  AND r.new_id IS NOT NULL;

UPDATE categories
SET type = 'ITEM'
WHERE type = 'DEALER';

DROP TABLE dealer_category_remap;
