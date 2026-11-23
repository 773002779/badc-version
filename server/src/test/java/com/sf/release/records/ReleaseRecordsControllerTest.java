package com.sf.release.records;

import com.sf.release.ReleaseApplication;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP seam of the global release-records page (GAP-10, P4-A Task 2): the path, the paging
 * defaults, the 40000 for an invalid page, the fail-closed data scope and the JSON row shape.
 *
 * <p>Package {@code com.sf.release.records}, not {@code ..web..}: the architecture rule forbids the
 * web package depending on infra, and this test needs the real schema and the authentication
 * provider to exercise the interceptor.
 */
@SpringBootTest(classes = {ReleaseApplication.class,
    ReleaseRecordsControllerTest.SqlCountingConfig.class})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReleaseRecordsControllerTest {

    private static final String USER_HEADER = "X-User-Id";
    private static final String ADMIN = "rec-ctrl-admin";
    private static final String SCOPED = "rec-ctrl-scoped";
    private static final String NO_SCOPE = "rec-ctrl-noscope";
    private static final String PATH = "/api/v1/releases";
    private static final Instant NOW = Instant.parse("2026-11-26T10:00:00Z");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired SqlStatementCounter sqlCounter;

    private ReleaseRecordsFixtures fixture;
    private long subsystemId;
    private long unitId;
    private long otherUnitId;
    private long taskId;

    @BeforeEach
    void setUp() {
        fixture = new ReleaseRecordsFixtures(jdbc);
        fixture.cleanDatabase();
        fixture.givenUser(ADMIN, "ADMIN");
        fixture.givenUser(SCOPED, "OPS");
        fixture.givenUser(NO_SCOPE, "DEV");

        subsystemId = fixture.givenSubsystem("RECCTRL-SYS");
        unitId = fixture.givenUnit(subsystemId, "recctrl-unit");
        otherUnitId = fixture.givenUnit(subsystemId, "recctrl-other");
        taskId = fixture.givenTask(subsystemId, "RECCTRL-TASK");
        fixture.givenUnitScope(SCOPED, unitId);
    }

    // ------------------------------------------------------------------ path and paging

    @Test
    void the_records_page_is_served_at_the_releases_path_with_the_paging_defaults()
            throws Exception {
        order(unitId, "CR-RECCTRL-1", "PO-RECCTRL-1", "DONE", NOW.minusSeconds(100), "ops-1");

        mvc.perform(get(PATH).header(USER_HEADER, ADMIN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.pageNo").value(1))
            .andExpect(jsonPath("$.data.pageSize").value(10))
            .andExpect(jsonPath("$.data.records.length()").value(1));
    }

    @Test
    void explicit_page_and_size_are_honoured() throws Exception {
        order(unitId, "CR-RECCTRL-1", "PO-RECCTRL-1", "DONE", NOW.minusSeconds(300), null);
        order(unitId, "CR-RECCTRL-2", "PO-RECCTRL-2", "DONE", NOW.minusSeconds(200), null);
        order(unitId, "CR-RECCTRL-3", "PO-RECCTRL-3", "DONE", NOW.minusSeconds(100), null);

        mvc.perform(get(PATH).param("page", "2").param("size", "2")
                .header(USER_HEADER, ADMIN))
            .andExpect(jsonPath("$.data.total").value(3))
            .andExpect(jsonPath("$.data.pageNo").value(2))
            .andExpect(jsonPath("$.data.pageSize").value(2))
            .andExpect(jsonPath("$.data.records.length()").value(1))
            .andExpect(jsonPath("$.data.records[0].publishNo").value("PO-RECCTRL-1"));
    }

    @Test
    void an_invalid_page_request_is_40000() throws Exception {
        mvc.perform(get(PATH).param("page", "0").header(USER_HEADER, ADMIN))
            .andExpect(jsonPath("$.code").value(40000))
            .andExpect(jsonPath("$.data.pageNo").isNotEmpty());

        mvc.perform(get(PATH).param("size", "101").header(USER_HEADER, ADMIN))
            .andExpect(jsonPath("$.code").value(40000))
            .andExpect(jsonPath("$.data.pageSize").isNotEmpty());
    }

    // ------------------------------------------------------------------ row shape

    @Test
    void a_row_sends_the_machine_values_the_labels_and_the_newest_pipeline() throws Exception {
        long withPipeline = order(unitId, "CR-RECCTRL-PL", "PO-RECCTRL-PL", "DONE",
            NOW.minusSeconds(200), "ops-9");
        fixture.givenInstance(withPipeline, "PL-RECCTRL-OLD", "RUNNING");
        fixture.givenInstance(withPipeline, "PL-RECCTRL-NEW", "DONE");
        order(unitId, "CR-RECCTRL-NOPL", "PO-RECCTRL-NOPL", "PENDING_APPROVAL",
            NOW.minusSeconds(100), null);

        MvcResult result = mvc.perform(get(PATH).header(USER_HEADER, ADMIN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.records.length()").value(2))
            .andExpect(jsonPath("$.data.records[0].publishNo").value("PO-RECCTRL-NOPL"))
            .andExpect(jsonPath("$.data.records[0].itemId").isNumber())
            .andExpect(jsonPath("$.data.records[0].crid").value("CR-RECCTRL-NOPL"))
            .andExpect(jsonPath("$.data.records[0].version").value("变更 CR-RECCTRL-NOPL"))
            .andExpect(jsonPath("$.data.records[0].subsystemId").value(subsystemId))
            .andExpect(jsonPath("$.data.records[0].subsystemName").value("RECCTRL-SYS"))
            .andExpect(jsonPath("$.data.records[0].unitId").value(unitId))
            .andExpect(jsonPath("$.data.records[0].unitName").value("recctrl-unit"))
            .andExpect(jsonPath("$.data.records[0].orderStatus").value("PENDING_APPROVAL"))
            .andExpect(jsonPath("$.data.records[0].orderStatusLabel").value("待审批"))
            .andExpect(jsonPath("$.data.records[0].itemStatus").value("PENDING_APPROVAL"))
            .andExpect(jsonPath("$.data.records[0].itemStatusLabel").value("待审批"))
            .andExpect(jsonPath("$.data.records[0].plannedReleaseTime")
                .value(containsString("2026-11-26T11:38")))
            .andExpect(jsonPath("$.data.records[0].createdAt")
                .value(containsString("2026-11-26T09:58")))
            .andExpect(jsonPath("$.data.records[0].pipelineNo").doesNotExist())
            .andExpect(jsonPath("$.data.records[0].pipelineStatus").doesNotExist())
            .andExpect(jsonPath("$.data.records[1].publishNo").value("PO-RECCTRL-PL"))
            .andExpect(jsonPath("$.data.records[1].orderStatusLabel").value("已完成"))
            .andExpect(jsonPath("$.data.records[1].pipelineNo").value("PL-RECCTRL-NEW"))
            .andExpect(jsonPath("$.data.records[1].pipelineStatus").value("DONE"))
            .andReturn();
        String body = result.getResponse().getContentAsString();
        // The two nullable fields stay present as null, and no internal id leaks beyond the listed.
        assertThat(body).contains("\"pipelineNo\":null").contains("\"pipelineStatus\":null")
            .contains("\"executedBy\":null");
        assertThat(body).doesNotContain("orderId").doesNotContain("instanceId")
            .doesNotContain("pipelineInstanceId");
    }

    // ------------------------------------------------------------------ scope

    @Test
    void the_page_follows_the_caller_scope_and_needs_authentication() throws Exception {
        order(unitId, "CR-RECCTRL-MINE", "PO-RECCTRL-MINE", "DONE", NOW.minusSeconds(200), null);
        order(otherUnitId, "CR-RECCTRL-OTHER", "PO-RECCTRL-OTHER", "DONE", NOW.minusSeconds(100),
            null);

        mvc.perform(get(PATH).header(USER_HEADER, SCOPED))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.records[0].publishNo").value("PO-RECCTRL-MINE"));

        mvc.perform(get(PATH).header(USER_HEADER, NO_SCOPE))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.total").value(0))
            .andExpect(jsonPath("$.data.records").isEmpty());

        mvc.perform(get(PATH))
            .andExpect(jsonPath("$.code").value(40009));
    }

    @Test
    void an_empty_page_is_a_normal_answer() throws Exception {
        mvc.perform(get(PATH).header(USER_HEADER, ADMIN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.total").value(0))
            .andExpect(jsonPath("$.data.pageNo").value(1))
            .andExpect(jsonPath("$.data.pageSize").value(10))
            .andExpect(jsonPath("$.data.records").isEmpty());
    }

    // ------------------------------------------------------------------ query budget

    @Test
    void the_page_costs_exactly_one_count_and_one_page_query() throws Exception {
        order(unitId, "CR-RECCTRL-1", "PO-RECCTRL-1", "DONE", NOW.minusSeconds(200), null);
        order(unitId, "CR-RECCTRL-2", "PO-RECCTRL-2", "DONE", NOW.minusSeconds(100), null);
        sqlCounter.reset();

        mvc.perform(get(PATH).header(USER_HEADER, ADMIN))
            .andExpect(jsonPath("$.code").value(0));

        assertThat(sqlCounter.count())
            .as("one count query plus one page query, no per-row follow-up")
            .isEqualTo(2);
    }

    // ------------------------------------------------------------------ fixtures

    private long order(long unit, String crid, String publishNo, String status, Instant createdAt,
                       String executedBy) {
        long itemId = fixture.givenItem(taskId, unit, crid, "变更 " + crid, status);
        return fixture.givenOrder(itemId, publishNo, status, createdAt,
            createdAt.plusSeconds(6000), executedBy);
    }

    // ------------------------------------------------------------------ SQL statement counting

    @TestConfiguration(proxyBeanMethods = false)
    static class SqlCountingConfig {

        @Bean
        SqlStatementCounter sqlStatementCounter() {
            return new SqlStatementCounter();
        }
    }

    @Intercepts({
        @Signature(type = Executor.class, method = "query",
            args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
        @Signature(type = Executor.class, method = "query",
            args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class,
                CacheKey.class, BoundSql.class})})
    static class SqlStatementCounter implements Interceptor {

        private final AtomicInteger statements = new AtomicInteger();

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            statements.incrementAndGet();
            return invocation.proceed();
        }

        void reset() {
            statements.set(0);
        }

        int count() {
            return statements.get();
        }
    }
}