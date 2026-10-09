package com.tinqa.procurement.common.validation;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Rules every piece of text entering the service must pass (JSON string values, query parameters, path
 * segments, uploaded file names): no markup, script URLs, inline event handlers, encoded markup, template
 * expressions or control characters, and no links outside fields that are meant to hold one.
 */
public final class InputSafety {

    private record Rule(Pattern pattern, String message) {
    }

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    private static final List<Rule> RULES = List.of(
            new Rule(Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]"),
                    "contains control characters"),
            new Rule(Pattern.compile("<\\s*(?:[!?]|/?\\s*[a-z])", FLAGS),
                    "must not contain HTML tags"),
            new Rule(Pattern.compile("(?:java|vb|live)script\\s*:|data\\s*:\\s*[a-z]+/[a-z0-9.+-]+\\s*[;,]", FLAGS),
                    "must not contain script or data URLs"),
            new Rule(Pattern.compile("\\bon(?:load|unload|beforeunload|error|abort|click|dblclick|contextmenu|mouse[a-z]*|pointer[a-z]*"
                    + "|touch[a-z]*|drag[a-z]*|drop|key[a-z]*|focus[a-z]*|blur|change|input|invalid|submit|reset|select|scroll"
                    + "|resize|wheel|copy|cut|paste|toggle|show|message|animation[a-z]*|transition[a-z]*|play[a-z]*|pause"
                    + "|ended|begin|end|start|finish|hashchange|popstate|storage|search|content[a-z]*)\\s*=", FLAGS),
                    "must not contain script event handlers"),
            new Rule(Pattern.compile("&#x?[0-9a-f]+;?|&(?:lt|gt|quot|apos);|%3c|%3e|%00|\\\\u00(?:3c|3e)|\\\\x(?:3c|3e)", FLAGS),
                    "must not contain encoded HTML or script characters"),
            new Rule(Pattern.compile("expression\\s*\\(|\\$\\{|#\\{|\\{\\{|\\}\\}", FLAGS),
                    "must not contain script or template expressions")
    );

    private static final Rule LINKS = new Rule(
            Pattern.compile("\\b(?:https?|ftp|file)\\s*:\\s*/{2}|\\bwww\\.[a-z0-9-]+\\.", FLAGS),
            "must not contain links");

    private InputSafety() {
    }

    /**
     * @return why the text is unsafe, or null when it is safe
     */
    public static String findViolation(String text, boolean allowLinks) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        for (Rule rule : RULES) {
            if (rule.pattern().matcher(text).find()) {
                return rule.message();
            }
        }
        if (!allowLinks && LINKS.pattern().matcher(text).find()) {
            return LINKS.message();
        }
        return null;
    }

    public static String findViolation(String text) {
        return findViolation(text, false);
    }
}
