package com.tinqa.procurement.employee.model;

import com.tinqa.procurement.security.model.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "employee",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_employee_user", columnNames = "user_id"),
                @UniqueConstraint(name = "uk_employee_code", columnNames = "employee_code")
        },
        indexes = {
                @Index(name = "idx_employee_status", columnList = "status"),
                @Index(name = "idx_employee_designation", columnList = "designation"),
                @Index(name = "idx_employee_department", columnList = "department")
        }
)
@Getter
@Setter
public class Employee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "user_id",
            nullable = false,
            unique = true,
            foreignKey = @ForeignKey(name = "fk_employee_user")
    )
    private User user;

    @Column(name = "employee_code", nullable = false, length = 50)
    private String employeeCode;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "middle_name", length = 100)
    private String middleName;

    @Column(name = "last_name", length = 100)
    private String lastName;

    @Column(name = "display_name", nullable = false, length = 200)
    private String displayName;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column(name = "gender", length = 20)
    private String gender;

    @Column(name = "designation", nullable = false, length = 100)
    private String designation;

    @Column(name = "department", length = 100)
    private String department;

    @Column(
            name = "employment_type",
            nullable = false,
            length = 30
    )
    private String employmentType;

    @Column(name = "joining_date", nullable = false)
    private LocalDate joiningDate;

    @Column(name = "leaving_date")
    private LocalDate leavingDate;

    @Column(name = "salary_amount", precision = 15, scale = 2)
    private BigDecimal salaryAmount;

    @Column(name = "salary_currency", length = 10)
    private String salaryCurrency;

    @Column(name = "phone", nullable = false, length = 20)
    private String phone;

    @Column(name = "alternate_phone", length = 20)
    private String alternatePhone;

    @Column(name = "personal_email", length = 150)
    private String personalEmail;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}