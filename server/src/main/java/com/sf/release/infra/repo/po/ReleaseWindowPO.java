package com.sf.release.infra.repo.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sf.release.domain.window.ReleaseWindow;
import com.sf.release.domain.window.WindowScope;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

/**
 * Persistence object for {@code release_window} (V21), the header of a release window.
 *
 * <p>A GLOBAL row stores the sentinel {@code subsystem_id = 0}: MySQL unique keys treat NULLs as
 * distinct, so a NULL would let several "global" rows exist next to {@code uk_window_scope}.
 * {@code enabled} is stored as a TINYINT(1) and mapped to a {@code Boolean}.
 *
 * <p>The child rows ({@code window_rule}, {@code window_exception}) are not part of this object;
 * {@link #toDomain(List, List)} receives them already loaded, which keeps the PO a single-table
 * mirror and lets the service decide how to read the children.
 */
@TableName("release_window")
public class ReleaseWindowPO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String scope;
    private Long subsystemId;
    private Boolean enabled;
    private String timezone;
    private Instant createdAt;
    private Instant updatedAt;

    /** A new header row for {@code window}; the id stays null so the insert assigns it. */
    public static ReleaseWindowPO of(ReleaseWindow window, Instant createdAt, Instant updatedAt) {
        ReleaseWindowPO po = new ReleaseWindowPO();
        po.scope = window.scope().name();
        po.subsystemId = window.subsystemId();
        po.enabled = window.enabled();
        po.timezone = window.zoneId().getId();
        po.createdAt = createdAt;
        po.updatedAt = updatedAt;
        return po;
    }

    /** The domain window assembled from this header plus its loaded child rows. */
    public ReleaseWindow toDomain(List<WindowRulePO> rules, List<WindowExceptionPO> exceptions) {
        return new ReleaseWindow(
            scope == null || scope.isBlank() ? WindowScope.GLOBAL : WindowScope.valueOf(scope),
            subsystemId == null ? ReleaseWindow.GLOBAL_SUBSYSTEM_ID : subsystemId,
            enabled != null && enabled,
            timezone == null || timezone.isBlank()
                ? ReleaseWindow.DEFAULT_ZONE : ZoneId.of(timezone),
            rules == null ? List.of() : rules.stream().map(WindowRulePO::toDomain).toList(),
            exceptions == null ? List.of()
                : exceptions.stream().map(WindowExceptionPO::toDomain).toList());
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public Long getSubsystemId() {
        return subsystemId;
    }

    public void setSubsystemId(Long subsystemId) {
        this.subsystemId = subsystemId;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}