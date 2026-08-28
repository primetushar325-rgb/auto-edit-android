package com.autoedit.update;

/**
 * Semantic version handling (spec §32).
 *
 * Version <em>names</em> are compared component by component as integers, so
 * {@code 1.10.0 > 1.9.0} — the bug you get from comparing the strings, where
 * {@code "1.10.0".compareTo("1.9.0")} is negative because {@code '1' < '9'}.
 *
 * The integer {@code versionCode} remains the authority for update gating
 * (it is what the Play/Android installer uses); this class exists so the
 * user-facing "Version X is newer than Y" decision is also correct.
 */
public final class SemVer implements Comparable<SemVer> {

    public final int major, minor, patch;
    /** Pre-release/build suffix, e.g. "beta1" from "1.2.0-beta1". Kept for display. */
    public final String suffix;

    private SemVer(int major, int minor, int patch, String suffix) {
        this.major = major; this.minor = minor; this.patch = patch; this.suffix = suffix;
    }

    /**
     * Parses "1", "1.2", "1.2.3", "v1.2.3", "1.2.3-beta1". Anything unparseable
     * yields 0.0.0 rather than throwing, so a malformed remote value can never
     * crash the update check.
     */
    public static SemVer parse(String s) {
        if (s == null) return new SemVer(0, 0, 0, "");
        String t = s.trim();
        if (t.startsWith("v") || t.startsWith("V")) t = t.substring(1);
        String suffix = "";
        int dash = t.indexOf('-');
        if (dash >= 0) { suffix = t.substring(dash + 1); t = t.substring(0, dash); }
        String[] parts = t.split("\\.");
        int[] n = new int[3];
        for (int i = 0; i < 3 && i < parts.length; i++) n[i] = digits(parts[i]);
        return new SemVer(n[0], n[1], n[2], suffix);
    }

    /** Leading integer of a component; non-numeric components count as 0. */
    private static int digits(String s) {
        int v = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') break;
            v = v * 10 + (c - '0');
        }
        return v;
    }

    @Override public int compareTo(SemVer o) {
        if (o == null) return 1;
        if (major != o.major) return major < o.major ? -1 : 1;
        if (minor != o.minor) return minor < o.minor ? -1 : 1;
        if (patch != o.patch) return patch < o.patch ? -1 : 1;
        // A release outranks a pre-release of the same numeric version.
        boolean aPre = !suffix.isEmpty(), bPre = !o.suffix.isEmpty();
        if (aPre != bPre) return aPre ? -1 : 1;
        return suffix.compareTo(o.suffix);
    }

    /** True when {@code candidate} is strictly newer than {@code current}. */
    public static boolean isNewer(String candidate, String current) {
        return parse(candidate).compareTo(parse(current)) > 0;
    }

    public boolean isNewerThan(SemVer other) { return compareTo(other) > 0; }

    @Override public String toString() {
        return major + "." + minor + "." + patch + (suffix.isEmpty() ? "" : "-" + suffix);
    }

    @Override public boolean equals(Object o) {
        return o instanceof SemVer && compareTo((SemVer) o) == 0;
    }

    @Override public int hashCode() { return major * 1000000 + minor * 1000 + patch; }
}
