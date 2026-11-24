package com.sf.release.infra.repo.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * Persistence object for {@code notification_setting} (V26). The table holds exactly one row
 * ({@code id = 1}); the platform has one WeCom robot in this phase (design section 9: one webhook
 * per tenant, per-system robots are phase two).
 */
@TableName("notification_setting")
public class NotificationSettingPO {

    @TableId(value = "id", type = IdType.INPUT)
    private Integer id;

    /** {@code TINYINT} in the schema; {@code Boolean} keeps the domain meaning. */
    private Boolean wecomEnabled;

    private String wecomWebhook;
    private String updatedBy;
    private Instant updatedAt;

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public Boolean getWecomEnabled() {
        return wecomEnabled;
    }

    public void setWecomEnabled(Boolean wecomEnabled) {
        this.wecomEnabled = wecomEnabled;
    }

    public String getWecomWebhook() {
        return wecomWebhook;
    }

    public void setWecomWebhook(String wecomWebhook) {
        this.wecomWebhook = wecomWebhook;
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