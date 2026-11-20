package com.sf.release.common;

import java.util.regex.Pattern;

/**
 * Masks credential values before they reach a log line.
 */
public final class Masker {

    /**
     * Double-quoted values (JSON or {@code key="value"}): mask up to the closing quote,
     * including commas, ampersands, apostrophes and escaped quotes inside the value.
     */
    private static final Pattern QUOTED_SECRET = Pattern.compile(
        "(?i)(\"?(?:token|secret|password|authorization)\"?\\s*[:=]\\s*\")(?:[^\"\\\\]|\\\\.)*\"");

    /**
     * Unquoted key/value pairs: mask up to a structural separator.
     */
    private static final Pattern UNQUOTED_SECRET = Pattern.compile(
        "(?i)(\"?(?:token|secret|password|authorization)\"?\\s*[:=]\\s*)([^\"',}&\\r\\n]+)");

    private Masker() {
    }

    public static String mask(String text) {
        if (text == null) {
            return null;
        }
        String masked = QUOTED_SECRET.matcher(text).replaceAll("$1***\"");
        return UNQUOTED_SECRET.matcher(masked).replaceAll("$1***");
    }
}