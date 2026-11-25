package com.sf.release.lib;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract of {@code GET /api/v1/libs/{unitId}/dependents} (P6-A design §4, test §5 item
 * 1): the module-path heuristic hits only the UNIT units of the same subsystem, the response
 * names the heuristic in {@code source}, a non-LIB unit is 40001 and an out-of-scope caller is
 * 40009.
 *
 * <p>The test lives outside the {@code web} package on purpose: the slice's ArchUnit rule forbids
 * tests from living in the HTTP layer package.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LibDependentsTest {

    private static final String USER_HEADER = "X-User-Id";
    private static final String ADMIN = "p6a-http-admin";
    private static final String SCOPED = "p6a-http-scoped";
    private static final String DEPENDENTS = "/api/v1/libs/{unitId}/dependents";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private long libUnitId;
    private long siblingDependentId;
    private long normalUnitId;
    private long otherSubsystemUnitId;

    @BeforeEach
    void setUp() {
        LibFixtures.reset(jdbc);
        LibFixtures.givenUser(jdbc, ADMIN);
        jdbc.update("INSERT INTO user_scope (user_id, scope_type, scope_id) VALUES (?, 'ALL', 0)",
            ADMIN);
        LibFixtures.givenUser(jdbc, SCOPED);

        long subsystemA = LibFixtures.givenSubsystem(jdbc, "SCC-P6A-DEP-A");
        long subsystemB = LibFixtures.givenSubsystem(jdbc, "SCC-P6A-DEP-B");

        libUnitId = LibFixtures.givenUnit(jdbc, subsystemA, "quota-sdk", "LIB", "libs/quota-sdk",
            "com.sf.quota", "quota-sdk");
        siblingDependentId = LibFixtures.givenUnit(jdbc, subsystemA, "quota-api", "UNIT",
            "quota-sdk", null, null);
        LibFixtures.givenUnit(jdbc, subsystemA, "quota-job", "UNIT", "quota-sdk", null, null);
        normalUnitId = LibFixtures.givenUnit(jdbc, subsystemA, "quota-web", "UNIT", "quota-web",
            null, null);
        // Same module path, different subsystem: not a dependent of this library.
        otherSubsystemUnitId = LibFixtures.givenUnit(jdbc, subsystemB, "other-api", "UNIT",
            "quota-sdk", null, null);
        // Another LIB with the same module path is not a deployment unit either.
        LibFixtures.givenUnit(jdbc, subsystemA, "other-sdk", "LIB", "quota-sdk",
            "com.sf.other", "other-sdk");
    }

    @Test
    void dependents_are_the_same_subsystem_units_whose_module_path_equals_the_artifact_id()
        throws Exception {
        mvc.perform(get(DEPENDENTS, libUnitId).header(USER_HEADER, ADMIN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.libUnitId").value(libUnitId))
            .andExpect(jsonPath("$.data.artifactId").value("quota-sdk"))
            .andExpect(jsonPath("$.data.source").value("MODULE_PATH"))
            .andExpect(jsonPath("$.data.dependents.length()").value(2))
            .andExpect(jsonPath("$.data.dependents[0].name").value("quota-api"))
            .andExpect(jsonPath("$.data.dependents[0].unitId").value(siblingDependentId))
            .andExpect(jsonPath("$.data.dependents[0].modulePath").value("quota-sdk"))
            .andExpect(jsonPath("$.data.dependents[1].name").value("quota-job"));
    }

    @Test
    void a_non_lib_unit_is_40001() throws Exception {
        mvc.perform(get(DEPENDENTS, normalUnitId).header(USER_HEADER, ADMIN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(40001))
            .andExpect(jsonPath("$.data.unitId").value(normalUnitId));
    }

    @Test
    void an_unknown_unit_is_40000() throws Exception {
        mvc.perform(get(DEPENDENTS, 999_999L).header(USER_HEADER, ADMIN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void a_caller_without_unit_scope_is_denied_40009() throws Exception {
        mvc.perform(get(DEPENDENTS, libUnitId).header(USER_HEADER, SCOPED))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(40009));

        // A scoped caller that only holds the sibling unit still cannot read the library it
        // depends on (the endpoint is scoped on the library itself).
        jdbc.update("INSERT INTO user_scope (user_id, scope_type, scope_id)"
            + " VALUES (?, 'UNIT', ?)", SCOPED, siblingDependentId);
        mvc.perform(get(DEPENDENTS, libUnitId).header(USER_HEADER, SCOPED))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(40009));
    }

    @Test
    void a_missing_login_is_rejected() throws Exception {
        mvc.perform(get(DEPENDENTS, libUnitId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(40009));
    }
}