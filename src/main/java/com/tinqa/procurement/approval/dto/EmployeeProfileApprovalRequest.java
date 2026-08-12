package com.tinqa.procurement.approval.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmployeeProfileApprovalRequest {

    @NotBlank(message = "Decision is required")
    @Pattern(
            regexp = "APPROVE|REJECT",
            message = "Decision must be APPROVE or REJECT"
    )
    private String decision;

    private String rejectionReason;
}