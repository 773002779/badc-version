package com.sf.release.infra.git;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sf.release.common.BizError;
import com.sf.release.common.Masker;
import com.sf.release.domain.engine.EngineException;
import com.sf.release.domain.pipeline.port.GitCommitProbe;
import com.sf.release.infra.engine.CodingEngine;
import com.sf.release.infra.engine.CodingProperties;
import com.sf.release.infra.engine.EndpointTemplateResolver;
import com.sf.release.infra.engine.EngineHttpClient;
import com.sf.release.infra.engine.EngineProperties;
import com.sf.release.infra.engine.GlobalCodingConnectionProvider;
import com.sf.release.infra.engine.JsonPathLite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * CODING implementation of the rerun planner's {@link GitCommitProbe} (tech design §5.3).
 *
 * <p><strong>Configuration, not code.</strong> The two calls are the {@code git.head} and
 * {@code git.changes} actions of {@code coding-endpoints.yml}, resolved through the same
 * {@link EndpointTemplateResolver}, connection ({@link GlobalCodingConnectionProvider}) and
 * {@link EngineHttpClient} as build/deploy calls. When the real CODING repository API shape
 * arrives, only that YAML file changes.
 *
 * <p><strong>Failure policy.</strong> The probe reports failures as {@link EngineException} with
 * {@code ENGINE_UNAVAILABLE}/{@code ENGINE_TIMEOUT}; it never fabricates a commit or a
 * "no changes" answer. {@link #headSha(long, String)} may return {@code null} when the configured
 * response expression is absent (the branch is unknown to the engine); the planner then treats
 * that as "cannot prove the commit is unchanged" and chooses the conservative full rerun.
 *
 * <p><strong>No eager I/O.</strong> The connection is resolved lazily per call, so the
 * application boots without a CODING tenant. Tests inject a {@link GitCommitProbe} stub and never
 * exercise this class over the network.
 */
@Component
public class CodingCommitProbe implements GitCommitProbe {

    static final String ACTION_HEAD = "git.head";
    static final String ACTION_CHANGES = "git.changes";

    private static final Logger log = LoggerFactory.getLogger(CodingCommitProbe.class);
    private static final String REDACTED = "***";

    private final CodingProperties properties;
    private final EndpointTemplateResolver resolver;
    private final ObjectMapper objectMapper;
    private final EngineHttpClient http;
    private final EngineProperties engineProperties;
    private final GlobalCodingConnectionProvider connectionProvider;

    public CodingCommitProbe(CodingProperties properties,
                             EndpointTemplateResolver resolver,
                             ObjectMapper objectMapper,
                             EngineHttpClient http,
                             EngineProperties engineProperties,
                             GlobalCodingConnectionProvider connectionProvider) {
        this.properties = properties;
        this.resolver = resolver;
        this.objectMapper = objectMapper;
        this.http = http;
        this.engineProperties = engineProperties;
        this.connectionProvider = connectionProvider;
    }

    @Override
    public String headSha(long unitId, String branch) {
        Map<String, String> vars = new LinkedHashMap<>();
        put(vars, "unitId", String.valueOf(unitId));
        put(vars, "branch", branch);
        JsonNode root = parse(execute(ACTION_HEAD, vars), ACTION_HEAD);
        String sha = text(root, endpoint(ACTION_HEAD).getResponseCommit(), ACTION_HEAD);
        log.info("coding git head probed: unitId={}, branch={}, commit={}",
            unitId, branch, sha == null ? "<unreported>" : sha);
        return sha;
    }

    @Override
    public boolean hasChangesUnder(long unitId, String branch, String modulePath, String baseSha) {
        Map<String, String> vars = new LinkedHashMap<>();
        put(vars, "unitId", String.valueOf(unitId));
        put(vars, "branch", branch);
        put(vars, "modulePath", modulePath);
        put(vars, "baseSha", baseSha);
        JsonNode root = parse(execute(ACTION_CHANGES, vars), ACTION_CHANGES);
        String value = text(root, endpoint(ACTION_CHANGES).getResponseValue(), ACTION_CHANGES);
        if (value == null) {
            throw EngineException.unavailable(
                "git.changes 响应缺少变更标志（请检查 responseValue 配置："
                    + "unitId=" + unitId + ", modulePath=" + modulePath + "）");
        }
        boolean changed = parseBoolean(value);
        log.info("coding git changes probed: unitId={}, branch={}, modulePath={}, baseSha={},"
                + " changed={}", unitId, branch, modulePath, baseSha, changed);
        return changed;
    }

    // ------------------------------------------------------------------ request plumbing

    private EngineHttpClient.HttpResult execute(String action, Map<String, String> vars) {
        CodingEngine.Connection connection = connectionProvider.get();
        if (connection == null) {
            throw EngineException.unavailable("CODING 连接未配置：" + action);
        }
        EndpointTemplateResolver.ResolvedCall call = resolver.resolve(action, vars);
        String url = absolutize(connection.baseUrl(), call.url());
        validateOutboundScheme(url, connection);
        Map<String, String> headers = new LinkedHashMap<>(call.headers());
        headers.put("Authorization", "Bearer " + connection.token());
        headers.putIfAbsent("Accept", "application/json");
        log.info("coding git probe request: {} {} (action={})",
            call.method(), Masker.mask(url), action);
        try {
            return http.send(call.method(), URI.create(url), headers, call.body());
        } catch (EngineException ex) {
            throw redacted(ex, connection);
        } catch (IllegalArgumentException ex) {
            throw EngineException.unavailable(
                redact("引擎地址不合法：" + ex.getMessage(), connection));
        }
    }

    private CodingProperties.Endpoint endpoint(String action) {
        CodingProperties.Endpoint endpoint = properties.getEndpoints().get(action);
        if (endpoint == null) {
            throw EngineException.unavailable(
                "引擎端点未配置：action=" + action + "（请检查 coding-endpoints.yml）");
        }
        return endpoint;
    }

    private static String absolutize(String baseUrl, String url) {
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return url.startsWith("/") ? base + url : base + "/" + url;
    }

    private void validateOutboundScheme(String url, CodingEngine.Connection connection) {
        if (engineProperties.isAllowInsecureHttp()) {
            return;
        }
        if (url.toLowerCase(Locale.ROOT).startsWith("https://")) {
            return;
        }
        throw EngineException.unavailable(redact(
            "CODING 出站地址必须使用 HTTPS：" + url
                + "（仅内网测试可配置 release.engine.allow-insecure-http=true）",
            connection));
    }

    private JsonNode parse(EngineHttpClient.HttpResult result, String action) {
        String body = result.body();
        if (body == null || body.isBlank()) {
            throw EngineException.unavailable(
                "引擎响应为空（action=" + action + "，HTTP " + result.status() + "）");
        }
        try {
            return objectMapper.readTree(body);
        } catch (JsonProcessingException ex) {
            throw EngineException.unavailable(
                "引擎响应不是合法 JSON（action=" + action + "）");
        }
    }

    /** One configured JSONPath-subset expression; missing nodes yield {@code null}. */
    private static String text(JsonNode root, String expression, String action) {
        if (expression == null || expression.isBlank()) {
            return null;
        }
        JsonNode node;
        try {
            node = JsonPathLite.read(root, expression.trim());
        } catch (IllegalArgumentException ex) {
            throw EngineException.unavailable(
                "端点 action=" + action + " 的响应表达式不合法：" + expression);
        }
        if (node == null || node.isMissingNode() || node.isNull() || !node.isValueNode()) {
            return null;
        }
        String value = node.asText();
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static boolean parseBoolean(String value) {
        String word = value.toLowerCase(Locale.ROOT);
        return switch (word) {
            case "true", "1", "yes", "y" -> true;
            case "false", "0", "no", "n" -> false;
            default -> throw EngineException.unavailable(
                "无法解析 git.changes 的变更标志：" + value + "（期望 true/false、1/0 或 yes/no）");
        };
    }

    private static void put(Map<String, String> vars, String name, String value) {
        if (value != null) {
            vars.put(name, value);
        }
    }

    private EngineException redacted(EngineException ex, CodingEngine.Connection connection) {
        String message = redact(ex.getMessage(), connection);
        return ex.getError() == BizError.ENGINE_TIMEOUT
            ? EngineException.timeout(message)
            : EngineException.unavailable(message);
    }

    private static String redact(String message, CodingEngine.Connection connection) {
        String result = message == null ? "" : message;
        if (connection != null && connection.token() != null && !connection.token().isEmpty()) {
            result = result.replace(connection.token(), REDACTED);
        }
        return Masker.mask(result);
    }
}
