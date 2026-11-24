package com.sf.release.application.notification;

import com.sf.release.domain.pipeline.NodeType;
import com.sf.release.domain.task.ItemStatus;
import com.sf.release.domain.task.MemberRole;

/**
 * Chinese labels used in notification titles and bodies. They live in the application layer so the
 * web DTOs and the domain enums stay free of presentation strings (the page takes {@code eventLabel}
 * from {@code NotificationEventKey}, the trigger points take the rest from here).
 */
final class NotificationLabels {

    private NotificationLabels() {
    }

    static String itemStatus(String status) {
        if (status == null || status.isBlank()) {
            return "未知状态";
        }
        ItemStatus parsed;
        try {
            parsed = ItemStatus.valueOf(status);
        } catch (IllegalArgumentException ex) {
            return status;
        }
        return switch (parsed) {
            case PENDING -> "待发布";
            case BUILDING -> "构建中";
            case TESTING -> "测试中";
            case GATE_PENDING -> "待生产准入";
            case SUBMITTED -> "已提交";
            case PENDING_APPROVAL -> "待审批";
            case READY -> "待执行";
            case PROD_DEPLOYING -> "生产部署中";
            case DONE -> "已完成";
            case GATE_FAILED -> "门禁未通过";
            case FAILED -> "失败";
            case REJECTED -> "已驳回";
            case CLOSED -> "已关闭";
            case TERMINATED -> "已终止";
            case ROLLED_BACK -> "已回滚";
        };
    }

    static String memberRole(MemberRole role) {
        return switch (role) {
            case DEV, OWNER -> "研发";
            case TEST -> "测试";
            case OPS -> "运维";
            case PRODUCT -> "产品";
        };
    }

    static String node(NodeType nodeType) {
        return switch (nodeType) {
            case BUILD -> "构建";
            case TEST_DEPLOY -> "测试部署";
            case VERIFY -> "验证";
            case PROD_DEPLOY -> "生产部署";
        };
    }
}