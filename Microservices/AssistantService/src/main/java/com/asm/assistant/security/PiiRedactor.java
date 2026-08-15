package com.asm.assistant.security;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Redacts secrets and personal data from anything about to be persisted (audit) or logged. Ingestion
 * already excludes secret files; this is the second line for free-text — a question a user types, or a
 * value that slipped into an answer — so the audit trail and logs never become a leak of their own.
 *
 * <p>Conservative by design: it masks high-confidence patterns (bearer tokens, API keys, emails,
 * phone numbers, long digit runs like card/IBAN fragments) and leaves ordinary text intact.
 */
@Component
public class PiiRedactor {

    private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._\\-]+");
    private static final Pattern API_KEY = Pattern.compile("(?i)(AIza|AQ\\.|sk-|xoxb-)[A-Za-z0-9._\\-]{8,}");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(\\+?\\d[\\d\\s\\-]{7,}\\d)(?!\\d)");
    private static final Pattern LONG_DIGITS = Pattern.compile("\\b\\d{12,}\\b");

    public String redact(String text) {
        if (text == null || text.isBlank()) return text;
        String out = text;
        out = BEARER.matcher(out).replaceAll("[REDACTED_TOKEN]");
        out = API_KEY.matcher(out).replaceAll("[REDACTED_KEY]");
        out = EMAIL.matcher(out).replaceAll("[REDACTED_EMAIL]");
        out = LONG_DIGITS.matcher(out).replaceAll("[REDACTED_NUMBER]");
        out = PHONE.matcher(out).replaceAll("[REDACTED_PHONE]");
        return out;
    }
}
