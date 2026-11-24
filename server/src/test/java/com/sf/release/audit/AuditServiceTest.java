package com.sf.release.audit;

import com.sf.release.application.audit.AuditCommand;
import com.sf.release.application.audit.AuditService;
import com.sf.release.application.notification.NotificationSettingService;
import com.sf.release.domain.audit.AuditAction;
import com.sf.release.domain.audit.AuditObjectType;
import com.sf.release.infra.repo.AuditLogMapper;
import com.sf.release.infra.repo.po.AuditLogPO;
import com.sf.release.infra.security.TestUserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The write model of the audit design section 3: the actor resolution (including the SYSTEM job),
 * the MDC trace id, the defensive truncation and — above all — the rule that an audit failure never
 * changes a business result.
 *
 * <p>{@link AuditLogMapper} is a mock here, which is exactly how the failure isolation is proven:
 * the mapper throws on insert and the real business service still succeeds.
 */
@SpringBootTest
@ActiveProfiles("test")
class AuditServiceTest {

    private static final String USER = AuditFixtures.USER;

    @Autowired AuditService audit;
    @Autowired NotificationSettingService notificationSettings;
    @Autowired JdbcTemplate jdbc;
    @MockBean AuditLogMapper auditLogMapper;

    @BeforeEach
    void setUp() {
        AuditFixtures.clean(jdbc);
        TestUserContext.as(USER, Set.of(), Set.of());
    }

    @AfterEach
    void tearDown() {
        TestUserContext.clear();
        MDC.remove("traceId");
    }

    @Test
    void the_row_takes_the_actor_the_trace_id_and_the_column_widths() {
        MDC.put("traceId", "trace-audit-1");
        AuditCommand command = AuditCommand.of(AuditAction.SETTING_UPDATE, "NOTIFICATION",
                "消息通知设置", "s".repeat(600))
            .reason("r".repeat(600))
            .before(Map.of())
            .after(Map.of("enabled", true))
            .build();

        audit.record(command);

        ArgumentCaptor<AuditLogPO> captor = ArgumentCaptor.forClass(AuditLogPO.class);
        verify(auditLogMapper).insertAudit(captor.capture());
        AuditLogPO row = captor.getValue();
        assertThat(row.getActor()).isEqualTo(USER);
        assertThat(row.getAction()).isEqualTo("SETTING_UPDATE");
        assertThat(row.getObjectType()).isEqualTo("SETTING");
        assertThat(row.getObjectId()).isEqualTo("NOTIFICATION");
        assertThat(row.getSummary()).hasSize(512);
        assertThat(row.getReason()).hasSize(512);
        assertThat(row.getBeforeJson()).as("an empty change set is null").isNull();
        assertThat(row.getAfterJson()).contains("\"enabled\":true");
        assertThat(row.getTraceId()).isEqualTo("trace-audit-1");
        assertThat(row.getCreatedAt()).isNotNull();
        assertThat(row.getScopeUnitId()).isNull();
        assertThat(row.getScopeSubsystemId()).isNull();
    }

    @Test
    void a_command_without_a_request_context_is_attributed_to_system_and_has_no_trace() {
        TestUserContext.clear();

        audit.record(AuditCommand.of(AuditAction.FREEZE_EXPIRE, "7", "全局封板", "封板到期")
            .before(Map.of("status", "ACTIVE"))
            .after(Map.of("status", "EXPIRED"))
            .build());

        ArgumentCaptor<AuditLogPO> captor = ArgumentCaptor.forClass(AuditLogPO.class);
        verify(auditLogMapper).insertAudit(captor.capture());
        assertThat(captor.getValue().getActor()).isEqualTo("SYSTEM");
        assertThat(captor.getValue().getTraceId()).isNull();
    }

    @Test
    void repeated_commands_are_separate_rows() {
        AuditCommand command = AuditCommand.of(AuditAction.GATE_REFRESH, "5", "CR-5", "刷新检查")
            .build();

        audit.record(command);
        audit.record(command);

        verify(auditLogMapper, times(2)).insertAudit(any());
    }

    @Test
    void a_mapper_failure_does_not_change_the_business_result() {
        when(auditLogMapper.insertAudit(any()))
            .thenThrow(new IllegalStateException("simulated audit mapper failure"));

        assertThatCode(() -> notificationSettings.save(true,
            "https://qyapi.example.invalid/hook")).doesNotThrowAnyException();

        assertThat(jdbc.queryForObject(
            "SELECT wecom_enabled FROM notification_setting WHERE id = 1", Integer.class))
            .as("the business write committed even though the audit insert failed")
            .isEqualTo(1);
        assertThatCode(() -> audit.record(AuditCommand.of(AuditAction.PUBLISH, "PO-X", "PO-X",
            "发起发布").build())).doesNotThrowAnyException();
    }

    @Test
    void a_null_or_actionless_command_is_ignored() {
        assertThatCode(() -> {
            audit.record(null);
            audit.record(new AuditCommand(null, AuditObjectType.ORDER, "X", null, "s", null,
                null, null, null, null));
        }).doesNotThrowAnyException();

        verify(auditLogMapper, times(0)).insertAudit(any());
    }
}