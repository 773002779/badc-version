package com.sf.release.application;

import com.sf.release.domain.gate.GateCheck;
import com.sf.release.domain.pipeline.CheckType;
import com.sf.release.domain.pipeline.NodeHandler;
import com.sf.release.domain.pipeline.NodeType;
import com.sf.release.domain.pipeline.PipelineInstance;
import com.sf.release.domain.pipeline.PipelineNode;
import com.sf.release.domain.pipeline.port.PipelineRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Executes the {@code LIB_SECURITY} node of a second-party library (P6-A design §3): the three
 * artifact checks {@link CheckType#libSecurityChecks()} must pass (or be waived) before the
 * artifact may be uploaded.
 *
 * <p><strong>Checks are gate rows, not node tasks.</strong> The node has no {@code node_task}
 * rows, exactly like {@code VERIFY} with respect to its {@code CHECK} tasks: the results live in
 * {@code gate_check}, {@link GateCheckService} records them, and the node is completed through
 * {@link #mayComplete} rather than by the orchestrator's task fold. The orchestrator already
 * excludes {@code CHECK} tasks from that fold and needs a handler verdict for a task-less node.
 *
 * <p><strong>Idempotent entry.</strong> The node can be re-entered (a replay, a recovery). A
 * check type that already has a row is left alone: {@link GateCheckService#refresh} safely
 * re-triggers a check but would reset a finished result, so it is called only for the types the
 * item does not have yet. A second entry therefore never starts a fourth check or re-runs a
 * passed one.
 *
 * <p><strong>Blocked is a node failure, but only when final.</strong> A non-waivable finding
 * (高危/严重) or an invalidated result fails the node through {@link #failureReason}; a 中危
 * block keeps the node waiting so the existing waiver flow can lift it (P2 rules), and the
 * approved waiver makes {@link #mayComplete} true.
 *
 * <p><strong>Layering.</strong> Like {@code VerifyNodeHandler}, this handler needs the gate use
 * cases, so it lives in the application layer; the domain only sees the {@link NodeHandler}
 * contract.
 */
@Component
public final class LibSecurityNodeHandler implements NodeHandler {

    private static final Logger log = LoggerFactory.getLogger(LibSecurityNodeHandler.class);

    private final GateCheckService checkService;
    private final GateService gateService;
    private final PipelineRepository repository;

    public LibSecurityNodeHandler(GateCheckService checkService, GateService gateService,
                                  PipelineRepository repository) {
        this.checkService = Objects.requireNonNull(checkService, "checkService must not be null");
        this.gateService = Objects.requireNonNull(gateService, "gateService must not be null");
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
    }

    @Override
    public NodeType nodeType() {
        return NodeType.LIB_SECURITY;
    }

    /**
     * Triggers only the artifact checks the item has no row for. The aggregate is re-read first,
     * so a stale in-memory instance cannot make a re-entry start a check twice.
     */
    @Override
    public void start(PipelineInstance instance) {
        PipelineInstance current = repository.loadInstance(instance.id());
        if (current == null) {
            return;
        }
        long itemId = repository.itemIdOf(current.id());
        if (itemId <= 0) {
            log.warn("library security checks not started: the instance has no 变更项:"
                + " instanceId={}", current.id());
            return;
        }
        Set<CheckType> existing = EnumSet.noneOf(CheckType.class);
        for (GateCheck check : checkService.checks(itemId)) {
            existing.add(check.checkType());
        }
        int started = 0;
        for (CheckType type : CheckType.libSecurityChecks()) {
            if (existing.contains(type)) {
                continue;
            }
            // The existing gate refresh path: it records/restarts the row and triggers the
            // engine's check endpoint. It is only used for a type without any recorded result.
            checkService.refresh(itemId, type.name(), current.id());
            started++;
        }
        log.info("library security node started: instanceId={}, itemId={}, checksStarted={}",
            current.id(), itemId, started);
    }

    /** The node may complete when the three checks passed or are covered by a valid waiver. */
    @Override
    public boolean mayComplete(PipelineInstance instance, PipelineNode node) {
        return gateService.libSecuritySatisfied(instance);
    }

    /** A final check verdict (non-waivable block or invalidation) fails the node and instance. */
    @Override
    public String failureReason(PipelineInstance instance, PipelineNode node) {
        return gateService.libSecurityBlockedReason(instance);
    }
}