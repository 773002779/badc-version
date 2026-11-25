package com.sf.release.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sf.release.common.Masker;
import com.sf.release.domain.engine.EngineStatus;
import com.sf.release.domain.engine.JobKind;
import com.sf.release.domain.pipeline.NodeTaskStatus;
import com.sf.release.domain.pipeline.PipelineOrchestrator;
import com.sf.release.domain.pipeline.TaskType;
import com.sf.release.infra.engine.CodingProperties;
import com.sf.release.infra.engine.CodingStatusMapper;
import com.sf.release.infra.engine.WebhookVerifier;
import com.sf.release.infra.repo.EngineEventDedupMapper;
import com.sf.release.infra.repo.NodeTaskMapper;
import com.sf.release.infra.repo.po.EngineEventDedupPO;
import com.sf.release.infra.repo.po.NodeTaskPO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Receives a CODING callback (tech design §6.3): verify the HMAC over the raw body, dedup by
 * event id, map the vendor status, then hand a terminal result to the orchestrator.
 *
 * <p><strong>Order matters.</strong> Signature verification is the first statement and it works
 * on the raw bytes; a rejected request performs <em>no</em> database write. The payload is then
 * parsed against the configurable field mapping ({@code coding.webhook}), including the required
 * job {@code kind} ({@code BUILD}/{@code DEPLOY}); a missing or unknown kind is rejected as a
 * malformed delivery (HTTP 400) <em>before</em> the dedup insert, so nothing is recorded. The
 * {@code event_id} row is inserted before any task write, so a duplicate delivery is
 * acknowledged without a second advance. The dedup row and the task completion are separate
 * transactions on purpose: a crash after the insert loses the event for the callback path, but
 * the 30s reconcile query is the durable backstop (design §5.2/§6.3). The row also makes vendor
 * retries cheap.
 *
 * <p><strong>Kind scopes the lookup.</strong> The task is resolved by
 * {@code (external_id, kind)}: a {@code BUILD} callback only matches the build-chain tasks and a
 * {@code DEPLOY} callback only the deploy tasks, because the two CODING namespaces may reuse
 * numeric ids. A callback of the wrong kind finds no task and changes nothing.
 *
 * <p><strong>Status vocabulary.</strong> The word is mapped by {@link CodingStatusMapper}, i.e.
 * by {@code coding.statusMapping}; the webhook and the poll path cannot disagree. {@code
 * UNKNOWN} is non-terminal: it is logged and changes nothing. {@code QUEUED}/{@code RUNNING}
 * are observations that only refresh the reconcile poll time. {@code SUCCESS} completes the
 * task; {@code FAILED} and {@code CANCELED} fail it. {@code CANCELED} is persisted as
 * {@code FAILED} because the node-task vocabulary has no separate canceled state.
 *
 * <p><strong>No second path.</strong> Every terminal callback goes through
 * {@link PipelineOrchestrator#onTaskCompleted(long, NodeTaskStatus, String, String)}, which
 * persists the completion and advances idempotently; this service never touches node/instance
 * status itself.
 */
@Service
public class CodingWebhookAppService {

    /** Header carrying the HMAC-SHA256 signature over the raw body. */
    public static final String SIGNATURE_HEADER = WebhookVerifier.SIGNATURE_HEADER;

    private static final Logger log = LoggerFactory.getLogger(CodingWebhookAppService.class);

    private static final int MAX_EVENT_ID_LENGTH = 128;
    private static final int MAX_EXTERNAL_ID_LENGTH = 128;
    private static final int MAX_DETAIL_LENGTH = 200;
    private static final Set<NodeTaskStatus> TERMINAL_TASKS = EnumSet.of(
        NodeTaskStatus.SUCCESS, NodeTaskStatus.FAILED, NodeTaskStatus.TIMEOUT,
        NodeTaskStatus.SKIPPED);

    private final WebhookVerifier verifier;
    private final EngineEventDedupMapper dedupMapper;
    private final NodeTaskMapper taskMapper;
    private final CodingStatusMapper statusMapper;
    private final CodingProperties properties;
    private final PipelineOrchestrator orchestrator;
    private final ObjectMapper objectMapper;

    public CodingWebhookAppService(WebhookVerifier verifier,
                                   EngineEventDedupMapper dedupMapper,
                                   NodeTaskMapper taskMapper,
                                   CodingStatusMapper statusMapper,
                                   CodingProperties properties,
                                   PipelineOrchestrator orchestrator,
                                   ObjectMapper objectMapper) {
        this.verifier = verifier;
        this.dedupMapper = dedupMapper;
        this.taskMapper = taskMapper;
        this.statusMapper = statusMapper;
        this.properties = properties;
        this.orchestrator = orchestrator;
        this.objectMapper = objectMapper;
    }

    /**
     * Processes one callback delivery.
     *
     * @param rawBody          the exact request bytes (never the parsed JSON)
     * @param signatureHeader  value of the {@value #SIGNATURE_HEADER} header, may be absent
     */
    public WebhookResult receive(byte[] rawBody, String signatureHeader) {
        if (!verifier.verify(rawBody, signatureHeader)) {
            log.warn("coding webhook rejected: invalid or missing {} signature",
                SIGNATURE_HEADER);
            return new WebhookResult(Outcome.INVALID_SIGNATURE, null,
                "签名校验失败");
        }
        Payload payload;
        try {
            payload = parse(rawBody);
        } catch (MalformedPayloadException ex) {
            log.warn("coding webhook rejected: malformed payload ({})", ex.getMessage());
            return new WebhookResult(Outcome.MALFORMED, null, ex.getMessage());
        }

        try {
            dedupMapper.insert(EngineEventDedupPO.of(payload.eventId(), Instant.now(),
                verifier.payloadHash(rawBody)));
        } catch (DuplicateKeyException ex) {
            log.info("coding webhook duplicate ignored: eventId={}", payload.eventId());
            return new WebhookResult(Outcome.DUPLICATE, payload.eventId(), "重复事件");
        }

        try {
            return process(payload);
        } catch (RuntimeException ex) {
            // The event itself was accepted and recorded; the 30s reconcile is the durable
            // backstop for a completion that could not be applied right now (design §6.4:
            // the failure is logged, never silently dropped).
            log.error("coding webhook event {} could not be applied, reconcile will repair:"
                + " externalId={}, status={}, reason={}", payload.eventId(),
                payload.externalId(), payload.status(), Masker.mask(messageOf(ex)), ex);
            return new WebhookResult(Outcome.IGNORED, payload.eventId(), "处理失败，已交由对账修复");
        }
    }

    // ------------------------------------------------------------------ payload handling

    private WebhookResult process(Payload payload) {
        NodeTaskPO task = taskMapper.selectLatestByExternalId(payload.externalId(),
            taskTypesOf(payload.kind()));
        if (task == null) {
            log.warn("coding webhook event {} references no {} node_task: externalId={}",
                payload.eventId(), payload.kind(), payload.externalId());
            return new WebhookResult(Outcome.IGNORED, payload.eventId(), "任务不存在");
        }
        EngineStatus engineStatus = statusMapper.map(payload.status(), "webhook");
        NodeTaskStatus nodeStatus = nodeStatusOf(engineStatus);

        if (nodeStatus == null) {
            if (engineStatus == EngineStatus.UNKNOWN) {
                // Non-terminal on purpose: an unmapped vendor word must not advance, fail or
                // time the task out (design §6.4; Task 10 amendment). The delivery is still a
                // successful observation for bookkeeping, so the reconcile failure counter is
                // reset and the next poll is pushed out by the normal 90s window.
                log.warn("coding webhook event {} reported an unmapped status word: {}, task {}"
                    + " stays {}", payload.eventId(), payload.status(), task.getId(),
                    task.getStatus());
                taskMapper.recordQuerySuccess(task.getId(), Instant.now());
                return new WebhookResult(Outcome.IGNORED, payload.eventId(), "状态未知");
            }
            // QUEUED/RUNNING: an observation only. Moving updated_at forward keeps the 90s
            // reconcile window meaningful instead of polling immediately after a callback.
            taskMapper.recordQuerySuccess(task.getId(), Instant.now());
            log.debug("coding webhook observation: eventId={}, taskId={}, status={}",
                payload.eventId(), task.getId(), payload.status());
            return new WebhookResult(Outcome.ACCEPTED, payload.eventId(), "已记录进度");
        }

        if (isTerminal(task.getStatus())) {
            // A duplicate delivery with a different event id, or an out-of-order callback:
            // the task is already terminal and the pipeline layer must not be triggered again.
            log.warn("coding webhook event {} is late: task {} is already {}", payload.eventId(),
                task.getId(), task.getStatus());
            return new WebhookResult(Outcome.IGNORED, payload.eventId(), "任务已终态");
        }

        String message = messageOf(nodeStatus, engineStatus, payload);
        orchestrator.onTaskCompleted(task.getId(), nodeStatus, payload.digest(), message);
        log.info("coding webhook applied: eventId={}, taskId={}, engineStatus={},"
            + " taskStatus={}", payload.eventId(), task.getId(), engineStatus, nodeStatus);
        return new WebhookResult(Outcome.ACCEPTED, payload.eventId(), "已更新任务");
    }

    private Payload parse(byte[] rawBody) {
        JsonNode root;
        try {
            root = objectMapper.readTree(rawBody);
        } catch (IOException ex) {
            throw new MalformedPayloadException("请求体不是合法 JSON");
        }
        if (root == null || !root.isObject()) {
            throw new MalformedPayloadException("请求体不是 JSON 对象");
        }
        JsonNode data = root.path("data").isObject() ? root.path("data") : root;
        CodingProperties.Webhook mapping =
            properties == null || properties.getWebhook() == null
                ? new CodingProperties.Webhook() : properties.getWebhook();
        String eventId = text(root, data, mapping.getEventIdField(),
            "eventId", "event_id", "id");
        String externalId = text(root, data, mapping.getExternalIdField(),
            "externalId", "external_id", "jobId", "job_id");
        String status = text(root, data, mapping.getStatusField(),
            "status", "state", "statusWord");
        String kindWord = text(root, data, mapping.getKindField(),
            "kind", "jobKind", "job_kind");
        String digest = text(root, data, mapping.getDigestField(),
            "digest", "imageDigest", "image_digest");
        String message = text(root, data, mapping.getMessageField(), "message", "reason");
        if (eventId == null) {
            throw new MalformedPayloadException("缺少 eventId");
        }
        if (externalId == null) {
            throw new MalformedPayloadException("缺少 externalId");
        }
        if (status == null) {
            throw new MalformedPayloadException("缺少 status");
        }
        if (kindWord == null) {
            throw new MalformedPayloadException("缺少 kind（BUILD/DEPLOY），拒绝内联猜测任务类型");
        }
        JobKind kind = parseKind(kindWord);
        if (eventId.length() > MAX_EVENT_ID_LENGTH) {
            throw new MalformedPayloadException("eventId 长度超限");
        }
        if (externalId.length() > MAX_EXTERNAL_ID_LENGTH) {
            throw new MalformedPayloadException("externalId 长度超限");
        }
        return new Payload(eventId, externalId, status, kind, digest, message);
    }

    /** {@code BUILD}/{@code DEPLOY}/{@code PUBLISH}, case-insensitive; anything else is invalid. */
    private static JobKind parseKind(String raw) {
        try {
            return JobKind.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new MalformedPayloadException(
                "kind 不合法：" + raw + "（期望 BUILD、DEPLOY 或 PUBLISH）");
        }
    }

    /**
     * The node-task types of one engine job family: a {@code BUILD} callback can only resolve
     * the build chain, a {@code DEPLOY} callback only the deploy tasks, a {@code PUBLISH}
     * callback only the library artifact upload (P6-A). This is the same split the reconcile
     * query uses, expressed in the task vocabulary the webhook looks up.
     */
    static List<String> taskTypesOf(JobKind kind) {
        return switch (kind) {
            case BUILD -> List.of(TaskType.CODE_CHECK.name(), TaskType.PKG_BUILD.name(),
                TaskType.IMG_BUILD.name());
            case DEPLOY -> List.of(TaskType.DEPLOY.name(), TaskType.ROLLBACK.name());
            case PUBLISH -> List.of(TaskType.LIB_PUBLISH.name());
        };
    }

    private static NodeTaskStatus nodeStatusOf(EngineStatus status) {
        return switch (status) {
            case SUCCESS -> NodeTaskStatus.SUCCESS;
            case FAILED, CANCELED -> NodeTaskStatus.FAILED;
            case QUEUED, RUNNING, UNKNOWN -> null;
        };
    }

    private static String messageOf(NodeTaskStatus nodeStatus, EngineStatus engineStatus,
                                    Payload payload) {
        if (nodeStatus == NodeTaskStatus.SUCCESS) {
            return null;
        }
        if (payload.message() != null) {
            return payload.message();
        }
        return "引擎状态：" + engineStatus + "（原始状态词：" + payload.status() + "）";
    }

    private static boolean isTerminal(String status) {
        if (status == null || status.isBlank()) {
            return false;
        }
        try {
            return TERMINAL_TASKS.contains(NodeTaskStatus.valueOf(status));
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static String text(JsonNode root, JsonNode data, String preferred, String... aliases) {
        String value = textOf(root.get(preferred));
        if (value == null) {
            value = textOf(data.get(preferred));
        }
        if (value != null) {
            return value;
        }
        for (String name : aliases) {
            value = textOf(root.get(name));
            if (value == null) {
                value = textOf(data.get(name));
            }
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String textOf(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isValueNode()) {
            return null;
        }
        String value = node.asText();
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String messageOf(RuntimeException ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank()
            ? ex.getClass().getSimpleName() : message;
    }

    /** Contract outcome exposed to the webhook caller. */
    public enum Outcome {
        ACCEPTED,
        DUPLICATE,
        IGNORED,
        INVALID_SIGNATURE,
        MALFORMED
    }

    /** Acknowledgement returned to CODING; {@code detail} is operator-facing, never the body. */
    public record WebhookResult(Outcome outcome, String eventId, String detail) {

        public WebhookResult {
            detail = detail == null ? null
                : detail.substring(0, Math.min(detail.length(), MAX_DETAIL_LENGTH));
        }
    }

    /** Canonical payload of the callback; see the report for the documented field aliases. */
    private record Payload(String eventId, String externalId, String status, JobKind kind,
                           String digest, String message) {
    }

    /** A verification-passing body that cannot be mapped to the contract. */
    private static final class MalformedPayloadException extends RuntimeException {

        private MalformedPayloadException(String message) {
            super(message);
        }
    }
}