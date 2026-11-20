package com.sf.release.hook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sf.release.application.ItemAppService;
import com.sf.release.application.ItemAppService.CreateItemCmd;
import com.sf.release.application.PublishAppService;
import com.sf.release.domain.pipeline.NodeHandler;
import com.sf.release.domain.pipeline.NodeHandlerRegistry;
import com.sf.release.domain.pipeline.NodeTaskStatus;
import com.sf.release.domain.pipeline.NodeType;
import com.sf.release.domain.pipeline.PipelineInstance;
import com.sf.release.domain.pipeline.PipelineOrchestrator;
import com.sf.release.domain.pipeline.port.PipelineRepository;
import com.sf.release.domain.pipeline.port.TransactionRunner;
import com.sf.release.infra.security.TestUserContext;
import com.sf.release.web.support.WebhookBodySizeFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract of the CODING webhook (tech design §6.3/§6.4): HMAC-SHA256 over the raw body,
 * {@code event_id} dedup before any side effect, status mapping through the configured engine
 * vocabulary, and advancement only through the idempotent orchestrator path.
 *
 * <p>The test lives outside the {@code web} package on purpose: ArchUnit forbids anything in
 * {@code ..web..} from depending on the domain/infra layers, and driving a real pipeline here
 * needs the orchestrator and the repository. The HTTP path itself is still exercised through
 * MockMvc, exactly like the controller tests.
 *
 * <p>The node handlers are fakes here: this test is about the webhook seam, not about the
 * BUILD/TEST_DEPLOY chains (Tasks 12/13 cover those). A completed {@code IMG_BUILD} is the
 * shortest observable "pipeline advanced" signal: the BUILD node finishes and TEST_DEPLOY is
 * entered exactly once.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class CodingWebhookTest {

    private static final String OWNER = "01388007";
    private static final LocalDate PLAN_DATE = LocalDate.of(2026, 11, 25);
    private static final String EXTERNAL_ID = "ext-webhook-1";
    private static final String DIGEST = "sha256:webhook";

    private static final String PATH = "/api/v1/hooks/coding";
    private static final String SIGNATURE_HEADER = "X-Signature";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired ItemAppService itemService;
    @Autowired PublishAppService publishService;
    @Autowired PipelineOrchestrator orchestrator;
    @Autowired NodeHandlerRegistry handlers;
    @Autowired PipelineRepository repository;
    @Autowired TransactionRunner transactionRunner;
    @Autowired WebhookBodySizeFilter bodySizeFilter;

    @Value("${release.webhook.secret:}")
    String secret;

    private final Map<NodeType, AtomicInteger> starts = new EnumMap<>(NodeType.class);
    private long instanceId;
    private long imageBuildTaskId;

    @BeforeEach
    void setUp() {
        TestUserContext.allUnits(OWNER);
        jdbc.update("DELETE FROM engine_event_dedup");
        jdbc.update("DELETE FROM node_task");
        jdbc.update("DELETE FROM pipeline_node");
        jdbc.update("DELETE FROM pipeline_instance");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM deploy_unit_lock");
        jdbc.update("DELETE FROM release_item");
        jdbc.update("DELETE FROM release_task_member");
        jdbc.update("DELETE FROM release_task");
        jdbc.update("DELETE FROM env_de");
        jdbc.update("DELETE FROM coding_task_binding");
        jdbc.update("DELETE FROM deploy_unit");
        jdbc.update("DELETE FROM subsystem");
        jdbc.update("DELETE FROM app_user WHERE id = ?", OWNER);

        givenUser(OWNER);
        long subsystemId = givenSubsystem("SCC-QUOTA");
        long releaseTaskId = givenTask(subsystemId, "202611-V6.10.12");
        long unitId = givenUnit(subsystemId, "quota-api");
        givenBinding(unitId, "BUILD", "P-BUILD-QUOTA");
        givenBinding(unitId, "TEST_DEPLOY", "P-TEST-QUOTA");
        givenBinding(unitId, "PROD_DEPLOY", "P-PROD-QUOTA");
        givenDe(unitId, "TEST", "quota-sit", 0);
        givenDe(unitId, "PROD", "quota-prod-a", 1);
        long itemId = itemService.create(releaseTaskId,
            new CreateItemCmd(unitId, "release/202611", "webhook 用例", PLAN_DATE));
        PublishAppService.PublishResult published = publishService.publish(itemId);
        instanceId = jdbc.queryForObject(
            "SELECT id FROM pipeline_instance WHERE pipeline_no = ?", Long.class,
            published.pipelineNo());

        handlers.clear();
        starts.clear();
        registerCountingHandlers();
        orchestrator.advance(instanceId);
        List<Long> buildTasks = taskIds(NodeType.BUILD);
        orchestrator.onTaskCompleted(buildTasks.get(0), NodeTaskStatus.SUCCESS, null, null);
        orchestrator.onTaskCompleted(buildTasks.get(1), NodeTaskStatus.SUCCESS, null, null);
        imageBuildTaskId = buildTasks.get(2);

        // The engine trigger already happened in a real run; the webhook only needs the
        // task to be WAITING_EXTERNAL with an external id (Task 12 does that).
        transactionRunner.inTransaction(() -> repository.markTaskWaitingExternal(
            imageBuildTaskId, EXTERNAL_ID, null, Instant.now().plus(Duration.ofHours(1)),
            Instant.now()));
    }

    @AfterEach
    void tearDown() {
        TestUserContext.clear();
        handlers.clear();
    }

    // ------------------------------------------------------------------ happy path

    @Test
    void a_signed_new_event_completes_the_task_and_advances_the_pipeline() throws Exception {
        byte[] body = webhook("evt-1", EXTERNAL_ID, "SUCCESS");

        mvc.perform(post(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .header(SIGNATURE_HEADER, sign(body))
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.outcome").value("ACCEPTED"));

        assertThat(dedupCount("evt-1")).isEqualTo(1);
        assertThat(payloadHash("evt-1"))
            .as("the raw payload is fingerprinted with SHA-256")
            .hasSize(64);
        assertThat(taskStatus(imageBuildTaskId)).isEqualTo("SUCCESS");
        assertThat(nodeStatus(NodeType.BUILD)).isEqualTo("SUCCESS");
        assertThat(instanceStatus()).isEqualTo("RUNNING");
        assertThat(currentNode()).isEqualTo("TEST_DEPLOY");
        assertThat(nodeStatus(NodeType.TEST_DEPLOY)).isEqualTo("RUNNING");
        assertThat(starts(NodeType.TEST_DEPLOY))
            .as("the orchestrator advanced to the next node exactly once")
            .isEqualTo(1);
        assertThat(instanceImageDigest()).isEqualTo(DIGEST);
    }

    @Test
    void a_duplicate_event_is_acknowledged_and_never_advances_twice() throws Exception {
        byte[] first = webhook("evt-1", EXTERNAL_ID, "SUCCESS");
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                .header(SIGNATURE_HEADER, sign(first)).content(first))
            .andExpect(status().isOk());

        byte[] second = webhook("evt-1", EXTERNAL_ID, "SUCCESS");
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                .header(SIGNATURE_HEADER, sign(second)).content(second))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.outcome").value("DUPLICATE"));

        assertThat(dedupCount("evt-1"))
            .as("one dedup row for the event id")
            .isEqualTo(1);
        assertThat(taskStatus(imageBuildTaskId)).isEqualTo("SUCCESS");
        assertThat(currentNode()).isEqualTo("TEST_DEPLOY");
        assertThat(starts(NodeType.TEST_DEPLOY))
            .as("the duplicate took the dedup path and never reached the orchestrator")
            .isEqualTo(1);
    }

    // ------------------------------------------------------------------ authentication

    @Test
    void a_tampered_signature_is_rejected_with_401_and_writes_nothing() throws Exception {
        byte[] body = webhook("evt-1", EXTERNAL_ID, "SUCCESS");

        mvc.perform(post(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .header(SIGNATURE_HEADER, sign("some-other-body".getBytes(StandardCharsets.UTF_8)))
                .content(body))
            .andExpect(status().isUnauthorized());

        assertThat(dedupCount("evt-1")).isZero();
        assertThat(taskStatus(imageBuildTaskId))
            .as("no signature means no DB write at all")
            .isEqualTo("WAITING_EXTERNAL");
        assertThat(nodeStatus(NodeType.BUILD)).isEqualTo("RUNNING");
        assertThat(starts(NodeType.TEST_DEPLOY)).isZero();
    }

    @Test
    void a_missing_signature_is_rejected_with_401_and_writes_nothing() throws Exception {
        byte[] body = webhook("evt-1", EXTERNAL_ID, "SUCCESS");

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isUnauthorized());

        assertThat(dedupCount("evt-1")).isZero();
        assertThat(taskStatus(imageBuildTaskId)).isEqualTo("WAITING_EXTERNAL");
        assertThat(starts(NodeType.TEST_DEPLOY)).isZero();
    }

    @Test
    void the_prefixed_sha256_signature_form_is_accepted() throws Exception {
        byte[] body = webhook("evt-1", EXTERNAL_ID, "SUCCESS");

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                .header(SIGNATURE_HEADER, "sha256=" + sign(body)).content(body))
            .andExpect(status().isOk());

        assertThat(taskStatus(imageBuildTaskId)).isEqualTo("SUCCESS");
    }

    // ------------------------------------------------------------------ status mapping

    @Test
    void an_unmapped_vendor_status_is_non_terminal_and_is_logged(CapturedOutput output)
        throws Exception {
        byte[] body = webhook("evt-unknown", EXTERNAL_ID, "MYSTERY");

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                .header(SIGNATURE_HEADER, sign(body)).content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));

        assertThat(dedupCount("evt-unknown")).isEqualTo(1);
        assertThat(taskStatus(imageBuildTaskId))
            .as("UNKNOWN is non-terminal: no completion, no TIMEOUT")
            .isEqualTo("WAITING_EXTERNAL");
        assertThat(nodeStatus(NodeType.BUILD)).isEqualTo("RUNNING");
        assertThat(starts(NodeType.TEST_DEPLOY)).isZero();
        assertThat(output.getAll())
            .contains("MYSTERY")
            .contains("UNKNOWN");
    }

    @Test
    void a_running_callback_after_success_does_not_regress_the_task() throws Exception {
        byte[] success = webhook("evt-1", EXTERNAL_ID, "SUCCESS");
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                .header(SIGNATURE_HEADER, sign(success)).content(success))
            .andExpect(status().isOk());

        byte[] late = webhook("evt-2", EXTERNAL_ID, "RUNNING");
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                .header(SIGNATURE_HEADER, sign(late)).content(late))
            .andExpect(status().isOk());

        assertThat(taskStatus(imageBuildTaskId)).isEqualTo("SUCCESS");
        assertThat(starts(NodeType.TEST_DEPLOY)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ body size limit

    @Test
    void an_oversized_body_is_rejected_with_413_before_authentication(CapturedOutput output)
        throws Exception {
        byte[] body = oversizedWebhook();
        assertThat(body.length).isGreaterThan(WebhookBodySizeFilter.MAX_BODY_BYTES);

        // The signature is valid on purpose: if the filter did not run before authentication,
        // the oversized callback would be accepted instead of rejected.
        mvc.perform(post(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .header(SIGNATURE_HEADER, sign(body))
                .content(body))
            .andExpect(status().is(413))
            .andExpect(jsonPath("$.code").value(40000));

        assertThat(dedupCount("evt-big")).isZero();
        assertThat(taskStatus(imageBuildTaskId)).isEqualTo("WAITING_EXTERNAL");
        assertThat(starts(NodeType.TEST_DEPLOY)).isZero();
        assertThat(output.getAll())
            .as("neither the controller nor the webhook service ran")
            .doesNotContain("coding webhook");
    }

    @Test
    void a_chunked_body_is_rejected_while_streaming() throws Exception {
        byte[] body = oversizedWebhook();
        MockHttpServletRequest delegate = new MockHttpServletRequest("POST",
            WebhookBodySizeFilter.WEBHOOK_PATH);
        delegate.setContent(body);
        // No declared Content-Length: the filter must stop the streaming read at the cap.
        HttpServletRequest request = new jakarta.servlet.http.HttpServletRequestWrapper(delegate) {
            @Override
            public int getContentLength() {
                return -1;
            }

            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        MockHttpServletResponse response = new MockHttpServletResponse();

        bodySizeFilter.doFilter(request, response, (req, res) ->
            ((HttpServletRequest) req).getInputStream().readAllBytes());

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("40000");
    }

    // ------------------------------------------------------------------ fixtures and helpers

    private byte[] webhook(String eventId, String externalId, String status) {
        try {
            JsonNode body = json.createObjectNode()
                .put("eventId", eventId)
                .put("externalId", externalId)
                .put("status", status)
                .put("digest", DIGEST);
            return json.writeValueAsBytes(body);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** A structurally valid but over-limit callback (the padding is ignored by the contract). */
    private byte[] oversizedWebhook() throws Exception {
        String padding = "a".repeat(WebhookBodySizeFilter.MAX_BODY_BYTES + 1024);
        return json.writeValueAsBytes(json.createObjectNode()
            .put("eventId", "evt-big")
            .put("externalId", EXTERNAL_ID)
            .put("status", "SUCCESS")
            .put("digest", DIGEST)
            .put("pad", padding));
    }

    private String sign(byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private int dedupCount(String eventId) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM engine_event_dedup WHERE event_id = ?", Integer.class, eventId);
        return count == null ? 0 : count;
    }

    private String payloadHash(String eventId) {
        return jdbc.queryForObject(
            "SELECT payload_hash FROM engine_event_dedup WHERE event_id = ?", String.class,
            eventId);
    }

    private void registerCountingHandlers() {
        for (NodeType type : NodeType.values()) {
            handlers.register(new CountingHandler(type));
        }
    }

    private int starts(NodeType type) {
        AtomicInteger count = starts.get(type);
        return count == null ? 0 : count.get();
    }

    private final class CountingHandler implements NodeHandler {

        private final NodeType type;

        private CountingHandler(NodeType type) {
            this.type = type;
        }

        @Override
        public NodeType nodeType() {
            return type;
        }

        @Override
        public void start(PipelineInstance instance) {
            starts.computeIfAbsent(type, ignored -> new AtomicInteger()).incrementAndGet();
        }
    }

    private List<Long> taskIds(NodeType nodeType) {
        return jdbc.queryForList(
            "SELECT id FROM node_task WHERE instance_id = ? AND node_type = ? ORDER BY id",
            Long.class, instanceId, nodeType.name());
    }

    private String taskStatus(long taskId) {
        return jdbc.queryForObject("SELECT status FROM node_task WHERE id = ?", String.class,
            taskId);
    }

    private String nodeStatus(NodeType nodeType) {
        return jdbc.queryForObject(
            "SELECT status FROM pipeline_node WHERE instance_id = ? AND node_type = ?",
            String.class, instanceId, nodeType.name());
    }

    private String instanceStatus() {
        return jdbc.queryForObject("SELECT status FROM pipeline_instance WHERE id = ?",
            String.class, instanceId);
    }

    private String currentNode() {
        return jdbc.queryForObject("SELECT current_node FROM pipeline_instance WHERE id = ?",
            String.class, instanceId);
    }

    private String instanceImageDigest() {
        return jdbc.queryForObject("SELECT image_digest FROM pipeline_instance WHERE id = ?",
            String.class, instanceId);
    }

    private void givenUser(String userId) {
        jdbc.update("INSERT INTO app_user (id, name, status, created_at) VALUES (?, ?, 'ACTIVE',"
            + " NOW(3))", userId, "用户" + userId);
    }

    private long givenSubsystem(String name) {
        jdbc.update("INSERT INTO subsystem (name, owner_id, status, created_at, updated_at)"
            + " VALUES (?, ?, 'ACTIVE', NOW(3), NOW(3))", name, OWNER);
        Long id = jdbc.queryForObject("SELECT id FROM subsystem WHERE name = ?", Long.class, name);
        return id == null ? 0L : id;
    }

    private long givenTask(long subsystem, String name) {
        jdbc.update("INSERT INTO release_task (subsystem_id, name, creator, purpose,"
                + " plan_finish_date, status, created_at, updated_at)"
                + " VALUES (?, ?, ?, '新功能', ?, 'ACTIVE', NOW(3), NOW(3))",
            subsystem, name, OWNER, PLAN_DATE);
        Long id = jdbc.queryForObject(
            "SELECT id FROM release_task WHERE subsystem_id = ? AND name = ?", Long.class,
            subsystem, name);
        return id == null ? 0L : id;
    }

    private long givenUnit(long subsystem, String name) {
        jdbc.update("INSERT INTO deploy_unit (subsystem_id, name, type, owner_id,"
                + " framework_template, branch_mode, status, module_path, created_at, updated_at)"
                + " VALUES (?, ?, 'UNIT', ?, 'spring-boot', 'STANDARD', 'ACTIVE',"
                + " 'outbound-os/quota', NOW(3), NOW(3))", subsystem, name, OWNER);
        Long id = jdbc.queryForObject(
            "SELECT id FROM deploy_unit WHERE subsystem_id = ? AND name = ?", Long.class,
            subsystem, name);
        return id == null ? 0L : id;
    }

    private void givenBinding(long unit, String kind, String codingTaskId) {
        jdbc.update("INSERT INTO coding_task_binding (unit_id, task_kind, task_id, enabled,"
                + " created_at, updated_at) VALUES (?, ?, ?, 1, NOW(3), NOW(3))",
            unit, kind, codingTaskId);
    }

    private void givenDe(long unit, String envKind, String name, int batchNo) {
        jdbc.update("INSERT INTO env_de (unit_id, env_kind, de_name, deploy_batch_no,"
                + " created_at, updated_at) VALUES (?, ?, ?, ?, NOW(3), NOW(3))",
            unit, envKind, name, batchNo);
    }
}