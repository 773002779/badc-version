package com.sf.release.records;

import com.sf.release.application.ReleaseRecordsService;
import com.sf.release.application.ReleaseRecordsService.PageResult;
import com.sf.release.application.ReleaseRecordsService.ReleaseRecordView;
import com.sf.release.application.ReleaseRecordsService.ReleaseRecordsQuery;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The global release-records page (GAP-10, P4-A Task 2) against the real test schema: paging and
 * ordering, the keyword/status/system/unit filters, the fail-closed data scope and the
 * newest-pipeline join.
 *
 * <p>Package {@code com.sf.release.records}, not {@code ..web..}: the architecture rule forbids the
 * web package depending on infra, and this test installs the caller context and writes the schema
 * rows directly.
 */
@SpringBootTest
@ActiveProfiles("test")
class ReleaseRecordsTest {

    private static final String USER = "rec-viewer";
    private static final Instant NOW = Instant.parse("2026-11-26T10:00:00Z");

    @Autowired ReleaseRecordsService service;
    @Autowired JdbcTemplate jdbc;

    private ReleaseRecordsFixtures fixture;

    private long subsystemId;
    private long otherSubsystemId;
    private long unitA;
    private long unitB;
    private long unitOtherSystem;
    private long taskA;
    private long taskOtherSystem;

    @BeforeEach
    void setUp() {
        fixture = new ReleaseRecordsFixtures(jdbc);
        fixture.cleanDatabase();
        subsystemId = fixture.givenSubsystem("REC-SYS");
        otherSubsystemId = fixture.givenSubsystem("REC-OTHER-SYS");
        unitA = fixture.givenUnit(subsystemId, "rec-unit-a");
        unitB = fixture.givenUnit(subsystemId, "rec-unit-b");
        unitOtherSystem = fixture.givenUnit(otherSubsystemId, "rec-unit-c");
        taskA = fixture.givenTask(subsystemId, "REC-TASK-A");
        taskOtherSystem = fixture.givenTask(otherSubsystemId, "REC-TASK-B");
    }

    @AfterEach
    void clearUser() {
        TestUserContext.clear();
    }

    // ------------------------------------------------------------------ paging and ordering

    @Test
    void the_page_is_newest_first_and_carries_the_total_across_two_pages() {
        order(unitA, taskA, "CR-REC-OLD", "PO-REC-OLD", "DONE", NOW.minusSeconds(300));
        order(unitA, taskA, "CR-REC-MID", "PO-REC-MID", "DONE", NOW.minusSeconds(200));
        order(unitA, taskA, "CR-REC-NEW", "PO-REC-NEW", "EXECUTING", NOW.minusSeconds(100));
        TestUserContext.allUnits(USER);

        PageResult first = service.page(new ReleaseRecordsQuery(1, 2, null, null, null, null));

        assertThat(first.total()).isEqualTo(3);
        assertThat(first.pageNo()).isEqualTo(1);
        assertThat(first.pageSize()).isEqualTo(2);
        assertThat(first.records()).extracting(ReleaseRecordView::publishNo)
            .containsExactly("PO-REC-NEW", "PO-REC-MID");

        PageResult second = service.page(new ReleaseRecordsQuery(2, 2, null, null, null, null));
        assertThat(second.total()).isEqualTo(3);
        assertThat(second.records()).extracting(ReleaseRecordView::publishNo)
            .containsExactly("PO-REC-OLD");
    }

    @Test
    void a_row_carries_the_item_system_unit_statuses_and_the_chinese_labels() {
        long itemId = fixture.givenItem(taskA, unitA, "CR-REC-SHAPE", "2026.11 镜像更新",
            "ROLLED_BACK");
        fixture.givenOrder(itemId, "PO-REC-SHAPE", "DONE", NOW, NOW.plusSeconds(3600),
            "operator-1");
        TestUserContext.allUnits(USER);

        ReleaseRecordView row = service.page(query(null, null, null, null)).records().get(0);

        assertThat(row.publishNo()).isEqualTo("PO-REC-SHAPE");
        assertThat(row.itemId()).isEqualTo(itemId);
        assertThat(row.crid()).isEqualTo("CR-REC-SHAPE");
        assertThat(row.version()).isEqualTo("2026.11 镜像更新");
        assertThat(row.subsystemId()).isEqualTo(subsystemId);
        assertThat(row.subsystemName()).isEqualTo("REC-SYS");
        assertThat(row.unitId()).isEqualTo(unitA);
        assertThat(row.unitName()).isEqualTo("rec-unit-a");
        assertThat(row.orderStatus()).isEqualTo("DONE");
        assertThat(row.orderStatusLabel()).isEqualTo("已完成");
        assertThat(row.itemStatus()).isEqualTo("ROLLED_BACK");
        assertThat(row.itemStatusLabel()).isEqualTo("已回滚");
        assertThat(row.plannedReleaseTime()).isEqualTo(NOW.plusSeconds(3600));
        assertThat(row.createdAt()).isEqualTo(NOW);
        assertThat(row.executedBy()).isEqualTo("operator-1");
    }

