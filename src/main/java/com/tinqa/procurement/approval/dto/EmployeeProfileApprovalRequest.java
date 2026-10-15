package com.tinqa.procurement.approval.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.tinqa.procurement.common.enums.ApprovalStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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
            regexp = "APPROVED|REJECTED",
            message = "Decision must be APPROVED or REJECTED"
    )
    private String decision;

    @Size(max = 500, message = "Rejection reason cannot exceed 500 characters")
    private String rejectionReason;

    @JsonIgnore
    public boolean isApproved() {
        return ApprovalStatus.APPROVED.name().equals(decision);
    }
}