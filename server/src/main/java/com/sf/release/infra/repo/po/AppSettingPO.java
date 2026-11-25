package com.sf.release.infra.repo.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * Persistence object for {@code app_setting} (V30, P6-B design §1).
 *
 * <p>One row per settings section ({@code GATE} / {@code APPROVER} / {@code LOCK}). The JSON column
 * stays a {@link String} at this layer: the service serializes and parses the section payload with
 * the application {@code ObjectMapper}, so MyBatis never has to own a Jackson type handler — the
 * same choice {@code AuditLogPO} makes for its JSON columns.
 */
@TableName("app_setting")
public class AppSettingPO {

    @TableId(value = "setting_key", type = IdType.INPUT)
    private String settingKey;

    private String valueJson;

    private String updatedBy;

    private Instant updatedAt;

    public String getSettingKey() {
        return settingKey;
    }

    public void setSettingKey(String settingKey) {
        this.settingKey = settingKey;
    }

    public String getValueJson() {
        return valueJson;
    }

    public void setValueJson(String valueJson) {
        this.valueJson = valueJson;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}