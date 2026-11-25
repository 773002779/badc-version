package com.sf.release.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sf.release.common.BizError;
import com.sf.release.domain.engine.BuildCmd;
import com.sf.release.domain.engine.CicdEngine;
import com.sf.release.domain.engine.DeployCmd;
import com.sf.release.domain.engine.EngineException;
import com.sf.release.domain.engine.EngineJob;
import com.sf.release.domain.engine.EngineResult;
import com.sf.release.domain.engine.EngineStatus;
import com.sf.release.domain.engine.GateCheckCmd;
import com.sf.release.domain.engine.GateCheckOutcome;
import com.sf.release.domain.engine.GateCheckReport;
import com.sf.release.domain.engine.JobKind;
import com.sf.release.domain.engine.TriggerResult;
import com.sf.release.domain.gate.Severity;
import com.sf.release.domain.pipeline.CheckType;
import com.sf.release.infra.engine.CodingEngine;
import com.sf.release.infra.engine.CodingProperties;
import com.sf.release.infra.engine.EngineHttpClient;
import com.sf.release.infra.engine.EngineProperties;
import com.sf.release.infra.engine.EngineRegistry;
import com.sf.release.infra.engine.EndpointTemplateResolver;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Task 10: the CODING adapter is driven by {@code coding-endpoints.yml}, not by hard-coded
 * paths or field names. Every test loads the real bundled YAML through Spring's binder, then
 * points the resulting endpoint template at MockWebServer.
 *
 * <p>Covers the review amendments too: job-kind discriminated query/log endpoints, UNKNOWN
 * as a non-failure status, no timeout retry on POST triggers, HTTPS enforcement, and the
 * validated JSONPath subset.
 */
@ExtendWith(OutputCaptureExtension.class)
class CodingEngineTest {

    private static final String TOKEN = "tok-123";

    private MockWebServer server;
    private CodingProperties properties;
    private EndpointTemplateResolver resolver;
    private EngineProperties engineProperties;
    private String baseUrl;

    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        baseUrl = "http://localhost:" + server.getPort();
        properties = loadBundledProperties();
        resolver = new EndpointTemplateResolver(properties);
        engineProperties = new EngineProperties();
        engineProperties.setAllowInsecureHttp(true);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void trigger_build_uses_the_template_and_maps_the_external_id() throws Exception {
        server.enqueue(new MockResponse()
            .setBody("{\"Response\":{\"Data\":{\"JobId\":\"j-123\"}}}"));

        TriggerResult result = engine().triggerBuild(buildCmd());

        assertThat(result.externalId()).isEqualTo("j-123");
        assertThat(result.externalUrl()).isNull();

        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/open-api/build/start");
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer " + TOKEN);
        assertThat(request.getHeader("Content-Type")).contains("application/json");

        String body = request.getBody().readUtf8();
        assertThat(body).contains("\"CommitSha\":\"sha1\"");
        assertThat(body).contains("\"Branch\":\"feature/x\"");
        assertThat(body).contains("\"TaskId\":\"P-BUILD\"");
        assertThat(body).contains("\"UnitId\":88");
        assertThat(body).contains("\"PipelineNo\":\"PL-1\"");
        assertThat(body).contains("\"Attempt\":1");
    }

    @Test
    void trigger_build_maps_the_optional_response_commit() {
        server.enqueue(new MockResponse().setBody(
            "{\"Response\":{\"Data\":{\"JobId\":\"j-1\","
                + "\"CommitSha\":\"9f2a1c3d4e5f60718293a4b5c6d7e8f901234567\"}}}"));

        TriggerResult result = engine().triggerBuild(buildCmd());

        assertThat(result.externalId()).isEqualTo("j-1");
        assertThat(result.commitSha()).isEqualTo("9f2a1c3d4e5f60718293a4b5c6d7e8f901234567");
    }

