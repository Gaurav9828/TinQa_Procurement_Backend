-- The department now follows the designation (com.tinqa.procurement.security.access.Designation#department):
-- bring every existing employee in line. Pending profile change requests no longer carry a department.
UPDATE employee
SET department = CASE designation
    WHEN 'SUPER_ADMIN' THEN 'Administration'
    WHEN 'STORE_MANAGER' THEN 'Store Operations'
    WHEN 'DEALER_MANAGER' THEN 'Procurement'
    WHEN 'CATALOG_SPECIALIST' THEN 'Catalog & Content'
    WHEN 'FULFILLMENT_SPECIALIST' THEN 'Warehouse & Fulfillment'
    WHEN 'DELIVERY_RIDER' THEN 'Logistics & Delivery'
    WHEN 'FLEET_COORDINATOR' THEN 'Logistics & Delivery'
    WHEN 'CUSTOMER_SUPPORT' THEN 'Customer Support'
    WHEN 'FINANCIAL_AUDITOR' THEN 'Finance & Accounts'
    ELSE department
END;

UPDATE employee_profile_change_request SET department = NULL WHERE department IS NOT NULL AND status = 'PENDING';
