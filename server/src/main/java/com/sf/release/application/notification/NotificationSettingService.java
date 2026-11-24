package com.sf.release.application.notification;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sf.release.application.audit.AuditCommand;
import com.sf.release.application.audit.AuditService;
import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.domain.audit.AuditAction;
import com.sf.release.infra.repo.NotificationSettingMapper;
import com.sf.release.infra.repo.po.NotificationSettingPO;
import com.sf.release.infra.security.UserContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The 「消息通知」 settings block (design section 5/6): the WeCom robot switch and webhook.
 *
 * <p>The webhook is a sensitive deployment configuration. It is returned as-is to a
 * {@code setting:manage} caller (the page edits it, like the CODING connection) but the dispatch log
 * only names its host.
 *
 * <p>Validation is deliberately small in this phase: a non-blank webhook must fit the column and start
 * with {@code https://}; a plain {@code http://} address is accepted only when the deployment sets
 * {@code release.notification.allow-insecure-http=true} (the engine's {@code allow-insecure-http}
 * pattern, needed for an internal robot or a local test server). The switch may be off with no
 * webhook; switching it on without an address is a parameter error instead of a silently dead channel.
 */
@Service
public class NotificationSettingService {

    /** The single {@code notification_setting} row (V26 seed). */
    static final int SETTING_ID = 1;

    /** {@code wecom_webhook} is {@code VARCHAR(512)}. */
    static final int MAX_WEBHOOK_LENGTH = 512;

    private final NotificationSettingMapper settingMapper;
    private final boolean allowInsecureHttp;
    private final AuditService audit;

    public NotificationSettingService(NotificationSettingMapper settingMapper,
                                      @Value("${release.notification.allow-insecure-http:false}")
                                      boolean allowInsecureHttp,
                                      AuditService audit) {
        this.settingMapper = settingMapper;
        this.allowInsecureHttp = allowInsecureHttp;
        this.audit = audit;
    }

    /** The current setting; the seeded row is the default (WeCom off, no webhook). */
    public NotificationSettingView view() {
        NotificationSettingPO po = settingMapper.selectById(SETTING_ID);
        if (po == null) {
            return new NotificationSettingView(false, null, null, null);
        }
        return new NotificationSettingView(Boolean.TRUE.equals(po.getWecomEnabled()),
            po.getWecomWebhook(), po.getUpdatedBy(), po.getUpdatedAt());
    }

    /**
     * Saves the block.
     *
     * @param enabled   the WeCom switch; {@code null} is treated as off
     * @param webhook   the robot address; blank clears it
     * @throws BizException 40000 a blank address while the switch is on, an address that is not
     *                      {@code https://} (unless the deployment allows plain HTTP), or one longer
     *                      than the column
     */
    public NotificationSettingView save(Boolean enabled, String webhook) {
        boolean on = Boolean.TRUE.equals(enabled);
        String hook = requireWebhook(on, webhook);
        NotificationSettingView before = view();
        String actor = UserContext.current().userId();
        Instant now = Instant.now();
        int rows = settingMapper.update(null, Wrappers.<NotificationSettingPO>lambdaUpdate()
            .eq(NotificationSettingPO::getId, SETTING_ID)
            .set(NotificationSettingPO::getWecomEnabled, on)
            .set(NotificationSettingPO::getWecomWebhook, hook)
            .set(NotificationSettingPO::getUpdatedBy, actor)
            .set(NotificationSettingPO::getUpdatedAt, now));
        if (rows == 0) {
            // The seed row is part of V26; this only repairs a schema that lost it.
            NotificationSettingPO po = new NotificationSettingPO();
            po.setId(SETTING_ID);
            po.setWecomEnabled(on);
            po.setWecomWebhook(hook);
            po.setUpdatedBy(actor);
            po.setUpdatedAt(now);
            settingMapper.insert(po);
        }
        NotificationSettingView saved = view();
        Map<String, Object> beforeValues = new LinkedHashMap<>();
        beforeValues.put("wecomEnabled", before.wecomEnabled());
        beforeValues.put("wecomWebhook", before.wecomWebhook());
        Map<String, Object> afterValues = new LinkedHashMap<>();
        afterValues.put("wecomEnabled", saved.wecomEnabled());
        afterValues.put("wecomWebhook", saved.wecomWebhook());
        audit.record(AuditCommand.of(AuditAction.SETTING_UPDATE, "NOTIFICATION", "消息通知设置",
                "更新消息通知设置（企微通知" + (saved.wecomEnabled() ? "开启" : "关闭") + "）")
            .before(beforeValues)
            .after(afterValues)
            .build());
        return saved;
    }

    private String requireWebhook(boolean enabled, String raw) {
        String hook = trimToNull(raw);
        if (hook == null) {
            if (enabled) {
                throw new BizException(BizError.PARAM_INVALID, "开启企微通知时必须填写机器人地址",
                    Map.of("wecomWebhook", "不能为空"));
            }
            return null;
        }
        if (hook.length() > MAX_WEBHOOK_LENGTH) {
            throw new BizException(BizError.PARAM_INVALID,
                "企微机器人地址长度不能超过 " + MAX_WEBHOOK_LENGTH,
                Map.of("wecomWebhook", "长度超限"));
        }
        URI uri;
        try {
            uri = URI.create(hook);
        } catch (IllegalArgumentException ex) {
            throw new BizException(BizError.PARAM_INVALID, "企微机器人地址格式不合法",
                Map.of("wecomWebhook", "格式不合法"));
        }
        String scheme = uri.getScheme();
        boolean https = "https".equalsIgnoreCase(scheme);
        boolean insecureHttp = "http".equalsIgnoreCase(scheme) && allowInsecureHttp;
        if ((!https && !insecureHttp) || uri.getHost() == null) {
            throw new BizException(BizError.PARAM_INVALID,
                "企微机器人地址必须以 https:// 开头", Map.of("wecomWebhook", "协议不合法"));
        }
        return hook;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** The 「消息通知」 block as the settings page shows it. */
    public record NotificationSettingView(
        boolean wecomEnabled,
        String wecomWebhook,
        String updatedBy,
        Instant updatedAt) {
    }
}