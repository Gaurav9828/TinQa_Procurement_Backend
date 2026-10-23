package com.tinqa.procurement.company;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * The company as printed at the top of documents this service renders (payslips), from the approved company profile
 * the e-commerce service publishes. Any field but the brand name may be null.
 */
public record CompanyLetterhead(String brandName, String legalName, String address, String gstin, String pan,
                                String supportEmail, String supportPhone) {

    /** Used while the company profile can't be read: the document still renders, with the brand name only. */
    public static final CompanyLetterhead FALLBACK = new CompanyLetterhead("TinQa", null, null, null, null, null, null);

    /** The lines under the brand name, blanks left out. */
    public List<String> detailLines() {
        String contact = String.join("  |  ", Stream.of(supportEmail, supportPhone).filter(CompanyLetterhead::present).toList());
        return Stream.of(legalName, address, gstin == null ? null : "GSTIN " + gstin, pan == null ? null : "PAN " + pan, contact)
                .filter(CompanyLetterhead::present)
                .toList();
    }

    private static boolean present(String value) {
        return Objects.nonNull(value) && !value.isBlank();
    }
}
