package com.sf.release.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sf.release.application.audit.AuditCommand;
import com.sf.release.application.audit.AuditService;
import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.domain.audit.AuditAction;
import com.sf.release.domain.window.ReleaseWindow;
import com.sf.release.domain.window.WindowCheck;
import com.sf.release.domain.window.WindowException;
import com.sf.release.domain.window.WindowRule;
import com.sf.release.domain.window.WindowScope;
import com.sf.release.domain.window.WindowSpan;
import com.sf.release.infra.repo.ReleaseWindowMapper;
import com.sf.release.infra.repo.SubsystemMapper;
import com.sf.release.infra.repo.WindowExceptionMapper;
import com.sf.release.infra.repo.WindowRuleMapper;
import com.sf.release.infra.repo.po.ReleaseWindowPO;
import com.sf.release.infra.repo.po.WindowExceptionPO;
import com.sf.release.infra.repo.po.WindowRulePO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The 发布窗口 use cases (需求 5.6, tech design 4.1): the effective window of a subsystem, its
 * maintenance, and the one question the admission flow asks — "is this planned time inside?".
 *
 * <p><strong>Effective window.</strong> A subsystem override wins; otherwise the GLOBAL default
 * applies and the read model says {@code inherited = true}. When neither exists the platform has
 * no window: {@link #effectiveWindow(long)} returns {@code null} and {@link #check(long, Instant)}
 * answers {@code configured = false, inWindow = false}. "Nothing configured" must never become
 * "always exempt" (design 4.1) — it is the opposite: a window-less platform always requires
 * approval. {@code enabled = false} on an existing window is the deliberate "constraint off"
 * switch and answers {@code inWindow = true}.
 *
 * <p><strong>Saving.</strong> A save replaces the rule and exception sets of one scope (they are
 * small, and diffing them would only create chances to drift). Validated:
 * <ul>
 *   <li>a rule with equal start and end is rejected (40000) — it can mean "zero length" or "all
 *       day", so guessing would be worse than asking;</li>
 *   <li>two rules of the same weekday may not overlap (40000);</li>
 *   <li>a slot shorter than 30 minutes or longer than 12 hours is <em>accepted</em> but reported as
 *       a warning, because a one-off odd window is legitimate and the operator should see it
 *       rather than be blocked;</li>
 *   <li>one exception per date; {@code CLOSED} drops any time bounds, {@code OPEN} may omit them
 *       (the whole day is open).</li>
 * </ul>
 *
 * <p><strong>Time.</strong> an {@code Instant} is converted into the window's zone (default
 * {@code Asia/Shanghai}) before the pure domain decides. Nothing here stores a local date-time for
 * "now": persisted timestamps are {@code Instant}s.
 */
@Service
public class WindowService {

    private static final Logger log = LoggerFactory.getLogger(WindowService.class);

    /** Shortest slot worth calling a window, in minutes (design 4.1: 30 分钟–12 小时). */
    static final Duration MIN_SPAN = Duration.ofMinutes(30);
    /** Longest slot accepted without a warning. */
    static final Duration MAX_SPAN = Duration.ofHours(12);

    /** Upper bound of a saved rule set: 7 weekdays x 4 slots is already more than a page shows. */
    static final int MAX_RULES = 28;
    /** Upper bound of a saved exception calendar; a year of weekly exceptions is 52 rows. */
    static final int MAX_EXCEPTIONS = 100;

