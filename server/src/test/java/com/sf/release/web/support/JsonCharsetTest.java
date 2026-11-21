package com.sf.release.web.support;

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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * JSON answers must say {@code charset=UTF-8}.
 *
 * <p>Without it the bytes are still UTF-8 but the client has to guess: PowerShell's
 * {@code Invoke-RestMethod} guesses ISO-8859-1 and shows Chinese as mojibake, which makes a
 * perfectly good payload look like a server-side encoding bug.
 *
 * <p>A probe controller is registered instead of reusing a business endpoint, so the assertion is
 * about the message converter rather than about which tables happen to be populated.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JsonCharsetTest {

    private static final String USER_ID = "t8-charset";
    private static final String USER_HEADER = "X-User-Id";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void givenAuthenticatedUser() {
        jdbc.update("DELETE FROM app_user WHERE id = ?", USER_ID);
        jdbc.update("INSERT INTO app_user (id, name, status, created_at)"
            + " VALUES (?, '用户', 'ACTIVE', NOW(3))", USER_ID);
    }

    @Test
    void json_response_declares_utf8_and_keeps_the_json_media_type() throws Exception {
        MvcResult result = mvc.perform(get("/test/json-charset").header(USER_HEADER, USER_ID))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", allOf(
                containsString("application/json"),
                containsString("charset=UTF-8"))))
            .andReturn();

        assertThat(result.getResponse().getContentAsString()).isEqualTo("{\"name\":\"配额\"}");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfig {

        @Bean
        ProbeController jsonCharsetProbeController() {
            return new ProbeController();
        }
    }

    @RestController
    static class ProbeController {

        @GetMapping("/test/json-charset")
        Map<String, String> probe() {
            return Map.of("name", "配额");
        }
    }
}