    // ------------------------------------------------------------------ filters

    @Test
    void the_keyword_matches_the_publish_number_or_the_crid_and_is_trimmed() {
        order(unitA, taskA, "CR-REC-ONE", "PO-REC-ALPHA", "DONE", NOW.minusSeconds(200));
        order(unitA, taskA, "CR-REC-BETA", "PO-REC-TWO", "DONE", NOW.minusSeconds(100));
        TestUserContext.allUnits(USER);

        assertThat(service.page(query("ALPHA", null, null, null)).records())
            .extracting(ReleaseRecordView::publishNo).containsExactly("PO-REC-ALPHA");
        assertThat(service.page(query("BETA", null, null, null)).records())
            .extracting(ReleaseRecordView::crid).containsExactly("CR-REC-BETA");
        assertThat(service.page(query("  ALPHA  ", null, null, null)).records())
            .extracting(ReleaseRecordView::publishNo).containsExactly("PO-REC-ALPHA");
        assertThat(service.page(query("NO-SUCH-NEEDLE", null, null, null)).records()).isEmpty();
    }

    @Test
    void the_status_filter_matches_the_order_status_and_an_unknown_value_is_an_empty_page() {
        order(unitA, taskA, "CR-REC-DONE", "PO-REC-DONE", "DONE", NOW.minusSeconds(300));
        order(unitA, taskA, "CR-REC-FAILED", "PO-REC-FAILED", "FAILED", NOW.minusSeconds(200));
        order(unitA, taskA, "CR-REC-BACK", "PO-REC-BACK", "ROLLED_BACK", NOW.minusSeconds(100));
        TestUserContext.allUnits(USER);

        assertThat(service.page(query(null, "DONE", null, null)).total()).isEqualTo(1);
        assertThat(service.page(query(null, "DONE", null, null)).records())
            .extracting(ReleaseRecordView::publishNo).containsExactly("PO-REC-DONE");

        PageResult unknown = service.page(query(null, "NOT_A_STATUS", null, null));
        assertThat(unknown.total()).isZero();
        assertThat(unknown.records()).isEmpty();

        assertThat(service.page(query(null, "  ", null, null)).total())
            .as("a blank status is no filter, not an unknown value")
            .isEqualTo(3);
    }

    @Test
    void the_system_and_unit_filters_narrow_the_page() {
        order(unitA, taskA, "CR-REC-A1", "PO-REC-A1", "DONE", NOW.minusSeconds(300));
        order(unitB, taskA, "CR-REC-B1", "PO-REC-B1", "DONE", NOW.minusSeconds(200));
        order(unitOtherSystem, taskOtherSystem, "CR-REC-C1", "PO-REC-C1", "DONE",
            NOW.minusSeconds(100));
        TestUserContext.allUnits(USER);

        assertThat(service.page(query(null, null, otherSubsystemId, null)).records())
            .extracting(ReleaseRecordView::publishNo).containsExactly("PO-REC-C1");
        assertThat(service.page(query(null, null, null, unitB)).records())
            .extracting(ReleaseRecordView::publishNo).containsExactly("PO-REC-B1");
        assertThat(service.page(query(null, null, subsystemId, unitB)).records())
            .extracting(ReleaseRecordView::publishNo).containsExactly("PO-REC-B1");
    }

    // ------------------------------------------------------------------ data scope

    @Test
    void the_data_scope_is_fail_closed_for_all_units_scoped_and_empty_callers() {
        order(unitA, taskA, "CR-REC-A1", "PO-REC-A1", "DONE", NOW.minusSeconds(300));
        order(unitB, taskA, "CR-REC-B1", "PO-REC-B1", "DONE", NOW.minusSeconds(200));
        order(unitOtherSystem, taskOtherSystem, "CR-REC-C1", "PO-REC-C1", "DONE",
            NOW.minusSeconds(100));

        TestUserContext.allUnits(USER);
        assertThat(service.page(query(null, null, null, null)).total()).isEqualTo(3);

        TestUserContext.scoped(USER, unitA, unitOtherSystem);
        assertThat(service.page(query(null, null, null, null)).records())
            .extracting(ReleaseRecordView::publishNo)
            .containsExactly("PO-REC-C1", "PO-REC-A1");

        TestUserContext.noUnits(USER);
        PageResult none = service.page(query(null, null, null, null));
        assertThat(none.total()).isZero();
        assertThat(none.records()).isEmpty();
    }

