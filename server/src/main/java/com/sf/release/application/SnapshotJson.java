package com.sf.release.application;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The tiny JSON codec of the three list columns of {@code admission_snapshot}
 * ({@code freeze_ids}, {@code waiver_ids}, {@code chain_json}).
 *
 * <p><strong>Why not a JSON library.</strong> The columns hold a flat array of ids or of enum names
 * — nothing else, ever (design 4.5: "清单字段 JSON"). A general object mapper would add a
 * dependency on the mapping behaviour (field naming, null handling, exception type) for values that
 * are already constrained by the application; this codec is total for those values and throws on
 * anything else. It is the same reasoning that keeps {@code chain_json} a list of role names rather
 * than the whole chain: the snapshot records <em>what was required</em>, and {@code approval}
 * records what happened.
 */
final class SnapshotJson {

    private SnapshotJson() {
    }

    /** {@code [1,2]} — or {@code []} for an empty collection, never {@code NULL}. */
    static String ofLongs(List<Long> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        return values.stream().map(String::valueOf).collect(Collectors.joining(",", "[", "]"));
    }

    /** {@code ["OPS_LEAD","DEPT_MANAGER"]} — or {@code []}. */
    static String ofStrings(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        return values.stream()
            .map(value -> "\"" + value.replace("\"", "") + "\"")
            .collect(Collectors.joining(",", "[", "]"));
    }

    /**
     * The ids of a stored array.
     *
     * @throws IllegalStateException the column holds something that is not a list of ids (a corrupt
     *                               snapshot must be visible, not read as "no freezes")
     */
    static List<Long> longsOf(String json) {
        List<String> tokens = tokensOf(json);
        List<Long> values = new ArrayList<>(tokens.size());
        for (String token : tokens) {
            try {
                values.add(Long.valueOf(token));
            } catch (NumberFormatException ex) {
                throw new IllegalStateException("not an id list: " + json, ex);
            }
        }
        return List.copyOf(values);
    }

    /** The strings of a stored array (the required roles). */
    static List<String> stringsOf(String json) {
        return tokensOf(json).stream()
            .filter(token -> !token.isBlank())
            .toList();
    }

    private static List<String> tokensOf(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        String body = json.trim();
        if (body.startsWith("[")) {
            body = body.substring(1);
        }
        if (body.endsWith("]")) {
            body = body.substring(0, body.length() - 1);
        }
        List<String> tokens = new ArrayList<>();
        for (String raw : body.split(",")) {
            String token = raw.trim();
            if (token.startsWith("\"") && token.endsWith("\"") && token.length() >= 2) {
                token = token.substring(1, token.length() - 1);
            }
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        return tokens;
    }
}