package com.tinqa.procurement.employee.enums;

/**
 * Lifecycle of an employee record (stored by name in employee.status).
 */
public enum EmployeeStatus {
    FIRST_LOGIN,
    ACTIVE,
    BLOCKED,
    IN_ACTIVE,
    WAITING_FOR_DELETION,
    APPROVAL_PENDING,
    // Deleted by an employee approver: the record is kept (read-only, account disabled) and listed only on request
    DELETED
}
