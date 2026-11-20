package com.sf.release.infra.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sf.release.common.BizError;
import com.sf.release.common.Masker;
import com.sf.release.domain.engine.BuildCmd;
import com.sf.release.domain.engine.CicdEngine;
import com.sf.release.domain.engine.DeployCmd;
import com.sf.release.domain.engine.EngineException;
import com.sf.release.domain.engine.EngineResult;
import com.sf.release.domain.engine.EngineStatus;
import com.sf.release.domain.engine.TriggerResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * {@code CODING} engine adapter, written from the documented capability contract (no CODING
 * tenant was reachable while implementing this task).
 *
 * <p><strong>Configuration, not code.</strong> Paths, HTTP methods, request-body field names
 * and response {@code $.a.b} expressions come from {@code coding-endpoints.yml}; engine
 * status words are mapped by {@code coding.statusMapping}. Anything guessed from incomplete
 * documentation is therefore correctable by editing YAML only.
 *
 * <p><strong>Credentials.</strong> The caller supplies the current connection (base URL plus
 * the already-decrypted token). The token is only ever placed in the {@code Authorization}
 * header and is redacted from every log line and exception message.
 *
 * <p><strong>Digest.</strong> {@link #query(String)} returns a {@code null} digest when the
 * configured expression is absent from the response; it never fails the call for that.
 */
public class CodingEngine implements CicdEngine {

    public static final String ENGINE_NAME = "CODING";

    static final String ACTION_BUILD_START = "build.start";
    static final String ACTION_BUILD_LOGS = "build.logs";
    static final String ACTION_DEPLOY_START = "deploy.start";
    static final String ACTION_CANCEL = "cancel";

    private static final Logger log = LoggerFactory.getLogger(CodingEngine.class);
    private static final String REDACTED = "***";

    private final CodingProperties properties;
    private final EndpointTemplateResolver resolver;
    private final ObjectMapper objectMapper;
    private final EngineHttpClient http;
    private final Supplier<Connection> connectionSupplier;

    public CodingEngine(CodingProperties properties,
                        EndpointTemplateResolver resolver,
                        ObjectMapper objectMapper,
                        EngineHttpClient http,
                        Connection connection) {
        this(properties, resolver, objectMapper, http, () -> connection);
    }

    /**
     * @param connectionSupplier resolves the current decrypted connection per call, so a
     *                           credential rotation or base-URL change takes effect without
     *                           rebuilding the adapter
     */
    public CodingEngine(CodingProperties properties,
                        EndpointTemplateResolver resolver,
                        ObjectMapper objectMapper,
                        EngineHttpClient http,
                        Supplier<Connection> connectionSupplier) {
        if (properties == null || resolver == null || objectMapper == null || http == null
                || connectionSupplier == null) {
            throw new IllegalArgumentException("CodingEngine dependencies are required");
        }
        this.properties = properties;
        this.resolver = resolver;
        this.objectMapper = objectMapper;
        this.http = http;
        this.connectionSupplier = connectionSupplier;
    }

    @Override
    public String name() {
        return ENGINE_NAME;
    }

    /**
     * Starts the bound build task with branch/commit variables. The body field names come
     * from the endpoint's {@code body} template.
     */
    @Override
    public TriggerResult triggerBuild(BuildCmd cmd) {
        if (cmd == null) {
            throw EngineException.unavailable("构建参数不能为空");
        }
        CodingProperties.Endpoint endpoint = endpoint(ACTION_BUILD_START);
        Map<String, String> vars = new LinkedHashMap<>();
        put(vars, "unitId", String.valueOf(cmd.unitId()));
        put(vars, "taskId", cmd.taskId());
        put(vars, "branch", cmd.branch());
        put(vars, "commitSha", cmd.commitSha());
        put(vars, "pipelineNo", cmd.pipelineNo());
        put(vars, "attempt", String.valueOf(cmd.attempt()));

        EngineHttpClient.HttpResult result = execute(ACTION_BUILD_START, vars);
        JsonNode root = parse(result, ACTION_BUILD_START);
        String externalId = text(root, endpoint.getResponseId(), ACTION_BUILD_START);
        if (externalId == null) {
            throw EngineException.unavailable(
                "触发构建的响应缺少 externalId（action=" + ACTION_BUILD_START
                    + "，请检查 responseId 配置）");
        }
        String externalUrl = text(root, endpoint.getResponseUrl(), ACTION_BUILD_START);
        log.info("coding build triggered: externalId={}, pipelineNo={}",
            externalId, cmd.pipelineNo());
        return new TriggerResult(externalId, externalUrl);
    }

    /** Starts the bound deploy task for one DE with the image built for the instance. */
    @Override
    public TriggerResult triggerDeploy(DeployCmd cmd) {
        if (cmd == null) {
            throw EngineException.unavailable("部署参数不能为空");
        }
        CodingProperties.Endpoint endpoint = endpoint(ACTION_DEPLOY_START);
        Map<String, String> vars = new LinkedHashMap<>();
        put(vars, "unitId", String.valueOf(cmd.unitId()));
        put(vars, "taskId", cmd.taskId());
        put(vars, "envKind", cmd.envKind());
        put(vars, "deName", cmd.deName());
        put(vars, "imageDigest", cmd.imageDigest());
        put(vars, "pipelineNo", cmd.pipelineNo());
        put(vars, "attempt", String.valueOf(cmd.attempt()));

        EngineHttpClient.HttpResult result = execute(ACTION_DEPLOY_START, vars);
        JsonNode root = parse(result, ACTION_DEPLOY_START);
        String externalId = text(root, endpoint.getResponseId(), ACTION_DEPLOY_START);
        if (externalId == null) {
            throw EngineException.unavailable(
                "触发部署的响应缺少 externalId（action=" + ACTION_DEPLOY_START
                    + "，请检查 responseId 配置）");
        }
        String externalUrl = text(root, endpoint.getResponseUrl(), ACTION_DEPLOY_START);
        log.info("coding deploy triggered: externalId={}, de={}, pipelineNo={}",
            externalId, cmd.deName(), cmd.pipelineNo());
        return new TriggerResult(externalId, externalUrl);
    }

    /** Queries the action configured by {@code coding.query-action} (default build.query). */
    @Override
    public EngineResult query(String externalId) {
        String action = properties.getQueryAction();
        CodingProperties.Endpoint endpoint = endpoint(action);
        String statusExpression = endpoint.getResponseStatus();
        if (statusExpression == null || statusExpression.isBlank()) {
            throw EngineException.unavailable(
                "端点 action=" + action + " 缺少 responseStatus 配置");
        }
        EngineHttpClient.HttpResult result =
            execute(action, Map.of("externalId", requireExternalId(externalId)));
        JsonNode root = parse(result, action);
        String word = text(root, statusExpression, action);
        if (word == null) {
            throw EngineException.unavailable(
                "引擎响应缺少状态字段（action=" + action + "，expression="
                    + statusExpression + "）");
        }
        EngineStatus status = mapStatus(word, action);
        String digest = text(root, endpoint.getResponseDigest(), action);
        return new EngineResult(status, digest, word);
    }

    /** Returns the raw log body reported by the engine. */
    @Override
    public String logs(String externalId, int offset, int limit) {
        Map<String, String> vars = new LinkedHashMap<>();
        put(vars, "externalId", requireExternalId(externalId));
        put(vars, "offset", String.valueOf(offset));
        put(vars, "limit", String.valueOf(limit));
        EngineHttpClient.HttpResult result = execute(ACTION_BUILD_LOGS, vars);
        return result.body() == null ? "" : result.body();
    }

    @Override
    public void cancel(String externalId) {
        execute(ACTION_CANCEL, Map.of("externalId", requireExternalId(externalId)));
    }

    // ------------------------------------------------------------------ request plumbing

    private EngineHttpClient.HttpResult execute(String action, Map<String, String> vars) {
        Connection connection = connectionSupplier.get();
        if (connection == null) {
            throw EngineException.unavailable("CODING 连接未配置：" + action);
        }
        EndpointTemplateResolver.ResolvedCall call = resolver.resolve(action, vars);
        String url = absolutize(connection.baseUrl(), call.url());
        Map<String, String> headers = new LinkedHashMap<>(call.headers());
        headers.put("Authorization", "Bearer " + connection.token());
        headers.putIfAbsent("Accept", "application/json");
        if (call.body() != null) {
            headers.putIfAbsent("Content-Type", "application/json; charset=UTF-8");
        }
        log.info("coding engine request: {} {} (action={})",
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
        String base = baseUrl.endsWith("/")
            ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return url.startsWith("/") ? base + url : base + "/" + url;
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
            // The parser message can quote the response body; do not risk leaking a token.
            throw EngineException.unavailable(
                "引擎响应不是合法 JSON（action=" + action + "）");
        }
    }

    private EngineStatus mapStatus(String word, String action) {
        String normalized = word.trim();
        for (Map.Entry<String, List<String>> entry : properties.getStatusMapping().entrySet()) {
            EngineStatus status = parseStatusKey(entry.getKey(), action);
            if (entry.getValue() == null) {
                continue;
            }
            for (String engineWord : entry.getValue()) {
                if (engineWord != null && normalized.equalsIgnoreCase(engineWord.trim())) {
                    return status;
                }
            }
        }
        throw EngineException.unavailable(
            "未识别的引擎状态词：" + normalized + "（action=" + action
                + "，请检查 coding.statusMapping）");
    }

    private static EngineStatus parseStatusKey(String key, String action) {
        String normalized = key == null ? "" : key.trim();
        // The design brief writes keys as "#{QUEUED}"; accept that form as well as "QUEUED"
        // so either spelling in configuration maps to the same EngineStatus.
        if (normalized.startsWith("#{") && normalized.endsWith("}")) {
            normalized = normalized.substring(2, normalized.length() - 1).trim();
        }
        try {
            return EngineStatus.valueOf(normalized.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw EngineException.unavailable(
                "statusMapping 的键不是 EngineStatus：" + key + "（action=" + action + "）");
        }
    }

    /**
     * Extracts one JSONPath-lite expression ({@code $.a.b[0].c}) from a response.
     * Missing nodes and non-value nodes yield {@code null}.
     */
    private static String text(JsonNode root, String expression, String action) {
        if (expression == null || expression.isBlank()) {
            return null;
        }
        JsonNode node = path(root, expression.trim(), action);
        if (node == null || node.isMissingNode() || node.isNull() || !node.isValueNode()) {
            return null;
        }
        String value = node.asText();
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static JsonNode path(JsonNode root, String expression, String action) {
        if (root == null) {
            return null;
        }
        if (!expression.startsWith("$")) {
            throw EngineException.unavailable(
                "JSONPath 表达式必须以 $ 开头：" + expression + "（action=" + action + "）");
        }
        JsonNode node = root;
        int i = 1;
        while (i < expression.length()) {
            char ch = expression.charAt(i);
            if (ch == '.') {
                int start = ++i;
                while (i < expression.length()
                        && expression.charAt(i) != '.'
                        && expression.charAt(i) != '[') {
                    i++;
                }
                if (start == i) {
                    throw badPath(expression, action);
                }
                node = node == null || !node.isObject()
                    ? null : node.get(expression.substring(start, i));
            } else if (ch == '[') {
                int end = expression.indexOf(']', i);
                if (end < 0) {
                    throw badPath(expression, action);
                }
                String index = expression.substring(i + 1, end).trim();
                if (index.isEmpty() || !index.chars().allMatch(Character::isDigit)) {
                    throw badPath(expression, action);
                }
                node = node == null || !node.isArray() ? null : node.get(Integer.parseInt(index));
                i = end + 1;
            } else {
                throw badPath(expression, action);
            }
        }
        return node;
    }

    private static EngineException badPath(String expression, String action) {
        return EngineException.unavailable(
            "JSONPath 表达式不合法：" + expression + "（action=" + action + "）");
    }

    private static String requireExternalId(String externalId) {
        if (externalId == null || externalId.isBlank()) {
            throw EngineException.unavailable("externalId 不能为空");
        }
        return externalId.trim();
    }

    private static void put(Map<String, String> vars, String name, String value) {
        if (value != null) {
            vars.put(name, value);
        }
    }

    private EngineException redacted(EngineException ex, Connection connection) {
        String message = redact(ex.getMessage(), connection);
        return ex.getError() == BizError.ENGINE_TIMEOUT
            ? EngineException.timeout(message)
            : EngineException.unavailable(message);
    }

    private static String redact(String message, Connection connection) {
        String result = message == null ? "" : message;
        if (connection != null && connection.token() != null && !connection.token().isEmpty()) {
            result = result.replace(connection.token(), REDACTED);
        }
        return Masker.mask(result);
    }

    /**
     * Current CODING endpoint and decrypted credential. The record's {@code toString} masks
     * the token so accidental logging cannot expose it.
     */
    public record Connection(String baseUrl, String token) {

        public Connection {
            if (baseUrl == null || baseUrl.isBlank()) {
                throw new IllegalArgumentException("CODING baseUrl is required");
            }
            if (token == null || token.isBlank()) {
                throw new IllegalArgumentException("CODING token is required");
            }
            baseUrl = baseUrl.trim();
            token = token.trim();
        }

        @Override
        public String toString() {
            return "Connection[baseUrl=" + baseUrl + ", token=" + REDACTED + "]";
        }
    }

    }