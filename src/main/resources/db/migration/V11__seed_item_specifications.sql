INSERT INTO specification_definition (item_type_id, code, name, description, data_type, unit, required, display_order, created_at, updated_at)
SELECT id, 'LENGTH', 'Length', 'Length of the item.', 'DECIMAL', 'MM', FALSE, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM item_type
WHERE code = 'STONE';

INSERT INTO specification_definition (item_type_id, code, name, description, data_type, unit, required, display_order, created_at, updated_at)
SELECT id, 'WIDTH', 'Width', 'Width of the item.', 'DECIMAL', 'MM', FALSE, 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM item_type
WHERE code = 'STONE';

INSERT INTO specification_definition (item_type_id, code, name, description, data_type, unit, required, display_order, created_at, updated_at)
SELECT id, 'THICKNESS', 'Thickness', 'Thickness of the item.', 'DECIMAL', 'MM', TRUE, 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM item_type
WHERE code = 'STONE';

INSERT INTO specification_definition (item_type_id, code, name, description, data_type, unit, required, display_order, created_at, updated_at)
SELECT id, 'FINISH', 'Finish', 'Surface finish of the stone.', 'OPTION', NULL, TRUE, 4, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM item_type
WHERE code = 'STONE';

INSERT INTO specification_definition (item_type_id, code, name, description, data_type, unit, required, display_order, created_at, updated_at)
SELECT id, 'GRADE', 'Grade', 'Quality grade of the stone.', 'OPTION', NULL, TRUE, 5, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM item_type
WHERE code = 'STONE';

INSERT INTO specification_definition (item_type_id, code, name, description, data_type, unit, required, display_order, created_at, updated_at)
SELECT id, 'WATTAGE', 'Wattage', 'Power consumption of the LED.', 'DECIMAL', 'W', TRUE, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM item_type
WHERE code = 'LED';

INSERT INTO specification_definition (item_type_id, code, name, description, data_type, unit, required, display_order, created_at, updated_at)
SELECT id, 'VOLTAGE', 'Voltage', 'Operating voltage of the LED.', 'DECIMAL', 'V', TRUE, 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM item_type
WHERE code = 'LED';

INSERT INTO specification_definition (item_type_id, code, name, description, data_type, unit, required, display_order, created_at, updated_at)
SELECT id, 'COLOR_TEMPERATURE', 'Color Temperature', 'Color temperature of the LED.', 'DECIMAL', 'K', FALSE, 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM item_type
WHERE code = 'LED';

INSERT INTO specification_definition (item_type_id, code, name, description, data_type, unit, required, display_order, created_at, updated_at)
SELECT id, 'LUMENS', 'Lumens', 'Light output of the LED.', 'DECIMAL', 'LM', FALSE, 4, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM item_type
WHERE code = 'LED';

INSERT INTO specification_definition (item_type_id, code, name, description, data_type, unit, required, display_order, created_at, updated_at)
SELECT id, 'RESOLUTION', 'Resolution', 'Native image resolution of the projector.', 'OPTION', NULL, TRUE, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM item_type
WHERE code = 'PROJECTOR';

INSERT INTO specification_definition (item_type_id, code, name, description, data_type, unit, required, display_order, created_at, updated_at)
SELECT id, 'BRIGHTNESS', 'Brightness', 'Brightness rating of the projector.', 'DECIMAL', 'LM', TRUE, 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM item_type
WHERE code = 'PROJECTOR';

INSERT INTO specification_definition (item_type_id, code, name, description, data_type, unit, required, display_order, created_at, updated_at)
SELECT id, 'THROW_RATIO', 'Throw Ratio', 'Projection throw ratio.', 'DECIMAL', NULL, FALSE, 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM item_type
WHERE code = 'PROJECTOR';

INSERT INTO specification_definition (item_type_id, code, name, description, data_type, unit, required, display_order, created_at, updated_at)
SELECT id, 'LAMP_LIFE', 'Lamp Life', 'Expected operating life of the projector light source.', 'DECIMAL', 'HOURS', FALSE, 4, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM item_type
WHERE code = 'PROJECTOR';

INSERT INTO specification_definition (item_type_id, code, name, description, data_type, unit, required, display_order, created_at, updated_at)
SELECT id, 'THICKNESS', 'Thickness', 'Thickness of the glass.', 'DECIMAL', 'MM', TRUE, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM item_type
WHERE code = 'GLASS';

INSERT INTO specification_definition (item_type_id, code, name, description, data_type, unit, required, display_order, created_at, updated_at)
SELECT id, 'GLASS_TYPE', 'Glass Type', 'Type of glass.', 'OPTION', NULL, TRUE, 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM item_type
WHERE code = 'GLASS';

INSERT INTO specification_option (specification_definition_id, code, name, display_order, created_at, updated_at)
SELECT id, 'POLISHED', 'Polished', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM specification_definition
WHERE code = 'FINISH';

INSERT INTO specification_option (specification_definition_id, code, name, display_order, created_at, updated_at)
SELECT id, 'HONED', 'Honed', 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM specification_definition
WHERE code = 'FINISH';

INSERT INTO specification_option (specification_definition_id, code, name, display_order, created_at, updated_at)
SELECT id, 'FLAMED', 'Flamed', 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM specification_definition
WHERE code = 'FINISH';

INSERT INTO specification_option (specification_definition_id, code, name, display_order, created_at, updated_at)
SELECT id, 'LEATHERED', 'Leathered', 4, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM specification_definition
WHERE code = 'FINISH';

INSERT INTO specification_option (specification_definition_id, code, name, display_order, created_at, updated_at)
SELECT id, 'STANDARD', 'Standard', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM specification_definition
WHERE code = 'GRADE';

INSERT INTO specification_option (specification_definition_id, code, name, display_order, created_at, updated_at)
SELECT id, 'PREMIUM', 'Premium', 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM specification_definition
WHERE code = 'GRADE';

INSERT INTO specification_option (specification_definition_id, code, name, display_order, created_at, updated_at)
SELECT id, 'LUXURY', 'Luxury', 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM specification_definition
WHERE code = 'GRADE';

INSERT INTO specification_option (specification_definition_id, code, name, display_order, created_at, updated_at)
SELECT id, '1920X1080', '1920x1080', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM specification_definition
WHERE code = 'RESOLUTION';

INSERT INTO specification_option (specification_definition_id, code, name, display_order, created_at, updated_at)
SELECT id, '3840X2160', '3840x2160', 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM specification_definition
WHERE code = 'RESOLUTION';

INSERT INTO specification_option (specification_definition_id, code, name, display_order, created_at, updated_at)
SELECT id, '7680X4320', '7680x4320', 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM specification_definition
WHERE code = 'RESOLUTION';

INSERT INTO specification_option (specification_definition_id, code, name, display_order, created_at, updated_at)
SELECT id, 'ANNEALED', 'Annealed', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM specification_definition
WHERE code = 'GLASS_TYPE';

INSERT INTO specification_option (specification_definition_id, code, name, display_order, created_at, updated_at)
SELECT id, 'TEMPERED', 'Tempered', 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM specification_definition
WHERE code = 'GLASS_TYPE';

INSERT INTO specification_option (specification_definition_id, code, name, display_order, created_at, updated_at)
SELECT id, 'LAMINATED', 'Laminated', 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM specification_definition
WHERE code = 'GLASS_TYPE';