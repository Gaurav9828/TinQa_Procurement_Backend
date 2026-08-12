package com.tinqa.procurement.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.Set;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminProfileResponse {

    private Long employeeId;

    private String employeeCode;

    private String displayName;

    private String primaryPhone;

    private String alternatePhone;

    private String personalEmail;

    private String firstName;

    private String middleName;

    private String lastName;

    private String department;

    private String designation;

    private String workEmail;

    private String username;

    private String gender;

    private String employmentType;

    private LocalDate joiningDate;

    private LocalDate dateOfBirth;

    private String status;

    private boolean approvalPending;

    private Set<String> pendingApprovalFields;
}