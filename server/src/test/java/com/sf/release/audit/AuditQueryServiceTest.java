package com.sf.release.audit;

import com.sf.release.application.audit.AuditQueryService;
import com.sf.release.application.audit.AuditQueryService.AuditQuery;
import com.sf.release.application.audit.AuditView;
import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.infra.security.TestUserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The audit read model against the real test schema (audit design sections 4 and 6): the filters,
 * paging, the fail-closed data scope, the explicit-unit convenience filter, the CSV content and the
 * 5000-row truncation.
 */
@SpringBootTest
@ActiveProfiles("test")
class AuditQueryServiceTest {

    private static final String USER = AuditFixtures.USER;
    private static final String OTHER = "audit-other";

    @Autowired AuditQueryService queries;
    @Autowired JdbcTemplate jdbc;

    private long subsystem;
    private long unitA;
    private long unitB;

    @BeforeEach
    void setUp() {
        AuditFixtures.clean(jdbc);
        subsystem = AuditFixtures.givenSubsystem(jdbc, "AUD-Q-SYS", USER);
        unitA = AuditFixtures.givenUnit(jdbc, subsystem, "aud-q-a", USER);
        long otherSubsystem = AuditFixtures.givenSubsystem(jdbc, "AUD-Q-OTHER", OTHER);
        unitB = AuditFixtures.givenUnit(jdbc, otherSubsystem, "aud-q-b", OTHER);
        TestUserContext.allUnits(USER);
    }

    @AfterEach
    void clearUser() {
        TestUserContext.clear();
    }

    // ------------------------------------------------------------------ filters and paging

    @Test
    void every_filter_narrows_the_same_count_and_page() {
        Instant now = Instant.now();
        givenAudit("PUBLISH", "ORDER", "PO-1", USER, "c", null, now.minusSeconds(30), unitA, null);
        givenAudit("APPROVE", "ORDER", "PO-1", OTHER, "c", null, now.minusSeconds(20), unitA, null);
        givenAudit("APPROVE", "ORDER", "PO-2", USER, "c", null, now.minusSeconds(10), unitA, null);

        assertThat(totals(new AuditQuery(null, null, null, null, null, null, null))).isEqualTo(3);
        assertThat(totals(new AuditQuery("ORDER", null, null, null, null, null, null)))
            .isEqualTo(3);
        assertThat(totals(new AuditQuery(null, "PO-1", null, null, null, null, null)))
            .isEqualTo(2);
        assertThat(totals(new AuditQuery(null, null, "APPROVE", null, null, null, null)))
            .isEqualTo(2);
        assertThat(totals(new AuditQuery(null, null, null, USER, null, null, null)))
            .isEqualTo(2);
        assertThat(totals(new AuditQuery(null, null, null, null, now.minusSeconds(15), null,
            null))).isEqualTo(1);
        assertThat(totals(new AuditQuery(null, null, null, null, null, now.minusSeconds(15),
            null))).isEqualTo(2);
        assertThat(totals(new AuditQuery(null, "PO-1", "APPROVE", OTHER, null, null, null)))
            .isEqualTo(1);
    }

    @Test
    void the_page_is_newest_first_and_reports_the_full_total() {
        Instant now = Instant.now();
        givenAudit("PUBLISH", "ORDER", "PO-1", USER, "c", null, now.minusSeconds(30), unitA, null);
        givenAudit("APPROVE", "ORDER", "PO-2", USER, "c", null, now.minusSeconds(20), unitA, null);
        givenAudit("RERUN", "ORDER", "PO-3", USER, "c", null, now.minusSeconds(10), unitA, null);

        AuditQueryService.Page page = queries.page(2, 2,
            new AuditQuery(null, null, null, null, null, null, null));

        assertThat(page.total()).isEqualTo(3);
        assertThat(page.pageNo()).isEqualTo(2);
        assertThat(page.pageSize()).isEqualTo(2);
        assertThat(page.records()).extracting(AuditView::objectId).containsExactly("PO-1");
        assertThat(page.records().get(0).actionLabel()).isEqualTo("发起发布");
    }

    @Test
    void an_invalid_page_action_or_object_type_is_40000() {
        assertThatThrownBy(() -> queries.page(0, 10, emptyQuery()))
            .isInstanceOfSatisfying(BizException.class,
                ex -> assertThat(ex.getError()).isEqualTo(BizError.PARAM_INVALID));
        assertThatThrownBy(() -> queries.page(1, 101, emptyQuery()))
            .isInstanceOfSatisfying(BizException.class,
                ex -> assertThat(ex.getError()).isEqualTo(BizError.PARAM_INVALID));
        assertThatThrownBy(() -> queries.page(1, 10,
            new AuditQuery(null, null, "NOT_AN_ACTION", null, null, null, null)))
            .isInstanceOfSatisfying(BizException.class, ex -> {
                assertThat(ex.getError()).isEqualTo(BizError.PARAM_INVALID);
                assertThat((Map<String, Object>) ex.getPayload()).containsKey("action");
            });
        assertThatThrownBy(() -> queries.page(1, 10,
            new AuditQuery("NOT_A_TYPE", null, null, null, null, null, null)))
            .isInstanceOfSatisfying(BizException.class, ex -> {
                assertThat(ex.getError()).isEqualTo(BizError.PARAM_INVALID);
                assertThat((Map<String, Object>) ex.getPayload()).containsKey("objectType");
            });
    }

