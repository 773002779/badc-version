package com.sf.release.resource;

import com.sf.release.application.CodingJobQueryService;
import com.sf.release.application.UnitAppService;
import com.sf.release.application.UnitAppService.CreateCmd;
import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.domain.engine.BuildCmd;
import com.sf.release.domain.engine.CicdEngine;
import com.sf.release.domain.engine.DeployCmd;
import com.sf.release.domain.engine.EngineJob;
import com.sf.release.domain.engine.EngineResult;
import com.sf.release.domain.engine.JobKind;
import com.sf.release.domain.engine.TriggerResult;
import com.sf.release.infra.security.TestUserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The settings form offers real CODING jobs instead of asking for an id. This covers the
 * lookup between a unit, its system's CODING project and the engine, plus the fail-closed
 * scope and the "system has no project" case.
 *
 * <p>The engine is a {@code @Primary} stub: no network call happens and the project/kind the
 * service passed to it is asserted directly.
 */
@SpringBootTest
@ActiveProfiles("test")
class CodingJobQueryTest {

    private static final String OWNER = "01388007";

    @Autowired CodingJobQueryService jobQueryService;
    @Autowired UnitAppService unitService;
    @Autowired JdbcTemplate jdbc;
    @Autowired StubEngine engine;

    private long subsystemId;

    @BeforeEach
    void resetFixtures() {
        TestUserContext.allUnits("t6-admin");
        jdbc.update("DELETE FROM coding_conn");
        jdbc.update("DELETE FROM coding_task_binding");
        jdbc.update("DELETE FROM env_de");
        jdbc.update("DELETE FROM deploy_unit");
        jdbc.update("DELETE FROM subsystem");
        jdbc.update("DELETE FROM app_user WHERE id = ?", OWNER);
        jdbc.update("INSERT INTO app_user (id, name, status, created_at)"
            + " VALUES (?, '用户', 'ACTIVE', NOW(3))", OWNER);
        subsystemId = givenSubsystem("SCC-QUOTA", "quota-project");
        engine.reset();
    }

    @AfterEach
    void clearContext() {
        TestUserContext.clear();
    }

    @Test
    void build_binding_queries_the_build_namespace_of_the_system_project() {
        long unitId = unitService.create(new CreateCmd(subsystemId, "quota-api", OWNER));
        engine.buildJobs = List.of(new EngineJob("P-BUILD", "配额构建", "BUILD"));
        engine.deployJobs = List.of(new EngineJob("P-TEST", "测试部署", "DEPLOY"));

        List<CodingJobQueryService.CodingJobView> jobs = jobQueryService.list(unitId, "BUILD");

        assertThat(jobs).extracting(CodingJobQueryService.CodingJobView::taskId)
            .containsExactly("P-BUILD");
        assertThat(jobs.get(0).name()).isEqualTo("配额构建");
        assertThat(jobs.get(0).type()).isEqualTo("BUILD");
        assertThat(engine.lastProject()).isEqualTo("quota-project");
        assertThat(engine.lastKind()).isEqualTo(JobKind.BUILD);
        assertThat(engine.deployCalls).isZero();
    }

    @Test
    void every_deploy_kind_queries_the_deploy_namespace() {
        long unitId = unitService.create(new CreateCmd(subsystemId, "quota-api", OWNER));
        engine.deployJobs = List.of(new EngineJob("P-TEST", "测试部署", "DEPLOY"));

        for (String kind : List.of("TEST_DEPLOY", "PROD_DEPLOY", "DAILY_DEPLOY")) {
            assertThat(jobQueryService.list(unitId, kind))
                .extracting(CodingJobQueryService.CodingJobView::taskId)
                .containsExactly("P-TEST");
            assertThat(engine.lastKind()).isEqualTo(JobKind.DEPLOY);
            assertThat(engine.lastProject()).isEqualTo("quota-project");
        }
        assertThat(engine.buildCalls).isZero();
    }

