package com.sf.release.part05;

import com.sf.release.infra.crypto.CredentialCipher;
import com.sf.release.infra.engine.CodingConnectionSource;
import com.sf.release.infra.engine.CodingEngine;
import com.sf.release.infra.engine.GlobalCodingConnectionProvider;
import com.sf.release.infra.security.TestUserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PART-05 design §1/§3: the engine connection resolution order {@code SUBSYSTEM → GLOBAL}
 * against the real schema.
 *
 * <p>Pinned here: a call carrying a unit whose subsystem has an override answers the override's
 * base URL; deleting the override falls back to the GLOBAL row; a unit of a subsystem without an
 * override and an unknown unit both answer GLOBAL; no GLOBAL row answers {@code null} (the
 * engine's ENGINE_UNAVAILABLE path). The provider is a {@link CodingConnectionSource}, so the
 * start-class engine calls resolve per unit while the plain-Supplier tests keep the old
 * behaviour.
 */
@SpringBootTest
@ActiveProfiles("test")
class CodingConnectionResolutionTest {

    private static final String OWNER = "p05-conn-owner";

    @Autowired GlobalCodingConnectionProvider provider;
    @Autowired CredentialCipher cipher;
    @Autowired JdbcTemplate jdbc;

    private long subsystemId;
    private long unitId;
    private long otherSubsystemUnitId;

    @BeforeEach
    void resetFixtures() {
        TestUserContext.allUnits(OWNER);
        jdbc.update("DELETE FROM coding_conn");
        jdbc.update("DELETE FROM deploy_unit WHERE name LIKE 'p05-%'");
        jdbc.update("DELETE FROM subsystem WHERE name LIKE 'SCC-P05-%'");
        jdbc.update("DELETE FROM app_user WHERE id = ?", OWNER);

        jdbc.update("INSERT INTO app_user (id, name, status, created_at) VALUES (?, ?, 'ACTIVE', NOW(3))",
            OWNER, "用户" + OWNER);
        jdbc.update("INSERT INTO subsystem (name, owner_id, status, created_at, updated_at)"
            + " VALUES ('SCC-P05-A', ?, 'ACTIVE', NOW(3), NOW(3))", OWNER);
        jdbc.update("INSERT INTO subsystem (name, owner_id, status, created_at, updated_at)"
            + " VALUES ('SCC-P05-B', ?, 'ACTIVE', NOW(3), NOW(3))", OWNER);
        subsystemId = jdbc.queryForObject(
            "SELECT id FROM subsystem WHERE name = 'SCC-P05-A'", Long.class);
        long otherSubsystem = jdbc.queryForObject(
            "SELECT id FROM subsystem WHERE name = 'SCC-P05-B'", Long.class);
        jdbc.update("INSERT INTO deploy_unit (subsystem_id, name, type, owner_id, framework_template,"
                + " branch_mode, status, created_at, updated_at)"
                + " VALUES (?, 'p05-api-a', 'UNIT', ?, 'spring-boot', 'STANDARD', 'ACTIVE',"
                + " NOW(3), NOW(3))", subsystemId, OWNER);
        unitId = jdbc.queryForObject(
            "SELECT id FROM deploy_unit WHERE name = 'p05-api-a'", Long.class);
        jdbc.update("INSERT INTO deploy_unit (subsystem_id, name, type, owner_id, framework_template,"
                + " branch_mode, status, created_at, updated_at)"
                + " VALUES (?, 'p05-api-b', 'UNIT', ?, 'spring-boot', 'STANDARD', 'ACTIVE',"
                + " NOW(3), NOW(3))", otherSubsystem, OWNER);
        otherSubsystemUnitId = jdbc.queryForObject(
            "SELECT id FROM deploy_unit WHERE name = 'p05-api-b'", Long.class);
    }

    @AfterEach
    void cleanup() {
        TestUserContext.clear();
        jdbc.update("DELETE FROM coding_conn");
        jdbc.update("DELETE FROM deploy_unit WHERE name LIKE 'p05-%'");
        jdbc.update("DELETE FROM subsystem WHERE name LIKE 'SCC-P05-%'");
        jdbc.update("DELETE FROM app_user WHERE id = ?", OWNER);
    }

    private void givenConn(String scope, long subsystemId, String baseUrl) {
        jdbc.update("INSERT INTO coding_conn (scope, subsystem_id, base_url, team, project,"
                + " credential_cipher, created_at, updated_at) VALUES (?, ?, ?, 't', 'p', ?,"
                + " NOW(3), NOW(3))",
            scope, scope.equals("GLOBAL") ? 0 : subsystemId, baseUrl,
            cipher.encrypt("tok-" + scope.toLowerCase() + "-" + subsystemId));
    }

    @Test
    void the_subsystem_override_wins_over_global_for_its_units() {
        givenConn("GLOBAL", 0, "https://global.coding.example");
        givenConn("SUBSYSTEM", subsystemId, "https://subsystem.coding.example");

        CodingEngine.Connection connection = provider.forUnit(unitId);

        assertThat(connection.baseUrl()).isEqualTo("https://subsystem.coding.example");
    }

    @Test
    void deleting_the_override_falls_back_to_the_global_row() {
        givenConn("GLOBAL", 0, "https://global.coding.example");
        givenConn("SUBSYSTEM", subsystemId, "https://subsystem.coding.example");
        jdbc.update("DELETE FROM coding_conn WHERE scope = 'SUBSYSTEM'");

        assertThat(provider.forUnit(unitId).baseUrl())
            .isEqualTo("https://global.coding.example");
    }

    @Test
    void a_subsystem_without_an_override_and_an_unknown_unit_answer_global() {
        givenConn("GLOBAL", 0, "https://global.coding.example");

        assertThat(provider.forUnit(otherSubsystemUnitId).baseUrl())
            .as("SCC-P05-B has no override row")
            .isEqualTo("https://global.coding.example");
        assertThat(provider.forUnit(999_999L).baseUrl())
            .as("an unknown unit cannot resolve a subsystem")
            .isEqualTo("https://global.coding.example");
        assertThat(provider.forUnit(0).baseUrl())
            .as("the plain supplier path answers GLOBAL")
            .isEqualTo("https://global.coding.example");
    }

    @Test
    void without_any_row_the_connection_is_null_and_the_engine_fails_unavailable() {
        assertThat(provider.forUnit(unitId)).isNull();
        assertThat(provider.get()).isNull();
        // The provider stays a Supplier so the engine wiring (and its ENGINE_UNAVAILABLE path)
        // is unchanged for the calls that only carry an externalId.
        assertThat(provider).isInstanceOf(CodingConnectionSource.class);
    }
}
