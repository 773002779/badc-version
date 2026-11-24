package com.sf.release.notification;

import com.sf.release.application.notification.NotificationCommand;
import com.sf.release.application.notification.NotificationService;
import com.sf.release.application.notification.NotificationTarget;
import com.sf.release.domain.notification.NotificationEventKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@code notify} against the real test schema (notification design sections 2, 3 and 5; test plan
 * items 2, 3 and 5): dedupe through the unique key, the in-app row always written, the WeCom row only
 * when the channel is enabled and configured, and an internal failure never reaching the caller.
 */
@SpringBootTest
@ActiveProfiles("test")
class NotificationServiceTest {

    @Autowired NotificationService notifications;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        NotificationFixtures.clean(jdbc);
        long subsystem = NotificationFixtures.givenSubsystem(jdbc, "SVC-SYS", "sys-owner");
        long unit = NotificationFixtures.givenUnit(jdbc, subsystem, "svc-unit", "unit-owner");
        long task = NotificationFixtures.givenTask(jdbc, subsystem, "SVC-TASK", "creator");
        NotificationFixtures.givenMember(jdbc, task, "dev-1", "DEV");
        NotificationFixtures.givenMember(jdbc, task, "test-1", "TEST");
        long item = NotificationFixtures.givenItem(jdbc, task, unit, "CR-SVC-1", "READY");
        NotificationFixtures.givenOrder(jdbc, item, "PO-SVC-1", "READY");
    }

    // ------------------------------------------------------------------ dedupe

    @Test
    void the_same_event_recipient_and_ref_are_written_once() {
        NotificationCommand command = command("ref-1");

        notifications.notify(command);
        notifications.notify(command);

        assertThat(rows()).hasSize(1);
        Map<String, Object> row = rows().get(0);
        assertThat(row.get("event_key")).isEqualTo("BUILD_FAILED");
        assertThat(row.get("object_type")).isEqualTo("ORDER");
        assertThat(row.get("object_id")).isEqualTo("PO-SVC-1");
        assertThat(row.get("object_label")).isEqualTo("PO-SVC-1");
        assertThat(row.get("channel")).isEqualTo("INAPP");
        assertThat(row.get("status")).isEqualTo("SENT");
        assertThat(row.get("read_at")).isNull();
        assertThat(row.get("sent_at")).isNotNull();
        assertThat((String) row.get("dedupe_key"))
            .isEqualTo("BUILD_FAILED|ORDER|PO-SVC-1|dev-1|INAPP|ref-1");
    }

    @Test
    void another_occurrence_is_another_row() {
        notifications.notify(command("ref-1"));
        notifications.notify(command("ref-2"));

        assertThat(rows()).hasSize(2);
        assertThat(recipients()).containsOnly("dev-1");
    }

    @Test
    void another_channel_is_its_own_row() {
        enableWeCom("https://wecom.example.invalid/hook");

        notifications.notify(command("ref-1"));

        assertThat(rows()).hasSize(2);
        assertThat(channels()).containsExactlyInAnyOrder("INAPP", "WECOM");
        Map<String, Object> wecom = rows().stream()
            .filter(row -> "WECOM".equals(row.get("channel")))
            .findFirst().orElseThrow();
        assertThat(wecom.get("status")).isEqualTo("PENDING");
        assertThat(wecom.get("attempts")).isEqualTo(0);
        assertThat(wecom.get("sent_at")).isNull();
        assertThat(wecom.get("dedupe_key"))
            .isEqualTo("BUILD_FAILED|ORDER|PO-SVC-1|dev-1|WECOM|ref-1");
    }

    // ------------------------------------------------------------------ channel switch

    @Test
    void the_default_setting_sends_in_app_only() {
        notifications.notify(command("ref-1"));

        assertThat(channels()).containsExactly("INAPP");
    }

    @Test
    void an_enabled_channel_without_a_webhook_writes_no_wecom_row() {
        jdbc.update("UPDATE notification_setting SET wecom_enabled = 1, wecom_webhook = NULL"
            + " WHERE id = 1");

        notifications.notify(command("ref-1"));

        assertThat(channels()).containsExactly("INAPP");
    }

    @Test
    void a_disabled_channel_writes_no_wecom_row_even_with_a_webhook() {
        enableWeCom("https://wecom.example.invalid/hook");
        jdbc.update("UPDATE notification_setting SET wecom_enabled = 0 WHERE id = 1");

        notifications.notify(command("ref-1"));

        assertThat(channels()).containsExactly("INAPP");
    }

    // ------------------------------------------------------------------ failure isolation

    @Test
    void an_internal_failure_never_reaches_the_caller() {
        NotificationCommand broken = new NotificationCommand(NotificationEventKey.BUILD_FAILED,
            "PO-SVC-1", "PO-SVC-1", "构建失败", "body", "ref-1",
            (resolver, baseBody) -> {
                throw new IllegalStateException("simulated resolver failure");
            });

        assertThatCode(() -> notifications.notify(broken)).doesNotThrowAnyException();
        assertThat(rows()).isEmpty();

        notifications.notify(command("ref-2"));
        assertThat(rows()).hasSize(1);
    }

    // ------------------------------------------------------------------ fixtures and helpers

    private NotificationCommand command(String ref) {
        return new NotificationCommand(NotificationEventKey.BUILD_FAILED, "PO-SVC-1", "PO-SVC-1",
            "构建失败", "发布单 PO-SVC-1 的构建节点执行失败。", ref,
            (resolver, baseBody) -> List.of(new NotificationTarget("dev-1", null)));
    }

    private void enableWeCom(String webhook) {
        jdbc.update("UPDATE notification_setting SET wecom_enabled = 1, wecom_webhook = ?"
            + " WHERE id = 1", webhook);
    }

    private List<Map<String, Object>> rows() {
        return jdbc.queryForList("SELECT * FROM notification ORDER BY id");
    }

    private List<String> channels() {
        return jdbc.queryForList("SELECT channel FROM notification ORDER BY channel", String.class);
    }

    private List<String> recipients() {
        return jdbc.queryForList("SELECT recipient FROM notification ORDER BY recipient",
            String.class);
    }
}