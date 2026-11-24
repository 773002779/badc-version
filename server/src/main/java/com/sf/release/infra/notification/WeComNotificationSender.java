package com.sf.release.infra.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sf.release.application.notification.NotificationMessage;
import com.sf.release.application.notification.NotificationSendException;
import com.sf.release.application.notification.NotificationSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * The 企微机器人 sender of the notification subsystem (design section 5): a markdown message POSTed to
 * the configured webhook with a five-second total deadline.
 *
 * <p><strong>Body.</strong> {@code ### {title}} + the plain-text body + a signed line
 * {@code > {object_label} · {createdAt}}; the same values are stored on the row, so the in-app and
 * WeCom views cannot drift.
 *
 * <p><strong>Failure.</strong> A non-2xx status, a non-zero {@code errcode} or a transport error
 * throws {@link NotificationSendException}; the dispatcher owns the single retry. The webhook is a
 * sensitive configuration value, so the log names only its host and the failure text carries no full
 * URL.
 *
 * <p><strong>One instance, no shared client state.</strong> The JDK {@link HttpClient} is stateless per
 * call; the sender is a singleton bean and performs no I/O at construction.
 */
public final class WeComNotificationSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(WeComNotificationSender.class);

    /** The design's HTTP timeout of one delivery attempt. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    private static final int MAX_ERROR_BODY = 200;

    private static final DateTimeFormatter CREATED_AT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final ObjectMapper objectMapper;
    private final boolean allowInsecureHttp;
    private final Duration timeout;
    private final HttpClient client;

    public WeComNotificationSender(ObjectMapper objectMapper, boolean allowInsecureHttp) {
        this(objectMapper, allowInsecureHttp, DEFAULT_TIMEOUT);
    }

    public WeComNotificationSender(ObjectMapper objectMapper, boolean allowInsecureHttp,
                                   Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        this.objectMapper = objectMapper;
        this.allowInsecureHttp = allowInsecureHttp;
        this.timeout = timeout;
        this.client = HttpClient.newBuilder()
            .connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    @Override
    public void send(String webhook, NotificationMessage message) {
        URI uri = requireWebhook(webhook);
        String host = uri.getHost();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("msgtype", "markdown");
        payload.putObject("markdown").put("content", markdownOf(message));
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                .build();
            HttpResponse<String> response = client.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status < 200 || status > 299) {
                throw new NotificationSendException("企微机器人返回 HTTP " + status
                    + "（host=" + host + "）" + detail(response.body()));
            }
            verifyErrcode(response.body(), host);
            log.debug("wecom notification sent: host={}, title={}", host, message.title());
        } catch (NotificationSendException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new NotificationSendException("企微通知发送失败（host=" + host + "）："
                + ex.getClass().getSimpleName(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new NotificationSendException("企微通知发送被中断（host=" + host + "）", ex);
        }
    }

    // ------------------------------------------------------------------ payload

    /** {@code ### title} + body + {@code > label · createdAt}. */
    static String markdownOf(NotificationMessage message) {
        StringBuilder content = new StringBuilder("### ");
        content.append(message.title() == null ? "" : message.title()).append('\n');
        content.append(message.body() == null ? "" : message.body()).append('\n');
        content.append("> ");
        content.append(message.objectLabel() == null || message.objectLabel().isBlank()
            ? "通知" : message.objectLabel());
        content.append(" · ");
        content.append(message.createdAt() == null ? "" : CREATED_AT.format(message.createdAt()));
        return content.toString();
    }

    private void verifyErrcode(String body, String host) {
        if (body == null || body.isBlank()) {
            // A 2xx with no body is not the documented answer, but there is no failure to report.
            return;
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(body);
        } catch (IOException ex) {
            throw new NotificationSendException("企微机器人返回了无法解析的响应（host=" + host + "）",
                ex);
        }
        JsonNode errcode = node.get("errcode");
        if (errcode != null && errcode.asInt(0) != 0) {
            throw new NotificationSendException("企微机器人拒绝了消息：errcode="
                + errcode.asInt() + "，errmsg=" + node.path("errmsg").asText("")
                + "（host=" + host + "）");
        }
    }

    private URI requireWebhook(String webhook) {
        if (webhook == null || webhook.isBlank()) {
            throw new NotificationSendException("企微机器人地址为空");
        }
        URI uri;
        try {
            uri = URI.create(webhook.trim());
        } catch (IllegalArgumentException ex) {
            throw new NotificationSendException("企微机器人地址格式不合法", ex);
        }
        String scheme = uri.getScheme();
        boolean https = "https".equalsIgnoreCase(scheme);
        boolean insecureHttp = "http".equalsIgnoreCase(scheme) && allowInsecureHttp;
        if ((!https && !insecureHttp) || uri.getHost() == null) {
            throw new NotificationSendException("企微机器人地址必须以 https:// 开头");
        }
        return uri;
    }

    private static String detail(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String trimmed = body.trim();
        if (trimmed.length() > MAX_ERROR_BODY) {
            trimmed = trimmed.substring(0, MAX_ERROR_BODY) + "…";
        }
        return " | " + trimmed;
    }
}