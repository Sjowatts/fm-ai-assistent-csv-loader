package com.github.fmaiassistent.csv;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Parses the display strings FM writes into a view export. Everything here returns an
 * empty Optional rather than a default, so an unreadable cell stays unknown instead of
 * silently becoming a real-looking value.
 */
public final class CsvValues {
    private static final List<String> UNKNOWN = List.of("", "-", "--", "n/a", "na", "unknown", "?");
    private static final Map<String, Integer> FOOT_RATINGS = Map.of(
            "very weak", 1,
            "weak", 5,
            "reasonable", 10,
            "fairly strong", 13,
            "strong", 16,
            "very strong", 19);
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMMM d yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMM d yyyy", Locale.ENGLISH));

    private CsvValues() {
    }

    public static boolean isUnknown(String raw) {
        return raw == null || UNKNOWN.contains(raw.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Reads an attribute cell. FM renders unscouted players as a range ("12-15"); the
     * midpoint is used and the caller is expected to treat such a row as an estimate.
     */
    public static Optional<Integer> attribute(String raw) {
        if (isUnknown(raw)) {
            return Optional.empty();
        }
        String text = raw.trim();
        Integer foot = FOOT_RATINGS.get(text.toLowerCase(Locale.ROOT));
        if (foot != null) {
            return Optional.of(foot);
        }
        int dash = text.indexOf('-', 1);
        if (dash > 0) {
            Optional<Integer> low = integer(text.substring(0, dash));
            Optional<Integer> high = integer(text.substring(dash + 1));
            if (low.isPresent() && high.isPresent()) {
                return Optional.of(clamp((low.get() + high.get()) / 2));
            }
        }
        return integer(text).map(CsvValues::clamp);
    }

    public static boolean isRange(String raw) {
        return raw != null && raw.trim().indexOf('-', 1) > 0 && attribute(raw).isPresent();
    }

    public static Optional<Integer> integer(String raw) {
        if (isUnknown(raw)) {
            return Optional.empty();
        }
        StringBuilder digits = new StringBuilder();
        for (char ch : raw.trim().toCharArray()) {
            if (Character.isDigit(ch) || (ch == '-' && digits.isEmpty())) {
                digits.append(ch);
            } else if (!digits.isEmpty()) {
                break;
            }
        }
        try {
            return digits.isEmpty() || "-".contentEquals(digits)
                    ? Optional.empty()
                    : Optional.of(Integer.parseInt(digits.toString()));
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    /**
     * Reads a money cell such as "£14K p/w", "€2.5M" or "£11.5M - £14M". Ranges collapse
     * to their midpoint. "Free" is zero; anything unpriced stays unknown.
     */
    public static Optional<Long> money(String raw) {
        if (isUnknown(raw)) {
            return Optional.empty();
        }
        String text = raw.trim().toLowerCase(Locale.ROOT);
        if (text.startsWith("free")) {
            return Optional.of(0L);
        }
        if (text.contains("not for sale") || text.contains("unavailable") || text.contains("on loan")) {
            return Optional.empty();
        }
        int separator = rangeSeparator(text);
        if (separator > 0) {
            Optional<Long> low = singleMoney(text.substring(0, separator));
            Optional<Long> high = singleMoney(text.substring(separator + 1));
            if (low.isPresent() && high.isPresent()) {
                return Optional.of((low.get() + high.get()) / 2);
            }
        }
        return singleMoney(text);
    }

    public static Optional<Integer> heightCm(String raw) {
        if (isUnknown(raw)) {
            return Optional.empty();
        }
        String text = raw.trim().toLowerCase(Locale.ROOT);
        int feetMark = text.indexOf('\'');
        if (feetMark > 0) {
            Optional<Integer> feet = integer(text.substring(0, feetMark));
            Optional<Integer> inches = integer(text.substring(feetMark + 1));
            if (feet.isPresent()) {
                return Optional.of((int) Math.round((feet.get() * 12 + inches.orElse(0)) * 2.54));
            }
        }
        Optional<Integer> value = integer(text);
        if (value.isEmpty()) {
            return Optional.empty();
        }
        if (text.contains("m") && !text.contains("cm") && value.get() < 10) {
            return decimal(text).map(metres -> (int) Math.round(metres * 100));
        }
        return value;
    }

    /**
     * Normalises a date cell to the ISO form the RAM exporter writes. Ambiguous numeric
     * dates are read day-first, matching FM's English (UK) default.
     */
    public static Optional<String> date(String raw) {
        if (isUnknown(raw)) {
            return Optional.empty();
        }
        String text = raw.trim().replace(',', ' ').replaceAll("\\s+", " ");
        try {
            return Optional.of(LocalDate.parse(text).toString());
        } catch (DateTimeParseException ignored) {
            // Not ISO; fall through to the display formats below.
        }
        String[] parts = text.split("[/.\\-]");
        if (parts.length == 3) {
            Optional<Integer> first = integer(parts[0]);
            Optional<Integer> second = integer(parts[1]);
            Optional<Integer> year = integer(parts[2]);
            if (first.isPresent() && second.isPresent() && year.isPresent()) {
                boolean monthFirst = first.get() <= 12 && second.get() > 12;
                int day = monthFirst ? second.get() : first.get();
                int month = monthFirst ? first.get() : second.get();
                try {
                    return Optional.of(LocalDate.of(fullYear(year.get()), month, day).toString());
                } catch (RuntimeException exception) {
                    return Optional.empty();
                }
            }
        }
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return Optional.of(LocalDate.parse(text, format).toString());
            } catch (DateTimeParseException ignored) {
                // Try the next display format.
            }
        }
        return Optional.empty();
    }

    public static Optional<Boolean> flag(String raw) {
        if (isUnknown(raw)) {
            return Optional.empty();
        }
        String text = raw.trim().toLowerCase(Locale.ROOT);
        if (List.of("yes", "y", "true", "1").contains(text)) {
            return Optional.of(true);
        }
        if (List.of("no", "n", "false", "0").contains(text)) {
            return Optional.of(false);
        }
        return Optional.of(true);
    }

    private static Optional<Long> singleMoney(String text) {
        Optional<Double> amount = decimal(text);
        if (amount.isEmpty()) {
            return Optional.empty();
        }
        double multiplier = 1;
        String tail = text.substring(text.lastIndexOf(lastDigit(text)) + 1).trim();
        if (tail.startsWith("k")) {
            multiplier = 1_000;
        } else if (tail.startsWith("m")) {
            multiplier = 1_000_000;
        } else if (tail.startsWith("b")) {
            multiplier = 1_000_000_000;
        }
        return Optional.of(Math.round(amount.get() * multiplier));
    }

    private static char lastDigit(String text) {
        for (int i = text.length() - 1; i >= 0; i--) {
            if (Character.isDigit(text.charAt(i))) {
                return text.charAt(i);
            }
        }
        return ' ';
    }

    private static Optional<Double> decimal(String text) {
        StringBuilder number = new StringBuilder();
        for (char ch : text.toCharArray()) {
            if (Character.isDigit(ch) || (ch == '.' && !number.isEmpty())) {
                number.append(ch);
            } else if (ch == ',' ) {
                continue;
            } else if (!number.isEmpty()) {
                break;
            }
        }
        try {
            return number.isEmpty() ? Optional.empty() : Optional.of(Double.parseDouble(number.toString()));
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    private static int rangeSeparator(String text) {
        for (int i = 1; i < text.length() - 1; i++) {
            if (text.charAt(i) == '-' && text.charAt(i - 1) == ' ') {
                return i;
            }
        }
        return -1;
    }

    private static int fullYear(int year) {
        if (year >= 100) {
            return year;
        }
        return year < 50 ? 2000 + year : 1900 + year;
    }

    private static int clamp(int value) {
        return Math.max(1, Math.min(20, value));
    }
}
