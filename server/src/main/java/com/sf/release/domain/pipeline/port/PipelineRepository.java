package com.sf.release.domain.pipeline.port;

import com.sf.release.domain.pipeline.InstanceStatus;
import com.sf.release.domain.pipeline.NodeStatus;
import com.sf.release.domain.pipeline.NodeTask;
import com.sf.release.domain.pipeline.NodeTaskStatus;
import com.sf.release.domain.pipeline.NodeType;
import com.sf.release.domain.pipeline.PipelineInstance;

import java.time.Instant;
import java.util.List;

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

    /**
     * The most recent instance of a 变更项 (the newest release order's newest instance), with its
     * nodes and tasks, or {@code null} when the item has never been published. Used by the rerun
     * planner to find the resume node and by the rerun service to reuse the built image.
     */
    PipelineInstance latestInstanceOf(long itemId);

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
     * Claims one task for an engine trigger: a compare-and-set {@code PENDING → RUNNING}. Returns
     * {@code true} only for the caller that won, so a handler can call the engine <em>after</em>
     * claiming and a concurrent callback cannot trigger the same task twice. A task that is already
     * running, waiting or terminal is never claimed again.
     */
    boolean claimTaskStart(long taskId);

    /**
     * Completes the node only when, at the moment of the update, every task of the node is
     * {@code SUCCESS} or {@code SKIPPED} and none is {@code FAILED}/{@code TIMEOUT}. The guard is
     * part of the SQL (not an in-memory check only) because task completions persist outside the
     * instance lock.
     */
    boolean finishNodeIfComplete(long nodeId, Instant now);

    /**
     * Completes a node whose completion its {@code NodeHandler} decided (the {@code VERIFY} node:
     * the gate decides, the {@code CHECK} task rows are only evidence). Unlike
     * {@link #finishNodeIfComplete(long, Instant)} this does not require a counted non-{@code CHECK}
     * task, because such a node has none by construction; the guard still refuses to complete while
     * any latest counted attempt is not {@code SUCCESS}/{@code SKIPPED}.
     *
     * @return {@code true} when this call completed the node
     */
    boolean finishNodeSatisfiedByHandler(long nodeId, Instant now);

    /** {@code RUNNING → status} for the node, used for the failure transition. */
    boolean finishNode(long nodeId, NodeStatus status, Instant now);

    /** Marks the still-{@code PENDING} tasks of one node {@code SKIPPED}, leaving other rows alone. */
    boolean skipPendingTasks(long instanceId, NodeType nodeType, Instant now);

    /** {@code PENDING/RUNNING → status} for the instance, setting {@code end_at}. */
    boolean finishInstance(long instanceId, InstanceStatus status, Instant now);

    /**
     * {@code PENDING/RUNNING → FAILED} with the operator-facing reason stored in
     * {@code status_reason}.
     *
     * <p>Requirements §4.9/FR-G-05: a stopped pipeline must be explainable from its row, not from a
     * log line. A failure without a reason ("节点失败") leaves the operator guessing whether to rerun,
     * fix the gate or escalate, so the same column that explains a {@code PAUSED} instance also
     * explains a failed one.
     *
     * @return {@code true} when this call marked the instance failed
     */
    boolean failInstance(long instanceId, String reason, Instant now);

    /**
     * Stops a running instance because it would have to enter {@code blockedNode} without a
     * registered handler: the status becomes {@link InstanceStatus#PAUSED}, {@code currentNode}
     * stays (or becomes) the blocked node, {@code status_reason} explains it and {@code end_at}
     * is set. The compare-and-set only matches a {@code PENDING}/{@code RUNNING} instance, so a
     * second caller cannot overwrite a terminal status with a pause.
     *
     * @return {@code true} when this call paused the instance
     */
    boolean pauseInstance(long instanceId, NodeType blockedNode, String reason, Instant now);

    /**
     * {@code PAUSED → RUNNING}: resumes an instance an operator explicitly released (a gate that
     * blocked the next node has been satisfied again, or the paused node now has a handler). The
     * compare-and-set only matches a {@code PAUSED} row, so a resume raced against another one is a
     * no-op; {@code status_reason} and {@code end_at} are cleared because the instance is live
     * again.
     *
     * @return {@code true} when this call resumed the instance
     */
    boolean resumeInstance(long instanceId, Instant now);

    /**
     * The build coordinates of an instance: the deployment unit, the CODING plan (the unit's
     * enabled {@code BUILD} binding), the item's read-only source branch and the commit the
     * pipeline already knows. The commit is never the item's pre-build {@code base_commit};
     * it is {@code null} until an engine trigger reports one (the engine then builds the
     * branch head). Unresolvable parts are {@code 0}/blank instead of failing the pipeline.
     */
    BuildContext buildContextOf(long instanceId);

    /**
     * The deploy coordinates of an instance's {@code TEST_DEPLOY} node: the deployment unit, the
     * publish number of its release order, the unit's enabled {@code TEST_DEPLOY} binding (the
     * deploy plan) and the {@code env_kind = TEST} DEs it is configured for. Unresolvable parts
     * are {@code 0}/blank/an empty list instead of failing the pipeline.
     */
    DeployContext deployContextOf(long instanceId);

    /**
     * The deploy coordinates of an instance's {@code PROD_DEPLOY} node: the deployment unit, the
     * publish number of its release order, the unit's enabled {@code PROD_DEPLOY} binding (the
     * deploy plan) and the production-family DEs it is configured for ({@code PROD}/{@code DR}/
     * {@code BLUE}, ordered by batch then id so a batch is a stable set). Unresolvable parts are
     * {@code 0}/blank/an empty list instead of failing the pipeline.
     */
    DeployContext prodDeployContextOf(long instanceId);

    /**
     * Durably claims the once-only {@code TestEnvReadyEvent} publication of an instance: the
     * first caller sets {@code pipeline_instance.test_ready_at} and wins, every later caller —
     * another thread, another application instance, a retry — loses. The compare-and-set is the
     * exactly-once guarantee because the completion hook that observes readiness runs outside
     * the instance lock and an in-process flag would not survive a restart.
     *
     * @return {@code true} when this call claimed the publication
     */
    boolean claimTestReady(long instanceId);

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

    /** Test-deploy coordinates; see {@link #deployContextOf(long)}. */
    record DeployContext(long unitId, String publishNo, String planId,
                         List<DeployTarget> targets) {

        public DeployContext {
            targets = targets == null ? List.of() : List.copyOf(targets);
        }

        /** The DE with {@code deId}, or {@code null} when the unit no longer configures it. */
        public DeployTarget targetOf(long deId) {
            for (DeployTarget target : targets) {
                if (target.deId() == deId) {
                    return target;
                }
            }
            return null;
        }
    }

    /**
     * One TEST DE a {@code DEPLOY} task can target: the {@code env_de} id stored as
     * {@code node_task.target_id}, its environment family and its concrete name.
     */
    record DeployTarget(long deId, String envKind, String deName) {
    }
}