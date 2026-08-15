package com.tinqa.procurement.employee.service.impl;

import com.tinqa.procurement.common.exception.BadRequestException;
import com.tinqa.procurement.common.exception.ConflictException;
import com.tinqa.procurement.common.exception.ResourceNotFoundException;
import com.tinqa.procurement.employee.constants.EmployeeConstants;
import com.tinqa.procurement.employee.dto.CreateEmployeeRequest;
import com.tinqa.procurement.employee.dto.EmployeeResponse;
import com.tinqa.procurement.employee.dto.UpdateEmployeeRequest;
import com.tinqa.procurement.employee.model.Employee;
import com.tinqa.procurement.employee.repository.EmployeeRepository;
import com.tinqa.procurement.employee.service.EmployeeService;
import com.tinqa.procurement.security.model.AuthClient;
import com.tinqa.procurement.security.model.Role;
import com.tinqa.procurement.security.model.User;
import com.tinqa.procurement.security.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class EmployeeServiceImpl implements EmployeeService {

    private static final String DEFAULT_PASSWORD = "TinQa";

    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public EmployeeResponse createEmployee(CreateEmployeeRequest request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new ConflictException("Username '" + request.getUsername() + "' is already taken");
        }
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new ConflictException("Email '" + request.getEmail() + "' is already in use");
        }

        Role userRole = Role.ADMIN_L1;
        if (request.getRole() != null && !request.getRole().isBlank()) {
            try {
                userRole = Role.valueOf(request.getRole().toUpperCase());
            } catch (IllegalArgumentException e) {
                userRole = Role.ADMIN_L1;
            }
        }

        User user = User.builder()
                .username(request.getUsername())
                .email(request.getUsername() + "@tinqa.com")
                .password(passwordEncoder.encode(DEFAULT_PASSWORD))
                .role(userRole)
                .authClient(AuthClient.WEB)
                .enabled(true)
                .accountNonLocked(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        User savedUser = userRepository.save(user);

        String employeeCode = generateNextEmployeeCode();

        String displayName = request.getDisplayName();
        if (displayName == null || displayName.isBlank()) {
            displayName = (request.getFirstName() + " " +
                    (request.getMiddleName() != null ? request.getMiddleName() + " " : "") +
                    request.getLastName()).trim();
        }

        Employee employee = Employee.builder()
                .userId(savedUser.getId())
                .employeeCode(employeeCode)
                .firstName(request.getFirstName())
                .middleName(request.getMiddleName())
                .lastName(request.getLastName())
                .displayName(displayName)
                .dateOfBirth(request.getDateOfBirth())
                .gender(request.getGender())
                .designation(request.getDesignation())
                .department(request.getDepartment())
                .employmentType(request.getEmploymentType())
                .joiningDate(request.getJoiningDate())
                .salaryAmount(request.getSalaryAmount() != null ? request.getSalaryAmount() : BigDecimal.ZERO)
                .salaryCurrency(request.getSalaryCurrency() != null ? request.getSalaryCurrency() : "INR")
                .phone(request.getPhone())
                .alternatePhone(request.getAlternatePhone())
                .personalEmail(request.getPersonalEmail())
                .status(userRole == Role.ADMIN_L2 ? EmployeeConstants.APPROVAL_PENDING : EmployeeConstants.FIRST_LOGIN)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        Employee savedEmployee = employeeRepository.save(employee);
        return mapToResponse(savedUser, savedEmployee);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<EmployeeResponse> getAllEmployees(EmployeeConstants status, String search, Pageable pageable) {
        Page<Employee> employees;

        if (status != null && search != null && !search.isBlank()) {
            employees = employeeRepository.findByStatusAndSearchTerm(status, search.trim().toLowerCase(), pageable);
        } else if (status != null) {
            employees = employeeRepository.findByStatus(status, pageable);
        } else if (search != null && !search.isBlank()) {
            employees = employeeRepository.findBySearchTerm(search.trim().toLowerCase(), pageable);
        } else {
            employees = employeeRepository.findAll(pageable);
        }

        return employees.map(emp -> {
            User user = userRepository.findById(emp.getUserId()).orElse(null);
            return mapToResponse(user, emp);
        });
    }

    @Override
    @Transactional(readOnly = true)
    public EmployeeResponse getEmployeeById(Long id) {
        Employee employee = findEmployeeOrThrow(id);
        User user = userRepository.findById(employee.getUserId()).orElse(null);
        return mapToResponse(user, employee);
    }

    @Override
    @Transactional
    public EmployeeResponse updateEmployee(Long id, UpdateEmployeeRequest request) {
        Employee employee = findEmployeeOrThrow(id);

        if (request.getFirstName() != null) employee.setFirstName(request.getFirstName());
        if (request.getMiddleName() != null) employee.setMiddleName(request.getMiddleName());
        if (request.getLastName() != null) employee.setLastName(request.getLastName());
        if (request.getDisplayName() != null) employee.setDisplayName(request.getDisplayName());
        if (request.getDateOfBirth() != null) employee.setDateOfBirth(request.getDateOfBirth());
        if (request.getGender() != null) employee.setGender(request.getGender());
        if (request.getDesignation() != null) employee.setDesignation(request.getDesignation());
        if (request.getDepartment() != null) employee.setDepartment(request.getDepartment());
        if (request.getEmploymentType() != null) employee.setEmploymentType(request.getEmploymentType());
        if (request.getJoiningDate() != null) employee.setJoiningDate(request.getJoiningDate());
        if (request.getLeavingDate() != null) employee.setLeavingDate(request.getLeavingDate());
        if (request.getSalaryAmount() != null) employee.setSalaryAmount(request.getSalaryAmount());
        if (request.getSalaryCurrency() != null) employee.setSalaryCurrency(request.getSalaryCurrency());
        if (request.getPhone() != null) employee.setPhone(request.getPhone());
        if (request.getAlternatePhone() != null) employee.setAlternatePhone(request.getAlternatePhone());
        if (request.getPersonalEmail() != null) employee.setPersonalEmail(request.getPersonalEmail());
        if (request.getStatus() != null) employee.setStatus(request.getStatus());

        employee.setUpdatedAt(LocalDateTime.now());
        Employee updatedEmployee = employeeRepository.save(employee);
        User user = userRepository.findById(updatedEmployee.getUserId()).orElse(null);

        return mapToResponse(user, updatedEmployee);
    }

    @Override
    @Transactional
    public EmployeeResponse requestEmployeeDeletion(Long id) {
        Employee employee = findEmployeeOrThrow(id);

        if (employee.getStatus() == EmployeeConstants.WAITING_FOR_DELETION) {
            throw new BadRequestException("Employee is already waiting for deletion");
        }

        employee.setStatus(EmployeeConstants.WAITING_FOR_DELETION);
        employee.setUpdatedAt(LocalDateTime.now());
        Employee savedEmployee = employeeRepository.save(employee);

        User user = userRepository.findById(savedEmployee.getUserId()).orElse(null);
        return mapToResponse(user, savedEmployee);
    }

    @Override
    @Transactional
    public void finalizeDeleteEmployee(Long id) {
        Employee employee = findEmployeeOrThrow(id);

        if (employee.getStatus() != EmployeeConstants.WAITING_FOR_DELETION) {
            throw new BadRequestException("Employee status must be 'WAITING_FOR_DELETION' to finalize deletion");
        }

        Long userId = employee.getUserId();

        // 1. Delete Employee profile
        employeeRepository.delete(employee);

        // 2. Delete corresponding Security User
        if (userId != null && userRepository.existsById(userId)) {
            userRepository.deleteById(userId);
        }
    }

    private Employee findEmployeeOrThrow(Long id) {
        return employeeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found with ID: " + id));
    }

    private String generateNextEmployeeCode() {
        Long count = employeeRepository.count();
        return String.format("EMP-%04d", count + 1);
    }

    private EmployeeResponse mapToResponse(User user, Employee employee) {
        return EmployeeResponse.builder()
                .id(employee.getId())
                .userId(employee.getUserId())
                .username(user != null ? user.getUsername() : null)
                .employeeCode(employee.getEmployeeCode())
                .firstName(employee.getFirstName())
                .middleName(employee.getMiddleName())
                .lastName(employee.getLastName())
                .displayName(employee.getDisplayName())
                .dateOfBirth(employee.getDateOfBirth())
                .gender(employee.getGender())
                .designation(employee.getDesignation())
                .department(employee.getDepartment())
                .employmentType(employee.getEmploymentType())
                .joiningDate(employee.getJoiningDate())
                .leavingDate(employee.getLeavingDate())
                .salaryAmount(employee.getSalaryAmount())
                .salaryCurrency(employee.getSalaryCurrency())
                .phone(employee.getPhone())
                .alternatePhone(employee.getAlternatePhone())
                .personalEmail(employee.getPersonalEmail())
                .workEmail(Objects.nonNull(user) ? user.getEmail() : "")
                .status(employee.getStatus())
                .createdAt(employee.getCreatedAt())
                .updatedAt(employee.getUpdatedAt())
                .build();
    }
}