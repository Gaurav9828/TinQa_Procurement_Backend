-- Designations become fixed codes that decide each employee's permission groups
-- (com.tinqa.procurement.security.access.Designation / DesignationAccess).
--
-- Every existing employee becomes SUPER_ADMIN. Combined with their admin level this keeps exactly what they could
-- do before: ADMIN_L1 = everything except approvals, ADMIN_L2 = everything. Re-assign real designations in
-- Employee Management afterwards. Mobile accounts (DEALER, INSPECTOR) ignore the designation.
UPDATE employee
SET designation = 'SUPER_ADMIN'
WHERE designation NOT IN ('SUPER_ADMIN', 'STORE_MANAGER', 'DEALER_MANAGER', 'CATALOG_SPECIALIST', 'FULFILLMENT_SPECIALIST',
                          'DELIVERY_RIDER', 'FLEET_COORDINATOR', 'CUSTOMER_SUPPORT', 'FINANCIAL_AUDITOR');

-- Designation is no longer changed through the self-service profile approval flow
UPDATE employee_profile_change_request SET designation = NULL WHERE designation IS NOT NULL;
