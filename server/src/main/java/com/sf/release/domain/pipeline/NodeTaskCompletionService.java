package com.sf.release.domain.pipeline;

import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.domain.pipeline.event.NodeTaskCompletedEvent;
import com.sf.release.domain.pipeline.port.PipelineEventBus;
import com.sf.release.domain.pipeline.port.PipelineRepository;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Applies one engine/webhook completion to a {@code node_task}, idempotently (tech design §5.2).
 *
 * <p>The state transition itself is a compare-and-set in the repository: only a non-terminal
 * task row may change, so a duplicate callback changes nothing and a late {@code RUNNING}
 * callback cannot pull a completed task back. {@code SUCCESS} is sticky together with the
 * other terminal results ({@code FAILED}/{@code TIMEOUT}/{@code SKIPPED}); a manual retry is a
 * new {@code attempt} row (design §5.4), never a mutation of a finished row.
 *
 * <p>A terminal result publishes {@link NodeTaskCompletedEvent}. The orchestrator then decides
 * whether the enclosing node finished; this service never advances a node by itself.
 */
public final class NodeTaskCompletionService {

    private static final Set<NodeTaskStatus> TERMINAL = EnumSet.of(
        NodeTaskStatus.SUCCESS, NodeTaskStatus.FAILED, NodeTaskStatus.TIMEOUT,
        NodeTaskStatus.SKIPPED);

    private final PipelineRepository repository;
    private final PipelineEventBus eventBus;

    public NodeTaskCompletionService(PipelineRepository repository, PipelineEventBus eventBus) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus must not be null");
    }

    /**
     * Persists the completion of {@code taskId}.
     *
     * @param status  the engine-reported task status; terminal statuses drive advancement
     * @param digest  image digest carried by a successful {@code IMG_BUILD} callback (may be null)
     * @param message failure/reason text persisted as {@code result_summary} (may be null)
     * @return the applied completion, including whether the row actually changed
     * @throws BizException 40000 when the task row does not exist
     */
    public Completion apply(long taskId, NodeTaskStatus status, String digest, String message) {
        Objects.requireNonNull(status, "status must not be null");
        NodeTask task = repository.loadTask(taskId);
        if (task == null) {
            throw new BizException(BizError.PARAM_INVALID, "流水线任务不存在",
                Map.of("taskId", taskId));
        }
        Instant now = Instant.now();
        boolean changed = repository.completeTask(taskId, status, digest, message, now);
        boolean terminal = TERMINAL.contains(status);
        if (changed && terminal) {
            eventBus.publish(new NodeTaskCompletedEvent(task.instanceId(), task.id(),
                task.nodeType(), task.taskType(), status, digest, message, now));
        }
        return new Completion(task.instanceId(), task.nodeType(), changed, terminal);
    }

    /**
     * Result of applying a callback.
     *
     * @param changed  whether the row was updated (false for a duplicate/late callback)
     * @param terminal whether the reported status is a terminal task result
     */
    public record Completion(long instanceId, NodeType nodeType, boolean changed,
                             boolean terminal) {
    }
}