    @Test
    void trigger_build_without_a_response_commit_leaves_it_null() {
        server.enqueue(new MockResponse()
            .setBody("{\"Response\":{\"Data\":{\"JobId\":\"j-2\"}}}"));

        TriggerResult result = engine().triggerBuild(buildCmd());

        assertThat(result.externalId()).isEqualTo("j-2");
        assertThat(result.commitSha())
            .as("no value is fabricated when the engine does not report one")
            .isNull();
    }

    @Test
    void build_trigger_without_a_known_commit_uses_the_branch_head_body() throws Exception {
        server.enqueue(new MockResponse()
            .setBody("{\"Response\":{\"Data\":{\"JobId\":\"j-3\"}}}"));
        BuildCmd withoutCommit = new BuildCmd(88L, "P-BUILD", "feature/x", null, "PL-1", 0);

        TriggerResult result = engine().triggerBuild(withoutCommit);

        assertThat(result.externalId()).isEqualTo("j-3");
        String body = server.takeRequest().getBody().readUtf8();
        assertThat(body)
            .as("with no known commit the engine resolves the branch head")
            .doesNotContain("CommitSha")
            .contains("\"Branch\":\"feature/x\"")
            .contains("\"TaskId\":\"P-BUILD\"");
    }

    @Test
    void trigger_deploy_posts_de_env_and_image_variables() throws Exception {
        server.enqueue(new MockResponse()
            .setBody("{\"Data\":{\"DeployId\":\"d-456\"}}"));

        TriggerResult result = engine().triggerDeploy(deployCmd());

        assertThat(result.externalId()).isEqualTo("d-456");

        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/open-api/deploy/start");
        String body = request.getBody().readUtf8();
        assertThat(body).contains("\"TaskId\":\"P-DEPLOY\"");
        assertThat(body).contains("\"EnvKind\":\"TEST\"");
        assertThat(body).contains("\"DeName\":\"quota-sit\"");
        assertThat(body).contains("\"ImageDigest\":\"sha256:ab\"");
        assertThat(body).contains("\"Attempt\":2");
    }

