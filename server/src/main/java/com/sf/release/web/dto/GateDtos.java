package com.sf.release.web.dto;

import com.sf.release.application.GateService;
import com.sf.release.application.GateService.Admission;
import com.sf.release.application.GateService.CheckGroup;
import com.sf.release.application.GateService.CheckView;
import com.sf.release.application.GateService.GateView;
import com.sf.release.application.GateService.WaiverView;

import java.time.Instant;
import java.util.List;

/**
 * Response bodies of the gate endpoints (P2). The web layer owns the wire shape — field names, null
 * semantics and ordering — while {@link GateService}'s views stay HTTP-agnostic, exactly like
 * {@link ReleaseDtos}.
 *
 * <p><strong>Null fields are meaningful and stay present.</strong> A check that never ran has
 * {@code null} timestamps; {@code waiver} is {@code null} when the item has no waiver;
 * {@code check.reportUrl} is {@code null} until the engine reports one. The page distinguishes
 * "absent" from "empty string", so the fields are never defaulted here.
 *
 * <p><strong>No enum escapes.</strong> Conclusions, statuses and severities are sent twice: the
 * stable machine value (for filtering and colouring) and the Chinese label (for display). The page
 * therefore never has to keep its own copy of the vocabulary.
 */
public final class GateDtos {

    private GateDtos() {
    }

    public static GateViewView of(GateView view) {
        return new GateViewView(
            view.itemId(),
            view.crid(),
            view.itemStatus(),
            view.gateStatus(),
            view.conclusion(),
            view.conclusionLabel(),
            view.maxSeverity(),
            view.maxSeverityLabel(),
            view.passed(),
            view.total(),
            view.blocking(),
            view.waivable(),
            view.summary(),
            view.reasons(),
            view.testStatus(),
            view.test(),
            view.testPassBy(),
            view.testPassAt(),
            view.testFailReason(),
            view.groups().stream().map(GateDtos::groupOf).toList(),
            view.waiver() == null ? null : waiverOf(view.waiver()),
            admissionOf(view.admission()),
            view.instanceId(),
            view.imageDigest(),
            view.commitSha());
    }

    public static AdmissionView admissionOf(Admission admission) {
        return new AdmissionView(admission.eligible(), admission.missing(),
            admission.checkConclusion(), admission.checkSummary(), admission.test());
    }

    private static CheckGroupView groupOf(CheckGroup group) {
        return new CheckGroupView(group.stage(), group.stageLabel(), group.passed(), group.total(),
            group.checks().stream().map(GateDtos::checkOf).toList());
    }

    private static CheckViewView checkOf(CheckView check) {
        return new CheckViewView(
            check.kind(),
            check.label(),
            check.stage(),
            check.status(),
            check.statusLabel(),
            check.severity(),
            check.severityLabel(),
            check.issues(),
            check.scannedObject(),
            check.summary(),
            check.reportUrl(),
            check.startedAt(),
            check.finishedAt(),
            check.deadlineAt(),
            check.passed(),
            check.blocking(),
            check.waiverHint());
    }

    private static WaiverViewView waiverOf(WaiverView waiver) {
        return new WaiverViewView(
            waiver.id(),
            waiver.status(),
            waiver.statusLabel(),
            waiver.reason(),
            waiver.impact(),
            waiver.requestedBy(),
            waiver.requestedAt(),
            waiver.approvedBy(),
            waiver.approvedAt(),
            waiver.decideReason(),
            waiver.expireAt(),
            waiver.active());
    }

    /** The whole verification panel of one 变更项. */
    public record GateViewView(
        long itemId,
        String crid,
        String itemStatus,
        String gateStatus,
        String conclusion,
        String conclusionLabel,
        String maxSeverity,
        String maxSeverityLabel,
        int passed,
        int total,
        int blocking,
        boolean waivable,
        String summary,
        List<String> reasons,
        String testStatus,
        String test,
        String testPassBy,
        Instant testPassAt,
        String testFailReason,
        List<CheckGroupView> groups,
        WaiverViewView waiver,
        AdmissionView admission,
        long instanceId,
        String imageDigest,
        String commitSha) {
    }

    /** The checks of one stage ({@code IMAGE} / {@code ENV}) and their pass count. */
    public record CheckGroupView(String stage, String stageLabel, int passed, int total,
                                 List<CheckViewView> checks) {
    }

    /** One check row: machine values plus labels, never a bare enum. */
    public record CheckViewView(
        String kind,
        String label,
        String stage,
        String status,
        String statusLabel,
        String severity,
        String severityLabel,
        int issues,
        String scannedObject,
        String summary,
        String reportUrl,
        Instant startedAt,
        Instant finishedAt,
        Instant deadlineAt,
        boolean passed,
        boolean blocking,
        String waiverHint) {
    }

    /** The item's waiver (only the newest one), including whether it still counts. */
    public record WaiverViewView(
        long id,
        String status,
        String statusLabel,
        String reason,
        String impact,
        String requestedBy,
        Instant requestedAt,
        String approvedBy,
        Instant approvedAt,
        String decideReason,
        Instant expireAt,
        boolean active) {
    }

    /** The production admission: eligible plus every reason it is not. */
    public record AdmissionView(boolean eligible, List<String> missing, String checkConclusion,
                                String checkSummary, String test) {

        public AdmissionView {
            missing = missing == null ? List.of() : List.copyOf(missing);
        }
    }

    /** Body of the test-fail and test-revoke actions. */
    public record ReasonRequest(String reason) {
    }

    /** Body of a waiver request: why it is acceptable, its impact and its expiry. */
    public record WaiverRequest(String reason, String impact, Instant expireAt) {
    }

    /** Body of a waiver approval/rejection; the note is required for a rejection. */
    public record WaiverDecisionRequest(String note) {
    }
}