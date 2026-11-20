package com.sf.release.common;

import java.util.regex.Pattern;

/**
 * Masks credential values before they reach a log line.
 */
public final class Masker {

    private static final Pattern SECRET = Pattern.compile(
        "(?i)(\"?(?:token|secret|password|authorization)\"?\\s*[:=]\\s*\"?)([^\"',}&\\r\\n]+)");
    private static final String MASK = "$1***";

    private Masker() {
    }

    public static String mask(String text) {
        return text == null ? null : SECRET.matcher(text).replaceAll(MASK);
    }
}