    @Test
    void a_system_without_a_coding_project_is_reported_by_name() {
        long bareSubsystem = givenSubsystem("SCC-BARE", null);
        long unitId = unitService.create(new CreateCmd(bareSubsystem, "bare-api", OWNER));

        assertThatThrownBy(() -> jobQueryService.list(unitId, "BUILD"))
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.PARAM_INVALID)
            .satisfies(error -> assertThat(((BizError) error).getCode()).isEqualTo(40000));
        assertThat(engine.buildCalls).isZero();
    }

    @Test
    void unknown_task_kind_and_unscoped_unit_are_rejected_before_the_engine() {
        long unitId = unitService.create(new CreateCmd(subsystemId, "quota-api", OWNER));

        assertThatThrownBy(() -> jobQueryService.list(unitId, "DEPLOY"))
            .as("the binding vocabulary is TaskKind, not JobKind")
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.PARAM_INVALID);
        assertThat(engine.buildCalls).isZero();

        TestUserContext.scoped("t6-dev", -1L);
        assertThatThrownBy(() -> jobQueryService.list(unitId, "BUILD"))
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.FORBIDDEN);
        assertThat(engine.buildCalls).isZero();
    }

    @Test
    void a_system_without_a_project_inherits_the_default_project_of_the_connection() {
        givenGlobalConn("default-project");
        long bareSubsystem = givenSubsystem("SCC-BARE", null);
        long unitId = unitService.create(new CreateCmd(bareSubsystem, "bare-api", OWNER));
        engine.buildJobs = List.of(new EngineJob("D-BUILD", "默认构建", "BUILD"));

        List<CodingJobQueryService.CodingJobView> jobs = jobQueryService.list(unitId, "BUILD");

        assertThat(jobs).extracting(CodingJobQueryService.CodingJobView::taskId)
            .containsExactly("D-BUILD");
        assertThat(engine.lastProject())
            .as("an empty subsystem project falls back to the connection's default project")
            .isEqualTo("default-project");
    }

    @Test
    void a_system_project_wins_over_the_connection_default() {
        givenGlobalConn("default-project");
        long unitId = unitService.create(new CreateCmd(subsystemId, "quota-api", OWNER));

        jobQueryService.list(unitId, "BUILD");

        assertThat(engine.lastProject()).isEqualTo("quota-project");
    }

    private void givenGlobalConn(String project) {
        jdbc.update("INSERT INTO coding_conn (scope, subsystem_id, base_url, team, project,"
            + " credential_cipher, created_at, updated_at)"
            + " VALUES ('GLOBAL', 0, 'https://coding.example.com', 'team-a', ?, 'AAAA',"
            + " NOW(3), NOW(3))", project);
    }

    private long givenSubsystem(String name, String codingProject) {
        jdbc.update("INSERT INTO subsystem (name, owner_id, status, coding_project, created_at,"
            + " updated_at) VALUES (?, ?, 'ACTIVE', ?, NOW(3), NOW(3))",
            name, OWNER, codingProject);
        Long id = jdbc.queryForObject("SELECT id FROM subsystem WHERE name = ?", Long.class, name);
        return id == null ? 0L : id;
    }

    /** Records what the service asked for; answers from the fixed lists set by each test. */
    static final class StubEngine implements CicdEngine {

        private String project;
        private JobKind kind;
        int buildCalls;
        int deployCalls;
        List<EngineJob> buildJobs = List.of();
        List<EngineJob> deployJobs = List.of();

        @Override
        public String name() {
            return "JOB-QUERY-STUB";
        }

        @Override
        public List<EngineJob> listJobs(String project, JobKind kind) {
            this.project = project;
            this.kind = kind;
            if (kind == JobKind.BUILD) {
                buildCalls++;
                return buildJobs;
            }
            deployCalls++;
            return deployJobs;
        }

        String lastProject() {
            return project;
        }

        JobKind lastKind() {
            return kind;
        }

        void reset() {
            project = null;
            kind = null;
            buildCalls = 0;
            deployCalls = 0;
            buildJobs = List.of();
            deployJobs = List.of();
        }

        @Override
        public TriggerResult triggerBuild(BuildCmd cmd) {
            throw new UnsupportedOperationException("not exercised by this test");
        }

        @Override
        public TriggerResult triggerDeploy(DeployCmd cmd) {
            throw new UnsupportedOperationException("not exercised by this test");
        }

        @Override
        public EngineResult query(String externalId, JobKind kind) {
            throw new UnsupportedOperationException("not exercised by this test");
        }

        @Override
        public String logs(String externalId, JobKind kind, int offset, int limit) {
            throw new UnsupportedOperationException("not exercised by this test");
        }

        @Override
        public void cancel(String externalId) {
            throw new UnsupportedOperationException("not exercised by this test");
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubEngineConfig {

        @Bean
        @Primary
        StubEngine codingJobQueryStubEngine() {
            return new StubEngine();
        }
    }
}