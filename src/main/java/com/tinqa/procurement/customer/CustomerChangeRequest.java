package com.tinqa.procurement.customer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Customer care asking for a correction to a storefront customer's details. Customers live in the Ecommerce backend,
 * so the id and name are only shown to the approvers, who open the customer and make the change there.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerChangeRequest {

    @NotNull(message = "Customer is required")
    @Positive(message = "Customer is required")
    private Long customerId;

    @NotBlank(message = "Customer name is required")
    @Size(max = 150, message = "Customer name cannot exceed 150 characters")
    private String customerName;

    /** Field (see {@link CustomerField}) -> the value the customer asked for. */
    @NotEmpty(message = "Say what should change")
    private Map<CustomerField, @Size(max = 100, message = "A new value cannot exceed 100 characters") String> changes;

    @NotBlank(message = "Reason is required")
    @Size(min = 3, max = 500, message = "Reason must be 3-500 characters")
    private String reason;

    /** The customer details a Super Admin can edit (Ecommerce PATCH /api/admin/customers/{id}). */
    public enum CustomerField {
        FIRST_NAME("first name"),
        MIDDLE_NAME("middle name"),
        LAST_NAME("last name"),
        PHONE_NUMBER("phone number"),
        DOB("date of birth"),
        GENDER("gender");

        private final String label;

        CustomerField(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}
