package com.sf.release.emergency;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP seam of {@code POST /api/v1/releases/{publishNo}/emergency-release} (P6-D design §3, §5 items
 * 1-3 and 6): the real interceptor, the real ADMIN-role rule (no permission point), the real schema
 * and the business error codes the page branches on.
 *
 * <p>Pinned here: the endpoint has no {@code @RequirePerm} and a caller holding
 * {@code control:approve} is still refused with 40009; an unauthenticated call is 40009; unknown
 * order is 40000, blank/oversized reason is 40000, an illegal state or a repeat call is 40001 with
 * {@code currentStatus}; success answers {@code {status, itemStatus, emergency:true}} and the release
 * detail carries the four emergency fields.
 *
 * <p>Package {@code com.sf.release.emergency}, not {@code ..web..} (design §5 item 7).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EmergencyReleaseControllerTest {

    private static final String USER_HEADER = "X-User-Id";
    private static final String PATH = "/api/v1/releases/{publishNo}/emergency-release";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private EmergencyFixtures fixtures;
    private EmergencyFixtures.Base base;

    @BeforeEach
    void setUp() {
        fixtures = new EmergencyFixtures(jdbc);
        fixtures.clean();
        base = fixtures.base();
    }

    @AfterEach
    void tearDown() {
        com.sf.release.infra.security.TestUserContext.clear();
    }

    // ------------------------------------------------------------------ permission

    @Test
    void an_admin_releases_and_the_answer_echoes_the_statuses() throws Exception {
        long[] id = pendingRelease("CR-EMH-ONE", "PO-EMH-ONE");

        mvc.perform(post(PATH, "PO-EMH-ONE")
                .header(USER_HEADER, EmergencyFixtures.ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(EmergencyFixtures.REASON)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.status").value("READY"))
            .andExpect(jsonPath("$.data.itemStatus").value("READY"))
            .andExpect(jsonPath("$.data.emergency").value(true));

        assertThat(jdbc.queryForObject("SELECT status FROM release_item WHERE id = ?",
            String.class, id[0])).isEqualTo("READY");
    }

    @Test
    void a_non_admin_holding_control_approve_is_still_refused() throws Exception {
        pendingRelease("CR-EMH-PERM", "PO-EMH-PERM");

        mvc.perform(post(PATH, "PO-EMH-PERM")
                .header(USER_HEADER, EmergencyFixtures.LEAD)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(EmergencyFixtures.REASON)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(40009));
    }

    @Test
    void an_unauthenticated_call_is_forbidden() throws Exception {
        pendingRelease("CR-EMH-AUTH", "PO-EMH-AUTH");

        mvc.perform(post(PATH, "PO-EMH-AUTH")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(EmergencyFixtures.REASON)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(40009));
    }

    // ------------------------------------------------------------------ parameter / state

    @Test
    void the_reason_is_required_and_bounded() throws Exception {
        pendingRelease("CR-EMH-REASON", "PO-EMH-REASON");

        mvc.perform(post(PATH, "PO-EMH-REASON")
                .header(USER_HEADER, EmergencyFixtures.ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"  \"}"))
            .andExpect(jsonPath("$.code").value(40000));
        mvc.perform(post(PATH, "PO-EMH-REASON")
                .header(USER_HEADER, EmergencyFixtures.ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("x".repeat(201))))
            .andExpect(jsonPath("$.code").value(40000));
        mvc.perform(post(PATH, "PO-EMH-REASON")
                .header(USER_HEADER, EmergencyFixtures.ADMIN))
            .andExpect(jsonPath("$.code").value(40000));

        assertThat(jdbc.queryForObject("SELECT status FROM release_order WHERE publish_no = ?",
            String.class, "PO-EMH-REASON")).isEqualTo("PENDING_APPROVAL");
    }

    @Test
    void an_unknown_order_is_40000_and_an_illegal_state_is_40001_with_the_current_status()
            throws Exception {
        mvc.perform(post(PATH, "PO-EMH-NOSUCH")
                .header(USER_HEADER, EmergencyFixtures.ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(EmergencyFixtures.REASON)))
            .andExpect(jsonPath("$.code").value(40000))
            .andExpect(jsonPath("$.message").value(containsString("PO-EMH-NOSUCH")));

        long item = fixtures.givenItem(base.taskId(), base.unitId(), "CR-EMH-READY",
            "release/em-ready", "READY", "CHECK_PASS");
        fixtures.givenOrder(item, "PO-EMH-READY", "READY");
        mvc.perform(post(PATH, "PO-EMH-READY")
                .header(USER_HEADER, EmergencyFixtures.ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(EmergencyFixtures.REASON)))
            .andExpect(jsonPath("$.code").value(40001))
            .andExpect(jsonPath("$.data.currentStatus").value("READY"));
    }

    @Test
    void a_repeat_call_is_refused() throws Exception {
        pendingRelease("CR-EMH-TWICE", "PO-EMH-TWICE");
        postOk("PO-EMH-TWICE");

        mvc.perform(post(PATH, "PO-EMH-TWICE")
                .header(USER_HEADER, EmergencyFixtures.ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(EmergencyFixtures.REASON)))
            .andExpect(jsonPath("$.code").value(40001))
            .andExpect(jsonPath("$.data.currentStatus").value("READY"));
    }

    // ------------------------------------------------------------------ read models

    @Test
    void the_release_detail_and_the_record_row_expose_the_emergency_fields() throws Exception {
        pendingRelease("CR-EMH-DETAIL", "PO-EMH-DETAIL");
        postOk("PO-EMH-DETAIL");

        mvc.perform(get("/api/v1/releases/{publishNo}", "PO-EMH-DETAIL")
                .header(USER_HEADER, EmergencyFixtures.ADMIN))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.emergencyRelease").value("true"))
            .andExpect(jsonPath("$.data.emergencyReason").value(EmergencyFixtures.REASON))
            .andExpect(jsonPath("$.data.emergencyBy").value(EmergencyFixtures.ADMIN))
            .andExpect(jsonPath("$.data.emergencyAt").isNotEmpty());

        mvc.perform(get("/api/v1/releases")
                .header(USER_HEADER, EmergencyFixtures.ADMIN)
                .param("page", "1").param("size", "10"))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.records[0].publishNo").value("PO-EMH-DETAIL"))
            .andExpect(jsonPath("$.data.records[0].emergencyRelease").value("true"))
            .andExpect(jsonPath("$.data.records[0].emergencyReason")
                .value(EmergencyFixtures.REASON))
            .andExpect(jsonPath("$.data.records[0].emergencyBy")
                .value(EmergencyFixtures.ADMIN));
    }

    @Test
    void the_controller_test_lives_outside_the_web_package() {
        assertThat(getClass().getPackageName()).isEqualTo("com.sf.release.emergency");
    }

    // ------------------------------------------------------------------ fixtures and helpers

    private void postOk(String publishNo) throws Exception {
        mvc.perform(post(PATH, publishNo)
                .header(USER_HEADER, EmergencyFixtures.ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(EmergencyFixtures.REASON)))
            .andExpect(jsonPath("$.code").value(0));
    }

    /** A 变更项 + 发布单 waiting for the two signatures; returns {@code {itemId, orderId}}. */
    private long[] pendingRelease(String crid, String publishNo) {
        long itemId = fixtures.givenItem(base.taskId(), base.unitId(), crid,
            "release/http-" + publishNo, "PENDING_APPROVAL", "CHECK_PASS");
        long orderId = fixtures.givenOrder(itemId, publishNo, "PENDING_APPROVAL");
        long snapshotId = fixtures.givenSnapshot(orderId, itemId, EmergencyFixtures.DEV,
            EmergencyFixtures.DIGEST);
        fixtures.givenApproval(orderId, snapshotId, 1, "OPS_LEAD", "PENDING");
        fixtures.givenApproval(orderId, snapshotId, 2, "DEPT_MANAGER", "PENDING");
        return new long[] {itemId, orderId};
    }

    private static String body(String reason) {
        return "{\"reason\":\"" + reason + "\"}";
    }
}