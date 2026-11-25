package com.sf.release.application.settings;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sf.release.application.audit.AuditCommand;
import com.sf.release.application.audit.AuditService;
import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.domain.audit.AuditAction;
import com.sf.release.domain.pipeline.CheckType;
import com.sf.release.infra.repo.AppSettingMapper;
import com.sf.release.infra.repo.AppUserRepository;
import com.sf.release.infra.repo.UserRoleMapper;
import com.sf.release.infra.repo.po.AppSettingPO;
import com.sf.release.infra.security.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The P6-B platform settings (design §1/§2/§3): the gate check switches, the two approval-role
 * member lists and the abandoned-pause bound, plus the P6-E approval SLA and the P6-F waiver
 * default validity, stored as five sections of the generic {@code app_setting} key/value table.
 *
 * <p><strong>Typed views, one JSON section each.</strong> The five rows are addressed by
 * {@code GATE} / {@code APPROVER} / {@code LOCK} / {@code APPROVAL} / {@code WAIVER};
 * {@link #gate()}, {@link #approvers()}, {@link #lock()}, {@link #approvalSla()} and
 * {@link #waiver()} expose them as typed records, and each {@code save*} method validates the
 * payload, writes the section and then records one {@code SETTING_UPDATE} audit row whose
 * {@code before} and {@code after} are the whole section payload (the design's "整段 JSON").
 * Platform-level settings carry no scope columns, so only an ALL-scope caller can read the audit
 * rows.
 *
 * <p><strong>No cache, on purpose.</strong> The consumers of this configuration are the gate
 * evaluation, the lease renewer, the approval scan and the waiver request; each must see a save on
 * its very next read (design §2.1/§2.3), so every view method reads the row again. A cache would
 * add an invalidation problem for a row that is read a handful of times per minute.
 *
 * <p><strong>Read failures fail safe, not open.</strong> {@link #enabledChecks()} falls back to
 * "all checks enabled" when the row is unreadable or malformed, because the safe direction is to run
 * more checks, not fewer. {@link #pauseBoundHours(int)} falls back to the caller's configured bound
 * (the deployment's {@code release.unit-lease.max-paused-hours}) instead of inventing a value.
 * {@link #waiver()} falls back to the documented seven days, so a broken row keeps the documented
 * default instead of refusing every waiver (or granting an unbounded one).
 *
 * <p><strong>Approvers are role membership.</strong> The {@code APPROVER} section mirrors the two
 * approval roles, but the authority remains {@code user_role}: {@link #saveApprovers} replaces
 * exactly the {@code OPS_LEAD} and {@code DEPT_MANAGER} rows in one transaction, so
 * {@code JdbcAuthProvider} (which reads the table on every request) sees the change immediately.
 * Other roles are never touched. A stored list that is empty falls back to the live
 * {@code user_role} rows on read, which is what the seeded empty section means.
 */
@Service
public class SettingsService {

    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);

    /** Section keys of {@code app_setting}; also the audit {@code object_id}. */
    public static final String SECTION_GATE = "GATE";
    public static final String SECTION_APPROVER = "APPROVER";
    public static final String SECTION_LOCK = "LOCK";
    /** P6-E: the approval SLA (enabled + the two reminder thresholds), seeded by V33. */
    public static final String SECTION_APPROVAL = "APPROVAL";
    /** P6-F: the waiver default validity in days, seeded by V34. */
    public static final String SECTION_WAIVER = "WAIVER";

    /** The two approval roles the approver section owns (design §2.2). */
    public static final String ROLE_OPS_LEAD = "OPS_LEAD";
    public static final String ROLE_DEPT_MANAGER = "DEPT_MANAGER";

    /**
     * The P6-E approval-reminder defaults (design §1/§2): the requirement's 12h remind and 24h
     * escalate points. They are also the fallback when the {@code APPROVAL} row is unreadable,
     * malformed or inconsistent, and the enabled switch then falls back to {@code true}: a broken
     * settings row must not silently stop every reminder.
     */
    public static final int DEFAULT_REMIND_HOURS = 12;
    public static final int DEFAULT_ESCALATE_HOURS = 24;
    public static final int MIN_REMIND_HOURS = 1;
    public static final int MAX_REMIND_HOURS = 72;
    public static final int MIN_ESCALATE_HOURS = 2;
    public static final int MAX_ESCALATE_HOURS = 168;

    /** {@code release.unit-lease.max-paused-hours} default, identical to the pre-P6-B bound. */
    public static final int DEFAULT_PAUSE_BOUND_HOURS = 168;
    public static final int MIN_PAUSE_BOUND_HOURS = 1;
    public static final int MAX_PAUSE_BOUND_HOURS = 8760;

    /**
     * P6-F design §3: the waiver default validity in days. The default is seven days; the upper
     * bound is the unchanged waiver maximum of 90 days ({@code GateWaiverService.MAX_VALIDITY}),
     * so the configured default can never produce a waiver the request path would reject.
     */
    public static final int DEFAULT_WAIVER_DAYS = 7;
    public static final int MIN_WAIVER_DAYS = 1;
    public static final int MAX_WAIVER_DAYS = 90;

    /** {@code user_role.user_id} is {@code VARCHAR(32)}. */
    static final int MAX_USER_ID_LENGTH = 32;

    /** Every check enabled: the fallback when the gate row cannot be read. */
    private static final Set<CheckType> ALL_CHECKS =
        Collections.unmodifiableSet(EnumSet.allOf(CheckType.class));

    private final AppSettingMapper settingMapper;
    private final UserRoleMapper userRoleMapper;
    private final AppUserRepository users;
    private final AuditService audit;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public SettingsService(AppSettingMapper settingMapper,
                           UserRoleMapper userRoleMapper,
                           AppUserRepository users,
                           AuditService audit,
                           ObjectMapper objectMapper,
                           PlatformTransactionManager transactionManager) {
        this.settingMapper = settingMapper;
        this.userRoleMapper = userRoleMapper;
        this.users = users;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    // ------------------------------------------------------------------ reads

    /** The gate switches: the enabled check types, newest-first canonical order. */
    public GateSettings gate() {
        AppSettingPO po = settingMapper.selectById(SECTION_GATE);
        return new GateSettings(parseGate(po), updatedBy(po), updatedAt(po));
    }

    /** The two approval-role member lists plus who last saved the section. */
    public ApproverSettings approvers() {
        AppSettingPO po = settingMapper.selectById(SECTION_APPROVER);
        ApproverJson stored = parseApprovers(po);
        // An empty stored list means "not configured yet": the seeded rows of user_role are the
        // live approvers, so show them instead of an empty page (design §1: "默认空 = 沿用种子").
        List<String> opsLead = stored.opsLead().isEmpty()
            ? listOrEmpty(userRoleMapper.selectUsersByRole(ROLE_OPS_LEAD))
            : stored.opsLead();
        List<String> deptManager = stored.deptManager().isEmpty()
            ? listOrEmpty(userRoleMapper.selectUsersByRole(ROLE_DEPT_MANAGER))
            : stored.deptManager();
        return new ApproverSettings(opsLead, deptManager, updatedBy(po), updatedAt(po));
    }

    /** The abandoned-pause bound; an invalid stored value degrades to the documented default. */
    public LockSettings lock() {
        AppSettingPO po = settingMapper.selectById(SECTION_LOCK);
        Integer hours = parsePauseBound(po);
        return new LockSettings(hours == null ? DEFAULT_PAUSE_BOUND_HOURS : hours,
            updatedBy(po), updatedAt(po));
    }

    /**
     * The approval SLA (P6-E design §1/§2): the enabled switch and the two reminder thresholds.
     *
     * <p>A missing, unreadable, malformed or inconsistent row degrades to the documented fallback —
     * enabled, 12 hours, 24 hours — because the safe direction for a reminder feature is to keep
     * reminding. The {@code APPROVAL_REMIND}/{@code APPROVAL_ESCALATE} unique keys make a repeated
     * scan harmless, so falling back to "enabled" cannot spam anybody; a threshold crossing
     * ({@code escalateHours <= remindHours}) is treated as a broken row rather than made to work.
     */
    public ApprovalSla approvalSla() {
        AppSettingPO po;
        try {
            po = settingMapper.selectById(SECTION_APPROVAL);
        } catch (RuntimeException ex) {
            log.warn("APPROVAL setting could not be read; using the enabled 12/24-hour defaults: {}",
                ex.getMessage());
            return defaultApprovalSla(null, null);
        }
        return parseApproval(po);
    }

    /**
     * The P6-F waiver default validity (design §3): the number of days a waiver runs when the
     * request leaves {@code expireAt} empty. The unchanged explicit-expiry rules stay in
     * {@code GateWaiverService}: this value is only the default, never an override.
     *
     * <p>A missing, unreadable, malformed or out-of-range row degrades to the documented seven
     * days, so a broken settings row cannot refuse every waiver or grant an unbounded one.
     */
    public WaiverSettings waiver() {
        AppSettingPO po;
        try {
            po = settingMapper.selectById(SECTION_WAIVER);
        } catch (RuntimeException ex) {
            log.warn("WAIVER setting could not be read; using the seven-day default: {}",
                ex.getMessage());
            return defaultWaiverSettings(null, null);
        }
        return parseWaiver(po);
    }

    // ------------------------------------------------------------------ consumer-facing reads

    /**
     * The enabled check types, read from the row on every call.
     *
     * <p>A missing/unreadable/malformed row falls back to every check: the gate must never become
     * more permissive because its configuration row failed to load.
     */
    public Set<CheckType> enabledChecks() {
        try {
            List<CheckType> checks = parseGate(settingMapper.selectById(SECTION_GATE));
            if (checks.isEmpty()) {
                return ALL_CHECKS;
            }
            return Collections.unmodifiableSet(EnumSet.copyOf(checks));
        } catch (RuntimeException ex) {
            log.warn("GATE setting could not be read; every check stays enabled: {}",
                ex.getMessage());
            return ALL_CHECKS;
        }
    }

    /**
     * The configured pause bound in hours, or {@code fallback} when the row is missing, malformed or
     * out of range. The lease renewer passes the deployment's {@code max-paused-hours} as the
     * fallback, so a broken settings row keeps the pre-P6-B behaviour instead of stalling the job
     * (design §2.3).
     */
    public int pauseBoundHours(int fallback) {
        int safeFallback = fallback >= MIN_PAUSE_BOUND_HOURS && fallback <= MAX_PAUSE_BOUND_HOURS
            ? fallback : DEFAULT_PAUSE_BOUND_HOURS;
        try {
            Integer stored = parsePauseBound(settingMapper.selectById(SECTION_LOCK));
            if (stored != null) {
                return stored;
            }
            log.warn("LOCK setting is missing or invalid; the configured bound of {} hours is"
                + " used instead", safeFallback);
        } catch (RuntimeException ex) {
            log.warn("LOCK setting could not be read; the configured bound of {} hours is used"
                + " instead: {}", safeFallback, ex.getMessage());
        }
        return safeFallback;
    }

    // ------------------------------------------------------------------ writes

    /**
     * Saves the gate switches.
     *
     * @param rawEnabledChecks check-type names from the request; every name must be a known
     *                         {@link CheckType} and at least one must remain
     * @throws BizException 40000 an empty list or an unknown check name
     */
    public GateSettings saveGate(List<String> rawEnabledChecks) {
        List<CheckType> enabled = requireChecks(rawEnabledChecks);
        GateSettings before = gate();
        String actor = UserContext.current().userId();
        Instant now = Instant.now();
        writeSection(SECTION_GATE, gateJson(enabled), actor, now);
        GateSettings saved = gate();
        audit.record(AuditCommand.of(AuditAction.SETTING_UPDATE, SECTION_GATE, "门禁规则设置",
                "更新门禁规则（启用 " + saved.enabledChecks().size() + " 项检查）")
            .before(gateAuditValues(before))
            .after(gateAuditValues(saved))
            .build());
        log.info("GATE setting saved: enabled={}, by={}", saved.enabledCheckNames(), actor);
        return saved;
    }

    /**
     * Saves the approval-role member lists. Every id must name an existing {@code ACTIVE} user; the
     * lists are de-duplicated and may be empty (which clears the role). The {@code user_role} rows
     * of exactly {@code OPS_LEAD} and {@code DEPT_MANAGER} are replaced in one transaction, so the
     * change is immediately visible to {@code JdbcAuthProvider}.
     *
     * @throws BizException 40000 a blank/oversized/nonexistent/inactive user id
     */
    public ApproverSettings saveApprovers(List<String> rawOpsLead, List<String> rawDeptManager) {
        List<String> opsLead = requireActiveUsers(rawOpsLead, "opsLead");
        List<String> deptManager = requireActiveUsers(rawDeptManager, "deptManager");
        ApproverSettings before = approvers();
        String actor = UserContext.current().userId();
        Instant now = Instant.now();
        String json = approverJson(opsLead, deptManager);
        transactionTemplate.executeWithoutResult(tx -> {
            writeSection(SECTION_APPROVER, json, actor, now);
            replaceRole(ROLE_OPS_LEAD, opsLead);
            replaceRole(ROLE_DEPT_MANAGER, deptManager);
        });
        ApproverSettings saved = approvers();
        audit.record(AuditCommand.of(AuditAction.SETTING_UPDATE, SECTION_APPROVER, "审批人名单设置",
                "更新审批人名单（运营负责人 " + saved.opsLead().size() + " 人、部门经理 "
                    + saved.deptManager().size() + " 人）")
            .before(approverAuditValues(before))
            .after(approverAuditValues(saved))
            .build());
        log.info("APPROVER setting saved: opsLead={}, deptManager={}, by={}",
            saved.opsLead(), saved.deptManager(), actor);
        return saved;
    }

    /**
     * Saves the abandoned-pause bound.
     *
     * @throws BizException 40000 missing or outside {@value #MIN_PAUSE_BOUND_HOURS}..
     *                      {@value #MAX_PAUSE_BOUND_HOURS} hours
     */
    public LockSettings saveLock(Integer pauseBoundHours) {
        if (pauseBoundHours == null || pauseBoundHours < MIN_PAUSE_BOUND_HOURS
            || pauseBoundHours > MAX_PAUSE_BOUND_HOURS) {
            throw new BizException(BizError.PARAM_INVALID,
                "暂停上限必须在 " + MIN_PAUSE_BOUND_HOURS + "-" + MAX_PAUSE_BOUND_HOURS + " 小时之间",
                Map.of("pauseBoundHours", "超出范围"));
        }
        LockSettings before = lock();
        String actor = UserContext.current().userId();
        Instant now = Instant.now();
        writeSection(SECTION_LOCK, lockJson(pauseBoundHours), actor, now);
        LockSettings saved = lock();
        audit.record(AuditCommand.of(AuditAction.SETTING_UPDATE, SECTION_LOCK, "锁超时设置",
                "更新暂停上限（" + saved.pauseBoundHours() + " 小时）")
            .before(lockAuditValues(before))
            .after(lockAuditValues(saved))
            .build());
        log.info("LOCK setting saved: pauseBoundHours={}, by={}", saved.pauseBoundHours(), actor);
        return saved;
    }

    /**
     * Saves the approval SLA (P6-E design §2). The two thresholds must stay in their documented
     * ranges and the escalation must stay strictly above the reminder, otherwise the same step would
     * be escalated before anybody had a chance to sign it.
     *
     * @throws BizException 40000 a missing switch, a threshold outside 1–72 / 2–168 hours, or an
     *                      escalation that is not greater than the reminder
     */
    public ApprovalSla saveApprovalSla(Boolean enabled, Integer remindHours,
                                       Integer escalateHours) {
        if (enabled == null) {
            throw new BizException(BizError.PARAM_INVALID, "启用开关不能为空",
                Map.of("enabled", "不能为空"));
        }
        if (remindHours == null || remindHours < MIN_REMIND_HOURS
            || remindHours > MAX_REMIND_HOURS) {
            throw new BizException(BizError.PARAM_INVALID, "提醒阈值必须在 "
                + MIN_REMIND_HOURS + "-" + MAX_REMIND_HOURS + " 小时之间",
                Map.of("remindHours", "超出范围"));
        }
        if (escalateHours == null || escalateHours < MIN_ESCALATE_HOURS
            || escalateHours > MAX_ESCALATE_HOURS) {
            throw new BizException(BizError.PARAM_INVALID, "升级阈值必须在 "
                + MIN_ESCALATE_HOURS + "-" + MAX_ESCALATE_HOURS + " 小时之间",
                Map.of("escalateHours", "超出范围"));
        }
        if (escalateHours <= remindHours) {
            throw new BizException(BizError.PARAM_INVALID, "升级阈值必须大于提醒阈值",
                Map.of("escalateHours", "必须大于提醒阈值"));
        }
        ApprovalSla before = approvalSla();
        String actor = UserContext.current().userId();
        Instant now = Instant.now();
        writeSection(SECTION_APPROVAL,
            approvalJson(enabled, remindHours, escalateHours), actor, now);
        ApprovalSla saved = approvalSla();
        audit.record(AuditCommand.of(AuditAction.SETTING_UPDATE, SECTION_APPROVAL, "审批时效设置",
                "更新审批时效（" + (saved.enabled() ? "启用" : "停用") + "，提醒 "
                    + saved.remindHours() + " 小时、升级 " + saved.escalateHours() + " 小时）")
            .before(approvalAuditValues(before))
            .after(approvalAuditValues(saved))
            .build());
        log.info("APPROVAL setting saved: enabled={}, remindHours={}, escalateHours={}, by={}",
            saved.enabled(), saved.remindHours(), saved.escalateHours(), actor);
        return saved;
    }

    /**
     * Saves the waiver default validity (P6-F design §3).
     *
     * @throws BizException 40000 a missing value or a value outside
     *                      {@value #MIN_WAIVER_DAYS}..{@value #MAX_WAIVER_DAYS} days
     */
    public WaiverSettings saveWaiver(Integer defaultDays) {
        if (defaultDays == null || defaultDays < MIN_WAIVER_DAYS
            || defaultDays > MAX_WAIVER_DAYS) {
            throw new BizException(BizError.PARAM_INVALID,
                "豁免默认有效期必须在 " + MIN_WAIVER_DAYS + "-" + MAX_WAIVER_DAYS + " 天之间",
                Map.of("defaultDays", "超出范围"));
        }
        WaiverSettings before = waiver();
        String actor = UserContext.current().userId();
        Instant now = Instant.now();
        writeSection(SECTION_WAIVER, waiverJson(defaultDays), actor, now);
        WaiverSettings saved = waiver();
        audit.record(AuditCommand.of(AuditAction.SETTING_UPDATE, SECTION_WAIVER,
                "豁免默认有效期设置", "更新豁免默认有效期（" + saved.defaultDays() + " 天）")
            .before(waiverAuditValues(before))
            .after(waiverAuditValues(saved))
            .build());
        log.info("WAIVER setting saved: defaultDays={}, by={}", saved.defaultDays(), actor);
        return saved;
    }

    // ------------------------------------------------------------------ validation

    /** Canonical check order, de-duplicated; an empty list is the design's 40000. */
    private static List<CheckType> requireChecks(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            throw new BizException(BizError.PARAM_INVALID, "至少需要启用一项检查",
                Map.of("enabledChecks", "不能为空"));
        }
        Set<CheckType> requested = EnumSet.noneOf(CheckType.class);
        for (String value : raw) {
            CheckType type = CheckType.fromName(value);
            if (type == null) {
                throw new BizException(BizError.PARAM_INVALID, "未知的检查项：" + value,
                    Map.of("enabledChecks", "未知检查项：" + (value == null ? "" : value)));
            }
            requested.add(type);
        }
        List<CheckType> ordered = new ArrayList<>(requested.size());
        for (CheckType type : CheckType.values()) {
            if (requested.contains(type)) {
                ordered.add(type);
            }
        }
        if (ordered.isEmpty()) {
            throw new BizException(BizError.PARAM_INVALID, "至少需要启用一项检查",
                Map.of("enabledChecks", "不能为空"));
        }
        return List.copyOf(ordered);
    }

    /**
     * Trims, de-duplicates and validates a member list. An empty/omitted list is legal (it clears
     * the role); every named user must exist and be {@code ACTIVE}.
     */
    private List<String> requireActiveUsers(List<String> raw, String field) {
        if (raw == null) {
            return List.of();
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String value : raw) {
            String id = value == null ? "" : value.trim();
            if (id.isEmpty()) {
                throw new BizException(BizError.PARAM_INVALID, "审批用户不能为空",
                    Map.of(field, "不能为空"));
            }
            if (id.length() > MAX_USER_ID_LENGTH) {
                throw new BizException(BizError.PARAM_INVALID,
                    "审批用户长度不能超过 " + MAX_USER_ID_LENGTH,
                    Map.of(field, "长度超限：" + id));
            }
            ids.add(id);
        }
        for (String id : ids) {
            if (!users.existsActive(id)) {
                throw new BizException(BizError.PARAM_INVALID, "审批用户不存在或已停用：" + id,
                    Map.of(field, "用户不存在或已停用：" + id));
            }
        }
        return List.copyOf(ids);
    }

    // ------------------------------------------------------------------ persistence helpers

    private void writeSection(String key, String json, String actor, Instant now) {
        int rows = settingMapper.update(null, Wrappers.<AppSettingPO>lambdaUpdate()
            .eq(AppSettingPO::getSettingKey, key)
            .set(AppSettingPO::getValueJson, json)
            .set(AppSettingPO::getUpdatedBy, actor)
            .set(AppSettingPO::getUpdatedAt, now));
        if (rows == 0) {
            // The seed rows are part of V30; this only repairs a schema that lost one.
            AppSettingPO po = new AppSettingPO();
            po.setSettingKey(key);
            po.setValueJson(json);
            po.setUpdatedBy(actor);
            po.setUpdatedAt(now);
            settingMapper.insert(po);
        }
    }

    private void replaceRole(String role, List<String> userIds) {
        userRoleMapper.deleteByRole(role);
        for (String userId : userIds) {
            userRoleMapper.insertMembership(userId, role);
        }
    }

    // ------------------------------------------------------------------ JSON

    private String gateJson(List<CheckType> checks) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("enabledChecks", namesOf(checks));
        return json(values);
    }

    private String approverJson(List<String> opsLead, List<String> deptManager) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("opsLead", opsLead);
        values.put("deptManager", deptManager);
        return json(values);
    }

    private String lockJson(int pauseBoundHours) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("pauseBoundHours", pauseBoundHours);
        return json(values);
    }

    private String approvalJson(boolean enabled, int remindHours, int escalateHours) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("enabled", enabled);
        values.put("remindHours", remindHours);
        values.put("escalateHours", escalateHours);
        return json(values);
    }

    private String waiverJson(int defaultDays) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("defaultDays", defaultDays);
        return json(values);
    }

    private String json(Map<String, Object> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException ex) {
            // Our own map of strings/numbers cannot fail; if it does, the save must not store a
            // half-written section.
            throw new IllegalStateException("settings section could not be serialized", ex);
        }
    }

    /**
     * The stored gate list, or every check when the row is missing/malformed. A name this build does
     * not know is logged and ignored: a row written by a newer version must not break an older one.
     */
    private List<CheckType> parseGate(AppSettingPO po) {
        if (po == null || po.getValueJson() == null) {
            return List.copyOf(ALL_CHECKS);
        }
        try {
            JsonNode root = objectMapper.readTree(po.getValueJson());
            JsonNode array = root.path("enabledChecks");
            if (!array.isArray()) {
                log.warn("GATE setting has no enabledChecks array; every check stays enabled");
                return List.copyOf(ALL_CHECKS);
            }
            List<CheckType> checks = new ArrayList<>();
            for (JsonNode node : array) {
                CheckType type = CheckType.fromName(node.asText());
                if (type == null) {
                    log.warn("GATE setting names an unknown check {}, ignored", node.asText());
                    continue;
                }
                if (!checks.contains(type)) {
                    checks.add(type);
                }
            }
            if (checks.isEmpty()) {
                log.warn("GATE setting enables no known check; every check stays enabled");
                return List.copyOf(ALL_CHECKS);
            }
            return List.copyOf(checks);
        } catch (JsonProcessingException ex) {
            log.warn("GATE setting is not valid JSON; every check stays enabled: {}",
                ex.getMessage());
            return List.copyOf(ALL_CHECKS);
        }
    }

    private ApproverJson parseApprovers(AppSettingPO po) {
        if (po == null || po.getValueJson() == null) {
            return new ApproverJson(List.of(), List.of());
        }
        try {
            JsonNode root = objectMapper.readTree(po.getValueJson());
            return new ApproverJson(strings(root.path("opsLead")),
                strings(root.path("deptManager")));
        } catch (JsonProcessingException ex) {
            log.warn("APPROVER setting is not valid JSON; showing the user_role rows instead: {}",
                ex.getMessage());
            return new ApproverJson(List.of(), List.of());
        }
    }

    private Integer parsePauseBound(AppSettingPO po) {
        if (po == null || po.getValueJson() == null) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(po.getValueJson());
            JsonNode node = root.path("pauseBoundHours");
            if (!node.canConvertToInt() || node.asInt() < MIN_PAUSE_BOUND_HOURS
                || node.asInt() > MAX_PAUSE_BOUND_HOURS) {
                return null;
            }
            return node.asInt();
        } catch (JsonProcessingException ex) {
            log.warn("LOCK setting is not valid JSON; using the configured fallback: {}",
                ex.getMessage());
            return null;
        }
    }

    /**
     * The stored approval SLA. Missing/malformed/out-of-range values, and an escalation that is not
     * above the reminder, all degrade to the documented enabled 12/24 fallback (see
     * {@link #approvalSla()}); a partially valid row is not trusted field by field, because a
     * threshold crossing is itself a broken section.
     */
    private ApprovalSla parseApproval(AppSettingPO po) {
        if (po == null || po.getValueJson() == null) {
            log.warn("APPROVAL setting is missing; using the enabled 12/24-hour defaults");
            return defaultApprovalSla(null, null);
        }
        try {
            JsonNode root = objectMapper.readTree(po.getValueJson());
            Boolean enabled = booleanOrNull(root.path("enabled"));
            Integer remind = intOrNull(root.path("remindHours"));
            Integer escalate = intOrNull(root.path("escalateHours"));
            if (enabled == null
                || !inRange(remind, MIN_REMIND_HOURS, MAX_REMIND_HOURS)
                || !inRange(escalate, MIN_ESCALATE_HOURS, MAX_ESCALATE_HOURS)
                || escalate <= remind) {
                log.warn("APPROVAL setting is invalid; using the enabled 12/24-hour defaults");
                return defaultApprovalSla(updatedBy(po), updatedAt(po));
            }
            return new ApprovalSla(enabled, remind, escalate, updatedBy(po), updatedAt(po));
        } catch (JsonProcessingException ex) {
            log.warn("APPROVAL setting is not valid JSON; using the enabled 12/24-hour defaults: {}",
                ex.getMessage());
            return defaultApprovalSla(updatedBy(po), updatedAt(po));
        }
    }

    private static ApprovalSla defaultApprovalSla(String updatedBy, Instant updatedAt) {
        return new ApprovalSla(true, DEFAULT_REMIND_HOURS, DEFAULT_ESCALATE_HOURS, updatedBy,
            updatedAt);
    }

    /**
     * The stored waiver default. A missing/malformed/out-of-range value degrades to the documented
     * seven days (see {@link #waiver()}); the section keeps its {@code updatedBy}/{@code updatedAt}
     * metadata where the row exists.
     */
    private WaiverSettings parseWaiver(AppSettingPO po) {
        if (po == null || po.getValueJson() == null) {
            log.warn("WAIVER setting is missing; using the seven-day default");
            return defaultWaiverSettings(null, null);
        }
        try {
            JsonNode root = objectMapper.readTree(po.getValueJson());
            Integer days = intOrNull(root.path("defaultDays"));
            if (!inRange(days, MIN_WAIVER_DAYS, MAX_WAIVER_DAYS)) {
                log.warn("WAIVER setting is invalid; using the seven-day default");
                return defaultWaiverSettings(updatedBy(po), updatedAt(po));
            }
            return new WaiverSettings(days, updatedBy(po), updatedAt(po));
        } catch (JsonProcessingException ex) {
            log.warn("WAIVER setting is not valid JSON; using the seven-day default: {}",
                ex.getMessage());
            return defaultWaiverSettings(updatedBy(po), updatedAt(po));
        }
    }

    private static WaiverSettings defaultWaiverSettings(String updatedBy, Instant updatedAt) {
        return new WaiverSettings(DEFAULT_WAIVER_DAYS, updatedBy, updatedAt);
    }

    private static boolean inRange(Integer value, int min, int max) {
        return value != null && value >= min && value <= max;
    }

    /** A numeric JSON field, or {@code null} when absent or of the wrong shape. */
    private static Integer intOrNull(JsonNode node) {
        return node != null && node.isNumber() && node.canConvertToInt() ? node.asInt() : null;
    }

    /** A boolean JSON field, or {@code null} when absent or of the wrong shape. */
    private static Boolean booleanOrNull(JsonNode node) {
        return node != null && node.isBoolean() ? node.asBoolean() : null;
    }

    private static List<String> strings(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (JsonNode item : node) {
            String text = item.asText(null);
            if (text != null && !text.isBlank()) {
                values.add(text.trim());
            }
        }
        return List.copyOf(values);
    }

    private static List<String> listOrEmpty(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static List<String> namesOf(List<CheckType> checks) {
        List<String> names = new ArrayList<>(checks.size());
        for (CheckType type : checks) {
            names.add(type.name());
        }
        return List.copyOf(names);
    }

    private static String updatedBy(AppSettingPO po) {
        return po == null ? null : po.getUpdatedBy();
    }

    private static Instant updatedAt(AppSettingPO po) {
        return po == null ? null : po.getUpdatedAt();
    }

    private static Map<String, Object> gateAuditValues(GateSettings settings) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("enabledChecks", settings.enabledCheckNames());
        return values;
    }

    private static Map<String, Object> approverAuditValues(ApproverSettings settings) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("opsLead", settings.opsLead());
        values.put("deptManager", settings.deptManager());
        return values;
    }

    private static Map<String, Object> lockAuditValues(LockSettings settings) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("pauseBoundHours", settings.pauseBoundHours());
        return values;
    }

    private static Map<String, Object> approvalAuditValues(ApprovalSla settings) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("enabled", settings.enabled());
        values.put("remindHours", settings.remindHours());
        values.put("escalateHours", settings.escalateHours());
        return values;
    }

    private static Map<String, Object> waiverAuditValues(WaiverSettings settings) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("defaultDays", settings.defaultDays());
        return values;
    }

    // ------------------------------------------------------------------ views

    /**
     * The gate switches. {@code enabledCheckNames()} keeps the check-type enum out of the web layer
     * (the DTOs never import a domain enum); application code uses the typed list.
     */
    public record GateSettings(
        List<CheckType> enabledChecks,
        String updatedBy,
        Instant updatedAt) {

        public GateSettings {
            enabledChecks = enabledChecks == null ? List.of() : List.copyOf(enabledChecks);
        }

        /** The stored check names, in canonical order, for the settings page. */
        public List<String> enabledCheckNames() {
            return namesOf(enabledChecks);
        }
    }

    /** The two approval-role member lists. */
    public record ApproverSettings(
        List<String> opsLead,
        List<String> deptManager,
        String updatedBy,
        Instant updatedAt) {

        public ApproverSettings {
            opsLead = opsLead == null ? List.of() : List.copyOf(opsLead);
            deptManager = deptManager == null ? List.of() : List.copyOf(deptManager);
        }
    }

    /** The abandoned-pause bound in hours. */
    public record LockSettings(int pauseBoundHours, String updatedBy, Instant updatedAt) {
    }

    /**
     * The approval SLA of P6-E: the on/off switch and the two wait thresholds in hours. The record is
     * the read view of the {@code APPROVAL} section and the consumer-facing value the scan uses.
     */
    public record ApprovalSla(boolean enabled, int remindHours, int escalateHours,
                              String updatedBy, Instant updatedAt) {
    }

    /**
     * The P6-F waiver default validity in days (1–90, default 7). It is the read view of the
     * {@code WAIVER} section and the value {@code GateWaiverService} uses when a request leaves
     * {@code expireAt} empty.
     */
    public record WaiverSettings(int defaultDays, String updatedBy, Instant updatedAt) {
    }

    /** The two lists of the {@code APPROVER} JSON section. */
    private record ApproverJson(List<String> opsLead, List<String> deptManager) {
    }
}
