package com.sf.release.infra.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sf.release.domain.engine.CicdEngine;
import com.sf.release.infra.engine.CodingEngine;
import com.sf.release.infra.engine.CodingProperties;
import com.sf.release.infra.engine.CodingStatusMapper;
import com.sf.release.infra.engine.EndpointTemplateResolver;
import com.sf.release.infra.engine.EngineHttpClient;
import com.sf.release.infra.engine.EngineProperties;
import com.sf.release.infra.engine.EngineRegistry;
import com.sf.release.infra.engine.GlobalCodingConnectionProvider;
import com.sf.release.infra.engine.HttpCodingProbe;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Wires the CODING adapter (Task 10) and its registry into the Spring context (Task 14
 * carry-over). The adapter performs no network I/O at construction: it only validates the
 * JSONPath expressions of {@code coding-endpoints.yml}, and the connection is resolved lazily per
 * call by {@link GlobalCodingConnectionProvider}. The application therefore boots with no CODING
 * tenant configured.
 */
@Configuration(proxyBeanMethods = false)
public class EngineConfig {

    /** Engine type registered for the CODING adapter. */
    public static final String CODING_ENGINE_TYPE = CodingEngine.ENGINE_NAME;

    @Bean
    public EngineHttpClient engineHttpClient() {
        return new EngineHttpClient();
    }

    @Bean
    public EndpointTemplateResolver endpointTemplateResolver(CodingProperties properties) {
        return new EndpointTemplateResolver(properties);
    }

    @Bean
    public CodingStatusMapper codingStatusMapper(CodingProperties properties) {
        return new CodingStatusMapper(properties);
    }

    @Bean
    public CodingEngine codingEngine(CodingProperties properties,
                                     EndpointTemplateResolver resolver,
                                     ObjectMapper objectMapper,
                                     EngineHttpClient http,
                                     EngineProperties engineProperties,
                                     GlobalCodingConnectionProvider connectionProvider) {
        return new CodingEngine(properties, resolver, objectMapper, http, engineProperties,
            connectionProvider);
    }

    @Bean
    public EngineRegistry engineRegistry(List<CicdEngine> engines) {
        return new EngineRegistry(engines);
    }

    /**
     * Connectivity probe used by the settings screen. Registered here (not via component scan)
     * so the whole engine wiring stays in one place; {@code CodingConnAppService} depends on the
     * {@code CodingProbe} port, never on this class.
     */
    @Bean
    public HttpCodingProbe httpCodingProbe(CodingEngine engine) {
        return new HttpCodingProbe(engine);
    }
}