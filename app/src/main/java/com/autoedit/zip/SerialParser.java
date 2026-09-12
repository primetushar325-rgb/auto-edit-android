package com.autoedit.zip;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts a numeric serial from an image filename.
 *
 * <p>Rules (master prompt Part 1 §§2–4):
 * <ul>
 *   <li>Ignore the file extension entirely.</li>
 *   <li>Strip path prefixes; operate on the basename only.</li>
 *   <li>Find every contiguous digit group in the stem.</li>
 *   <li>Use the <b>last</b> meaningful numeric group as the serial
 *       (e.g. {@code story_2026_scene_001.png} → 1).</li>
 *   <li>Leading zeros are insignificant ({@code 001} → 1).</li>
 *   <li>No digit group → no serial ({@code null}).</li>
 * </ul>
 *
 * Pure logic — no Android dependencies, unit-testable on the JVM.
 */
public final class SerialParser {

    private static final Pattern DIGITS = Pattern.compile("(\\d+)");

    private SerialParser() {}

    /**
     * @param pathOrName full ZIP entry path or plain filename
     * @return serial number, or {@code null} when no digits are present
     */
    public static Integer extractSerial(String pathOrName) {
        if (pathOrName == null || pathOrName.isEmpty()) return null;
        String base = basename(pathOrName);
        String stem = stripExtension(base);
        if (stem.isEmpty()) return null;

        List<String> groups = new ArrayList<>();
        Matcher m = DIGITS.matcher(stem);
        while (m.find()) groups.add(m.group(1));
        if (groups.isEmpty()) return null;

        // Last numeric group before the extension is the serial.
        String last = groups.get(groups.size() - 1);
        try {
            // Integer.parseInt drops leading zeros and rejects overflow.
            long v = Long.parseLong(last);
            if (v < 0 || v > Integer.MAX_VALUE) return null;
            return (int) v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Basename of a ZIP entry (handles both / and \ separators). */
    public static String basename(String path) {
        if (path == null) return "";
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /** Stem without the final extension (case-insensitive). */
    public static String stripExtension(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        if (dot <= 0) return name;
        return name.substring(0, dot);
    }

    /** Lower-case extension including the leading dot, or empty. */
    public static String extension(String name) {
        if (name == null) return "";
        String base = basename(name);
        int dot = base.lastIndexOf('.');
        if (dot < 0 || dot == base.length() - 1) return "";
        return base.substring(dot).toLowerCase(Locale.US);
    }

    /** True for jpg/jpeg/png/webp (case-insensitive). */
    public static boolean isSupportedImage(String pathOrName) {
        String ext = extension(pathOrName);
        return ".jpg".equals(ext) || ".jpeg".equals(ext)
                || ".png".equals(ext) || ".webp".equals(ext);
    }
}