    @Test
    void a_unit_filter_outside_the_scope_is_refused_and_a_system_filter_never_widens_it() {
        order(unitA, taskA, "CR-REC-A1", "PO-REC-A1", "DONE", NOW.minusSeconds(200));
        order(unitOtherSystem, taskOtherSystem, "CR-REC-C1", "PO-REC-C1", "DONE",
            NOW.minusSeconds(100));
        TestUserContext.scoped(USER, unitA);

        assertBizError(() -> service.page(query(null, null, null, unitOtherSystem)),
            BizError.FORBIDDEN, "unitId");

        PageResult widened = service.page(query(null, null, otherSubsystemId, null));
        assertThat(widened.total()).as("the scope is ANDed with every filter").isZero();
        assertThat(widened.records()).isEmpty();
    }

    // ------------------------------------------------------------------ newest instance

    @Test
    void the_joined_pipeline_is_the_newest_instance_of_the_order_and_may_be_null() {
        long withPipelines = order(unitA, taskA, "CR-REC-PL", "PO-REC-PL", "DONE",
            NOW.minusSeconds(200));
        fixture.givenInstance(withPipelines, "PL-REC-OLD", "RUNNING");
        fixture.givenInstance(withPipelines, "PL-REC-NEW", "DONE");
        long withoutPipeline = order(unitA, taskA, "CR-REC-NOPL", "PO-REC-NOPL", "SUBMITTED",
            NOW.minusSeconds(100));
        assertThat(withoutPipeline).isPositive();
        TestUserContext.allUnits(USER);

        List<ReleaseRecordView> rows = service.page(query(null, null, null, null)).records();

        assertThat(rows).as("one row per release order, not one per instance").hasSize(2);
        assertThat(rows.get(0).publishNo()).isEqualTo("PO-REC-NOPL");
        assertThat(rows.get(0).pipelineNo()).isNull();
        assertThat(rows.get(0).pipelineStatus()).isNull();
        assertThat(rows.get(1).publishNo()).isEqualTo("PO-REC-PL");
        assertThat(rows.get(1).pipelineNo()).isEqualTo("PL-REC-NEW");
        assertThat(rows.get(1).pipelineStatus()).isEqualTo("DONE");
    }

    // ------------------------------------------------------------------ validation

    @Test
    void invalid_paging_or_an_oversized_keyword_is_40000() {
        TestUserContext.allUnits(USER);

        assertBizError(() -> service.page(new ReleaseRecordsQuery(0, 10, null, null, null, null)),
            BizError.PARAM_INVALID, "pageNo");
        assertBizError(() -> service.page(new ReleaseRecordsQuery(1, 0, null, null, null, null)),
            BizError.PARAM_INVALID, "pageSize");
        assertBizError(() -> service.page(new ReleaseRecordsQuery(1, 101, null, null, null, null)),
            BizError.PARAM_INVALID, "pageSize");
        assertBizError(() -> service.page(query("x".repeat(65), null, null, null)),
            BizError.PARAM_INVALID, "keyword");
    }

    // ------------------------------------------------------------------ fixtures

    private long order(long unitId, long taskId, String crid, String publishNo, String status,
                       Instant createdAt) {
        long itemId = fixture.givenItem(taskId, unitId, crid, "变更 " + crid, status);
        return fixture.givenOrder(itemId, publishNo, status, createdAt,
            createdAt.plusSeconds(3600), null);
    }

    private static ReleaseRecordsQuery query(String keyword, String status, Long subsystemId,
                                             Long unitId) {
        return new ReleaseRecordsQuery(1, 10, keyword, status, subsystemId, unitId);
    }

    private static void assertBizError(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable call, BizError error,
            String field) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BizException.class, ex -> {
            assertThat(ex.getError()).isEqualTo(error);
            assertThat(ex.getPayload()).isInstanceOf(Map.class);
            assertThat((Map<String, Object>) ex.getPayload()).containsKey(field);
        });
    }
}