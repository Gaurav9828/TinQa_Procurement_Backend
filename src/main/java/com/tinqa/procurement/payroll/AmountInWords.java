package com.tinqa.procurement.payroll;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Rupee amounts in words, in the Indian system (thousand, lakh, crore), as printed on payslips. */
final class AmountInWords {

    private static final String[] ONES = {"", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten",
            "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen", "Eighteen", "Nineteen"};
    private static final String[] TENS = {"", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety"};

    private AmountInWords() {
    }

    /** 45000.50 -> "Rupees Forty Five Thousand and Fifty Paise Only" */
    static String rupees(BigDecimal amount) {
        BigDecimal value = amount.setScale(2, RoundingMode.HALF_UP).abs();
        long rupees = value.longValue();
        int paise = value.remainder(BigDecimal.ONE).movePointRight(2).intValue();
        StringBuilder words = new StringBuilder("Rupees ").append(rupees == 0 ? "Zero" : words(rupees));
        if (paise > 0) {
            words.append(" and ").append(belowHundred(paise)).append(" Paise");
        }
        return words.append(" Only").toString();
    }

    private static String words(long number) {
        StringBuilder out = new StringBuilder();
        long crores = number / 10_000_000;
        long rest = number % 10_000_000;
        if (crores > 0) {
            // Above 99 crore the crores are themselves written in lakhs and thousands
            out.append(words(crores)).append(" Crore ");
        }
        append(out, rest / 100_000, "Lakh");
        rest %= 100_000;
        append(out, rest / 1_000, "Thousand");
        rest %= 1_000;
        append(out, rest / 100, "Hundred");
        rest %= 100;
        if (rest > 0) {
            out.append(belowHundred((int) rest));
        }
        return out.toString().trim();
    }

    private static void append(StringBuilder out, long count, String unit) {
        if (count > 0) {
            out.append(belowHundred((int) count)).append(' ').append(unit).append(' ');
        }
    }

    private static String belowHundred(int number) {
        if (number < 20) {
            return ONES[number];
        }
        return TENS[number / 10] + (number % 10 == 0 ? "" : " " + ONES[number % 10]);
    }
}
