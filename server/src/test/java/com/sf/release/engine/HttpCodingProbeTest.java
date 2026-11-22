package com.sf.release.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sf.release.domain.resource.CodingConn;
import com.sf.release.domain.resource.CodingScope;
import com.sf.release.infra.engine.CodingEngine;
import com.sf.release.infra.engine.CodingProperties;
import com.sf.release.infra.engine.EngineHttpClient;
import com.sf.release.infra.engine.EngineProperties;
import com.sf.release.infra.engine.EndpointTemplateResolver;
import com.sf.release.infra.engine.HttpCodingProbe;
import com.sf.release.infra.engine.ProbeResult;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The settings screen "test connection" button: one probe proves base URL, credential and
 * project at once by listing the project's jobs, and a failure is returned as data (the
 * operator reads the engine message) instead of an exception.
 *
 * <p>The adapter is the real {@link CodingEngine} driven by the bundled
 * {@code coding-endpoints.yml}; only the network is a {@link MockWebServer}.
 */
class HttpCodingProbeTest {

    private static final String TOKEN = "tok-123";
    private static final String PROJECT = "quota-project";

    private MockWebServer server;
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        baseUrl = "http://localhost:" + server.getPort();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void a_reachable_project_reports_both_namespace_counts() throws Exception {
        givenJobLists(2, 1);

        ProbeResult result = probe(baseUrl).probe(conn(baseUrl, PROJECT), TOKEN);

        assertThat(result.isOk()).isTrue();
        assertThat(result.buildJobs()).isEqualTo(2);
        assertThat(result.deployJobs()).isEqualTo(1);
        assertThat(result.message()).contains("构建任务 2 个").contains("部署任务 1 个");

        RecordedRequest first = server.takeRequest();
        assertThat(first.getMethod()).isEqualTo("GET");
        assertThat(first.getPath())
            .isEqualTo("/open-api/project/" + PROJECT + "/job/list?type=BUILD&page=1&pageSize=100");
        assertThat(first.getHeader("Authorization")).isEqualTo("Bearer " + TOKEN);
        assertThat(server.takeRequest().getPath()).contains("type=DEPLOY");
    }

    @Test
    void a_rejected_credential_is_a_failed_result_with_the_reason_not_an_exception() {
        server.setDispatcher(fixed(new MockResponse().setResponseCode(401)
            .setBody("{\"error\":\"bad token\"}")));

        ProbeResult result = probe(baseUrl).probe(conn(baseUrl, PROJECT), TOKEN);

        assertThat(result.isOk()).isFalse();
        assertThat(result.buildJobs()).isZero();
        assertThat(result.deployJobs()).isZero();
        assertThat(result.message()).contains("HTTP 401");
    }

    @Test
    void a_blank_project_fails_before_any_request() {
        givenJobLists(1, 1);

        ProbeResult result = probe(baseUrl).probe(conn(baseUrl, "   "), TOKEN);

        assertThat(result.isOk()).isFalse();
        assertThat(result.message()).contains("CODING 项目");
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void a_blank_credential_fails_before_any_request() {
        givenJobLists(1, 1);

        ProbeResult result = probe(baseUrl).probe(conn(baseUrl, PROJECT), "   ");

        assertThat(result.isOk()).isFalse();
        assertThat(result.message()).contains("凭证");
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void the_probe_uses_the_given_connection_not_the_pipeline_one() {
        givenJobLists(1, 1);

        ProbeResult result = probe("https://not-the-draft.example.com")
            .probe(conn(baseUrl, PROJECT), TOKEN);

        assertThat(result.isOk())
            .as("the probe must talk to the draft base URL, never to the pipeline supplier")
            .isTrue();
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ helpers

    private HttpCodingProbe probe(String engineBaseUrl) {
        CodingProperties properties = loadBundledProperties();
        EngineProperties engineProperties = new EngineProperties();
        engineProperties.setAllowInsecureHttp(true);
        EngineHttpClient http = new EngineHttpClient(Duration.ofSeconds(5), Duration.ofSeconds(5),
            1, List.of(), duration -> { });
        CodingEngine engine = new CodingEngine(properties, new EndpointTemplateResolver(properties),
            new ObjectMapper(), http, engineProperties,
            () -> new CodingEngine.Connection(engineBaseUrl, TOKEN));
        return new HttpCodingProbe(engine);
    }

    private CodingConn conn(String url, String project) {
        return new CodingConn(1L, CodingScope.GLOBAL, CodingConn.GLOBAL_SUBSYSTEM_ID, url,
            "team-a", project, null, null, null);
    }

    /** Answers either namespace with a list of the requested size. */
    private void givenJobLists(int buildJobs, int deployJobs) {
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String type = request.getRequestUrl().queryParameter("type");
                int count = "DEPLOY".equals(type) ? deployJobs : buildJobs;
                return new MockResponse().setBody(jobList(count));
            }
        });
    }

    private static Dispatcher fixed(MockResponse response) {
        return new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return response;
            }
        };
    }

    private static String jobList(int count) {
        StringBuilder jobs = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (jobs.length() > 0) {
                jobs.append(',');
            }
            jobs.append("{\"Id\":\"J-").append(i).append("\",\"Name\":\"任务").append(i)
                .append("\"}");
        }
        return "{\"Response\":{\"Data\":{\"Jobs\":[" + jobs + "]}}}";
    }

    private static CodingProperties loadBundledProperties() {
        try {
            List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("coding-endpoints", new ClassPathResource("coding-endpoints.yml"));
            Binder binder = new Binder(ConfigurationPropertySources.from(sources));
            return binder.bind("coding", Bindable.of(CodingProperties.class))
                .orElseThrow(() -> new IllegalStateException(
                    "coding-endpoints.yml is missing or does not bind under 'coding'"));
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}