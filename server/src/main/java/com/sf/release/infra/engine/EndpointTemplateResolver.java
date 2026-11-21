package com.sf.release.infra.engine;

import com.sf.release.domain.engine.EngineException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns an endpoint template from {@link CodingProperties} into a concrete HTTP call.
 *
 * <p>This is the seam that keeps the adapter correctable by configuration: paths, methods,
 * request-body field names and response JSONPath expressions all come from
 * {@code coding-endpoints.yml}. When the real CODING tenant documentation arrives, only that
 * file changes.
 *
 * <p>A placeholder is never replaced with an empty string: if a variable is missing (or
 * {@code null}), the call fails with {@link EngineException} naming the placeholder and the
 * action, so the operator sees exactly which input or configuration is incomplete.
 */
public class EndpointTemplateResolver {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9_]+)}");
    private static final Set<String> SUPPORTED_METHODS =
        Set.of("GET", "POST", "PUT", "PATCH", "DELETE");

    private final CodingProperties properties;

    public EndpointTemplateResolver(CodingProperties properties) {
        this.properties = properties;
    }

    /**
     * Renders one configured action.
     *
     * @param action endpoint key, for example {@code "build.start"}
     * @param vars   values for every {@code {placeholder}} the template uses
     * @throws EngineException when the action is not configured, its method/URL is invalid or
     *                         a placeholder has no value
     */
    public ResolvedCall resolve(String action, Map<String, String> vars) {
        return resolve(action, vars, null);
    }

    /**
     * Renders one configured action with an optional request-body override (for example the
     * branch-head body of a build trigger whose commit is not known yet).
     */
    public ResolvedCall resolve(String action, Map<String, String> vars, String bodyOverride) {
        CodingProperties.Endpoint endpoint = properties.getEndpoints().get(action);
        if (endpoint == null) {
            throw EngineException.unavailable(
                "引擎端点未配置：action=" + action + "（请检查 coding-endpoints.yml）");
        }
        String method = methodOf(endpoint, action);
        String urlTemplate = requireText(endpoint.getUrl(),
            "端点 action=" + action + " 缺少 url 配置");
        String url = render(action, urlTemplate, vars, Encoding.PATH);
        String bodyTemplate = bodyOverride == null ? endpoint.getBody() : bodyOverride;
        String body = bodyTemplate == null
            ? null
            : render(action, bodyTemplate, vars, Encoding.JSON);
        Map<String, String> headers = new LinkedHashMap<>();
        if (endpoint.getHeaders() != null) {
            endpoint.getHeaders().forEach((name, template) -> {
                if (name == null || name.isBlank()) {
                    throw EngineException.unavailable(
                        "端点 action=" + action + " 的 header 名称为空");
                }
                headers.put(name.trim(), template == null ? "" : render(action, template, vars,
                    Encoding.RAW));
            });
        }
        return new ResolvedCall(method, url, Collections.unmodifiableMap(headers), body);
    }

    /**
     * Changes one field of an already configured endpoint. Key syntax is
     * {@code action.field}, for example {@code "build.start.url"}. Intended for tests and for
     * operational overrides that are applied after the YAML has been bound.
     */
    public void override(String key, String value) {
        String trimmed = requireText(key, "端点覆盖键不能为空");
        int separator = trimmed.lastIndexOf('.');
        if (separator <= 0 || separator == trimmed.length() - 1) {
            throw EngineException.unavailable(
                "端点覆盖键不合法：" + trimmed + "（期望 action.field）");
        }
        String action = trimmed.substring(0, separator);
        String field = trimmed.substring(separator + 1);
        CodingProperties.Endpoint endpoint = properties.getEndpoints().get(action);
        if (endpoint == null) {
            throw EngineException.unavailable("引擎端点未配置：action=" + action);
        }
        switch (field) {
            case "method" -> endpoint.setMethod(value);
            case "url" -> endpoint.setUrl(value);
            case "body" -> endpoint.setBody(value);
            case "bodyWithoutCommit" -> endpoint.setBodyWithoutCommit(value);
            case "responseId" -> endpoint.setResponseId(value);
            case "responseStatus" -> endpoint.setResponseStatus(value);
            case "responseDigest" -> endpoint.setResponseDigest(value);
            case "responseUrl" -> endpoint.setResponseUrl(value);
            case "responseCommit" -> endpoint.setResponseCommit(value);
            case "responseValue" -> endpoint.setResponseValue(value);
            default -> throw EngineException.unavailable(
                "端点字段不可覆盖：" + field + "（action=" + action + "）");
        }
    }

    private static String methodOf(CodingProperties.Endpoint endpoint, String action) {
        String raw = endpoint.getMethod();
        String method = raw == null || raw.isBlank()
            ? "GET" : raw.trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_METHODS.contains(method)) {
            throw EngineException.unavailable(
                "端点 action=" + action + " 的 method 不合法：" + raw);
        }
        return method;
    }

    private static String render(String action, String template, Map<String, String> vars,
                                 Encoding encoding) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder rendered = new StringBuilder(template.length() + 32);
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = vars == null ? null : vars.get(name);
            if (value == null || value.isBlank()) {
                throw EngineException.unavailable(
                    "端点模板 " + action + " 的占位符 {" + name + "} 未解析（无值）");
            }
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(encoding.encode(value)));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    private static String requireText(String value, String message) {
        String trimmed = value == null ? null : value.trim();
        if (trimmed == null || trimmed.isEmpty()) {
            throw EngineException.unavailable(message);
        }
        return trimmed;
    }

    /** How a placeholder value must be escaped for the place it is rendered into. */
    private enum Encoding {
        /** URL path/query segment. */
        PATH {
            @Override
            String encode(String value) {
                return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
            }
        },
        /** Inside a JSON string literal: escape without adding surrounding quotes. */
        JSON {
            @Override
            String encode(String value) {
                StringBuilder escaped = new StringBuilder(value.length() + 8);
                for (int i = 0; i < value.length(); i++) {
                    char ch = value.charAt(i);
                    switch (ch) {
                        case '\\' -> escaped.append("\\\\");
                        case '"' -> escaped.append("\\\"");
                        case '\n' -> escaped.append("\\n");
                        case '\r' -> escaped.append("\\r");
                        case '\t' -> escaped.append("\\t");
                        default -> {
                            if (ch < 0x20) {
                                escaped.append(String.format("\\u%04x", (int) ch));
                            } else {
                                escaped.append(ch);
                            }
                        }
                    }
                }
                return escaped.toString();
            }
        },
        /** Header values are used verbatim. */
        RAW {
            @Override
            String encode(String value) {
                return value;
            }
        };

        abstract String encode(String value);
    }

    /**
     * A fully rendered request. {@code body} is {@code null} when the endpoint declares no
     * body template; {@code headers} contains only configuration-declared headers (the
     * authorization header is added by the engine from the decrypted credential).
     */
    public record ResolvedCall(String method, String url, Map<String, String> headers,
                               String body) {
    }
}