    private static final int MINUTES_PER_DAY = 24 * 60;
    private static final DateTimeFormatter HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm");
    private static final String[] WEEKDAY_LABELS =
        {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    private final ReleaseWindowMapper windowMapper;
    private final WindowRuleMapper ruleMapper;
    private final WindowExceptionMapper exceptionMapper;
    private final SubsystemMapper subsystemMapper;
    private final AuditService audit;

    /**
     * The audit seam is optional in the overload below because a unit test constructs this service
     * with raw mappers to exercise the concurrent-insert path without a Spring context.
     */
    @Autowired
    public WindowService(ReleaseWindowMapper windowMapper,
                         WindowRuleMapper ruleMapper,
                         WindowExceptionMapper exceptionMapper,
                         SubsystemMapper subsystemMapper,
                         AuditService audit) {
        this.windowMapper = windowMapper;
        this.ruleMapper = ruleMapper;
        this.exceptionMapper = exceptionMapper;
        this.subsystemMapper = subsystemMapper;
        this.audit = audit;
    }

    /** Test-only constructor without the audit seam; a save simply writes no audit row. */
    public WindowService(ReleaseWindowMapper windowMapper,
                         WindowRuleMapper ruleMapper,
                         WindowExceptionMapper exceptionMapper,
                         SubsystemMapper subsystemMapper) {
        this(windowMapper, ruleMapper, exceptionMapper, subsystemMapper, null);
    }

    /** One weekly slot of the save form. */
    public record RuleInput(int dayOfWeek, LocalTime start, LocalTime end) {
    }

    /** One date exception of the save form; {@code mode} is {@code OPEN} (default) or {@code CLOSED}. */
    public record ExceptionInput(LocalDate date, String mode, LocalTime start, LocalTime end) {
    }

    /**
     * The save form of one scope. {@code subsystemId} is ignored for GLOBAL; {@code enabled} is
     * true when omitted (a save is a configuration, not a switch-off, unless it says so).
     */
    public record SaveCommand(String scope, Long subsystemId, Boolean enabled, String timezone,
                              List<RuleInput> rules, List<ExceptionInput> exceptions) {
    }

    /** The saved window plus the warnings the operator should read (nothing was blocked). */
    public record SaveResult(WindowView window, List<String> warnings) {
    }

    /**
     * The window that applies to a subsystem, or {@code null} when the platform has none. A
     * subsystem without its own row falls back to the GLOBAL default, marked {@code inherited}.
     *
     * @throws BizException 40000 when {@code subsystemId} is negative (0 is the GLOBAL query)
     */
    public WindowView effectiveWindow(long subsystemId) {
        requireValidSubsystemId(subsystemId);
        WindowHit hit = effective(subsystemId);
        return hit == null ? null : toView(hit);
    }

    /**
     * Answers "is this instant inside the effective window of the subsystem?".
     *
     * @param at the planned release time; {@code null} means now (convenience for the page's
     *           "check current time" button — nothing is persisted here)
     */
    public WindowCheckView check(long subsystemId, Instant at) {
        requireValidSubsystemId(subsystemId);
        Instant when = at == null ? Instant.now() : at;
        WindowHit hit = effective(subsystemId);
        if (hit == null) {
            return toView(WindowCheck.notConfigured(), null);
        }
        ReleaseWindow window = hit.window();
        WindowCheck check;
        if (!window.enabled()) {
            check = WindowCheck.constraintOff();
        } else {
            LocalDateTime local = window.local(when);
            WindowSpan span = window.spanAt(local).orElse(null);
            WindowSpan next = window.nextWindow(local, ReleaseWindow.MAX_SEARCH_DAYS).orElse(null);
            check = new WindowCheck(span != null, span, next, true, true);
        }
        return toView(check, hit);
    }

    /**
     * Upserts the window of one scope and replaces its rules and exceptions.
     *
     * @return the stored window and the warnings (short/long slots) the page shows
     * @throws BizException 40000 for a bad scope, a missing subsystem, a bad zone, a rule with
     *                      equal start and end, overlapping rules of one weekday, or two exceptions
     *                      on the same date
     */
    @Transactional
    public SaveResult save(SaveCommand command) {
        if (command == null) {
            throw new BizException(BizError.PARAM_INVALID, "窗口配置不能为空",
                Map.of("body", "请求体不能为空"));
        }
        WindowScope scope = parseScope(command.scope());
        long subsystemId = requireSubsystemId(scope, command.subsystemId());
        boolean enabled = command.enabled() == null || command.enabled();
        ZoneId zone = parseZone(command.timezone());
        List<WindowRule> rules = parseRules(command.rules());
        List<WindowException> exceptions = parseExceptions(command.exceptions());
        List<String> warnings = warningsOf(rules, exceptions);
        ReleaseWindow window = new ReleaseWindow(scope, subsystemId, enabled, zone, rules,
            exceptions);
        Instant now = Instant.now();

        ReleaseWindowPO stored = windowMapper.selectByScope(scope.name(), subsystemId);
        long windowId;
        if (stored == null) {
            ReleaseWindowPO header = ReleaseWindowPO.of(window, now, now);
            try {
                windowMapper.insert(header);
                windowId = header.getId();
            } catch (DuplicateKeyException ex) {
                // A concurrent save created the header first. uk_window_scope kept it single, so
                // this save must not fail: adopt the stored row and apply this save to it, exactly
                // as if the other transaction had committed a moment earlier.
                //
                // The re-read must be a LOCKING read: under MySQL's default REPEATABLE READ a plain
                // re-read inside this transaction still sees the snapshot taken at its first
                // consistent read, i.e. it would not see the winner's row at all.
                ReleaseWindowPO raced = windowMapper.selectByScopeForUpdate(scope.name(), subsystemId);
                if (raced == null) {
                    throw ex;
                }
                windowId = raced.getId();
                updateHeader(windowId, enabled, zone, now);
                log.info("release window header was created concurrently, reusing windowId={}",
                    windowId);
            }
        } else {
            windowId = stored.getId();
            updateHeader(windowId, enabled, zone, now);
        }
        // Replace the children: the sets are small, and a delete+insert keeps the stored window
        // exactly equal to what the operator submitted (no orphan rule from an earlier save).
        ruleMapper.deleteByWindow(windowId);
        exceptionMapper.deleteByWindow(windowId);
        for (WindowRule rule : rules) {
            ruleMapper.insert(WindowRulePO.of(windowId, rule));
        }
        for (WindowException exception : exceptions) {
            exceptionMapper.insert(WindowExceptionPO.of(windowId, exception));
        }

        WindowView view = toView(new WindowHit(windowId, window, false));
        log.info("release window saved: windowId={}, scope={}, subsystemId={}, enabled={},"
                + " rules={}, exceptions={}, warnings={}",
            windowId, scope, subsystemId, enabled, rules.size(), exceptions.size(),
            warnings.size());
        if (audit != null) {
            Map<String, Object> before = null;
            if (stored != null) {
                before = new LinkedHashMap<>();
                before.put("enabled", stored.getEnabled());
                before.put("timezone", stored.getTimezone());
            }
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("enabled", enabled);
            after.put("timezone", zone.getId());
            after.put("ruleCount", rules.size());
            after.put("exceptionCount", exceptions.size());
            String objectId = "WINDOW:" + scope.name()
                + (scope == WindowScope.SUBSYSTEM ? ":" + subsystemId : "");
            audit.record(AuditCommand.of(AuditAction.WINDOW_UPDATE, objectId, "发布窗口",
                    "更新发布窗口：" + (scope == WindowScope.GLOBAL ? "全局"
                        : "系统 " + subsystemId))
                .before(before)
                .after(after)
                .scopeSubsystemId(scope == WindowScope.SUBSYSTEM ? subsystemId : null)
                .build());
        }
        return new SaveResult(view, warnings);
    }

    /** Applies a save to an existing header without touching {@code created_at}. */
    private void updateHeader(long windowId, boolean enabled, ZoneId zone, Instant now) {
        windowMapper.update(null, Wrappers.<ReleaseWindowPO>lambdaUpdate()
            .eq(ReleaseWindowPO::getId, windowId)
            .set(ReleaseWindowPO::getEnabled, enabled)
            .set(ReleaseWindowPO::getTimezone, zone.getId())
            .set(ReleaseWindowPO::getUpdatedAt, now));
    }

    // ------------------------------------------------------------------ effective window

    private WindowHit effective(long subsystemId) {
        if (subsystemId > ReleaseWindow.GLOBAL_SUBSYSTEM_ID) {
            ReleaseWindowPO own = windowMapper.selectByScope(WindowScope.SUBSYSTEM.name(),
                subsystemId);
            if (own != null) {
                return new WindowHit(own.getId(), load(own), false);
            }
        }
        ReleaseWindowPO global = windowMapper.selectByScope(WindowScope.GLOBAL.name(),
            ReleaseWindow.GLOBAL_SUBSYSTEM_ID);
        if (global == null) {
            return null;
        }
        return new WindowHit(global.getId(), load(global),
            subsystemId > ReleaseWindow.GLOBAL_SUBSYSTEM_ID);
    }

    private ReleaseWindow load(ReleaseWindowPO header) {
        long windowId = header.getId();
        return header.toDomain(ruleMapper.selectByWindow(windowId),
            exceptionMapper.selectByWindow(windowId));
    }

    // ------------------------------------------------------------------ save validation

    private WindowScope parseScope(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            throw new BizException(BizError.PARAM_INVALID, "窗口范围不能为空",
                Map.of("scope", "不能为空"));
        }
        try {
            return WindowScope.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BizException(BizError.PARAM_INVALID, "窗口范围不合法：" + value,
                Map.of("scope", value));
        }
    }

