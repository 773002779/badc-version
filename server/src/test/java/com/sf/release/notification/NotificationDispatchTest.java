package com.sf.release.notification;

import com.sf.release.application.notification.NotificationCommand;
import com.sf.release.application.notification.NotificationDispatchService;
import com.sf.release.application.notification.NotificationService;
import com.sf.release.application.notification.NotificationTarget;
import com.sf.release.domain.notification.NotificationEventKey;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The WeCom delivery loop (notification design section 5; test plan item 4): one immediate retry, the
 * resulting {@code SENT}/{@code FAILED} status and attempt count, and one failed row never stopping
 * another row.
 *
 * <p>A {@link MockWebServer} is the robot; the rows are created through the real {@code notify} path
 * with an explicit audience so the in-app half stays out of the assertions. The test profile allows a
 * plain-HTTP webhook and disables the scheduled dispatch, so this test drives
 * {@link NotificationDispatchService#dispatchDue(int)} itself.
 */
@SpringBootTest
@ActiveProfiles("test")
class NotificationDispatchTest {

    @Autowired NotificationDispatchService dispatcher;
    @Autowired NotificationService notifications;
    @Autowired JdbcTemplate jdbc;

    private MockWebServer server;

    @BeforeEach
    void setUp() throws IOException {
        NotificationFixtures.clean(jdbc);
        server = new MockWebServer();
        server.start();
        jdbc.update("UPDATE notification_setting SET wecom_enabled = 1, wecom_webhook = ?"
            + " WHERE id = 1", server.url("/wecom-hook").toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void a_failure_followed_by_a_success_is_sent_with_two_attempts() throws InterruptedException {
        server.setDispatcher(scripted(500, 200));
        givenPending("ref-1");

        int finished = dispatcher.dispatchDue(50);

        assertThat(finished).isEqualTo(1);
        Map<String, Object> row = wecomRow("ref-1");
        assertThat(row.get("status")).isEqualTo("SENT");
        assertThat(row.get("attempts")).isEqualTo(2);
        assertThat(row.get("sent_at")).isNotNull();
        assertThat(row.get("failure_reason")).isNull();
        assertThat(server.getRequestCount()).isEqualTo(2);
        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getPath()).isEqualTo("/wecom-hook");
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getHeader("Content-Type")).contains("application/json");
        assertThat(request.getBody().readUtf8())
            .contains("\"msgtype\":\"markdown\"")
            .contains("### 构建失败")
            .contains("> PO-DISPATCH-1");
    }

    @Test
    void two_failures_mark_the_row_failed_with_the_reason() throws InterruptedException {
        server.setDispatcher(scripted(500, 500));
        givenPending("ref-1");

        assertThat(dispatcher.dispatchDue(50)).isEqualTo(1);

        Map<String, Object> row = wecomRow("ref-1");
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("attempts")).isEqualTo(2);
        assertThat(row.get("sent_at")).isNull();
        assertThat((String) row.get("failure_reason")).contains("HTTP 500");
        assertThat(server.getRequestCount()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT status FROM notification"
            + " WHERE dedupe_key LIKE '%|WECOM|ref-1'", String.class)).isEqualTo("FAILED");
    }

    @Test
    void one_failed_row_does_not_stop_the_next_pending_row() throws InterruptedException {
        server.setDispatcher(scripted(500, 500, 200));
        givenPending("ref-1");
        givenPending("ref-2");

        assertThat(dispatcher.dispatchDue(50)).isEqualTo(2);

        assertThat(wecomRow("ref-1").get("status")).isEqualTo("FAILED");
        assertThat(wecomRow("ref-2").get("status")).isEqualTo("SENT");
        assertThat(wecomRow("ref-2").get("attempts")).isEqualTo(1);
        assertThat(server.getRequestCount()).isEqualTo(3);
    }

    @Test
    void pending_rows_are_closed_when_the_channel_is_switched_off() throws InterruptedException {
        givenPending("ref-1");
        jdbc.update("UPDATE notification_setting SET wecom_enabled = 0 WHERE id = 1");

        assertThat(dispatcher.dispatchDue(50)).isEqualTo(1);

        Map<String, Object> row = wecomRow("ref-1");
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat((String) row.get("failure_reason")).contains("未启用");
        assertThat(server.getRequestCount()).isZero();
    }

    // ------------------------------------------------------------------ helpers

    /** One WeCom row through the real notify path. */
    private void givenPending(String ref) {
        notifications.notify(new NotificationCommand(NotificationEventKey.BUILD_FAILED,
            "PO-DISPATCH-1", "PO-DISPATCH-1", "构建失败", "发布单 PO-DISPATCH-1 的构建失败。", ref,
            (resolver, baseBody) -> List.of(new NotificationTarget("dev-1", null))));
    }

    private Map<String, Object> wecomRow(String ref) {
        return jdbc.queryForMap("SELECT * FROM notification WHERE channel = 'WECOM'"
            + " AND dedupe_key LIKE ?", "%|WECOM|" + ref);
    }

    /**
     * A dispatcher answering the scripted statuses in order, then 200: the sender treats a non-2xx
     * status as a failure, so two 500s are "two failed attempts".
     */
    private static Dispatcher scripted(int... statuses) {
        AtomicInteger calls = new AtomicInteger();
        return new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                int call = calls.incrementAndGet();
                int status = call <= statuses.length ? statuses[call - 1] : 200;
                if (status == 200) {
                    return new MockResponse().setResponseCode(200)
                        .setBody("{\"errcode\":0,\"errmsg\":\"ok\"}");
                }
                return new MockResponse().setResponseCode(status).setBody("robot error");
            }
        };
    }
}