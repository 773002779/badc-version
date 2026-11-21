package com.sf.release.application;

import com.sf.release.domain.pipeline.NodeHandler;
import com.sf.release.domain.pipeline.NodeTask;
import com.sf.release.domain.pipeline.NodeType;
import com.sf.release.domain.pipeline.PipelineInstance;
import com.sf.release.domain.pipeline.PipelineNode;
import com.sf.release.domain.pipeline.port.PipelineRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Executes the {@code VERIFY} node: triggers the gate checks and lets the gate decide when the node
 * is done (P2, replacing the P1 {@code PAUSED} boundary).
 *
 * <p><strong>The node is not completed by its task rows.</strong> The {@code VERIFY} node carries
 * seven {@code CHECK} tasks, and {@code CHECK} results never gate a node (the orchestrator's
 * completion fold excludes them, because a check result arrives asynchronously and is not a
 * deploy). The node completes when <em>both</em> gates are satisfied — checks pass/waived and the
 * test passed — which this handler answers through {@link #mayComplete}.
 *
 * <p><strong>A blocked gate is a node failure.</strong> Once the checks reach a final blocking
 * verdict (or the test fails / is revoked), {@link #failureReason} reports it and the orchestrator
 * fails the node and the instance with that reason. That releases the 部署单元 lock and leaves the
 * item {@code GATE_FAILED}, exactly the state the rerun use case expects — instead of leaving a node
 * {@code RUNNING} forever with nobody able to act on it.
 *
 * <p><strong>Layering.</strong> Unlike the build and test-deploy handlers — which only need domain
 * ports — this one needs the gate use cases, so it lives in the application layer instead of
 * {@code domain.pipeline.handler}. The domain stays free of the gate services; the orchestrator only
 * sees the {@link NodeHandler} contract.
 */
@Component
public class VerifyNodeHandler implements NodeHandler {

    private static final Logger log = LoggerFactory.getLogger(VerifyNodeHandler.class);

    private final GateCheckService checkService;
    private final GateService gateService;
    private final PipelineRepository repository;

    public VerifyNodeHandler(GateCheckService checkService, GateService gateService,
                             PipelineRepository repository) {
        this.checkService = Objects.requireNonNull(checkService, "checkService must not be null");
        this.gateService = Objects.requireNonNull(gateService, "gateService must not be null");
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
    }

    @Override
    public NodeType nodeType() {
        return NodeType.VERIFY;
    }

    /**
     * Triggers the checks that have no usable result yet. A re-entry (a replay, a recovery) is
     * idempotent: finished checks are kept, a running check is not triggered twice, and an already
     * satisfied gate starts nothing.
     */
    @Override
    public void start(PipelineInstance instance) {
        PipelineInstance current = repository.loadInstance(instance.id());
        if (current == null) {
            return;
        }
        if (gateService.verifySatisfied(current)) {
            log.info("gate already satisfied, no check needs to start: instanceId={}",
                current.id());
            return;
        }
        int started = checkService.startChecks(current);
        // Record CHECK_RUNNING immediately: the panel must say "being checked", not "never started".
        gateService.evaluateForInstance(current);
        log.info("verification node started: instanceId={}, checksStarted={}", current.id(),
            started);
    }

    @Override
    public boolean mayComplete(PipelineInstance instance, PipelineNode node) {
        return gateService.verifySatisfied(instance);
    }

    @Override
    public String failureReason(PipelineInstance instance, PipelineNode node) {
        return gateService.verifyBlockedReason(instance);
    }

    /** The verify node owns no task chain: its tasks are the checks, driven by the gate services. */
    @Override
    public void onTaskCompleted(PipelineInstance instance, NodeTask completed) {
    }
}