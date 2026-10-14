package com.tinqa.procurement.employee.service;

import com.tinqa.procurement.common.exception.ApiException;
import com.tinqa.procurement.common.response.ApiResponse;
import com.tinqa.procurement.common.validation.PhoneNumbers;
import com.tinqa.procurement.employee.model.Employee;
import com.tinqa.procurement.employee.repository.EmployeeRepository;
import com.tinqa.procurement.security.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Checks that a person's identifying details are not already used by someone else, and reports every
 * conflict at once, each on its own field, so the form can show exactly what to change.
 */
@Component
@RequiredArgsConstructor
public class EmployeeContactValidator {

    public static final String DUPLICATE_VALUE = "DUPLICATE_VALUE";

    private final EmployeeRepository employeeRepository;
    private final UserRepository userRepository;

    /**
     * Field names as the client sent them, so errors land on the right inputs.
     */
    public record Fields(String phone, String alternatePhone, String personalEmail) {
        public static final Fields EMPLOYEE = new Fields("phone", "alternatePhone", "personalEmail");
        public static final Fields ADMIN_PROFILE = new Fields("primaryPhone", "alternatePhone", "personalEmail");
    }

    public void checkNewAccount(String username, String email, List<ApiResponse.ApiErrorItem> errors) {
        if (username != null && userRepository.existsByUsernameIgnoreCase(username.trim())) {
            errors.add(error("username", "Username '" + username.trim() + "' is already taken. Choose a different username."));
        }
        if (email != null && userRepository.existsByEmailIgnoreCase(email.trim())) {
            errors.add(error("email", "Email '" + email.trim() + "' is already used by another account."));
        }
    }

    /**
     * @param employeeId the employee being edited, or null for a new one
     */
    public void checkContacts(Long employeeId, String phone, String alternatePhone, String personalEmail,
                              Fields fields, List<ApiResponse.ApiErrorItem> errors) {
        long exclude = employeeId == null ? -1L : employeeId;
        String normalizedPhone = PhoneNumbers.normalize(phone);
        String normalizedAlternate = PhoneNumbers.normalize(alternatePhone);

        if (normalizedPhone != null) {
            phoneOwner(normalizedPhone, exclude).ifPresent(owner -> errors.add(error(fields.phone(),
                    "Phone number " + normalizedPhone + " is already registered to employee " + owner.getEmployeeCode() + ".")));
        }
        if (normalizedAlternate != null) {
            if (Objects.equals(normalizedAlternate, normalizedPhone)) {
                errors.add(error(fields.alternatePhone(), "Alternate phone number must be different from the phone number."));
            } else {
                phoneOwner(normalizedAlternate, exclude).ifPresent(owner -> errors.add(error(fields.alternatePhone(),
                        "Alternate phone number " + normalizedAlternate + " is already registered to employee "
                                + owner.getEmployeeCode() + ".")));
            }
        }
        if (personalEmail != null && !personalEmail.isBlank()) {
            employeeRepository.findFirstByPersonalEmailIgnoreCaseAndIdNot(personalEmail.trim(), exclude)
                    .ifPresent(owner -> errors.add(error(fields.personalEmail(),
                            "Personal email '" + personalEmail.trim() + "' is already used by employee " + owner.getEmployeeCode() + ".")));
        }
    }

    public void throwIfAny(List<ApiResponse.ApiErrorItem> errors) {
        if (errors.isEmpty()) {
            return;
        }
        String message = errors.size() == 1
                ? errors.getFirst().getMessage()
                : "Some details already belong to someone else: " + String.join(" ", errors.stream().map(ApiResponse.ApiErrorItem::getMessage).toList());
        throw new ApiException(message, DUPLICATE_VALUE, HttpStatus.CONFLICT, new ArrayList<>(errors));
    }

    private java.util.Optional<Employee> phoneOwner(String normalizedPhone, long excludeEmployeeId) {
        return employeeRepository.findUsingAnyPhone(PhoneNumbers.storedVariants(normalizedPhone), excludeEmployeeId)
                .stream().findFirst();
    }

    private ApiResponse.ApiErrorItem error(String field, String message) {
        return new ApiResponse.ApiErrorItem(field, message);
    }
}
