package com.sf.release.web.dto;

import com.sf.release.application.settings.SettingsService.ApproverSettings;
import com.sf.release.application.settings.SettingsService.GateSettings;
import com.sf.release.application.settings.SettingsService.LockSettings;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

/**
 * Request and response bodies of the P6-B settings endpoints (design §3).
 *
 * <p>The gate check names travel as strings and the DTO never imports {@code CheckType}: the web
 * package may not depend on the domain layer, so the application views expose
 * {@code enabledCheckNames()} instead of the enum. A check's machine name is the contract; the
 * label belongs to the verification panel.
 *
 * <p>The three blocks mirror the stored sections one-to-one
 * ({@code enabledChecks}, {@code opsLead/deptManager}, {@code pauseBoundHours}) and carry the same
 * {@code updatedBy/updatedAt} metadata as the 「消息通知」 block.
 */
public final class SettingsDtos {

    private SettingsDtos() {
    }

    public static GateSettingsResponse gateOf(GateSettings view) {
        return new GateSettingsResponse(view.enabledCheckNames(), view.updatedBy(),
            view.updatedAt());
    }

    public static ApproverSettingsResponse approversOf(ApproverSettings view) {
        return new ApproverSettingsResponse(view.opsLead(), view.deptManager(), view.updatedBy(),
            view.updatedAt());
    }

    public static LockSettingsResponse lockOf(LockSettings view) {
        return new LockSettingsResponse(view.pauseBoundHours(), view.updatedBy(), view.updatedAt());
    }

    /** The 「门禁规则」 block: enabled check types plus who last saved it. */
    public record GateSettingsResponse(
        List<String> enabledChecks,
        String updatedBy,
        Instant updatedAt) {

        public GateSettingsResponse {
            enabledChecks = enabledChecks == null ? List.of() : List.copyOf(enabledChecks);
        }
    }

    /** Save form of the gate block; the service rejects an empty list and unknown names. */
    public record GateSettingsRequest(
        @NotNull(message = "启用检查项不能为空")
        List<String> enabledChecks) {
    }

    /** The 「审批人名单」 block: one list per approval role. */
    public record ApproverSettingsResponse(
        List<String> opsLead,
        List<String> deptManager,
        String updatedBy,
        Instant updatedAt) {

        public ApproverSettingsResponse {
            opsLead = opsLead == null ? List.of() : List.copyOf(opsLead);
            deptManager = deptManager == null ? List.of() : List.copyOf(deptManager);
        }
    }

    /** Save form of the approver block; an omitted list means "leave that role empty". */
    public record ApproverSettingsRequest(List<String> opsLead, List<String> deptManager) {
    }

    /** The 「锁超时」 block: the abandoned-pause bound in hours (the lease TTL stays 4h). */
    public record LockSettingsResponse(int pauseBoundHours, String updatedBy, Instant updatedAt) {
    }

    /** Save form of the lock block; the range is validated by the service. */
    public record LockSettingsRequest(
        @NotNull(message = "暂停上限不能为空")
        Integer pauseBoundHours) {
    }
}