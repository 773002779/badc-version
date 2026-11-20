package com.sf.release.domain.pipeline.port;

import com.sf.release.domain.pipeline.InstanceStatus;
import com.sf.release.domain.pipeline.NodeStatus;
import com.sf.release.domain.pipeline.NodeTask;
import com.sf.release.domain.pipeline.NodeTaskStatus;
import com.sf.release.domain.pipeline.NodeType;
import com.sf.release.domain.pipeline.PipelineInstance;

import java.time.Instant;

/**
 * Persistence port of the pipeline aggregate (tech design §3.1: the instance is the
 * consistency boundary; nodes and tasks are persisted with it).
 *
 * <p>The methods are deliberately explicit instead of a generic "save": almost every write is
 * a compare-and-set on the previous status, which is what makes duplicate callbacks and
 * concurrent advances safe (tech design §4.3 "状态前置校验（CAS）"). The implementation lives in
 * {@code infra}, so this package stays free of MyBatis and Spring.
 */
public interface PipelineRepository {

    /** The aggregate root with its nodes and tasks, or {@code null} when it does not exist. */
    PipelineInstance loadInstance(long instanceId);

    /** One task row as a domain object, or {@code null} when it does not exist. */
    NodeTask loadTask(long taskId);

    /**
     * The owning release order and deployment unit of an instance. {@code orderId} and
     * {@code unitId} are {@code 0} when the instance has no release order (daily pipeline) or
     * the order/item row is missing.
     */
    InstanceContext contextOf(long instanceId);

    /**
     * Applies one task completion, idempotently: a task that is already terminal
     * ({@code SUCCESS/FAILED/TIMEOUT/SKIPPED}) is never changed again, so a late
     * {@code RUNNING} callback cannot regress a {@code SUCCESS}. A non-terminal status
     * ({@code RUNNING}, {@code WAITING_EXTERNAL}) only fills {@code started_at}.
     *
     * <p>A successful {@code IMG_BUILD} completion persists {@code digest} as the instance's
     * {@code image_digest}; the other task types ignore it.
     *
     * @return {@code true} when a row was actually updated
     */
    boolean completeTask(long taskId, NodeTaskStatus status, String digest, String message,
                         Instant now);

    /** {@code PENDING → RUNNING} for the instance, setting {@code start_at}/{@code current_node}. */
    boolean startInstance(long instanceId, NodeType nodeType, Instant now);

    /** Moves {@code current_node} while the instance is still {@code RUNNING}. */
    boolean moveToNode(long instanceId, NodeType fromNode, NodeType toNode);

    /** {@code PENDING → RUNNING} for the node. */
    boolean startNode(long nodeId, Instant now);

    /**
     * Completes the node only when, at the moment of the update, every task of the node is
     * {@code SUCCESS} or {@code SKIPPED} and none is {@code FAILED}/{@code TIMEOUT}. The guard is
     * part of the SQL (not an in-memory check only) because task completions persist outside the
     * instance lock.
     */
    boolean finishNodeIfComplete(long nodeId, Instant now);

    /** {@code RUNNING → status} for the node, used for the failure transition. */
    boolean finishNode(long nodeId, NodeStatus status, Instant now);

    /** Marks the still-{@code PENDING} tasks of one node {@code SKIPPED}, leaving other rows alone. */
    boolean skipPendingTasks(long instanceId, NodeType nodeType, Instant now);

    /** {@code PENDING/RUNNING → status} for the instance, setting {@code end_at}. */
    boolean finishInstance(long instanceId, InstanceStatus status, Instant now);

    /**
     * The build coordinates of an instance: the deployment unit, the CODING plan (the unit's
     * enabled {@code BUILD} binding), the item's read-only source branch and the commit the
     * pipeline already knows. The commit is never the item's pre-build {@code base_commit};
     * it is {@code null} until an engine trigger reports one (the engine then builds the
     * branch head). Unresolvable parts are {@code 0}/blank instead of failing the pipeline.
     */
    BuildContext buildContextOf(long instanceId);

    /**
     * Records the commit an engine trigger reported for this instance, so later steps of the
     * chain build the same commit. Only a reported (non-blank) commit is written.
     */
    boolean recordCommit(long instanceId, String commitSha);

    /**
     * Marks a task {@code WAITING_EXTERNAL} with the engine identifiers and its build
     * deadline. Only a non-terminal row is changed, so a duplicate trigger cannot regress a
     * completion; the first trigger also fills {@code started_at}.
     *
     * @return {@code true} when the row was updated
     */
    boolean markTaskWaitingExternal(long taskId, String externalId, String externalUrl,
                                    Instant deadlineAt, Instant now);

    /**
     * Records the image an instance built: {@code image_digest} (when supplied) and the built
     * {@code commit_sha}. A {@code null} commit explicitly clears the column (the engine
     * reported no commit; the platform must not fabricate one). The digest is the input of the
     * production same-image check (J27).
     *
     * @return {@code true} when the instance row was updated
     */
    boolean recordBuiltImage(long instanceId, String imageDigest, String commitSha);

    /** Who owns the instance for lock and state purposes. */
    record InstanceContext(long instanceId, long orderId, long unitId) {
    }

    /** Build trigger coordinates; see {@link #buildContextOf(long)}. */
    record BuildContext(long unitId, String planId, String branch, String commitSha) {
    }
}