package com.ubp.rgd.proxy.transform;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognises the date formats a transformer able to preserve value types keeps as dates, and
 * rewrites a date with the exact layout it was read with.
 * <p>
 * The accepted formats, where the separator is one of {@code -}, {@code .} or {@code /} and must be
 * the same on both sides of the month:
 * <ul>
 *     <li>{@code yyyy-mm-dd}, optionally followed by a {@code Thh:mm:ss} time which is kept as is</li>
 *     <li>{@code dd-mm-yyyy}</li>
 *     <li>{@code ddmmyyyy}, without separator</li>
 * </ul>
 * Only real calendar dates from year 1 onwards are accepted: {@code 2024-02-30} is not a date.
 */
public final class DateValues {

    /** {@code yyyy?mm?dd[Thh:mm:ss]}, then {@code dd?mm?yyyy}, then {@code ddmmyyyy}. */
    private static final Pattern DATE = Pattern.compile(
            "^(?:(?<y1>\\d{4})(?<s1>[-./])(?<m1>\\d{2})\\k<s1>(?<d1>\\d{2})(?<time>T\\d{2}:\\d{2}:\\d{2})?"
                    + "|(?<d2>\\d{2})(?<s2>[-./])(?<m2>\\d{2})\\k<s2>(?<y2>\\d{4})"
                    + "|(?<d3>\\d{2})(?<m3>\\d{2})(?<y3>\\d{4}))$");

    private DateValues() {
    }

    /** The order of the fields of a date. */
    public enum Order {
        /** {@code yyyy?mm?dd}. */
        YEAR_FIRST,
        /** {@code dd?mm?yyyy}. */
        DAY_FIRST,
        /** {@code ddmmyyyy}. */
        COMPACT
    }

    /**
     * A date along with the layout it was written with.
     *
     * @param date      the calendar date
     * @param order     the order of the fields
     * @param separator the separator, empty for the compact form
     * @param time      the time part following a year first date, empty when there is none
     */
    public record ParsedDate(LocalDate date, Order order, String separator, String time) {

        /**
         * Write another date with the layout of this one.
         *
         * @param other the date to write, its year must have four digits
         * @return the date written with the same order, separator and time as this one
         */
        public String format(LocalDate other) {
            String year = String.format("%04d", other.getYear());
            String month = String.format("%02d", other.getMonthValue());
            String day = String.format("%02d", other.getDayOfMonth());
            return switch (order) {
                case YEAR_FIRST -> year + separator + month + separator + day + time;
                case DAY_FIRST -> day + separator + month + separator + year;
                case COMPACT -> day + month + year;
            };
        }
    }

    /**
     * Parse a value holding a date and nothing else.
     *
     * @param value the value to parse, may be {@code null}
     * @return the parsed date, or {@code null} when the value is not a date in an accepted format
     */
    public static ParsedDate parse(String value) {
        if (value == null || value.length() < 8 || value.length() > 19) {
            return null;
        }
        Matcher matcher = DATE.matcher(value);
        if (!matcher.matches()) {
            return null;
        }
        try {
            if (matcher.group("y1") != null) {
                String time = matcher.group("time");
                return parsed(matcher.group("y1"), matcher.group("m1"), matcher.group("d1"),
                        Order.YEAR_FIRST, matcher.group("s1"), time == null ? "" : time);
            }
            if (matcher.group("y2") != null) {
                return parsed(matcher.group("y2"), matcher.group("m2"), matcher.group("d2"),
                        Order.DAY_FIRST, matcher.group("s2"), "");
            }
            return parsed(matcher.group("y3"), matcher.group("m3"), matcher.group("d3"), Order.COMPACT, "", "");
        } catch (DateTimeException e) {
            // Matches the layout but is no calendar date, such as the 30th of February.
            return null;
        }
    }

    /**
     * Whether a value holds a date in an accepted format and nothing else.
     *
     * @param value the value to inspect, may be {@code null}
     * @return {@code true} when {@link #parse(String)} recognises it
     */
    public static boolean isDate(String value) {
        return parse(value) != null;
    }

    private static ParsedDate parsed(String year, String month, String day, Order order, String separator,
                                     String time) {
        int y = Integer.parseInt(year);
        if (y < 1) {
            throw new DateTimeException("year 0 is not supported");
        }
        LocalDate date = LocalDate.of(y, Integer.parseInt(month), Integer.parseInt(day));
        return new ParsedDate(date, order, separator, time);
    }
}
