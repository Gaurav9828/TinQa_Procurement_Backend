package com.tinqa.procurement.approval.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmployeeProfileApprovalResponse {

    private Long requestId;

    private Long employeeId;

    private String employeeCode;

    private String status;

    private Long requestedBy;

    private String requestedByUsername;

    private LocalDateTime requestedAt;

    private String displayName;

    private String primaryPhone;

    private String alternatePhone;

    private String personalEmail;

    private LocalDate dateOfBirth;

    private String firstName;

    private String middleName;

    private String lastName;

    private String department;

    private String designation;

    private String gender;
}