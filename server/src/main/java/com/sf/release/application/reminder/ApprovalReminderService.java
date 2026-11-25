package com.sf.release.application.reminder;

import com.sf.release.application.notification.NotificationAudiences;
import com.sf.release.application.notification.NotificationCommand;
import com.sf.release.application.notification.NotificationService;
import com.sf.release.application.settings.SettingsService;
import com.sf.release.application.settings.SettingsService.ApprovalSla;
import com.sf.release.application.settings.SettingsService.ApproverSettings;
import com.sf.release.domain.approval.ApprovalRole;
import com.sf.release.domain.notification.NotificationEventKey;
import com.sf.release.infra.repo.ApprovalReminderMapper;
import com.sf.release.infra.repo.po.ApprovalReminderRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The P6-E approval-SLA scan (GAP-12, design §1/§3): finds the still-pending approval steps of each
 * order's newest admission generation and sends the two-level reminder through the P4-B notification
 * channel.
 *
 * <p><strong>Two levels, one message each.</strong> A step waiting at least {@code escalateHours} is
 * escalated to the ADMIN users plus the system owner and is <em>never</em> also reminded — escalation
 * carries the reminder's meaning (design §1). A step that is merely at least {@code remindHours} old
 * is reminded to the pending role. Because the escalation branch is checked first, a step first seen
 * after 24 hours is escalated only; because the notification unique key carries
 * {@code ref = approval:{snapshotId}:{stepNo}:REMIND|ESCALATE}, a later scan re-sends nothing. The
 * caller does not have to remember what it sent.
 *
 * <p><strong>The scan never breaks on one row.</strong> {@link NotificationService#notify} already
 * swallows notification failures; this service additionally guards the per-round config reads and
 * the per-row dispatch so one unreadable audience or malformed row cannot stop the rest of the
 * round. A failed due query is logged and yields an empty round, which the next one retries.
 *
 * <p><strong>Time is a parameter.</strong> {@link #scan(Instant, int)} takes the scan instant so the
 * tests can assert exact 11h/12h/24h boundaries without a sleep; the job supplies it from the
 * application {@link java.time.Clock}.
 *
 * <p><strong>No audit.</strong> A reminder is a channel effect whose evidence is the notification
 * row itself (FR-N-02 does not list it); the {@code SETTING_UPDATE} audit covers the threshold save.
 */
@Service
public class ApprovalReminderService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalReminderService.class);

    /** Rows per round when the caller does not supply a positive limit. */
    public static final int DEFAULT_SCAN_LIMIT = 200;

    private static final String REF_REMIND = "REMIND";
    private static final String REF_ESCALATE = "ESCALATE";

    private final ApprovalReminderMapper reminderMapper;
    private final SettingsService settings;
    private final NotificationService notifications;

    public ApprovalReminderService(ApprovalReminderMapper reminderMapper, SettingsService settings,
                                   NotificationService notifications) {
        this.reminderMapper = reminderMapper;
        this.settings = settings;
        this.notifications = notifications;
    }

    /**
     * One scan round.
     *
     * @param now   the instant the wait is measured against; {@code null} means {@link Instant#now()}
     *              (the job always passes its clock's instant)
     * @param limit the maximum number of due steps read; a non-positive value uses
     *              {@link #DEFAULT_SCAN_LIMIT}
     * @return the round's counters; {@code reminded}/{@code escalated} count the messages this round
     *     attempted, and the notification unique key — not the counter — keeps them to one row per
     *     recipient/channel/level
     */
    public ScanResult scan(Instant now, int limit) {
        Instant scanTime = now == null ? Instant.now() : now;
        ApprovalSla sla = slaForRound();
        if (!sla.enabled()) {
            log.debug("approval reminder scan skipped: the APPROVAL switch is off");
            return ScanResult.none();
        }
        int rows = limit <= 0 ? DEFAULT_SCAN_LIMIT : limit;
        List<ApprovalReminderRow> due;
        try {
            due = reminderMapper.selectDue(scanTime.minus(Duration.ofHours(sla.remindHours())), rows);
        } catch (RuntimeException ex) {
            log.error("approval reminder due query failed; the next round retries", ex);
            return ScanResult.none();
        }

        Duration remindAfter = Duration.ofHours(sla.remindHours());
        Duration escalateAfter = Duration.ofHours(sla.escalateHours());
        ApproverSettings approvers = null;
        List<String> adminUserIds = null;
        int reminded = 0;
        int escalated = 0;
        for (ApprovalReminderRow row : due) {
            if (!isComplete(row)) {
                log.warn("approval reminder row is incomplete, skipped: snapshotId={}, stepNo={}",
                    row.getSnapshotId(), row.getStepNo());
                continue;
            }
            Duration waited = Duration.between(row.getWaitingSince(), scanTime);
            try {
                if (waited.compareTo(escalateAfter) >= 0) {
                    if (adminUserIds == null) {
                        adminUserIds = adminUserIds();
                    }
                    escalate(row, waited.toHours(), adminUserIds);
                    escalated++;
                } else if (waited.compareTo(remindAfter) >= 0) {
                    if (approvers == null) {
                        approvers = approvers();
                    }
                    remind(row, waited.toHours(), approvers);
                    reminded++;
                }
            } catch (RuntimeException ex) {
                log.warn("approval reminder dispatch failed: snapshotId={}, stepNo={}, order={}",
                    row.getSnapshotId(), row.getStepNo(), row.getPublishNo(), ex);
            }
        }
        if (reminded > 0 || escalated > 0) {
            log.info("approval reminder round: due={}, reminded={}, escalated={}", due.size(),
                reminded, escalated);
        }
        return new ScanResult(due.size(), reminded, escalated);
    }

    // ------------------------------------------------------------------ messages

    private void remind(ApprovalReminderRow row, long hours, ApproverSettings approvers) {
        String publishNo = row.getPublishNo();
        notifications.notify(new NotificationCommand(
            NotificationEventKey.APPROVAL_REMIND, publishNo, publishNo,
            NotificationEventKey.APPROVAL_REMIND.label(),
            "发布单 " + publishNo + " 的「" + roleLabel(row.getRole()) + "」审批已等待 " + hours
                + " 小时，请及时处理。直达链接：/releases/" + publishNo,
            ref(row, REF_REMIND),
            NotificationAudiences.approvalReminder(row.getOwnerId(),
                effectiveApprovers(approvers, row.getRole()))));
    }

    private void escalate(ApprovalReminderRow row, long hours, List<String> adminUserIds) {
        String publishNo = row.getPublishNo();
        notifications.notify(new NotificationCommand(
            NotificationEventKey.APPROVAL_ESCALATE, publishNo, publishNo,
            NotificationEventKey.APPROVAL_ESCALATE.label(),
            "发布单 " + publishNo + " 的「" + roleLabel(row.getRole()) + "」审批已等待 " + hours
                + " 小时仍未处理，已升级通知系统管理员与系统负责人。直达链接：/releases/"
                + publishNo,
            ref(row, REF_ESCALATE),
            NotificationAudiences.approvalEscalate(row.getOwnerId(), adminUserIds)));
    }

    /** The P6-E dedupe ref (design §1): one occurrence per generation, step and level. */
    private static String ref(ApprovalReminderRow row, String level) {
        return "approval:" + row.getSnapshotId() + ":" + row.getStepNo() + ":" + level;
    }

    /**
     * The effective approver list of the pending step's role. {@link SettingsService#approvers()}
     * already applies the P6-B "empty stored list = the live {@code user_role} rows" fallback; a role
     * this build does not know yields no configured list, so the reminder falls back to the system
     * owner rather than inventing recipients.
     */
    private static List<String> effectiveApprovers(ApproverSettings approvers, String role) {
        if (approvers == null || role == null) {
            return List.of();
        }
        if (ApprovalRole.OPS_LEAD.name().equals(role)) {
            return approvers.opsLead();
        }
        if (ApprovalRole.DEPT_MANAGER.name().equals(role)) {
            return approvers.deptManager();
        }
        return List.of();
    }

    private static String roleLabel(String role) {
        if (role == null || role.isBlank()) {
            return "未知角色";
        }
        try {
            return ApprovalRole.valueOf(role).label();
        } catch (IllegalArgumentException ex) {
            // A role written by a newer build: show its machine name instead of failing the notice.
            return role;
        }
    }

    // ------------------------------------------------------------------ reads with fallback

    private ApprovalSla slaForRound() {
        try {
            return settings.approvalSla();
        } catch (RuntimeException ex) {
            log.warn("APPROVAL setting could not be read; using the enabled 12/24-hour defaults: {}",
                ex.getMessage());
            return new ApprovalSla(true, SettingsService.DEFAULT_REMIND_HOURS,
                SettingsService.DEFAULT_ESCALATE_HOURS, null, null);
        }
    }

    /**
     * The configured approver lists; a read failure degrades to empty, which routes the reminder to
     * the system owner (the same safe direction as the roleless fallback).
     */
    private ApproverSettings approvers() {
        try {
            return settings.approvers();
        } catch (RuntimeException ex) {
            log.warn("APPROVER setting could not be read; the reminder falls back to the system"
                + " owner: {}", ex.getMessage());
            return new ApproverSettings(List.of(), List.of(), null, null);
        }
    }

    /** The ADMIN users; a read failure still escalates to the system owner. */
    private List<String> adminUserIds() {
        try {
            return reminderMapper.selectAdminUserIds();
        } catch (RuntimeException ex) {
            log.warn("ADMIN user list could not be read; escalating to the system owner only: {}",
                ex.getMessage());
            return List.of();
        }
    }

    private static boolean isComplete(ApprovalReminderRow row) {
        return row != null
            && row.getSnapshotId() != null && row.getSnapshotId() > 0
            && row.getStepNo() != null
            && row.getWaitingSince() != null
            && row.getPublishNo() != null && !row.getPublishNo().isBlank();
    }

    /**
     * The counters of one round. {@code scanned} is the number of due rows the query returned;
     * {@code reminded}/{@code escalated} are the messages this round attempted (the unique key, not
     * this record, is what makes a repeated scan idempotent).
     */
    public record ScanResult(int scanned, int reminded, int escalated) {

        public static ScanResult none() {
            return new ScanResult(0, 0, 0);
        }
    }
}
