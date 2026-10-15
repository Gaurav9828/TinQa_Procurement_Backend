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
    APPROVAL_PENDING
}