    private long requireSubsystemId(WindowScope scope, Long subsystemId) {
        if (scope == WindowScope.GLOBAL) {
            return ReleaseWindow.GLOBAL_SUBSYSTEM_ID;
        }
        if (subsystemId == null || subsystemId <= 0) {
            throw new BizException(BizError.PARAM_INVALID, "系统窗口必须指定系统",
                Map.of("subsystemId", String.valueOf(subsystemId)));
        }
        if (subsystemMapper.selectById(subsystemId) == null) {
            throw new BizException(BizError.PARAM_INVALID, "所属系统不存在",
                Map.of("subsystemId", subsystemId));
        }
        return subsystemId;
    }

    /**
     * A read for a negative subsystem id is a client bug, not "the global window": silently
     * answering GLOBAL would let a caller believe a subsystem has no window of its own.
     */
    private static void requireValidSubsystemId(long subsystemId) {
        if (subsystemId < 0) {
            throw new BizException(BizError.PARAM_INVALID, "系统 ID 不能为负数",
                Map.of("subsystemId", subsystemId));
        }
    }

    private ZoneId parseZone(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            return ReleaseWindow.DEFAULT_ZONE;
        }
        try {
            return ZoneId.of(value);
        } catch (RuntimeException ex) {
            throw new BizException(BizError.PARAM_INVALID, "时区不合法：" + value,
                Map.of("timezone", value));
        }
    }

    private static List<WindowRule> parseRules(List<RuleInput> inputs) {
        List<RuleInput> rows = inputs == null ? List.of() : inputs;
        if (rows.size() > MAX_RULES) {
            throw new BizException(BizError.PARAM_INVALID,
                "规则数量不能超过 " + MAX_RULES + " 条", Map.of("rules", rows.size()));
        }
        List<WindowRule> rules = new ArrayList<>(rows.size());
        for (int index = 0; index < rows.size(); index++) {
            RuleInput input = rows.get(index);
            int position = index + 1;
            if (input == null) {
                throw new BizException(BizError.PARAM_INVALID, "规则不能为空",
                    Map.of("rules", "第 " + position + " 条规则为空"));
            }
            if (input.dayOfWeek() < WindowRule.MIN_DAY_OF_WEEK
                    || input.dayOfWeek() > WindowRule.MAX_DAY_OF_WEEK) {
                throw new BizException(BizError.PARAM_INVALID, "星期必须是 1-7",
                    Map.of("dayOfWeek", "第 " + position + " 条规则的星期不合法：" + input.dayOfWeek()));
            }
            if (input.start() == null || input.end() == null) {
                throw new BizException(BizError.PARAM_INVALID, "规则起止时间不能为空",
                    Map.of("start", "第 " + position + " 条规则缺少起止时间"));
            }
            requireMinutePrecision(input.start(), "start", position, "规则");
            requireMinutePrecision(input.end(), "end", position, "规则");
            if (input.start().equals(input.end())) {
                throw new BizException(BizError.PARAM_INVALID, "规则起止时间不能相同",
                    Map.of("start", "第 " + position + " 条规则 " + format(input.start())
                        + " 与结束时间相同"));
            }
            rules.add(new WindowRule(input.dayOfWeek(), input.start(), input.end()));
        }
        rejectOverlaps(rules);
        return List.copyOf(rules);
    }

    /**
     * Rejects a time that is not on a whole minute. The window walk is minute-stepped (it truncates
     * to minutes), so a {@code 21:59:30} start would be evaluated as {@code 21:59} and disagree
     * with the stored value by up to 59 seconds — better to refuse the input than to answer a
     * different question than the one that was written down.
     */
    private static void requireMinutePrecision(LocalTime time, String field, int position,
                                               String kind) {
        if (time.getSecond() != 0 || time.getNano() != 0) {
            throw new BizException(BizError.PARAM_INVALID,
                "时间必须精确到分钟（秒必须为 0）",
                Map.of(field, "第 " + position + " 条" + kind + "时间 " + time
                    + " 带秒/纳秒"));
        }
    }

    /** Two rules of the same weekday may not share a minute. Boundary contact is not an overlap. */
    private static void rejectOverlaps(List<WindowRule> rules) {
        for (int i = 0; i < rules.size(); i++) {
            for (int j = i + 1; j < rules.size(); j++) {
                WindowRule a = rules.get(i);
                WindowRule b = rules.get(j);
                if (a.dayOfWeek() == b.dayOfWeek() && overlaps(a, b)) {
                    throw new BizException(BizError.PARAM_INVALID, "同一天的规则时段不能重叠",
                        Map.of("rules", WEEKDAY_LABELS[a.dayOfWeek() - 1] + "的规则 "
                            + format(a.start()) + "-" + format(a.end()) + " 与 "
                            + format(b.start()) + "-" + format(b.end()) + " 重叠"));
                }
            }
        }
    }

    /**
     * Same-weekday overlap, measured on that weekday's timeline. A cross-midnight rule occupies
     * everything from its start to midnight here; its tail into the next weekday is not compared
     * with that weekday's early rules (design 4.1 only forbids overlapping rules <em>per day</em>).
     */
    private static boolean overlaps(WindowRule a, WindowRule b) {
        int aStart = minutesOfDay(a.start());
        int aEnd = a.crossesMidnight() ? MINUTES_PER_DAY : minutesOfDay(a.end());
        int bStart = minutesOfDay(b.start());
        int bEnd = b.crossesMidnight() ? MINUTES_PER_DAY : minutesOfDay(b.end());
        return aStart < bEnd && bStart < aEnd;
    }

    private static List<WindowException> parseExceptions(List<ExceptionInput> inputs) {
        List<ExceptionInput> rows = inputs == null ? List.of() : inputs;
        if (rows.size() > MAX_EXCEPTIONS) {
            throw new BizException(BizError.PARAM_INVALID,
                "例外数量不能超过 " + MAX_EXCEPTIONS + " 条", Map.of("exceptions", rows.size()));
        }
        List<WindowException> exceptions = new ArrayList<>(rows.size());
        Set<LocalDate> dates = new HashSet<>();
        for (int index = 0; index < rows.size(); index++) {
            ExceptionInput input = rows.get(index);
            int position = index + 1;
            if (input == null || input.date() == null) {
                throw new BizException(BizError.PARAM_INVALID, "例外日期不能为空",
                    Map.of("date", "第 " + position + " 条例外缺少日期"));
            }
            if (!dates.add(input.date())) {
                throw new BizException(BizError.PARAM_INVALID, "同一天只能配置一条例外",
                    Map.of("date", "例外日期重复：" + input.date()));
            }
            WindowException.Mode mode = parseMode(input.mode());
            if (mode == WindowException.Mode.CLOSED) {
                // A closed date has no time bounds; a submitted bound is simply not meaningful and
                // is dropped instead of rejecting a save the operator clearly meant.
                exceptions.add(WindowException.closed(input.date()));
                continue;
            }
            boolean noStart = input.start() == null;
            boolean noEnd = input.end() == null;
            if (noStart && noEnd) {
                exceptions.add(WindowException.openAllDay(input.date()));
                continue;
            }
            if (noStart || noEnd) {
                throw new BizException(BizError.PARAM_INVALID, "例外时段需要同时填写起止时间",
                    Map.of("start", "第 " + position + " 条例外只填写了一个时间"));
            }
            requireMinutePrecision(input.start(), "start", position, "例外");
            requireMinutePrecision(input.end(), "end", position, "例外");
            if (input.start().equals(input.end())) {
                throw new BizException(BizError.PARAM_INVALID, "例外起止时间不能相同",
                    Map.of("start", "第 " + position + " 条例外 " + format(input.start())
                        + " 与结束时间相同"));
            }
            exceptions.add(new WindowException(input.date(), WindowException.Mode.OPEN,
                input.start(), input.end()));
        }
        return List.copyOf(exceptions);
    }

    private static WindowException.Mode parseMode(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            // OPEN is the common case (a one-off evening release); CLOSED must be said explicitly.
            return WindowException.Mode.OPEN;
        }
        try {
            return WindowException.Mode.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BizException(BizError.PARAM_INVALID, "例外类型不合法：" + value,
                Map.of("mode", value));
        }
    }

    /** Slots below 30 minutes or above 12 hours are saved but reported (design 4.1). */
    private static List<String> warningsOf(List<WindowRule> rules, List<WindowException> exceptions) {
        List<String> warnings = new ArrayList<>();
        for (WindowRule rule : rules) {
            String label = WEEKDAY_LABELS[rule.dayOfWeek() - 1] + " " + format(rule.start()) + "-"
                + format(rule.end());
            addLengthWarning(warnings, label, rule.length(), "规则");
        }
        for (WindowException exception : exceptions) {
            if (!exception.hasTimes()) {
                continue;
            }
            long minutes = Duration.between(exception.start(), exception.end()).toMinutes();
            if (minutes < 0) {
                minutes += MINUTES_PER_DAY;
            }
            String label = "例外 " + exception.date() + " " + format(exception.start()) + "-"
                + format(exception.end());
            addLengthWarning(warnings, label, Duration.ofMinutes(minutes), "例外");
        }
        return List.copyOf(warnings);
    }

    private static void addLengthWarning(List<String> warnings, String label, Duration length,
                                         String kind) {
        long minutes = length.toMinutes();
        if (minutes < MIN_SPAN.toMinutes()) {
            warnings.add(label + " " + kind + "时长 " + lengthLabel(length) + "，少于 "
                + MIN_SPAN.toMinutes() + " 分钟，请确认");
        } else if (minutes > MAX_SPAN.toMinutes()) {
            warnings.add(label + " " + kind + "时长 " + lengthLabel(length) + "，超过 "
                + MAX_SPAN.toHours() + " 小时，请确认");
        }
    }

    private static String lengthLabel(Duration length) {
        long minutes = length.toMinutes();
        if (minutes % 60 == 0) {
            return (minutes / 60) + " 小时";
        }
        if (minutes < 60) {
            return minutes + " 分钟";
        }
        return (minutes / 60) + " 小时 " + (minutes % 60) + " 分钟";
    }

    // ------------------------------------------------------------------ view mapping

    private static WindowView toView(WindowHit hit) {
        ReleaseWindow window = hit.window();
        return new WindowView(
            hit.id(),
            window.scope().name(),
            window.subsystemId(),
            window.enabled(),
            window.zoneId().getId(),
            window.rules().stream()
                .map(rule -> new WindowView.RuleView(rule.dayOfWeek(), rule.start(), rule.end()))
                .toList(),
            window.exceptions().stream()
                .map(exception -> new WindowView.ExceptionView(exception.date(),
                    exception.mode().name(), exception.start(), exception.end()))
                .toList(),
            hit.inherited());
    }

    private static WindowCheckView toView(WindowCheck check, WindowHit hit) {
        return new WindowCheckView(check.inWindow(), spanView(check.span()),
            spanView(check.next()), check.configured(), hit == null ? null : hit.id(),
            check.enabled(),
            hit == null ? null : hit.window().zoneId().getId(),
            hit == null ? null : hit.window().scope().name(),
            hit != null && hit.inherited());
    }

    private static WindowSpanView spanView(WindowSpan span) {
        return span == null ? null : new WindowSpanView(span.start(), span.end());
    }

    private static int minutesOfDay(LocalTime time) {
        return time.getHour() * 60 + time.getMinute();
    }

    private static String format(LocalTime time) {
        return time.format(HOUR_MINUTE);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** A stored window header with its loaded domain window and whether it was inherited. */
    private record WindowHit(long id, ReleaseWindow window, boolean inherited) {
    }
}