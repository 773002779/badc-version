package com.sf.release.infra.engine;

import com.sf.release.common.Masker;
import com.sf.release.domain.engine.EngineException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Thin JDK {@link HttpClient} wrapper implementing the engine call policy:
 *
 * <ul>
 *   <li>connect timeout 5s, read timeout 30s (both injectable);</li>
 *   <li>HTTP 5xx (an unambiguous rejection): at most 3 attempts for every method, sleeping
 *       1s then 3s between them (the sleeper is injectable so tests do not wait);</li>
 *   <li>transport failures (read timeout or other I/O): retried only for {@code GET} actions.
 *       A {@code POST} trigger may already have created a job, so it is attempted once and
 *       never retried on timeout/I/O — retrying could create a duplicate job;</li>
 *   <li>HTTP 4xx: never retried, the reason is surfaced immediately;</li>
 *   <li>a read timeout is reported as {@link com.sf.release.common.BizError#ENGINE_TIMEOUT},
 *       every other failure as {@link com.sf.release.common.BizError#ENGINE_UNAVAILABLE}.</li>
 * </ul>
 *
 * <p>Only the method and URL are logged. Request headers are never logged, so the bearer
 * token cannot leak into the log stream.
 */
public final class EngineHttpClient {

    private static final Logger log = LoggerFactory.getLogger(EngineHttpClient.class);

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(30);
    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final List<Duration> DEFAULT_BACKOFF =
        List.of(Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofSeconds(9));
    private static final int MAX_ERROR_BODY = 500;

    private final HttpClient client;
    private final Duration readTimeout;
    private final int maxAttempts;
    private final List<Duration> backoff;
    private final Sleeper sleeper;

    /** Production defaults: connect 5s, read 30s, three attempts, 1s/3s/9s backoff. */
    public EngineHttpClient() {
        this(DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT);
    }

    public EngineHttpClient(Duration connectTimeout, Duration readTimeout) {
        this(connectTimeout, readTimeout, DEFAULT_MAX_ATTEMPTS, DEFAULT_BACKOFF,
            EngineHttpClient::sleepUninterruptibly);
    }

    public EngineHttpClient(Duration connectTimeout, Duration readTimeout, int maxAttempts,
                            List<Duration> backoff, Sleeper sleeper) {
        if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()) {
            throw new IllegalArgumentException("connect timeout must be positive");
        }
        if (readTimeout == null || readTimeout.isNegative() || readTimeout.isZero()) {
            throw new IllegalArgumentException("read timeout must be positive");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("max attempts must be at least 1");
        }
        this.client = HttpClient.newBuilder()
            .connectTimeout(connectTimeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        this.readTimeout = readTimeout;
        this.maxAttempts = maxAttempts;
        this.backoff = backoff == null ? List.of() : List.copyOf(backoff);
        this.sleeper = sleeper == null ? EngineHttpClient::sleepUninterruptibly : sleeper;
    }

    /**
     * Sends one call, retrying per the policy above.
     *
     * @return the 2xx response (status + body)
     * @throws EngineException for 4xx/5xx after the allowed attempts, I/O failure or timeout
     */
    public HttpResult send(String method, URI uri, Map<String, String> headers, String body) {
        String target = Masker.mask(uri.toString());
        boolean retryTransport = "GET".equalsIgnoreCase(method);
        Throwable lastIo = null;
        int attemptsMade = 0;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            attemptsMade = attempt;
            try {
                HttpRequest request = request(method, uri, headers, body);
                HttpResponse<String> response = sendWithReadDeadline(request);
                int status = response.statusCode();
                if (status >= 200 && status <= 299) {
                    return new HttpResult(status, response.body());
                }
                if (status >= 400 && status <= 499) {
                    throw EngineException.unavailable(
                        "引擎返回 HTTP " + status + "（4xx 不重试）：" + method + " " + target
                            + errorDetail(response.body()));
                }
                if (status >= 500 && status <= 599) {
                    if (attempt < maxAttempts) {
                        log.warn("coding engine HTTP {} on {} {} (attempt {}/{}), retrying",
                            status, method, target, attempt, maxAttempts);
                        pause(attempt);
                        continue;
                    }
                    throw EngineException.unavailable(
                        "引擎返回 HTTP " + status + "：" + method + " " + target
                            + errorDetail(response.body()));
                }
                throw EngineException.unavailable(
                    "引擎返回非预期状态 HTTP " + status + "：" + method + " " + target
                        + errorDetail(response.body()));
            } catch (HttpTimeoutException ex) {
                lastIo = ex;
                if (retryTransport && attempt < maxAttempts) {
                    log.warn("coding engine read timeout on {} {} (attempt {}/{}), retrying",
                        method, target, attempt, maxAttempts);
                    pause(attempt);
                    continue;
                }
            } catch (IOException ex) {
                lastIo = ex;
                if (retryTransport && attempt < maxAttempts) {
                    log.warn("coding engine I/O failure on {} {} (attempt {}/{}), retrying: {}",
                        method, target, attempt, maxAttempts, ex.getClass().getSimpleName());
                    pause(attempt);
                    continue;
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw EngineException.unavailable(
                    "引擎调用被中断：" + method + " " + target);
            }
            // A POST transport failure is ambiguous: the engine may already have created the
            // job, so it is not retried even when attempts remain.
            break;
        }
        if (lastIo instanceof HttpTimeoutException) {
            throw EngineException.timeout(
                "引擎调用超时：" + method + " " + target
                    + "（readTimeout=" + readTimeout.toMillis() + "ms，尝试 "
                    + attemptsMade + " 次）");
        }
        throw EngineException.unavailable(
            "引擎调用失败：" + method + " " + target
                + "（" + (lastIo == null ? "unknown" : lastIo.getClass().getSimpleName())
                + "，尝试 " + attemptsMade + " 次）");
    }

    /**
     * Sends the request and applies an explicit total read deadline. {@code HttpRequest.timeout}
     * alone does not cover a body that is delivered slowly after the headers, so a delayed body
     * would otherwise hang for as long as the server chooses. The async future is cancelled when
     * the deadline expires.
     */
    private HttpResponse<String> sendWithReadDeadline(HttpRequest request)
        throws IOException, InterruptedException {
        CompletableFuture<HttpResponse<String>> future =
            client.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        try {
            return future.get(readTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            throw new HttpTimeoutException(
                "read timeout after " + readTimeout.toMillis() + "ms");
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IOException("engine request failed", cause);
        }
    }

    private HttpRequest request(String method, URI uri, Map<String, String> headers, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(readTimeout);
        if (headers != null) {
            headers.forEach(builder::header);
        }
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.method(method,
                HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        return builder.build();
    }

    private void pause(int attempt) {
        if (backoff.isEmpty()) {
            return;
        }
        Duration delay = backoff.get(Math.min(attempt - 1, backoff.size() - 1));
        log.debug("coding engine retry backoff {} (attempt {})", delay, attempt);
        sleeper.sleep(delay);
    }

    private static String errorDetail(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String masked = Masker.mask(body);
        if (masked.length() > MAX_ERROR_BODY) {
            masked = masked.substring(0, MAX_ERROR_BODY) + "…";
        }
        return " | " + masked;
    }

    private static void sleepUninterruptibly(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw EngineException.unavailable("引擎重试等待被中断");
        }
    }

    /** Response of a successful (2xx) call. */
    public record HttpResult(int status, String body) {
    }

    /** Test seam: lets tests make retry backoff instantaneous. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration);
    }
}