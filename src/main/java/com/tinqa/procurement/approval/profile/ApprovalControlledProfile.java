package com.tinqa.procurement.approval.profile;

import java.time.LocalDate;

/**
 * Profile fields an Admin L1 can only change with Admin L2 approval. Implemented by the profile update request
 * and by the stored change request, so both are compared and applied the same way. A null value means "not
 * requested"; for the optional middle name a blank value means "remove it".
 *
 * <p>Designation and department are deliberately not here: the designation decides the employee's permissions (and
 * fixes their department), so only Employee Management (EMPLOYEE_UPDATE) can change it, never the employee themselves.
 */
public interface ApprovalControlledProfile {

    LocalDate getDateOfBirth();

    String getFirstName();

    String getMiddleName();

    String getLastName();

    String getGender();
}