    @Test
    void build_query_maps_representative_status_words_and_extracts_the_digest() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"Data\":{\"Status\":\"QUEUEING\"}}"));
        server.enqueue(new MockResponse()
            .setBody("{\"Data\":{\"Status\":\"DEPLOYING\",\"Digest\":\"sha256:ab\"}}"));
        server.enqueue(new MockResponse().setBody("{\"Data\":{\"Status\":\"FINISHED\"}}"));

        EngineResult queued = engine().query("j-1", JobKind.BUILD);
        EngineResult running = engine().query("j-1", JobKind.BUILD);
        EngineResult success = engine().query("j-1", JobKind.BUILD);

        assertThat(queued.status()).isEqualTo(EngineStatus.QUEUED);
        assertThat(queued.imageDigest())
            .as("a missing digest is data for the caller, not an adapter failure")
            .isNull();
        assertThat(running.status()).isEqualTo(EngineStatus.RUNNING);
        assertThat(running.imageDigest()).isEqualTo("sha256:ab");
        assertThat(success.status()).isEqualTo(EngineStatus.SUCCESS);
        assertThat(success.imageDigest()).isNull();
        assertThat(server.takeRequest().getPath()).isEqualTo("/open-api/build/j-1");
        assertThat(server.takeRequest().getPath()).isEqualTo("/open-api/build/j-1");
        assertThat(server.takeRequest().getPath()).isEqualTo("/open-api/build/j-1");
    }

    @Test
    void list_jobs_maps_the_project_job_array_from_the_configured_expressions() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"Response\":{\"Data\":{\"Jobs\":["
            + "{\"Id\":\"P-BUILD\",\"Name\":\"配额构建\",\"Type\":\"BUILD\"},"
            + "{\"Id\":\"P-LINT\",\"Name\":\"静态检查\"}"
            + "]}}}"));
        server.enqueue(new MockResponse().setBody("{\"Response\":{\"Data\":{\"Jobs\":["
            + "{\"Id\":\"P-TEST-DEPLOY\",\"Name\":\"测试部署\",\"Type\":\"DEPLOY\"}"
            + "]}}}"));

        var buildJobs = engine().listJobs("quota-project", JobKind.BUILD);
        var deployJobs = engine().listJobs("quota-project", JobKind.DEPLOY);

        assertThat(buildJobs).extracting(EngineJob::taskId).containsExactly("P-BUILD", "P-LINT");
        assertThat(buildJobs.get(0).name()).isEqualTo("配额构建");
        assertThat(buildJobs.get(0).type()).isEqualTo("BUILD");
        assertThat(buildJobs.get(1).name()).isEqualTo("静态检查");
        assertThat(buildJobs.get(1).type())
            .as("a job without a type keeps null instead of a fabricated word")
            .isNull();
        assertThat(deployJobs).extracting(EngineJob::taskId).containsExactly("P-TEST-DEPLOY");

        RecordedRequest buildRequest = server.takeRequest();
        assertThat(buildRequest.getMethod()).isEqualTo("GET");
        assertThat(buildRequest.getPath())
            .isEqualTo("/open-api/project/quota-project/job/list?type=BUILD&page=1&pageSize=100");
        assertThat(server.takeRequest().getPath())
            .isEqualTo("/open-api/project/quota-project/job/list?type=DEPLOY&page=1&pageSize=100");
    }

    @Test
    void list_jobs_rejects_a_response_without_an_array() {
        server.enqueue(new MockResponse().setBody("{\"Response\":{\"Data\":{}}}"));

        Throwable thrown = catchThrowable(() -> engine().listJobs("quota-project", JobKind.BUILD));

        assertThat(thrown).isInstanceOf(EngineException.class);
        assertThat(thrown.getMessage()).contains("不是数组");
    }

    @Test
    void deploy_query_hits_the_deploy_endpoint_not_the_build_one() throws Exception {
        server.enqueue(new MockResponse()
            .setBody("{\"Data\":{\"Status\":\"RUNNING\",\"Digest\":\"sha256:de\"}}"));

        EngineResult result = engine().query("d-1", JobKind.DEPLOY);

        assertThat(result.status()).isEqualTo(EngineStatus.RUNNING);
        assertThat(result.imageDigest()).isEqualTo("sha256:de");
        assertThat(server.takeRequest().getPath()).isEqualTo("/open-api/deploy/d-1");
    }

    @Test
    void query_without_a_job_kind_fails_loudly_and_makes_no_request() {
        Throwable thrown = catchThrowable(() -> engine().query("j-1", null));

        assertThat(thrown).isInstanceOf(EngineException.class);
        assertThat(thrown.getMessage()).contains("JobKind");
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void status_mapping_accepts_the_hash_wrapped_key_form_from_the_brief() {
        properties.getStatusMapping().clear();
        properties.getStatusMapping().put("#{QUEUED}", List.of("WAITING"));
        server.enqueue(new MockResponse().setBody("{\"Data\":{\"Status\":\"WAITING\"}}"));

        assertThat(engine().query("j-1", JobKind.BUILD).status()).isEqualTo(EngineStatus.QUEUED);
    }

    @Test
    void unmapped_status_word_becomes_unknown_instead_of_engine_unavailable(
        CapturedOutput output) {
        server.enqueue(new MockResponse().setBody("{\"Data\":{\"Status\":\"MYSTERY\"}}"));

        EngineResult result = engine().query("j-1", JobKind.BUILD);

        assertThat(result.status())
            .as("an unmapped vendor word must not trigger the failure-to-TIMEOUT path")
            .isEqualTo(EngineStatus.UNKNOWN);
        assertThat(result.message()).isEqualTo("MYSTERY");
        assertThat(output.getAll())
            .as("the raw word must be visible in the log so the mapping can be extended")
            .contains("WARN")
            .contains("MYSTERY")
            .contains("UNKNOWN");
    }

    @Test
    void retries_back_off_one_second_then_three_seconds() {
        List<Duration> waits = new ArrayList<>();
        EngineHttpClient recording = new EngineHttpClient(Duration.ofSeconds(5),
            Duration.ofSeconds(5), 3, List.of(Duration.ofSeconds(1), Duration.ofSeconds(3)),
            waits::add);
        for (int i = 0; i < 3; i++) {
            server.enqueue(new MockResponse().setResponseCode(503));
        }

        catchThrowable(() -> engine(recording).triggerBuild(buildCmd()));

        assertThat(waits)
            .as("three attempts use the 1s/3s steps of the documented 1s/3s/9s policy")
            .containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(3));
        assertThat(server.getRequestCount()).isEqualTo(3);
    }

    @Test
    void engine_5xx_is_retried_three_times_then_fails() {
        for (int i = 0; i < 3; i++) {
            server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));
        }

        Throwable thrown = catchThrowable(() -> engine().triggerBuild(buildCmd()));

        assertThat(thrown).isInstanceOf(EngineException.class);
        assertThat(((EngineException) thrown).getError()).isEqualTo(BizError.ENGINE_UNAVAILABLE);
        assertThat(thrown.getMessage()).contains("500");
        assertThat(thrown.getMessage()).doesNotContain(TOKEN);
        assertThat(server.getRequestCount()).isEqualTo(3);
    }

    @Test
    void engine_4xx_is_not_retried() {
        server.enqueue(new MockResponse().setResponseCode(400).setBody("bad request"));

        Throwable thrown = catchThrowable(() -> engine().triggerBuild(buildCmd()));

        assertThat(thrown).isInstanceOf(EngineException.class);
        assertThat(((EngineException) thrown).getError()).isEqualTo(BizError.ENGINE_UNAVAILABLE);
        assertThat(thrown.getMessage()).contains("400");
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void get_read_timeout_is_retried_three_times_then_engine_timeout() {
        server.setDispatcher(delayedBody(1, java.util.concurrent.TimeUnit.SECONDS));

        Throwable thrown = catchThrowable(() -> engine(impatient()).query("j-1", JobKind.BUILD));

        assertThat(thrown).isInstanceOf(EngineException.class);
        assertThat(((EngineException) thrown).getError()).isEqualTo(BizError.ENGINE_TIMEOUT);
        assertThat(thrown.getMessage()).contains("尝试 3 次");
        assertThat(server.getRequestCount()).isEqualTo(3);
    }

    @Test
    void post_trigger_timeout_is_not_retried_to_avoid_duplicate_jobs() {
        server.setDispatcher(delayedBody(1, java.util.concurrent.TimeUnit.SECONDS));

        Throwable thrown = catchThrowable(() -> engine(impatient()).triggerBuild(buildCmd()));

        assertThat(thrown).isInstanceOf(EngineException.class);
        assertThat(((EngineException) thrown).getError()).isEqualTo(BizError.ENGINE_TIMEOUT);
        assertThat(thrown.getMessage()).contains("尝试 1 次");
        assertThat(server.getRequestCount())
            .as("a timed-out POST may already have created the job")
            .isEqualTo(1);
    }

    @Test
    void unresolved_placeholder_fails_loudly_and_names_the_placeholder() {
        BuildCmd withoutBranch = new BuildCmd(88L, "P-BUILD", null, "sha1", "PL-1", 1);

        Throwable thrown = catchThrowable(() -> engine().triggerBuild(withoutBranch));

        assertThat(thrown).isInstanceOf(EngineException.class);
        assertThat(((EngineException) thrown).getError()).isEqualTo(BizError.ENGINE_UNAVAILABLE);
        assertThat(thrown.getMessage())
            .contains("{branch}")
            .contains("build.start");
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void changing_the_endpoint_in_config_changes_the_request_path() throws Exception {
        resolver.override("build.start.url", "/v2/build");
        server.enqueue(new MockResponse()
            .setBody("{\"Response\":{\"Data\":{\"JobId\":\"j-999\"}}}"));

        TriggerResult result = engine().triggerBuild(buildCmd());

        assertThat(result.externalId()).isEqualTo("j-999");
        assertThat(server.takeRequest().getPath()).isEqualTo("/v2/build");
    }

    @Test
    void cancel_calls_the_templated_cancel_url() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

        engine().cancel("j-77");

        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/open-api/job/j-77/cancel");
    }

    @Test
    void build_logs_returns_the_response_body_and_passes_offset_and_limit() throws Exception {
        server.enqueue(new MockResponse().setBody("line-1\nline-2"));

        String logs = engine().logs("j-9", JobKind.BUILD, 0, 100);

        assertThat(logs).isEqualTo("line-1\nline-2");
        assertThat(server.takeRequest().getPath())
            .isEqualTo("/open-api/build/j-9/logs?offset=0&limit=100");
    }

    @Test
    void deploy_logs_uses_the_deploy_log_endpoint() throws Exception {
        server.enqueue(new MockResponse().setBody("deploy-line"));

        String logs = engine().logs("d-9", JobKind.DEPLOY, 5, 50);

        assertThat(logs).isEqualTo("deploy-line");
        assertThat(server.takeRequest().getPath())
            .isEqualTo("/open-api/deploy/d-9/logs?offset=5&limit=50");
    }

    @Test
    void token_is_a_bearer_header_but_never_reaches_logs_or_exception_messages(
        CapturedOutput output) throws Exception {
        server.enqueue(new MockResponse()
            .setBody("{\"Response\":{\"Data\":{\"JobId\":\"j-123\"}}}"));
        engine().triggerBuild(buildCmd());
        assertThat(server.takeRequest().getHeader("Authorization"))
            .isEqualTo("Bearer " + TOKEN);

        server.enqueue(new MockResponse()
            .setResponseCode(403)
            .setBody("{\"message\":\"credential " + TOKEN + " rejected\"}"));
        Throwable thrown = catchThrowable(() -> engine().triggerBuild(buildCmd()));

        assertThat(thrown).isInstanceOf(EngineException.class);
        assertThat(thrown.getMessage())
            .doesNotContain(TOKEN)
            .contains("***");
        assertThat(output.getAll())
            .as("the capture must include the engine request log, otherwise the assertion is vacuous")
            .contains("coding engine")
            .doesNotContain(TOKEN);
    }

    @Test
    void registry_selects_the_coding_engine_by_name_and_fails_loudly_otherwise() {
        CicdEngine coding = engine();
        EngineRegistry registry = new EngineRegistry(List.of(coding));

        assertThat(registry.find("coding")).containsSame(coding);
        assertThat(registry.names()).containsExactly("CODING");

        Throwable thrown = catchThrowable(() -> registry.require("SELF_HOSTED"));
        assertThat(thrown).isInstanceOf(EngineException.class);
        assertThat(((EngineException) thrown).getError()).isEqualTo(BizError.ENGINE_UNAVAILABLE);
        assertThat(thrown.getMessage()).contains("SELF_HOSTED");
    }

    @Test
    void bundled_yaml_declares_all_endpoints_and_the_status_mapping() {
        assertThat(properties.getEndpoints())
            .containsKeys("build.start", "build.query", "build.logs",
                "deploy.start", "deploy.query", "deploy.logs", "cancel",
                "git.head", "git.changes", "job.list",
                "check.start", "check.env.start", "check.query",
                "publish.start", "publish.query")
            .hasSize(15);
        assertThat(properties.getEndpoints().get("build.start").getMethod()).isEqualTo("POST");
        assertThat(properties.getEndpoints().get("build.start").getUrl())
            .isEqualTo("/open-api/build/start");
        assertThat(properties.getEndpoints().get("build.start").getResponseId())
            .isEqualTo("$.Response.Data.JobId");
        assertThat(properties.getEndpoints().get("build.start").getResponseCommit())
            .isEqualTo("$.Response.Data.CommitSha");
        assertThat(properties.getEndpoints().get("build.start").getBodyWithoutCommit())
            .isNotNull()
            .doesNotContain("CommitSha")
            .contains("\"Branch\":\"{branch}\"");
        assertThat(properties.getEndpoints().get("build.query").getUrl())
            .isEqualTo("/open-api/build/{externalId}");
        assertThat(properties.getEndpoints().get("deploy.query").getUrl())
            .isEqualTo("/open-api/deploy/{externalId}");
        assertThat(properties.getEndpoints().get("deploy.logs").getUrl())
            .isEqualTo("/open-api/deploy/{externalId}/logs?offset={offset}&limit={limit}");
        assertThat(properties.getEndpoints().get("git.head").getResponseCommit())
            .as("the rerun planner reads the branch head from this expression")
            .isEqualTo("$.Response.Data.CommitSha");
        assertThat(properties.getEndpoints().get("git.changes").getResponseValue())
            .as("the shared-repo filter reads the changed flag from this expression")
            .isEqualTo("$.Response.Data.Changed");
        assertThat(properties.getStatusMapping())
            .containsEntry("QUEUED", List.of("QUEUEING", "PENDING"))
            .containsEntry("SUCCESS", List.of("SUCCESS", "FINISHED"));
        assertThat(properties.getSeverityMapping())
            .as("a vendor defect level maps to the platform severity; an unmapped word yields"
                + " 'no level' instead of NONE")
            .containsEntry("S1", List.of("CRITICAL", "HIGH", "BLOCKER"))
            .containsEntry("S2", List.of("MEDIUM", "MAJOR"))
            .containsEntry("S3", List.of("LOW", "MINOR", "INFO", "SUGGESTION"));
        assertThat(properties.getEndpoints().get("check.query").getResponseSeverity())
            .isEqualTo("$.Response.Data.MaxSeverity");
        assertThat(properties.getEndpoints().get("check.env.start").getUrl())
            .as("an environment-level check scans a DE, so it has its own start endpoint")
            .isEqualTo("/open-api/check/env/start");
    }

    @Test
    void jsonpath_bracket_quotes_and_numeric_indexes_are_supported() {
        CodingProperties.Endpoint query = properties.getEndpoints().get("build.query");
        query.setResponseStatus("$['Data']['Jobs'][0]['Status']");
        query.setResponseDigest("$['Data']['Digest']");
        server.enqueue(new MockResponse().setBody(
            "{\"Data\":{\"Jobs\":[{\"Status\":\"RUNNING\"}],\"Digest\":\"sha256:cd\"}}"));

        EngineResult result = engine().query("j-1", JobKind.BUILD);

        assertThat(result.status()).isEqualTo(EngineStatus.RUNNING);
        assertThat(result.imageDigest()).isEqualTo("sha256:cd");
    }

    @Test
    void unsupported_jsonpath_is_rejected_at_startup_naming_action_and_expression() {
        properties.getEndpoints().get("build.query").setResponseStatus("$.Data[*].Status");

        Throwable thrown = catchThrowable(this::engine);

        assertThat(thrown).isInstanceOf(EngineException.class);
        assertThat(((EngineException) thrown).getError()).isEqualTo(BizError.ENGINE_UNAVAILABLE);
        assertThat(thrown.getMessage())
            .contains("build.query")
            .contains("$.Data[*].Status");
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void plain_http_base_url_is_rejected_unless_the_test_switch_allows_it() {
        EngineProperties secure = new EngineProperties();
        Throwable thrown = catchThrowable(
            () -> engine(defaultHttp(), secure).triggerBuild(buildCmd()));

        assertThat(thrown).isInstanceOf(EngineException.class);
        assertThat(((EngineException) thrown).getError()).isEqualTo(BizError.ENGINE_UNAVAILABLE);
        assertThat(thrown.getMessage())
            .contains("HTTPS")
            .doesNotContain(TOKEN);
        assertThat(server.getRequestCount()).isZero();

        EngineProperties insecure = new EngineProperties();
        insecure.setAllowInsecureHttp(true);
        server.enqueue(new MockResponse()
            .setBody("{\"Response\":{\"Data\":{\"JobId\":\"j-123\"}}}"));
        TriggerResult result = engine(defaultHttp(), insecure).triggerBuild(buildCmd());
        assertThat(result.externalId()).isEqualTo("j-123");
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ helpers

    private CodingEngine engine() {
        return engine(defaultHttp());
    }

    private CodingEngine engine(EngineHttpClient http) {
        return engine(http, engineProperties);
    }

    private CodingEngine engine(EngineHttpClient http, EngineProperties properties) {
        return new CodingEngine(this.properties, resolver, json, http, properties,
            () -> new CodingEngine.Connection(baseUrl, TOKEN));
    }

    private EngineHttpClient defaultHttp() {
        return new EngineHttpClient(Duration.ofSeconds(5), Duration.ofSeconds(5), 3,
            List.of(Duration.ofSeconds(1), Duration.ofSeconds(3)), duration -> { });
    }

    private EngineHttpClient impatient() {
        return new EngineHttpClient(Duration.ofSeconds(5), Duration.ofMillis(200), 3,
            List.of(Duration.ofSeconds(1), Duration.ofSeconds(3)), duration -> { });
    }

    private static Dispatcher delayedBody(long delay, java.util.concurrent.TimeUnit unit) {
        return new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse()
                    .setBody("{\"Data\":{\"Status\":\"RUNNING\"}}")
                    .setBodyDelay(delay, unit);
            }
        };
    }

    private static BuildCmd buildCmd() {
        return new BuildCmd(88L, "P-BUILD", "feature/x", "sha1", "PL-1", 1);
    }

    private static DeployCmd deployCmd() {
        return new DeployCmd(88L, "P-DEPLOY", "TEST", "quota-sit", "sha256:ab", "PL-1", 2);
    }

    // ------------------------------------------------------------------ gate checks (P2)

    @Test
    void start_check_uses_the_image_endpoint_for_an_image_level_check() throws Exception {
        server.enqueue(new MockResponse().setBody(
            "{\"Response\":{\"Data\":{\"CheckId\":\"c-1\","
                + "\"WebUrl\":\"https://coding.example/check/1\"}}}"));

        TriggerResult result = engine().startCheck(new GateCheckCmd(88L, "P-BUILD",
            CheckType.SONAR, "sha1", "sha256:ab", null, "PL-1", 1));

        assertThat(result.externalId()).isEqualTo("c-1");
        assertThat(result.externalUrl()).isEqualTo("https://coding.example/check/1");
        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/open-api/check/start");
        String body = request.getBody().readUtf8();
        assertThat(body).contains("\"CheckType\":\"SONAR\"")
            .contains("\"TaskId\":\"P-BUILD\"")
            .contains("\"CommitSha\":\"sha1\"")
            .contains("\"ImageDigest\":\"sha256:ab\"")
            .as("an image-level check has no DE to scan")
            .doesNotContain("DeName");
    }

    @Test
    void start_check_uses_the_env_endpoint_for_an_environment_level_check() throws Exception {
        server.enqueue(new MockResponse()
            .setBody("{\"Response\":{\"Data\":{\"CheckId\":\"c-2\"}}}"));

        TriggerResult result = engine().startCheck(new GateCheckCmd(88L, "P-TEST",
            CheckType.AUTO_TEST, null, "sha256:ab", "quota-sit", "PL-1", 3));

        assertThat(result.externalId()).isEqualTo("c-2");
        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath())
            .as("an environment-level check scans a deployed DE, so it posts to its own endpoint")
            .isEqualTo("/open-api/check/env/start");
        String body = request.getBody().readUtf8();
        assertThat(body).contains("\"CheckType\":\"AUTO_TEST\"")
            .contains("\"DeName\":\"quota-sit\"")
            .contains("\"Attempt\":3")
            .doesNotContain("CommitSha");
    }

    @Test
    void query_check_maps_the_status_word_and_the_defect_level() {
        server.enqueue(new MockResponse().setBody("{\"Response\":{\"Data\":{"
            + "\"Status\":\"FINISHED\",\"MaxSeverity\":\"CRITICAL\",\"IssueCount\":\"2\","
            + "\"Summary\":\"1 blocker\",\"ReportUrl\":\"https://coding.example/r/1\"}}}"));

        GateCheckReport report = engine().queryCheck("c-1", CheckType.SONAR);

        assertThat(report.outcome()).isEqualTo(GateCheckOutcome.SUCCESS);
        assertThat(report.severity()).isEqualTo(Severity.S1);
        assertThat(report.issues()).isEqualTo(2);
        assertThat(report.summary()).isEqualTo("1 blocker");
        assertThat(report.reportUrl()).isEqualTo("https://coding.example/r/1");
    }

    @Test
    void query_check_reports_running_while_the_engine_is_still_working() {
        server.enqueue(new MockResponse()
            .setBody("{\"Response\":{\"Data\":{\"Status\":\"RUNNING\"}}}"));

        GateCheckReport report = engine().queryCheck("c-1", CheckType.SONAR);

        assertThat(report.outcome()).isEqualTo(GateCheckOutcome.RUNNING);
        assertThat(report.severity()).isNull();
    }

    @Test
    void query_check_without_a_reported_level_is_unknown_not_a_pass() {
        server.enqueue(new MockResponse()
            .setBody("{\"Response\":{\"Data\":{\"Status\":\"FINISHED\"}}}"));

        GateCheckReport report = engine().queryCheck("c-1", CheckType.SONAR);

        assertThat(report.outcome())
            .as("a finished check with no level cannot be judged; it must not read as a pass")
            .isEqualTo(GateCheckOutcome.UNKNOWN);
        assertThat(report.severity()).isNull();
        assertThat(report.summary()).contains("severityMapping");
    }

    @Test
    void query_check_with_an_unmapped_level_word_is_unknown_not_none() {
        server.enqueue(new MockResponse().setBody(
            "{\"Response\":{\"Data\":{\"Status\":\"FINISHED\",\"MaxSeverity\":\"WEIRD\"}}}"));

        GateCheckReport report = engine().queryCheck("c-1", CheckType.SONAR);

        assertThat(report.outcome()).isEqualTo(GateCheckOutcome.UNKNOWN);
        assertThat(report.severity())
            .as("an unmapped vocabulary must not collapse into 'no findings'")
            .isNull();
    }

    @Test
    void query_check_maps_a_failed_result_with_its_level() {
        server.enqueue(new MockResponse().setBody(
            "{\"Response\":{\"Data\":{\"Status\":\"FAILED\",\"MaxSeverity\":\"MEDIUM\","
                + "\"IssueCount\":4}}}"));

        GateCheckReport report = engine().queryCheck("c-1", CheckType.AUTO_TEST);

        assertThat(report.outcome()).isEqualTo(GateCheckOutcome.FAILED);
        assertThat(report.severity()).isEqualTo(Severity.S2);
        assertThat(report.issues()).isEqualTo(4);
    }

    /** Loads the real {@code src/main/resources/coding-endpoints.yml} via Spring's binder. */
    private static CodingProperties loadBundledProperties() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
            .load("coding-endpoints", new ClassPathResource("coding-endpoints.yml"));
        Binder binder = new Binder(ConfigurationPropertySources.from(sources));
        return binder.bind("coding", Bindable.of(CodingProperties.class))
            .orElseThrow(() -> new IllegalStateException(
                "coding-endpoints.yml is missing or does not bind under 'coding'"));
    }
}