package com.sf.release.notification;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * HTTP seam of the notification centre and the 「消息通知」 settings (notification design section 6;
 * test plan item 6).
 *
 * <p>Pinned here: every logged-in caller reads only their own in-app rows, another recipient's id is
 * answered exactly like a missing one (40000, no enumeration), read and read-all are idempotent, and
 * the settings block needs {@code setting:manage}.
 *
 * <p>Package {@code com.sf.release.notification}, not {@code ..web..}: the architecture rule forbids
 * the web package depending on domain/infra, and these tests need the real schema.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NotificationControllerTest {

    private static final String USER_HEADER = "X-User-Id";
    private static final String USER = "notify-ctrl-user";
    private static final String OTHER = "notify-ctrl-other";
    private static final String ADMIN = "notify-ctrl-admin";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        NotificationFixtures.clean(jdbc);
        NotificationFixtures.givenUser(jdbc, USER, "DEV");
        NotificationFixtures.givenUser(jdbc, OTHER, "DEV");
        NotificationFixtures.givenUser(jdbc, ADMIN, "ADMIN");
    }

    // ------------------------------------------------------------------ reads

    @Test
    void the_page_returns_only_the_callers_in_app_rows_newest_first() throws Exception {
        long newer = givenNotification(USER, "BUILD_FAILED", "ORDER", "PO-CTRL-1", "INAPP",
            Instant.now().minusSeconds(60), null, "d1");
        long older = givenNotification(USER, "PROD_DONE", "ORDER", "PO-CTRL-2", "INAPP",
            Instant.now().minusSeconds(120), Instant.now().minusSeconds(30), "d2");
        givenNotification(OTHER, "APPROVAL_PENDING", "ORDER", "PO-CTRL-3", "INAPP",
            Instant.now(), null, "d3");
        givenNotification(USER, "BUILD_FAILED", "ORDER", "PO-CTRL-1", "WECOM",
            Instant.now(), null, "d4");

        mvc.perform(get("/api/v1/notifications").param("page", "1").param("size", "10")
                .header(USER_HEADER, USER))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.total").value(2))
            .andExpect(jsonPath("$.data.pageNo").value(1))
            .andExpect(jsonPath("$.data.pageSize").value(10))
            .andExpect(jsonPath("$.data.unread").value(1))
            .andExpect(jsonPath("$.data.records.length()").value(2))
            .andExpect(jsonPath("$.data.records[0].id").value(newer))
            .andExpect(jsonPath("$.data.records[0].eventKey").value("BUILD_FAILED"))
            .andExpect(jsonPath("$.data.records[0].eventLabel").value("构建失败"))
            .andExpect(jsonPath("$.data.records[0].objectType").value("ORDER"))
            .andExpect(jsonPath("$.data.records[0].objectId").value("PO-CTRL-1"))
            .andExpect(jsonPath("$.data.records[0].read").value(false))
            .andExpect(jsonPath("$.data.records[0].channel").doesNotExist())
            .andExpect(jsonPath("$.data.records[1].id").value(older))
            .andExpect(jsonPath("$.data.records[1].eventLabel").value("生产完成"))
            .andExpect(jsonPath("$.data.records[1].read").value(true));
    }

    @Test
    void the_unread_filter_and_the_count_agree() throws Exception {
        givenNotification(USER, "BUILD_FAILED", "ORDER", "PO-CTRL-1", "INAPP", Instant.now(),
            null, "d1");
        givenNotification(USER, "PROD_DONE", "ORDER", "PO-CTRL-2", "INAPP", Instant.now(),
            Instant.now(), "d2");

        mvc.perform(get("/api/v1/notifications").param("unreadOnly", "true")
                .header(USER_HEADER, USER))
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.records.length()").value(1))
            .andExpect(jsonPath("$.data.unread").value(1));

        mvc.perform(get("/api/v1/notifications/unread-count").header(USER_HEADER, USER))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.unread").value(1));
    }

    @Test
    void marking_one_row_read_is_idempotent_and_another_recipients_row_is_not_found()
        throws Exception {
        long mine = givenNotification(USER, "BUILD_FAILED", "ORDER", "PO-CTRL-1", "INAPP",
            Instant.now(), null, "d1");
        long other = givenNotification(OTHER, "BUILD_FAILED", "ORDER", "PO-CTRL-2", "INAPP",
            Instant.now(), null, "d2");

        mvc.perform(post("/api/v1/notifications/{id}/read", mine).header(USER_HEADER, USER))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.id").value(mine))
            .andExpect(jsonPath("$.data.read").value(true));
        mvc.perform(post("/api/v1/notifications/{id}/read", mine).header(USER_HEADER, USER))
            .andExpect(jsonPath("$.code").value(0));
        assertThat(readAt(mine)).isNotNull();

        mvc.perform(post("/api/v1/notifications/{id}/read", other).header(USER_HEADER, USER))
            .andExpect(jsonPath("$.code").value(40000));
        assertThat(readAt(other)).as("another recipient's row must stay untouched").isNull();
    }

    @Test
    void read_all_marks_only_the_callers_rows_and_reports_the_count_once() throws Exception {
        givenNotification(USER, "BUILD_FAILED", "ORDER", "PO-CTRL-1", "INAPP", Instant.now(),
            null, "d1");
        givenNotification(USER, "PROD_DONE", "ORDER", "PO-CTRL-2", "INAPP", Instant.now(),
            null, "d2");
        long other = givenNotification(OTHER, "BUILD_FAILED", "ORDER", "PO-CTRL-3", "INAPP",
            Instant.now(), null, "d3");

        mvc.perform(post("/api/v1/notifications/read-all").header(USER_HEADER, USER))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.updated").value(2));
        mvc.perform(post("/api/v1/notifications/read-all").header(USER_HEADER, USER))
            .andExpect(jsonPath("$.data.updated").value(0));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification"
            + " WHERE recipient = ? AND read_at IS NULL", Integer.class, USER)).isZero();
        assertThat(readAt(other)).isNull();
    }

    @Test
    void an_invalid_page_request_is_40000() throws Exception {
        mvc.perform(get("/api/v1/notifications").param("page", "0").header(USER_HEADER, USER))
            .andExpect(jsonPath("$.code").value(40000))
            .andExpect(jsonPath("$.data.pageNo").isNotEmpty());

        mvc.perform(get("/api/v1/notifications").param("size", "101").header(USER_HEADER, USER))
            .andExpect(jsonPath("$.code").value(40000))
            .andExpect(jsonPath("$.data.pageSize").isNotEmpty());
    }

    @Test
    void a_request_without_identity_is_forbidden() throws Exception {
        mvc.perform(get("/api/v1/notifications"))
            .andExpect(jsonPath("$.code").value(40009));
    }

    // ------------------------------------------------------------------ settings

    @Test
    void the_settings_block_requires_setting_manage() throws Exception {
        mvc.perform(get("/api/v1/settings/notifications").header(USER_HEADER, USER))
            .andExpect(jsonPath("$.code").value(40009))
            .andExpect(jsonPath("$.data.perm").value("setting:manage"));

        mvc.perform(get("/api/v1/settings/notifications").header(USER_HEADER, ADMIN))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.wecomEnabled").value(false))
            .andExpect(jsonPath("$.data.wecomWebhook").doesNotExist())
            .andExpect(jsonPath("$.data.updatedBy").doesNotExist());
    }

    @Test
    void saving_the_block_persists_the_switch_the_webhook_and_the_actor() throws Exception {
        mvc.perform(put("/api/v1/settings/notifications").header(USER_HEADER, ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"wecomEnabled\":true,\"wecomWebhook\":"
                    + "\"https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=test\"}"))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.wecomEnabled").value(true))
            .andExpect(jsonPath("$.data.updatedBy").value(ADMIN));

        assertThat(jdbc.queryForObject("SELECT wecom_enabled FROM notification_setting"
            + " WHERE id = 1", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT wecom_webhook FROM notification_setting"
            + " WHERE id = 1", String.class))
            .startsWith("https://qyapi.weixin.qq.com");
    }

    @Test
    void saving_validates_the_url_and_the_switch() throws Exception {
        mvc.perform(put("/api/v1/settings/notifications").header(USER_HEADER, ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"wecomEnabled\":true,\"wecomWebhook\":\"ftp://robot.example/hook\"}"))
            .andExpect(jsonPath("$.code").value(40000))
            .andExpect(jsonPath("$.data.wecomWebhook").isNotEmpty());

        mvc.perform(put("/api/v1/settings/notifications").header(USER_HEADER, ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"wecomEnabled\":true,\"wecomWebhook\":\"  \"}"))
            .andExpect(jsonPath("$.code").value(40000));

        mvc.perform(put("/api/v1/settings/notifications").header(USER_HEADER, ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"wecomEnabled\":true,\"wecomWebhook\":\"" + "h".repeat(513) + "\"}"))
            .andExpect(jsonPath("$.code").value(40000));

        mvc.perform(put("/api/v1/settings/notifications").header(USER_HEADER, ADMIN)
                .contentType(MediaType.APPLICATION_JSON).content("{\"wecomWebhook\":null}"))
            .andExpect(jsonPath("$.code").value(40000))
            .andExpect(jsonPath("$.data.wecomEnabled").isNotEmpty());
    }

    // ------------------------------------------------------------------ fixtures

    private long givenNotification(String recipient, String eventKey, String objectType,
                                   String objectId, String channel, Instant createdAt,
                                   Instant readAt, String dedupeSuffix) {
        jdbc.update("INSERT INTO notification (event_key, object_type, object_id, object_label,"
                + " recipient, channel, title, body, status, read_at, attempts, failure_reason,"
                + " dedupe_key, created_at, sent_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, '标题', '正文', ?, ?, 0, NULL, ?, ?, ?)",
            eventKey, objectType, objectId, objectId, recipient, channel,
            "INAPP".equals(channel) ? "SENT" : "PENDING",
            readAt == null ? null : Timestamp.from(readAt),
            recipient + "|" + channel + "|" + dedupeSuffix, Timestamp.from(createdAt),
            "INAPP".equals(channel) ? Timestamp.from(createdAt) : null);
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM notification", Long.class);
        return id == null ? 0L : id;
    }

    private Instant readAt(long id) {
        Timestamp value = jdbc.queryForObject(
            "SELECT read_at FROM notification WHERE id = ?", Timestamp.class, id);
        return value == null ? null : value.toInstant();
    }
}