package com.posiciontelefono;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Pattern;

/** Human-readable texts for the UI (Spanish, like the rest of the interface). */
public final class Format {

    private static final Locale ES = Locale.forLanguageTag("es");
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.ROOT);
    private static final DateTimeFormatter TITLE = DateTimeFormatter.ofPattern("EEEE d/M/yyyy · HH:mm", ES);
    static final int MAX_LABEL = 60;
    private static final int MAX_FILE_PART = 40;
    private static final Pattern BIDI_CONTROLS = Pattern.compile("[\\u202A-\\u202E\\u2066-\\u2069]");
    private static final Pattern SPACES_AND_CONTROLS = Pattern.compile("[\\s\\p{Cntrl}]+");
    private static final Pattern FILE_UNSAFE = Pattern.compile("[\\\\/:*?\"<>|]");

    private Format() {}

    /** Live timer: "0:07:05". */
    public static String clock(long ms) {
        long s = Math.max(0, ms) / 1000;
        return String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60);
    }

    /** Ride length: "40 s", "23 min", "1 h 05 min". */
    public static String duration(long ms) {
        long s = Math.max(0, ms) / 1000;
        if (s < 60) return s + " s";
        long min = s / 60;
        return min < 60 ? min + " min" : String.format(Locale.ROOT, "%d h %02d min", min / 60, min % 60);
    }

    /** "850 m" or "3,4 km". */
    public static String distance(double meters) {
        return meters < 1000
                ? String.format(ES, "%.0f m", meters)
                : String.format(ES, "%.1f km", meters / 1000);
    }

    /** "2026-11-22_14-05-30.csv" becomes "Domingo 22/11/2026 · 14:05". */
    public static String trackTitle(String fileName) {
        if (!Track.isValidFileName(fileName)) return fileName;
        try {
            String s = TITLE.format(LocalDateTime.parse(fileName.substring(0, 19), FILE_DATE));
            return Character.toUpperCase(s.charAt(0)) + s.substring(1);
        } catch (DateTimeParseException e) {
            return fileName;
        }
    }

    /**
     * Name typed by the user: one line, no control or text-direction characters (they can make a
     * name look like another), trimmed, at most {@link #MAX_LABEL} characters. "" means no name.
     */
    public static String cleanLabel(String raw) {
        if (raw == null) return "";
        String s = BIDI_CONTROLS.matcher(raw).replaceAll("");
        s = SPACES_AND_CONTROLS.matcher(s).replaceAll(" ").trim();
        return truncate(s, MAX_LABEL);
    }

    /** Name made safe for a file name in Downloads: "Vuelta: lago/río" becomes "Vuelta_ lago_río". */
    public static String fileNamePart(String label) {
        String s = FILE_UNSAFE.matcher(cleanLabel(label)).replaceAll("_");
        s = truncate(s, MAX_FILE_PART);
        return s.replaceAll("^[.\\s]+|[.\\s]+$", ""); // no hidden or dot-only names
    }

    /** Cuts by characters, never in the middle of an emoji. */
    private static String truncate(String s, int maxChars) {
        return s.codePointCount(0, s.length()) <= maxChars
                ? s : s.substring(0, s.offsetByCodePoints(0, maxChars)).trim();
    }
}
