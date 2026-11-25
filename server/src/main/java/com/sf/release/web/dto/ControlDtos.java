package com.sf.release.web.dto;

import com.sf.release.application.ReleaseControlService;
import com.sf.release.application.ReleaseControlService.ArchiveResult;
import com.sf.release.application.ReleaseControlService.ControlResult;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request and response bodies of the 停止 / 退出 / 关闭 / 归档 endpoints (P5-B design §4).
 *
 * <p>The web layer owns the wire shape while the application results stay HTTP-agnostic. Every
 * status travels as its machine string, so this class imports no domain or infrastructure enum
 * (the web package may only depend on {@code application} and {@code common}).
 *
 * <p><strong>The reason is required.</strong> All four actions are destructive, so the body is
 * always {@code {reason}}: a blank value is a 40000 from bean validation, and the service re-checks
 * the same rule and the 200-character cap so a non-HTTP caller gets exactly the same contract.
 *
 * <p><strong>Null fields are meaningful.</strong>
 * <ul>
 *   <li>{@code status} is the mirror of the item status on the 发布单, i.e. {@code TERMINATED} or
 *       {@code CLOSED}; it is {@code null} for a 变更项 that was never published (a close has no
 *       order to mirror on);</li>
 *   <li>{@code itemStatus} is the 变更项 status after the action;</li>
 *   <li>{@code terminatedPipelineNo} is the pipeline number of the instance the action terminated,
 *       {@code null} when no instance was in flight (for example a stop of an item whose execution
 *       already ended).</li>
 * </ul>
 */
public final class ControlDtos {

    private ControlDtos() {
    }

    /** The shared body of the four actions: a mandatory operator reason (1..200 characters). */
    public record ReasonRequest(
        @NotBlank(message = "原因不能为空")
        @Size(max = 200, message = "原因长度不能超过 200")
        String reason) {
    }

    /** {@code {status, itemStatus, terminatedPipelineNo?}} of stop/exit/close. */
    public record ControlResponse(String status, String itemStatus, String terminatedPipelineNo) {
    }

    /** {@code {taskStatus, closedItems, terminatedInstances}} of archive. */
    public record ArchiveResponse(String taskStatus, int closedItems, int terminatedInstances) {
    }

    public static ControlResponse of(ControlResult result) {
        return new ControlResponse(result.status(), result.itemStatus(),
            result.terminatedPipelineNo());
    }

    public static ArchiveResponse of(ArchiveResult result) {
        return new ArchiveResponse(result.taskStatus(), result.closedItems(),
            result.terminatedInstances());
    }
}