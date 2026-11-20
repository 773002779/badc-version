package com.sf.release.common;

/**
 * Business error catalogue. Codes are part of the API contract and must not be renumbered.
 */
public enum BizError {
    PARAM_INVALID(40000, "参数不合法"),
    STATE_NOT_ALLOWED(40001, "状态不允许该操作"),
    GATE_BLOCKED(40002, "门禁未通过"),
    OUT_OF_WINDOW(40003, "不在发布窗口内且未审批"),
    FREEZE_REQUIRES_DOUBLE_SIGN(40004, "封板期需双签"),
    OA_TICKET_INVALID(40005, "OA 工单号非法"),
    UNIT_LOCKED(40006, "部署单元已被占用"),
    DIGEST_MISMATCH(40007, "镜像与测试不一致"),
    SELF_APPROVAL_FORBIDDEN(40008, "禁止自审批"),
    FORBIDDEN(40009, "无权执行该操作"),
    DUPLICATE_NAME(40010, "名称已存在"),
    UNIT_NOT_CONFIGURED(40011, "部署单元未配置"),
    ENGINE_UNAVAILABLE(50001, "引擎不可用"),
    ENGINE_TIMEOUT(50002, "引擎调用超时");

    private final int code;
    private final String message;

    BizError(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}