package com.sf.release.infra.engine;

import com.sf.release.domain.engine.EngineException;
import com.sf.release.domain.engine.EngineJob;
import com.sf.release.domain.engine.JobKind;
import com.sf.release.domain.resource.CodingConn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * HTTP implementation of {@link CodingProbe}: proves base URL, credential and project in one
 * place by listing the project's jobs in both namespaces.
 *
 * <p><strong>Why {@code job.list}.</strong> It is the cheapest call that needs the credential
 * and the project together, and it is the same call the settings form already depends on for
 * its task picker — so a probe that passes means the picker will work too. Requirements
 * FR-I-01 asks for exactly this: credential valid, project reachable, build plan present.
 *
 * <p><strong>Draft connections.</strong> The caller may pass a connection that is not saved
 * yet; the engine's explicit-connection {@code listJobs} overload keeps the process-wide
 * connection untouched.
 *
 * <p>A failure is returned as data ({@link ProbeResult#failed}), never thrown: the engine
 * message is what the operator needs to read, and the engine already redacts the token from it.
 */
@Component
public class HttpCodingProbe implements CodingProbe {

    private static final Logger log = LoggerFactory.getLogger(HttpCodingProbe.class);

    private final CodingEngine engine;

    public HttpCodingProbe(CodingEngine engine) {
        this.engine = engine;
    }

    @Override
    public ProbeResult probe(CodingConn conn, String credential) {
        if (conn == null) {
            return ProbeResult.failed("没有可测试的连接配置");
        }
        String project = trimToNull(conn.project());
        if (project == null) {
            return ProbeResult.failed("未填写默认 CODING 项目，无法校验连接");
        }
        if (trimToNull(credential) == null) {
            return ProbeResult.failed("凭证为空，无法校验连接");
        }
        CodingEngine.Connection connection;
        try {
            connection = new CodingEngine.Connection(conn.baseUrl(), credential);
        } catch (IllegalArgumentException ex) {
            return ProbeResult.failed("连接配置不完整：" + ex.getMessage());
        }
        try {
            List<EngineJob> builds = engine.listJobs(connection, project, JobKind.BUILD);
            List<EngineJob> deploys = engine.listJobs(connection, project, JobKind.DEPLOY);
            log.info("coding connection probe ok: project={}, buildJobs={}, deployJobs={}",
                project, builds.size(), deploys.size());
            return ProbeResult.ok(
                "项目可达；构建任务 " + builds.size() + " 个，部署任务 " + deploys.size() + " 个",
                builds.size(), deploys.size());
        } catch (EngineException ex) {
            log.warn("coding connection probe failed: project={}, reason={}",
                project, ex.getMessage());
            return ProbeResult.failed(ex.getMessage());
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}