package com.sf.release.engine;

import com.sf.release.domain.resource.CodingConn;
import com.sf.release.infra.crypto.CredentialCipher;
import com.sf.release.infra.engine.CodingProbe;
import com.sf.release.infra.engine.ProbeResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP seam of the CODING 对接配置: an unconfigured platform answers {@code configured:false}
 * (not an error), the credential is only ever exposed as its hint, a blank token keeps the
 * stored one, and everything requires {@code setting:manage}.
 *
 * <p>The probe is a stub bean: this test asserts the HTTP contract, not the network. The real
 * HTTP probe is covered by {@code HttpCodingProbeTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CodingControllerTest {

    private static final String USER_HEADER = "X-User-Id";
    private static final String ADMIN = "t20-admin";
    private static final String DEV = "t20-dev";
    private static final String BASE_URL = "https://coding.example.com";
    private static final String TOKEN = "tok-123456";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired CredentialCipher cipher;
    @Autowired StubProbe probe;

    @BeforeEach
    void resetFixtures() {
        jdbc.update("DELETE FROM coding_conn");
        jdbc.update("DELETE FROM user_role WHERE user_id IN (?, ?)", ADMIN, DEV);
        jdbc.update("DELETE FROM app_user WHERE id IN (?, ?)", ADMIN, DEV);
        givenUser(ADMIN, "ADMIN");
        givenUser(DEV, "DEV");
        probe.reset();
    }

    @Test
    void an_unconfigured_platform_answers_configured_false() throws Exception {
        mvc.perform(get("/api/v1/coding/conn").header(USER_HEADER, ADMIN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.configured").value(false))
            .andExpect(jsonPath("$.data.baseUrl").doesNotExist());
    }

    @Test
    void save_then_read_round_trips_with_only_a_masked_credential() throws Exception {
        String withoutToken = saveConn("project-a");

        assertThat(withoutToken)
            .as("the response must never echo the credential")
            .doesNotContain(TOKEN);

        mvc.perform(get("/api/v1/coding/conn").header(USER_HEADER, ADMIN))
            .andExpect(jsonPath("$.data.configured").value(true))
            .andExpect(jsonPath("$.data.baseUrl").value(BASE_URL))
            .andExpect(jsonPath("$.data.team").value("team-a"))
            .andExpect(jsonPath("$.data.project").value("project-a"))
            .andExpect(jsonPath("$.data.tokenHint").value("tok-***"));

        assertThat(storedPlaintext()).isEqualTo(TOKEN);
    }

    @Test
    void saving_without_a_token_keeps_the_stored_credential() throws Exception {
        saveConn("project-a");
        String before = storedCipher();

        mvc.perform(put("/api/v1/coding/conn")
                .header(USER_HEADER, ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"baseUrl\":\"" + BASE_URL + "\",\"team\":\"team-a\","
                    + "\"project\":\"project-b\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.project").value("project-b"))
            .andExpect(jsonPath("$.data.tokenHint").value("tok-***"));

        assertThat(storedCipher()).isEqualTo(before);
        assertThat(storedPlaintext()).isEqualTo(TOKEN);
    }

    @Test
    void the_first_save_without_a_token_is_rejected() throws Exception {
        mvc.perform(put("/api/v1/coding/conn")
                .header(USER_HEADER, ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"baseUrl\":\"" + BASE_URL + "\",\"team\":\"team-a\","
                    + "\"project\":\"project-a\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(40000))
            .andExpect(jsonPath("$.data.token").isNotEmpty());
    }

    @Test
    void test_returns_the_probe_counts_and_a_draft_does_not_persist() throws Exception {
        probe.respondWith(ProbeResult.ok("项目可达；构建任务 3 个，部署任务 2 个", 3, 2));

        mvc.perform(post("/api/v1/coding/conn/test")
                .header(USER_HEADER, ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"baseUrl\":\"" + BASE_URL + "\",\"team\":\"team-a\","
                    + "\"project\":\"project-a\",\"token\":\"" + TOKEN + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.ok").value(true))
            .andExpect(jsonPath("$.data.buildJobs").value(3))
            .andExpect(jsonPath("$.data.deployJobs").value(2))
            .andExpect(jsonPath("$.data.persisted").value(false))
            .andExpect(jsonPath("$.data.at").isNotEmpty());

        assertThat(probe.lastConn().baseUrl()).isEqualTo(BASE_URL);
        assertThat(probe.lastConn().project()).isEqualTo("project-a");
        assertThat(probe.lastToken()).isEqualTo(TOKEN);
        assertThat(rowCount()).as("a draft probe writes nothing").isZero();
    }

    @Test
    void testing_the_saved_connection_records_the_outcome_for_the_next_page_load() throws Exception {
        saveConn("project-a");
        probe.respondWith(ProbeResult.ok("项目可达", 1, 0));

        mvc.perform(post("/api/v1/coding/conn/test").header(USER_HEADER, ADMIN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.persisted").value(true));

        mvc.perform(get("/api/v1/coding/conn").header(USER_HEADER, ADMIN))
            .andExpect(jsonPath("$.data.lastProbeOk").value(true))
            .andExpect(jsonPath("$.data.lastProbeMessage").value("项目可达"))
            .andExpect(jsonPath("$.data.lastProbeAt").isNotEmpty());
    }

    @Test
    void everything_requires_setting_manage() throws Exception {
        mvc.perform(get("/api/v1/coding/conn").header(USER_HEADER, DEV))
            .andExpect(jsonPath("$.code").value(40009));
        mvc.perform(put("/api/v1/coding/conn")
                .header(USER_HEADER, DEV)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(jsonPath("$.code").value(40009));
        mvc.perform(post("/api/v1/coding/conn/test").header(USER_HEADER, DEV))
            .andExpect(jsonPath("$.code").value(40009));
    }

    @Test
    void a_blank_base_url_returns_40000_with_field_detail() throws Exception {
        mvc.perform(put("/api/v1/coding/conn")
                .header(USER_HEADER, ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"baseUrl\":\"   \",\"team\":\"team-a\",\"project\":\"project-a\","
                    + "\"token\":\"" + TOKEN + "\"}"))
            .andExpect(jsonPath("$.code").value(40000))
            .andExpect(jsonPath("$.data.baseUrl").isNotEmpty());
    }

    @Test
    void a_full_successful_test_does_not_leak_the_token_into_the_body() throws Exception {
        probe.respondWith(ProbeResult.ok("项目可达", 1, 1));

        mvc.perform(post("/api/v1/coding/conn/test")
                .header(USER_HEADER, ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"baseUrl\":\"" + BASE_URL + "\",\"team\":\"team-a\","
                    + "\"project\":\"project-a\",\"token\":\"" + TOKEN + "\"}"))
            .andExpect(content().string(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString(TOKEN))));
    }

    private String saveConn(String project) throws Exception {
        return mvc.perform(put("/api/v1/coding/conn")
                .header(USER_HEADER, ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"baseUrl\":\"" + BASE_URL + "\",\"team\":\"team-a\","
                    + "\"project\":\"" + project + "\",\"token\":\"" + TOKEN + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.configured").value(true))
            .andExpect(jsonPath("$.data.tokenHint").value("tok-***"))
            .andReturn().getResponse().getContentAsString();
    }

    private String storedCipher() {
        return jdbc.queryForObject("SELECT credential_cipher FROM coding_conn LIMIT 1", String.class);
    }

    private String storedPlaintext() {
        return cipher.decrypt(storedCipher());
    }

    private int rowCount() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM coding_conn", Integer.class);
        return count == null ? 0 : count;
    }

    private void givenUser(String userId, String... roles) {
        jdbc.update("INSERT INTO app_user (id, name, status, created_at) VALUES (?, ?, 'ACTIVE', NOW(3))",
            userId, "用户" + userId);
        for (String role : roles) {
            jdbc.update("INSERT INTO user_role (user_id, role) VALUES (?, ?)", userId, role);
        }
    }

    /** Records what the controller handed to the probe; answers with a canned result. */
    static final class StubProbe implements CodingProbe {

        private ProbeResult next = ProbeResult.failed("not stubbed");
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
            this.next = ProbeResult.failed("not stubbed");
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

    @TestConfiguration(proxyBeanMethods = false)
    static class StubProbeConfiguration {

        @Bean
        @Primary
        StubProbe stubCodingProbe() {
            return new StubProbe();
        }
    }
}