package com.sf.release.domain.audit;

import java.util.Locale;

/**
 * The audit actions of the platform (audit design section 1).
 *
 * <p>Each action carries three facts the write path needs and the web layer must not duplicate:
 * its Chinese label, the <em>default</em> object type it is about, and whether it is a platform-level
 * action (both scope columns stay empty, so only an ALL-scope caller can read it). A trigger may name
 * a more specific object type than the default — {@link #RESOURCE_UPDATE} covers subsystems, units
 * and tasks, and {@link #SETTING_UPDATE} covers the CODING connection and the notification block —
 * which is why the command carries the object type as well.
 *
 * <p><strong>The enum grows with P5.</strong> The daily/stop/exit/close/archive actions are declared
 * here and written by their use cases (P5-A daily start, P5-B stop/exit/close/archive); the
 * force-unlock action is still a placeholder for GAP-11. Adding an action later is an enum value and
 * a trigger point; the table does not change.
 *
 * <p>{@link #STOP} is the production-flow action ("停止发布") and is separate from {@link #DAILY_STOP}
 * ("日常发布停止"): the two stop different pipelines with different cascades, so one row must not
 * describe both.
 */
public enum AuditAction {

    PUBLISH("发起发布", AuditObjectType.ORDER, false),
    SUBMIT_PROD("提交生产", AuditObjectType.ORDER, false),
    EXECUTE_PROD("生产执行", AuditObjectType.ORDER, false),
    RERUN("重新发布", AuditObjectType.ORDER, false),

    APPROVE("审批通过", AuditObjectType.ORDER, false),
    REJECT("审批驳回", AuditObjectType.ORDER, false),

    GATE_TEST_PASS("测试通过", AuditObjectType.ITEM, false),
    GATE_TEST_FAIL("测试不通过", AuditObjectType.ITEM, false),
    GATE_TEST_REVOKE("撤回测试通过", AuditObjectType.ITEM, false),
    GATE_REFRESH("刷新检查", AuditObjectType.ITEM, false),

    WAIVER_REQUEST("申请豁免", AuditObjectType.WAIVER, false),
    WAIVER_APPROVE("批准豁免", AuditObjectType.WAIVER, false),
    WAIVER_REJECT("驳回豁免", AuditObjectType.WAIVER, false),

    FREEZE_CREATE("创建封板", AuditObjectType.FREEZE, false),
    FREEZE_RELEASE("解除封板", AuditObjectType.FREEZE, false),
    FREEZE_EXPIRE("封板到期", AuditObjectType.FREEZE, false),

    ROLLBACK_EXECUTE("执行回滚", AuditObjectType.ROLLBACK, false),

    SETTING_UPDATE("更新设置", AuditObjectType.SETTING, true),
    WINDOW_UPDATE("更新发布窗口", AuditObjectType.SETTING, true),
    RESOURCE_UPDATE("更新资源", AuditObjectType.SUBSYSTEM, false),

    // ---------------------------------------------------------------- P5 actions
    DAILY_START("日常发布开始", AuditObjectType.ORDER, false),
    DAILY_STOP("日常发布停止", AuditObjectType.ORDER, false),
    STOP("停止发布", AuditObjectType.ORDER, false),
    EXIT("退出发布", AuditObjectType.ORDER, false),
    CLOSE("关闭发布", AuditObjectType.ORDER, false),
    ARCHIVE("归档发布", AuditObjectType.ORDER, false),
    LOCK_FORCE_RELEASE("强制解除单元锁", AuditObjectType.UNIT, false),
    /**
     * The J11 break-glass release (P6-D design §1/§2): only an administrator may force a release
     * that is still waiting for a signature to {@code READY}. One row per use, with the mandatory
     * reason and the before/after status, so every use stays visible.
     */
    EMERGENCY_RELEASE("紧急放行", AuditObjectType.ORDER, false);

    private final String label;
    private final AuditObjectType objectType;
    private final boolean platformLevel;

    AuditAction(String label, AuditObjectType objectType, boolean platformLevel) {
        this.label = label;
        this.objectType = objectType;
        this.platformLevel = platformLevel;
    }

    /** The Chinese action name the audit page shows. */
    public String label() {
        return label;
    }

    /** The object type a trigger writes unless it names a more specific one. */
    public AuditObjectType objectType() {
        return objectType;
    }

    /** Whether the action is platform-wide when no object scope is supplied. */
    public boolean platformLevel() {
        return platformLevel;
    }

    /**
     * The action named by {@code raw}, or {@code null} when the value is blank or unknown.
     * Matching is case-insensitive, matching the other API-string parsers of the platform.
     */
    public static AuditAction parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}