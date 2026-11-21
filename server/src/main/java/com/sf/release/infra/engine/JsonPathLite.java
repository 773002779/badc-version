package com.sf.release.infra.engine;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * A deliberately small JSONPath subset, sufficient for response mappings in
 * {@code coding-endpoints.yml}:
 *
 * <ul>
 *   <li>root: {@code $};</li>
 *   <li>dotted object member: {@code $.Data.Status}; dotted names are letters, digits,
 *       {@code _} and {@code -};</li>
 *   <li>bracket-quoted member for names containing dots, spaces or other characters:
 *       {@code $['Data']['Status']} (single or double quotes, no escapes);</li>
 *   <li>numeric array index: {@code $.Data.Jobs[0].Status} or {@code $['Data']['Jobs'][0]}.</li>
 * </ul>
 *
 * <p><strong>Not supported:</strong> wildcards ({@code *}), recursive descent ({@code ..}),
 * filters ({@code [?()]}), slices and unions. Such expressions are rejected at startup so a
 * silently-never-matching mapping cannot reach production.
 */
public final class JsonPathLite {

    private JsonPathLite() {
    }

    /** Validates a configured expression; throws {@link IllegalArgumentException} if invalid. */
    public static void validate(String expression) {
        parse(expression);
    }

    /**
     * Reads the value addressed by {@code expression}, or {@code null} when a member/index
     * does not exist or has the wrong node type.
     */
    public static JsonNode read(JsonNode root, String expression) {
        if (root == null) {
            return null;
        }
        JsonNode node = root;
        for (Step step : parse(expression)) {
            if (node == null) {
                return null;
            }
            if (step.index() != null) {
                node = node.isArray() ? node.get(step.index()) : null;
            } else {
                node = node.isObject() ? node.get(step.field()) : null;
            }
        }
        return node;
    }

    private static List<Step> parse(String expression) {
        String path = expression == null ? "" : expression.trim();
        if (path.isEmpty()) {
            throw new IllegalArgumentException("expression is empty");
        }
        if (path.charAt(0) != '$') {
            throw new IllegalArgumentException("expression must start with '$'");
        }
        List<Step> steps = new ArrayList<>();
        int i = 1;
        while (i < path.length()) {
            char ch = path.charAt(i);
            if (ch == '.') {
                int start = ++i;
                while (i < path.length() && path.charAt(i) != '.' && path.charAt(i) != '[') {
                    i++;
                }
                String name = path.substring(start, i);
                if (name.isEmpty()) {
                    throw new IllegalArgumentException("empty member name at index " + start);
                }
                for (int j = 0; j < name.length(); j++) {
                    char nameChar = name.charAt(j);
                    if (!Character.isLetterOrDigit(nameChar) && nameChar != '_'
                            && nameChar != '-') {
                        throw new IllegalArgumentException(
                            "unsupported character '" + nameChar + "' in dotted name '" + name
                                + "'; use $['" + name + "'] for names containing dots or "
                                + "special characters (wildcards and filters are not supported)");
                    }
                }
                steps.add(Step.field(name));
            } else if (ch == '[') {
                i++;
                if (i >= path.length()) {
                    throw new IllegalArgumentException("unterminated '[' at index " + (i - 1));
                }
                char first = path.charAt(i);
                if (first == '\'' || first == '"') {
                    char quote = first;
                    i++;
                    int start = i;
                    while (i < path.length() && path.charAt(i) != quote) {
                        i++;
                    }
                    if (i >= path.length()) {
                        throw new IllegalArgumentException("unterminated quoted key");
                    }
                    String key = path.substring(start, i);
                    if (key.isEmpty()) {
                        throw new IllegalArgumentException("empty quoted key");
                    }
                    i++;
                    if (i >= path.length() || path.charAt(i) != ']') {
                        throw new IllegalArgumentException(
                            "quoted key must be closed with ']'");
                    }
                    i++;
                    steps.add(Step.field(key));
                } else {
                    int start = i;
                    while (i < path.length() && Character.isDigit(path.charAt(i))) {
                        i++;
                    }
                    if (i == start) {
                        throw new IllegalArgumentException(
                            "expected a numeric array index or a quoted key at index " + start);
                    }
                    if (i >= path.length() || path.charAt(i) != ']') {
                        throw new IllegalArgumentException("array index must be closed with ']'");
                    }
                    int index = Integer.parseInt(path.substring(start, i));
                    i++;
                    steps.add(Step.index(index));
                }
            } else {
                throw new IllegalArgumentException(
                    "unexpected character '" + ch + "' at index " + i);
            }
        }
        return List.copyOf(steps);
    }

    /** One parsed navigation step: exactly one of {@code field} or {@code index} is set. */
    private record Step(String field, Integer index) {

        static Step field(String name) {
            return new Step(name, null);
        }

        static Step index(int value) {
            return new Step(null, value);
        }
    }
}