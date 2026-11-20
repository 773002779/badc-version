package com.sf.release.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sf.release.common.BizError;
import com.sf.release.domain.engine.BuildCmd;
import com.sf.release.domain.engine.CicdEngine;
import com.sf.release.domain.engine.DeployCmd;
import com.sf.release.domain.engine.EngineException;
import com.sf.release.domain.engine.EngineResult;
import com.sf.release.domain.engine.EngineStatus;
import com.sf.release.domain.engine.TriggerResult;
import com.sf.release.infra.engine.CodingEngine;
import com.sf.release.infra.engine.CodingProperties;
import com.sf.release.infra.engine.EngineHttpClient;
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
 * Task 10: the CODING adapter is driven by {@code /coding-endpoints.yml}, not by hard-coded
 * paths or field names. Every test loads the real bundled YAML through Spring's binder, then
 * points the resulting endpoint template at MockWebServer.
 */
@ExtendWith(OutputCaptureExtension.class)
class CodingEngineTest {

    private static final String TOKEN = "tok-123";
    private static final String NO_WAIT = "the retry sleeper is a no-op so backoff stays out of test time";

    private MockWebServer server;
    private CodingProperties properties;
    private EndpointTemplateResolver resolver;
    private String baseUrl;

    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        baseUrl = "http://localhost:" + server.getPort();
        properties = loadBundledProperties();
        resolver = new EndpointTemplateResolver(properties);
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
    void query_maps_representative_status_words_and_extracts_the_digest() {
        server.enqueue(new MockResponse().setBody("{\"Data\":{\"Status\":\"QUEUEING\"}}"));
        server.enqueue(new MockResponse()
            .setBody("{\"Data\":{\"Status\":\"DEPLOYING\",\"Digest\":\"sha256:ab\"}}"));
        server.enqueue(new MockResponse().setBody("{\"Data\":{\"Status\":\"FINISHED\"}}"));

        EngineResult queued = engine().query("j-1");
        EngineResult running = engine().query("j-1");
        EngineResult success = engine().query("j-1");

        assertThat(queued.status()).isEqualTo(EngineStatus.QUEUED);
        assertThat(queued.imageDigest())
            .as("a missing digest is data for the caller, not an adapter failure")
            .isNull();
        assertThat(running.status()).isEqualTo(EngineStatus.RUNNING);
        assertThat(running.imageDigest()).isEqualTo("sha256:ab");
        assertThat(success.status()).isEqualTo(EngineStatus.SUCCESS);
        assertThat(success.imageDigest()).isNull();
    }

    @Test
    void status_mapping_accepts_the_hash_wrapped_key_form_from_the_brief() {
        properties.getStatusMapping().clear();
        properties.getStatusMapping().put("#{QUEUED}", List.of("WAITING"));
        server.enqueue(new MockResponse().setBody("{\"Data\":{\"Status\":\"WAITING\"}}"));

        assertThat(engine().query("j-1").status()).isEqualTo(EngineStatus.QUEUED);
    }

    @Test
    void query_reports_an_unknown_status_word_without_guessing() {
        server.enqueue(new MockResponse().setBody("{\"Data\":{\"Status\":\"MYSTERY\"}}"));

        Throwable thrown = catchThrowable(() -> engine().query("j-1"));

        assertThat(thrown).isInstanceOf(EngineException.class);
        assertThat(((EngineException) thrown).getError()).isEqualTo(BizError.ENGINE_UNAVAILABLE);
        assertThat(thrown.getMessage()).contains("MYSTERY");
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
    void engine_4xx_is_not_retried() {
        server.enqueue(new MockResponse().setResponseCode(400).setBody("bad request"));

        Throwable thrown = catchThrowable(() -> engine().triggerBuild(buildCmd()));

        assertThat(thrown).isInstanceOf(EngineException.class);
        assertThat(((EngineException) thrown).getError()).isEqualTo(BizError.ENGINE_UNAVAILABLE);
        assertThat(thrown.getMessage()).contains("400");
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void read_timeout_maps_to_engine_timeout() {
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse()
                    .setBody("{\"Data\":{\"Status\":\"RUNNING\"}}")
                    .setBodyDelay(1, java.util.concurrent.TimeUnit.SECONDS);
            }
        });
        EngineHttpClient impatient = new EngineHttpClient(
            Duration.ofSeconds(5), Duration.ofMillis(200), 3,
            List.of(Duration.ofSeconds(1), Duration.ofSeconds(3)), duration -> { });

        Throwable thrown = catchThrowable(() -> engine(impatient).query("j-1"));

        assertThat(thrown).isInstanceOf(EngineException.class);
        assertThat(((EngineException) thrown).getError()).isEqualTo(BizError.ENGINE_TIMEOUT);
        assertThat(server.getRequestCount()).isEqualTo(3);
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
    void logs_returns_the_response_body_and_passes_offset_and_limit() throws Exception {
        server.enqueue(new MockResponse().setBody("line-1\nline-2"));

        String logs = engine().logs("j-9", 0, 100);

        assertThat(logs).isEqualTo("line-1\nline-2");
        assertThat(server.takeRequest().getPath())
            .isEqualTo("/open-api/build/j-9/logs?offset=0&limit=100");
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
        assertThat(properties.getQueryAction()).isEqualTo("build.query");
        assertThat(properties.getEndpoints())
            .containsKeys("build.start", "build.query", "build.logs",
                "deploy.start", "deploy.query", "cancel")
            .hasSize(6);
        assertThat(properties.getEndpoints().get("build.start").getMethod()).isEqualTo("POST");
        assertThat(properties.getEndpoints().get("build.start").getUrl())
            .isEqualTo("/open-api/build/start");
        assertThat(properties.getEndpoints().get("build.start").getResponseId())
            .isEqualTo("$.Response.Data.JobId");
        assertThat(properties.getEndpoints().get("build.query").getUrl())
            .isEqualTo("/open-api/build/{externalId}");
        assertThat(properties.getStatusMapping())
            .containsEntry("QUEUED", List.of("QUEUEING", "PENDING"))
            .containsEntry("SUCCESS", List.of("SUCCESS", "FINISHED"));
    }

    // ------------------------------------------------------------------ helpers

    private CodingEngine engine() {
        return engine(defaultHttp());
    }

    private CodingEngine engine(EngineHttpClient http) {
        return new CodingEngine(properties, resolver, json, http,
            () -> new CodingEngine.Connection(baseUrl, TOKEN));
    }

    private EngineHttpClient defaultHttp() {
        return new EngineHttpClient(Duration.ofSeconds(5), Duration.ofSeconds(5), 3,
            List.of(Duration.ofSeconds(1), Duration.ofSeconds(3)), duration -> { });
    }

    private static BuildCmd buildCmd() {
        return new BuildCmd(88L, "P-BUILD", "feature/x", "sha1", "PL-1", 1);
    }

    private static DeployCmd deployCmd() {
        return new DeployCmd(88L, "P-DEPLOY", "TEST", "quota-sit", "sha256:ab", "PL-1", 2);
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