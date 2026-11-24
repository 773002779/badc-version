package com.sf.release.audit;

import com.sf.release.domain.audit.AuditAction;
import com.sf.release.domain.audit.AuditObjectType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The audit vocabulary (design section 1/2): every wired action has its Chinese label, its default
 * object type and its platform flag; the P5 placeholders exist but claim no trigger yet; the API
 * parsers are case-insensitive and answer {@code null} for an unknown value (the query service turns
 * that into 40000).
 */
class AuditActionTest {

    @Test
    void every_wired_action_carries_a_label_and_an_object_type() {
        assertThat(AuditAction.PUBLISH.label()).isEqualTo("发起发布");
        assertThat(AuditAction.PUBLISH.objectType()).isEqualTo(AuditObjectType.ORDER);
        assertThat(AuditAction.SUBMIT_PROD.objectType()).isEqualTo(AuditObjectType.ORDER);
        assertThat(AuditAction.EXECUTE_PROD.objectType()).isEqualTo(AuditObjectType.ORDER);
        assertThat(AuditAction.RERUN.objectType()).isEqualTo(AuditObjectType.ORDER);
        assertThat(AuditAction.APPROVE.objectType()).isEqualTo(AuditObjectType.ORDER);
        assertThat(AuditAction.REJECT.objectType()).isEqualTo(AuditObjectType.ORDER);
        assertThat(AuditAction.GATE_TEST_PASS.objectType()).isEqualTo(AuditObjectType.ITEM);
        assertThat(AuditAction.GATE_TEST_FAIL.objectType()).isEqualTo(AuditObjectType.ITEM);
        assertThat(AuditAction.GATE_TEST_REVOKE.objectType()).isEqualTo(AuditObjectType.ITEM);
        assertThat(AuditAction.GATE_REFRESH.objectType()).isEqualTo(AuditObjectType.ITEM);
        assertThat(AuditAction.WAIVER_REQUEST.objectType()).isEqualTo(AuditObjectType.WAIVER);
        assertThat(AuditAction.WAIVER_APPROVE.objectType()).isEqualTo(AuditObjectType.WAIVER);
        assertThat(AuditAction.WAIVER_REJECT.objectType()).isEqualTo(AuditObjectType.WAIVER);
        assertThat(AuditAction.FREEZE_CREATE.objectType()).isEqualTo(AuditObjectType.FREEZE);
        assertThat(AuditAction.FREEZE_RELEASE.objectType()).isEqualTo(AuditObjectType.FREEZE);
        assertThat(AuditAction.FREEZE_EXPIRE.objectType()).isEqualTo(AuditObjectType.FREEZE);
        assertThat(AuditAction.ROLLBACK_EXECUTE.objectType()).isEqualTo(AuditObjectType.ROLLBACK);
        assertThat(AuditAction.SETTING_UPDATE.platformLevel()).isTrue();
        assertThat(AuditAction.WINDOW_UPDATE.platformLevel()).isTrue();
        assertThat(AuditAction.RESOURCE_UPDATE.platformLevel()).isFalse();
    }

    @Test
    void the_p5_actions_are_declared_but_not_part_of_the_wired_set() {
        assertThat(AuditAction.DAILY_START).isNotNull();
        assertThat(AuditAction.DAILY_STOP).isNotNull();
        assertThat(AuditAction.EXIT).isNotNull();
        assertThat(AuditAction.CLOSE).isNotNull();
        assertThat(AuditAction.ARCHIVE).isNotNull();
        assertThat(AuditAction.LOCK_FORCE_RELEASE).isNotNull();
    }

    @Test
    void parsing_is_case_insensitive_and_an_unknown_value_is_null() {
        assertThat(AuditAction.parse("publish")).isEqualTo(AuditAction.PUBLISH);
        assertThat(AuditAction.parse(" GATE_TEST_PASS ")).isEqualTo(AuditAction.GATE_TEST_PASS);
        assertThat(AuditAction.parse("NOT_AN_ACTION")).isNull();
        assertThat(AuditAction.parse("  ")).isNull();

        assertThat(AuditObjectType.parse("order")).isEqualTo(AuditObjectType.ORDER);
        assertThat(AuditObjectType.parse("NOT_A_TYPE")).isNull();
    }
}