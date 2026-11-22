package com.sf.release.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sf.release.application.CodingConnAppService;
import com.sf.release.application.CodingConnAppService.CodingConnView;
import com.sf.release.application.CodingConnAppService.CodingProbeView;
import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.domain.resource.CodingConn;
import com.sf.release.domain.resource.CodingScope;
import com.sf.release.infra.crypto.CredentialCipher;
import com.sf.release.infra.engine.CodingProbe;
import com.sf.release.infra.engine.ProbeResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.lang.reflect.Modifier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 6 CODING connection configuration against the real test schema.
 *
 * <p>Secrets are asserted three ways: the database column, the application log stream and
 * the returned view never carry the plaintext token. The connection test seam is a fake
 * {@link CodingProbe}: no HTTP call is made in this task.
 */
@SpringBootTest
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class CodingConnServiceTest {

    private static final String BASE_URL = "https://coding.example.com";
    private static final int PARAM_INVALID_CODE = 40000;
    private static final String GLOBAL_SCOPE = "GLOBAL";
    private static final String SUBSYSTEM_SCOPE = "SUBSYSTEM";

    @Autowired CodingConnAppService connService;
    @Autowired JdbcTemplate jdbc;
    @Autowired CredentialCipher cipher;
    @Autowired ObjectMapper objectMapper;
    @Autowired FakeCodingProbe fakeProbe;

    private long subsystemId;

    @BeforeEach
    void resetFixtures() {
        fakeProbe.reset();
        jdbc.update("DELETE FROM coding_conn");
        jdbc.update("DELETE FROM subsystem");
        subsystemId = givenSubsystem("SCC-QUOTA");
    }

    @Test
    void save_encrypts_and_get_returns_only_a_masked_hint() throws Exception {
        CodingConnView saved = connService.save(
            GLOBAL_SCOPE, null, BASE_URL, "team-a", "project-a", "tok-123");

        String stored = storedCipher();
        assertThat(stored).doesNotContain("tok-123");
        assertThat(cipher.decrypt(stored)).isEqualTo("tok-123");

        CodingConnView view = connService.get(GLOBAL_SCOPE, null);
        assertThat(view.id()).isEqualTo(saved.id());
        assertThat(view.scope()).isEqualTo("GLOBAL");
        assertThat(view.subsystemId()).isZero();
        assertThat(view.baseUrl()).isEqualTo(BASE_URL);
        assertThat(view.team()).isEqualTo("team-a");
        assertThat(view.project()).isEqualTo("project-a");
        assertThat(view.tokenHint()).isEqualTo("tok-***");
        assertThat(view.createdAt()).isNotNull();
        assertThat(view.updatedAt()).isNotNull();

        // The read model itself and its JSON serialisation never carry the token.
        assertThat(view.toString()).doesNotContain("tok-123");
        assertThat(objectMapper.writeValueAsString(view)).doesNotContain("tok-123");
    }

    @Test
    void token_never_appears_in_db_or_logs(CapturedOutput output) {
        String token = "SUPER-SECRET";
        connService.save(GLOBAL_SCOPE, null, BASE_URL, "team-a", "project-a", token);
        connService.get(GLOBAL_SCOPE, null);
        long id = connService.get(GLOBAL_SCOPE, null).id();
        connService.test(id);

        assertThat(storedCipher()).doesNotContain(token);
        assertThat(output.getAll())
            .as("the log capture must really include the service output, otherwise the "
                + "no-leak assertion would be vacuous")
            .contains("coding connection saved")
            .doesNotContain(token);
    }

    @Test
    void save_upserts_by_scope_and_keeps_a_single_row() {
        CodingConnView first = connService.save(
            GLOBAL_SCOPE, null, BASE_URL, "team-a", "project-a", "tok-123");
        CodingConnView second = connService.save(
            GLOBAL_SCOPE, 0L, BASE_URL + "/v2", "team-b", "project-b", "tok-456");

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(rowCount()).isEqualTo(1);
        assertThat(storedCipher()).doesNotContain("tok-123");
        assertThat(cipher.decrypt(storedCipher())).isEqualTo("tok-456");

        CodingConnView view = connService.get(GLOBAL_SCOPE, 0L);
        assertThat(view.id()).isEqualTo(first.id());
        assertThat(view.baseUrl()).isEqualTo(BASE_URL + "/v2");
        assertThat(view.team()).isEqualTo("team-b");
        assertThat(view.project()).isEqualTo("project-b");
        assertThat(view.tokenHint()).isEqualTo("tok-***");

        // The database unique key is the final guard for a concurrent first save.
        assertThatThrownBy(() -> jdbc.update(
            "INSERT INTO coding_conn (scope, subsystem_id, base_url, team, project,"
                + " credential_cipher, created_at, updated_at)"
                + " VALUES ('GLOBAL', 0, ?, 'team', 'project', 'AAAA', NOW(3), NOW(3))", BASE_URL))
            .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void global_scope_stores_zero_subsystem_id_and_subsystem_scope_validates() {
        connService.save(GLOBAL_SCOPE, null, BASE_URL, "team-a", "project-a", "tok-123");

        Integer globalId = jdbc.queryForObject(
            "SELECT subsystem_id FROM coding_conn WHERE scope = 'GLOBAL'", Integer.class);
        assertThat(globalId).as("GLOBAL rows use the NOT NULL DEFAULT 0 sentinel").isZero();
        assertThat(jdbc.queryForObject(
            "SELECT IS_NULLABLE FROM information_schema.columns"
                + " WHERE table_schema = DATABASE() AND table_name = 'coding_conn'"
                + " AND column_name = 'subsystem_id'", String.class)).isEqualTo("NO");

        CodingConnView subsystemView = connService.save(
            SUBSYSTEM_SCOPE, subsystemId, BASE_URL, "team-a", "project-a", "tok-789");
        assertThat(subsystemView.scope()).isEqualTo("SUBSYSTEM");
        assertThat(subsystemView.subsystemId()).isEqualTo(subsystemId);
        assertThat(connService.get(SUBSYSTEM_SCOPE, subsystemId).tokenHint()).isEqualTo("tok-***");
        assertThat(rowCount()).isEqualTo(2);

        assertThatThrownBy(() -> connService.save(
            SUBSYSTEM_SCOPE, null, BASE_URL, "team-a", "project-a", "tok-000"))
            .as("SUBSYSTEM scope needs a subsystem id")
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.PARAM_INVALID)
            .satisfies(error -> assertThat(((BizError) error).getCode())
                .isEqualTo(PARAM_INVALID_CODE));
        assertThatThrownBy(() -> connService.save(
            SUBSYSTEM_SCOPE, 999_999L, BASE_URL, "team-a", "project-a", "tok-000"))
            .as("an unknown subsystem cannot own a connection")
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.PARAM_INVALID);
        assertThat(rowCount()).isEqualTo(2);
    }

    @Test
    void save_rejects_blank_token_and_unknown_connection_is_param_invalid() {
        assertThatThrownBy(() -> connService.save(
            GLOBAL_SCOPE, null, BASE_URL, "team-a", "project-a", "   "))
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.PARAM_INVALID)
            .satisfies(error -> assertThat(((BizError) error).getCode())
                .isEqualTo(PARAM_INVALID_CODE));
        assertThat(rowCount()).isZero();

        assertThatThrownBy(() -> connService.get(GLOBAL_SCOPE, null))
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.PARAM_INVALID);
        assertThatThrownBy(() -> connService.test(999_999L))
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.PARAM_INVALID);
    }

    @Test
    void test_delegates_to_the_injected_probe_and_propagates_failed() {
        long id = connService.save(
            SUBSYSTEM_SCOPE, subsystemId, BASE_URL, "team-a", "project-a", "tok-123").id();

        fakeProbe.respondWith(ProbeResult.failed("凭证无效：401"));

        CodingProbeView result = connService.test(id);

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.ok()).isFalse();
        assertThat(result.message()).isEqualTo("凭证无效：401");
        assertThat(fakeProbe.lastConn()).isNotNull();
        assertThat(fakeProbe.lastConn().id()).isEqualTo(id);
        assertThat(fakeProbe.lastConn().scope()).isEqualTo(CodingScope.SUBSYSTEM);
        assertThat(fakeProbe.lastConn().subsystemId()).isEqualTo(subsystemId);
        assertThat(fakeProbe.lastConn().baseUrl()).isEqualTo(BASE_URL);
        assertThat(fakeProbe.lastToken()).as("the service decrypts before probing")
            .isEqualTo("tok-123");
    }

    @Test
    void save_without_a_token_keeps_the_stored_credential() {
        connService.save(GLOBAL_SCOPE, null, BASE_URL, "team-a", "project-a", "tok-123");
        String before = storedCipher();

        CodingConnView view = connService.save(
            GLOBAL_SCOPE, null, BASE_URL + "/v2", "team-b", "project-b", "   ");

        assertThat(storedCipher()).as("a blank token keeps the ciphertext byte for byte")
            .isEqualTo(before);
        assertThat(cipher.decrypt(storedCipher())).isEqualTo("tok-123");
        assertThat(view.baseUrl()).isEqualTo(BASE_URL + "/v2");
        assertThat(view.team()).isEqualTo("team-b");
        assertThat(view.project()).isEqualTo("project-b");
        assertThat(view.tokenHint()).isEqualTo("tok-***");
    }

    @Test
    void global_or_null_is_null_until_the_first_save() {
        assertThat(connService.globalOrNull()).isNull();

        connService.save(GLOBAL_SCOPE, null, BASE_URL, "team-a", "project-a", "tok-123");

        assertThat(connService.globalOrNull().baseUrl()).isEqualTo(BASE_URL);
    }

    @Test
    void test_records_the_probe_outcome_on_the_row() {
        long id = connService.save(
            GLOBAL_SCOPE, null, BASE_URL, "team-a", "project-a", "tok-123").id();
        fakeProbe.respondWith(ProbeResult.ok("项目可达；构建任务 3 个，部署任务 2 个", 3, 2));

        CodingProbeView result = connService.test(id);

        assertThat(result.ok()).isTrue();
        assertThat(result.buildJobs()).isEqualTo(3);
        assertThat(result.deployJobs()).isEqualTo(2);
        assertThat(result.at()).isNotNull();
        assertThat(result.persisted()).isTrue();

        CodingConnView view = connService.get(GLOBAL_SCOPE, null);
        assertThat(view.lastProbeOk()).isTrue();
        assertThat(view.lastProbeMessage()).isEqualTo("项目可达；构建任务 3 个，部署任务 2 个");
        assertThat(view.lastProbeAt()).isNotNull();
    }

    @Test
    void draft_probe_falls_back_to_stored_fields_and_persists_nothing() {
        connService.save(GLOBAL_SCOPE, null, BASE_URL, "team-a", "project-a", "tok-123");
        fakeProbe.respondWith(ProbeResult.ok("ok", 1, 0));

        CodingProbeView result = connService.testForm(null, "  ", "project-b", "  ");

        assertThat(result.ok()).isTrue();
        assertThat(result.persisted())
            .as("the draft differs from the saved project, so nothing is recorded")
            .isFalse();
        assertThat(fakeProbe.lastConn().baseUrl()).isEqualTo(BASE_URL);
        assertThat(fakeProbe.lastConn().team()).isEqualTo("team-a");
        assertThat(fakeProbe.lastConn().project()).isEqualTo("project-b");
        assertThat(fakeProbe.lastToken())
            .as("a blank draft token falls back to the stored credential")
            .isEqualTo("tok-123");
        assertThat(connService.get(GLOBAL_SCOPE, null).lastProbeAt())
            .as("a draft test never writes the row")
            .isNull();
        assertThat(rowCount()).isEqualTo(1);
    }

    @Test
    void testing_the_saved_values_records_the_outcome() {
        connService.save(GLOBAL_SCOPE, null, BASE_URL, "team-a", "project-a", "tok-123");
        fakeProbe.respondWith(ProbeResult.ok("ok", 2, 2));

        CodingProbeView result = connService.testForm(null, null, null, null);

        assertThat(result.persisted()).isTrue();
        assertThat(connService.get(GLOBAL_SCOPE, null).lastProbeOk()).isTrue();
        assertThat(connService.get(GLOBAL_SCOPE, null).lastProbeMessage()).isEqualTo("ok");
    }

    @Test
    void draft_probe_without_any_configuration_is_param_invalid() {
        assertThatThrownBy(() -> connService.testForm(null, null, null, null))
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.PARAM_INVALID);
        assertThat(fakeProbe.lastConn()).isNull();
    }

    @Test
    void invalid_scope_string_is_param_invalid() {
        assertThatThrownBy(() -> connService.save(
            "TENANT", null, BASE_URL, "team-a", "project-a", "tok-123"))
            .as("unknown scope vocabulary")
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.PARAM_INVALID)
            .satisfies(error -> assertThat(((BizError) error).getCode())
                .isEqualTo(PARAM_INVALID_CODE));
        assertThatThrownBy(() -> connService.save(
            "   ", null, BASE_URL, "team-a", "project-a", "tok-123"))
            .as("blank scope")
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.PARAM_INVALID);
        assertThatThrownBy(() -> connService.get("unknown", null))
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.PARAM_INVALID);
        assertThat(rowCount()).isZero();
    }

    @Test
    void string_typed_scope_round_trips_case_insensitively() {
        CodingConnView global = connService.save(
            "GLOBAL", null, BASE_URL, "team-a", "project-a", "tok-123");
        assertThat(connService.get("global", null).id()).isEqualTo(global.id());
        assertThat(connService.get(" Global ", null).tokenHint()).isEqualTo("tok-***");

        CodingConnView subsystem = connService.save(
            "subsystem", subsystemId, BASE_URL, "team-a", "project-a", "tok-456");
        assertThat(subsystem.scope()).isEqualTo("SUBSYSTEM");
        assertThat(connService.get("SUBSYSTEM", subsystemId).id()).isEqualTo(subsystem.id());
    }

    @Test
    void public_application_api_is_free_of_domain_and_infra_types() {
        String leaked = Stream.of(CodingConnAppService.class.getDeclaredMethods())
            .filter(method -> Modifier.isPublic(method.getModifiers()))
            .flatMap(method -> Stream.concat(
                Stream.of(method.getReturnType()), Stream.of(method.getParameterTypes())))
            .map(Class::getName)
            .filter(name -> name.startsWith("com.sf.release.domain.")
                || name.startsWith("com.sf.release.infra."))
            .collect(Collectors.joining(", "));

        assertThat(leaked)
            .as("the web layer must be able to call the application API without importing "
                + "domain or infra types")
            .isEmpty();
    }

    private String storedCipher() {
        return jdbc.queryForObject(
            "SELECT credential_cipher FROM coding_conn LIMIT 1", String.class);
    }

    private int rowCount() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM coding_conn", Integer.class);
        return count == null ? 0 : count;
    }

    private long givenSubsystem(String name) {
        jdbc.update("INSERT INTO subsystem (name, owner_id, status, created_at, updated_at)"
            + " VALUES (?, '01388007', 'ACTIVE', NOW(3), NOW(3))", name);
        Long id = jdbc.queryForObject("SELECT id FROM subsystem WHERE name = ?", Long.class, name);
        return id == null ? 0L : id;
    }

    /** Hand-written probe seam: captures its arguments and returns a canned result. */
    static final class FakeCodingProbe implements CodingProbe {

        private ProbeResult next = ProbeResult.failed("probe not stubbed");
        private CodingConn lastConn;
        private String lastToken;

        @Override
        public ProbeResult probe(CodingConn conn, String credential) {
            this.lastConn = conn;
            this.lastToken = credential;
            return next;
        }

        void respondWith(ProbeResult result) {
            this.next = result;
        }

        void reset() {
            this.next = ProbeResult.failed("probe not stubbed");
            this.lastConn = null;
            this.lastToken = null;
        }

        CodingConn lastConn() {
            return lastConn;
        }

        String lastToken() {
            return lastToken;
        }
    }

    @TestConfiguration
    static class FakeProbeConfiguration {

        @Bean
        @Primary
        FakeCodingProbe fakeCodingProbe() {
            return new FakeCodingProbe();
        }
    }
}
