package com.tinqa.procurement.payroll;

import com.tinqa.procurement.employee.model.Employee;
import com.tinqa.procurement.security.model.User;

/** Where an employee's payslip is emailed. */
final class PayslipEmails {

    private PayslipEmails() {
    }

    /**
     * The employee's personal email (verified when the account was created), else the email of the account they sign
     * in with; null when they have neither (the payslip is still downloadable).
     */
    static String recipient(Employee employee, User account) {
        if (employee.getPersonalEmail() != null && !employee.getPersonalEmail().isBlank()) {
            return employee.getPersonalEmail().trim();
        }
        if (account != null && account.getEmail() != null && !account.getEmail().isBlank()) {
            return account.getEmail().trim();
        }
        return null;
    }
}
