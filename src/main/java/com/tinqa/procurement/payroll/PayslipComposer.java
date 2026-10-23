package com.tinqa.procurement.payroll;

import com.tinqa.procurement.company.CompanyLetterhead;
import com.tinqa.procurement.company.CompanyLetterheadProvider;
import com.tinqa.procurement.employee.model.Employee;
import com.tinqa.procurement.employee.repository.EmployeeRepository;
import com.tinqa.procurement.security.model.User;
import com.tinqa.procurement.security.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/** Renders a confirmed salary payment as its payslip, on demand: payslips are never stored. */
@Component
@RequiredArgsConstructor
public class PayslipComposer {

    private final EmployeeRepository employeeRepository;
    private final UserRepository userRepository;
    private final CompanyLetterheadProvider letterheadProvider;
    private final PayslipPdf payslipPdf;

    /** A rendered payslip and what an email about it needs. */
    public record PayslipFile(String filename, byte[] content, String period, String employeeName, String netPay) {
    }

    public PayslipFile render(SalaryPayment payment) {
        Employee employee = employeeRepository.findById(payment.getEmployeeId()).orElseThrow();
        CompanyLetterhead company = letterheadProvider.current();
        YearMonth period = PayrollService.periodOf(payment);
        String confirmedBy = payment.getDecidedBy() == null ? "-"
                : userRepository.findById(payment.getDecidedBy()).map(User::getUsername).orElse("-");

        List<PayslipPdf.Row> earnings = new ArrayList<>();
        List<PayslipPdf.Row> deductions = new ArrayList<>();
        earnings.add(new PayslipPdf.Row("Monthly salary", payment.getMonthlySalary()));
        for (SalaryPaymentLine line : payment.getLines()) {
            (line.getKind() == SalaryLineKind.EARNING ? earnings : deductions).add(new PayslipPdf.Row(line.getLabel(), line.getAmount()));
        }
        byte[] content = payslipPdf.render(new PayslipPdf.Payslip(company, PayrollPeriods.label(period),
                employee.getDisplayName(), employee.getEmployeeCode(), payment.getDesignation(), payment.getDepartment(),
                PayrollPeriods.date(employee.getJoiningDate()), employee.getPanNumber(),
                PayrollPeriods.date(payment.getTransferDate()), payment.getBankReference(), earnings,
                payment.getTotalEarnings(), deductions, payment.getTotalDeductions(), payment.getNetPay(), confirmedBy,
                payment.getDecidedAt() == null ? "-"
                        : PayrollPeriods.date(LocalDate.ofInstant(payment.getDecidedAt(), PayrollPeriods.BUSINESS_ZONE))));
        return new PayslipFile(fileName(company, employee, period), content, PayrollPeriods.label(period),
                employee.getDisplayName(), PayrollPeriods.rupees(payment.getNetPay()));
    }

    // "TinQa-payslip-EMP-0007-2026-10.pdf"
    private static String fileName(CompanyLetterhead company, Employee employee, YearMonth period) {
        String brand = company.brandName().replaceAll("[^A-Za-z0-9]", "");
        return (brand.isEmpty() ? "" : brand + "-") + "payslip-" + employee.getEmployeeCode().replaceAll("[^A-Za-z0-9-]", "-")
                + "-" + period + ".pdf";
    }
}