    @Test
    void a_from_after_to_is_40000() {
        Instant now = Instant.now();

        assertThatThrownBy(() -> queries.page(1, 10,
            new AuditQuery(null, null, null, null, now, now.minusSeconds(60), null)))
            .isInstanceOfSatisfying(BizException.class, ex -> {
                assertThat(ex.getError()).isEqualTo(BizError.PARAM_INVALID);
                assertThat((Map<String, Object>) ex.getPayload()).containsKeys("from", "to");
            });
    }

    // ------------------------------------------------------------------ data scope

    @Test
    void a_scoped_caller_sees_the_unit_rows_and_the_system_rows_of_its_subsystem_only() {
        Instant now = Instant.now();
        givenAudit("PUBLISH", "ORDER", "PO-A", USER, "c", null, now.minusSeconds(30), unitA, null);
        givenAudit("FREEZE_CREATE", "FREEZE", "11", USER, "c", null, now.minusSeconds(20), null,
            subsystem);
        givenAudit("PUBLISH", "ORDER", "PO-B", OTHER, "c", null, now.minusSeconds(10), unitB,
            null);
        givenAudit("SETTING_UPDATE", "SETTING", "WINDOW:GLOBAL", USER, "c", null,
            now.minusSeconds(5), null, null);
        TestUserContext.scoped(USER, unitA);

        AuditQueryService.Page page = queries.page(1, 10, emptyQuery());

        assertThat(page.records()).extracting(AuditView::objectId)
            .containsExactly("11", "PO-A");
        assertThat(page.total()).isEqualTo(2);
    }

    @Test
    void an_explicit_unit_outside_the_scope_is_an_empty_result_not_a_permission_error() {
        Instant now = Instant.now();
        givenAudit("PUBLISH", "ORDER", "PO-B", OTHER, "c", null, now.minusSeconds(10), unitB,
            null);
        TestUserContext.scoped(USER, unitA);

        AuditQueryService.Page page = queries.page(1, 10,
            new AuditQuery(null, null, null, null, null, null, unitB));

        assertThat(page.total()).isZero();
        assertThat(page.records()).isEmpty();
    }

    @Test
    void a_caller_with_no_unit_scope_sees_nothing() {
        givenAudit("PUBLISH", "ORDER", "PO-A", USER, "c", null, Instant.now(), unitA, null);
        TestUserContext.noUnits(USER);

        assertThat(queries.page(1, 10, emptyQuery()).records()).isEmpty();
        String csv = csv(queries.exportCsv(emptyQuery()));
        assertThat(csv).as("only the BOM + header, never a row").doesNotContain("PO-A");
    }

    // ------------------------------------------------------------------ export

    @Test
    void the_export_has_a_bom_a_chinese_header_and_escaped_fields() {
        Instant now = Instant.now();
        jdbc.update("INSERT INTO audit_log (actor, action, object_type, object_id, object_label,"
                + " summary, reason, before_json, after_json, scope_unit_id, trace_id, created_at)"
                + " VALUES (?, 'PUBLISH', 'ORDER', ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            USER, "PO-1", "PO-1", "含逗号,引号\"与\n换行", "原因,\"引号\"",
            "{\"a\":1}", "{\"state\":\"BUILDING\"}", unitA, "trace-1",
            Timestamp.from(now));
        TestUserContext.allUnits(USER);

        byte[] bytes = queries.exportCsv(emptyQuery());

        assertThat(bytes).startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        String csv = new String(bytes, StandardCharsets.UTF_8);
        assertThat(csv).contains("\"编号\",\"动作码\",\"动作名称\",\"操作人\"")
            .contains("\"发起发布\"").contains("trace-1");
        assertThat(csv).as("an internal quote is doubled and the newline is kept")
            .contains("含逗号,引号\"\"与\n换行");
        assertThat(csv).as("the JSON columns are one CSV line each, with doubled quotes")
            .contains("{\"\"a\"\": 1}").contains("{\"\"state\"\": \"\"BUILDING\"\"}");
    }

    @Test
    void the_export_truncates_at_5000_rows_and_appends_a_note() {
        Instant now = Instant.now();
        List<Object[]> rows = new ArrayList<>(5001);
        for (int index = 0; index <= 5000; index++) {
            rows.add(new Object[] {USER, "PUBLISH", "ORDER", "OBJ-" + index, "OBJ-" + index,
                "summary " + index, null, Timestamp.from(now.plusMillis(index))});
        }
        jdbc.batchUpdate("INSERT INTO audit_log (actor, action, object_type, object_id,"
            + " object_label, summary, reason, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", rows);
        TestUserContext.allUnits(USER);

        String csv = csv(queries.exportCsv(emptyQuery()));

        assertThat(csv).contains("OBJ-5000").doesNotContain("\"OBJ-0\"");
        assertThat(csv).contains("已截断").contains("5000 行");
        assertThat(csv).as("header + 5000 rows + the truncation note")
            .contains("\"summary 5000\"");
    }

    // ------------------------------------------------------------------ fixtures

    private long givenAudit(String action, String objectType, String objectId, String actor,
                            String summary, String reason, Instant createdAt, Long unitId,
                            Long subsystemId) {
        jdbc.update("INSERT INTO audit_log (actor, action, object_type, object_id, object_label,"
                + " summary, reason, before_json, after_json, scope_unit_id,"
                + " scope_subsystem_id, trace_id, created_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, NULL, NULL, ?, ?, NULL, ?)",
            actor, action, objectType, objectId, objectId, summary, reason, unitId, subsystemId,
            Timestamp.from(createdAt));
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM audit_log", Long.class);
        return id == null ? 0L : id;
    }

    private long totals(AuditQuery query) {
        return queries.page(1, 10, query).total();
    }

    private static AuditQuery emptyQuery() {
        return new AuditQuery(null, null, null, null, null, null, null);
    }

    private static String csv(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}