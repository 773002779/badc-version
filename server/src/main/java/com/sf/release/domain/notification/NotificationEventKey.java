package com.sf.release.domain.notification;

/**
 * The notification events with their Chinese names and the object type they point at.
 *
 * <p>The name is part of the persisted contract ({@code notification.event_key}) and of the web
 * response ({@code eventKey}); the label is what the page and the WeCom markdown show. The
 * {@link #objectType()} is fixed per event so a trigger point cannot pair an event with the wrong
 * object, and the dedupe key can be built without asking the caller.
 *
 * <p>The object type is a stable machine word and the page decides the click target from it (design
 * section 7): an ORDER row opens the 发布单, an ITEM row the 变更项, a TASK row the 发布任务, a UNIT row
 * the deployment unit, a FREEZE row the 封板 page, a WAIVER row the gate section of its 发布单.
 */
public enum NotificationEventKey {
    BUILD_FAILED("构建失败", NotificationObjectType.ORDER),
    TEST_DEPLOY_FAILED("测试部署失败", NotificationObjectType.ORDER),
    PROD_DEPLOY_FAILED("生产部署失败", NotificationObjectType.ORDER),
    CHECK_FAILED("检查不通过", NotificationObjectType.ORDER),
    TEST_ENV_READY("测试部署已就绪", NotificationObjectType.ORDER),
    APPROVAL_PENDING("待审批", NotificationObjectType.ORDER),
    APPROVAL_DECIDED("审批结果", NotificationObjectType.ORDER),
    // P6-E (GAP-12): the approval SLA scan. REMIND is the 12h notice to the pending step's role;
    // ESCALATE is the 24h notice to ADMIN + the system owner that also suppresses the reminder
    // (escalation already carries the reminder's meaning). Both point at the 发布单.
    APPROVAL_REMIND("审批待处理提醒", NotificationObjectType.ORDER),
    APPROVAL_ESCALATE("审批超时升级", NotificationObjectType.ORDER),
    GATE_INVALIDATED("门禁失效", NotificationObjectType.ORDER),
    PROD_DONE("生产完成", NotificationObjectType.ORDER),
    WAIVER_PENDING("豁免待审批", NotificationObjectType.WAIVER),
    ROLLBACK_DONE("回滚完成", NotificationObjectType.UNIT),
    FREEZE_APPLIED("封板生效", NotificationObjectType.FREEZE),
    FREEZE_RELEASED("封板解除", NotificationObjectType.FREEZE),
    LOCK_TIMEOUT_RELEASED("锁超时释放", NotificationObjectType.UNIT),
    // P5-B stop / exit / close / archive (design §5): one event per action, so a recipient can tell
    // "production was stopped" from "the pipeline was exited" without parsing the body.
    RELEASE_STOPPED("发布单已停止", NotificationObjectType.ORDER),
    RELEASE_EXITED("发布单已退出", NotificationObjectType.ORDER),
    ITEM_CLOSED("变更项已关闭", NotificationObjectType.ITEM),
    TASK_ARCHIVED("任务已归档", NotificationObjectType.TASK),
    // P6-D J11: an administrator forced a release that was still waiting for a signature. The
    // audience is the task's OPS/DEV members plus its creator; the body carries the reason.
    EMERGENCY_RELEASE("紧急放行", NotificationObjectType.ORDER);

    private final String label;
    private final NotificationObjectType objectType;

    NotificationEventKey(String label, NotificationObjectType objectType) {
        this.label = label;
        this.objectType = objectType;
    }

    /** The Chinese name shown on the page and in the WeCom message. */
    public String label() {
        return label;
    }

    /** The row's {@code object_type}. */
    public NotificationObjectType objectType() {
        return objectType;
    }

    /**
     * Parses a stored {@code event_key} defensively.
     *
     * @return the event, or {@code null} for a value this build does not know (a row written by a
     *     newer build); the query service then falls back to the raw word instead of failing a page
     */
    public static NotificationEventKey parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
