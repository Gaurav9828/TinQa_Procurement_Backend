package com.tinqa.procurement.finance.service;

import com.tinqa.procurement.employee.model.Employee;
import com.tinqa.procurement.employee.repository.EmployeeRepository;
import com.tinqa.procurement.finance.dto.SalaryEventDTOs;
import com.tinqa.procurement.payroll.SalaryPayment;
import com.tinqa.procurement.payroll.SalaryPaymentRepository;
import com.tinqa.procurement.payroll.SalaryPaymentStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Reads confirmed salary payments for the company account, in confirmation order. A confirmed payment never changes,
 * so the payment rows themselves are the outbox.
 */
@Service
@RequiredArgsConstructor
public class SalaryEventService {

    public static final int MAX_LIMIT = 500;

    private final SalaryPaymentRepository paymentRepository;
    private final EmployeeRepository employeeRepository;

    /** Events confirmed at or after {@code since}, oldest first. The reader re-reads an overlap and de-duplicates by postingKey. */
    @Transactional(readOnly = true)
    public SalaryEventDTOs.SalaryEventPage eventsSince(Instant since, int limit) {
        int size = Math.max(1, Math.min(limit, MAX_LIMIT));
        List<SalaryPayment> rows = paymentRepository.findDecidedSince(SalaryPaymentStatus.APPROVED,
                since == null ? Instant.EPOCH : since, PageRequest.of(0, size + 1));
        boolean hasMore = rows.size() > size;
        List<SalaryPayment> page = hasMore ? rows.subList(0, size) : rows;
        Map<Long, Employee> employees = employeeRepository.findAllById(page.stream().map(SalaryPayment::getEmployeeId).toList())
                .stream().collect(Collectors.toMap(Employee::getId, Function.identity()));
        List<SalaryEventDTOs.SalaryEvent> events = page.stream().map(payment -> {
            Employee employee = employees.get(payment.getEmployeeId());
            return new SalaryEventDTOs.SalaryEvent(
                    payment.getId(),
                    payment.postingKey(),
                    employee != null ? employee.getEmployeeCode() : null,
                    employee != null ? employee.getDisplayName() : null,
                    payment.getPayYear(),
                    payment.getPayMonth(),
                    payment.getTotalEarnings(),
                    payment.getTotalDeductions(),
                    payment.getNetPay(),
                    payment.getBankReference(),
                    payment.getTransferDate(),
                    payment.getDecidedAt());
        }).toList();
        return new SalaryEventDTOs.SalaryEventPage(events, hasMore);
    }
}
