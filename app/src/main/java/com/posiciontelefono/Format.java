package com.posiciontelefono;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/** Human-readable texts for the UI (Spanish, like the rest of the interface). */
public final class Format {

    private static final Locale ES = Locale.forLanguageTag("es");
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.ROOT);
    private static final DateTimeFormatter TITLE = DateTimeFormatter.ofPattern("EEEE d/M/yyyy · HH:mm", ES);

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
}
