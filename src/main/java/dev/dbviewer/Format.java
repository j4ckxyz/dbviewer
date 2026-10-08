package dev.dbviewer;

import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Display formatting for values, sizes and times. */
final class Format {
    private Format() {}

    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    /** Single-line text for a cell value, cut to {@code max} characters. */
    static String cell(Object v, int max) {
        if (v == null) return "NULL";
        if (v instanceof byte[] b) return "BLOB " + bytes(b.length);
        String s = v.toString();
        boolean cut = s.length() > max;
        if (cut) s = s.substring(0, max);
        if (s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0 || s.indexOf('\t') >= 0) {
            s = s.replace("\r\n", "⏎").replace('\n', '⏎').replace('\r', '⏎').replace('\t', ' ');
        }
        return cut ? s + "…" : s;
    }

    static String count(long n) {
        return NumberFormat.getIntegerInstance(Locale.US).format(n);
    }

    static String bytes(long n) {
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return String.format(Locale.US, "%.1f KB", n / 1024.0);
        if (n < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", n / (1024.0 * 1024));
        return String.format(Locale.US, "%.2f GB", n / (1024.0 * 1024 * 1024));
    }

    static String clock(long millis) {
        return CLOCK.format(Instant.ofEpochMilli(millis));
    }

    static String stamp(long millis) {
        return STAMP.format(Instant.ofEpochMilli(millis));
    }
}
