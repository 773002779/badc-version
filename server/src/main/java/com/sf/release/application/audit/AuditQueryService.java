package com.sf.release.application.audit;

import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.domain.audit.AuditAction;
import com.sf.release.domain.audit.AuditObjectType;
import com.sf.release.infra.repo.AuditLogMapper;
import com.sf.release.infra.repo.po.AuditLogCriteria;
import com.sf.release.infra.repo.po.AuditLogPO;
import com.sf.release.infra.security.CurrentUser;
import com.sf.release.infra.security.UserContext;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The 审计日志 reads (audit design section 4): the paged page and the CSV export, both driven by the
 * same filter and the same data-scope predicate.
 *
 * <p><strong>Data scope is fail-closed.</strong> The visibility rule lives in
 * {@link AuditLogCriteria} and in the mapper SQL: a scoped caller sees unit-scoped rows of the
 * caller's units and system-scoped rows of those units' subsystems; platform rows (both scope
 * columns empty) stay invisible. A caller with no unit scope gets an empty page/CSV without any
 * statement running. Unlike {@code ReleaseRecordsService}, an explicit {@code unitId} outside the
 * scope is not 40009 but an empty result: the audit page must not reveal whether a unit exists
 * (design section 6). The check is the same {@code allUnits ∪ scopeUnitIds} rule
 * {@code ScopeGuard} enforces, applied as a filter instead of a throw.
 *
 * <p><strong>Validation.</strong> {@code page >= 1}, {@code 1 <= size <= 100}, {@code from <= to},
 * and an {@code action}/{@code objectType} that is not in the enum is a 40000 naming the parameter.
 * The mapper compares the stored machine values, so the parsed enum names are what is bound.
 *
 * <p><strong>Export.</strong> At most 5000 rows, newest first, UTF-8 with a BOM, Chinese header,
 * every field quoted and internal quotes doubled; the two JSON columns are flattened to one line.
 * The fetch asks for 5001 rows so "exactly 5000" is not reported as truncated; a truncated export
 * appends a final note row. The newlines inside other fields are kept (a quoted CSV field may span
 * lines, and the design asks for that explicitly).
 */
@Service
public class AuditQueryService {

    /** Page size ceiling, shared with the other paged read services. */
    static final int MAX_PAGE_SIZE = 100;

    /** The design's export ceiling. */
    static final int EXPORT_LIMIT = 5000;

    /** One row more than the ceiling, so truncation is detectable. */
    static final int EXPORT_FETCH = EXPORT_LIMIT + 1;

    /** The note appended as the last CSV row when the result was truncated. */
    static final String TRUNCATION_NOTE =
        "结果超过 " + EXPORT_LIMIT + " 行，已截断；请缩小时间范围或增加筛选条件后重新导出";

    private static final String CSV_HEADER = String.join(",", quoted("编号"), quoted("动作码"),
        quoted("动作名称"), quoted("操作人"), quoted("对象类型"), quoted("对象ID"), quoted("对象名称"),
        quoted("摘要"), quoted("原因"), quoted("变更前"), quoted("变更后"), quoted("跟踪号"),
        quoted("时间"));

    private static final String CSV_LINE = "\r\n";

    /** Local time, matching the other operator-facing timestamps of the platform. */
    private static final DateTimeFormatter CSV_TIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final AuditLogMapper auditLogMapper;

    public AuditQueryService(AuditLogMapper auditLogMapper) {
        this.auditLogMapper = auditLogMapper;
    }

    // ------------------------------------------------------------------ page

    /**
     * One page of audit rows, newest first.
     *
     * @throws BizException 40000 an invalid page/filter request
     */
    public Page page(int pageNo, int pageSize, AuditQuery query) {
        requirePage(pageNo, pageSize);
        AuditLogCriteria criteria = criteriaOf(query);
        if (criteria == null) {
            return new Page(0, pageNo, pageSize, List.of());
        }
        long total = auditLogMapper.countLogs(criteria);
        if (total == 0) {
            return new Page(0, pageNo, pageSize, List.of());
        }
        long offset = (long) (pageNo - 1) * pageSize;
        List<AuditView> records = auditLogMapper
            .selectPage(criteria, offset, pageSize)
            .stream()
            .map(AuditQueryService::toView)
            .toList();
        return new Page(total, pageNo, pageSize, records);
    }

    // ------------------------------------------------------------------ export

    /**
     * The CSV export of the same filter, newest first, at most {@link #EXPORT_LIMIT} rows.
     *
     * @throws BizException 40000 an invalid filter request
     */
    public byte[] exportCsv(AuditQuery query) {
        AuditLogCriteria criteria = criteriaOf(query);
        List<AuditLogPO> rows = criteria == null
            ? List.of() : auditLogMapper.selectExport(criteria, EXPORT_FETCH);
        boolean truncated = rows.size() > EXPORT_LIMIT;
        if (truncated) {
            rows = rows.subList(0, EXPORT_LIMIT);
        }
        StringBuilder csv = new StringBuilder("\uFEFF");
        csv.append(CSV_HEADER).append(CSV_LINE);
        for (AuditLogPO row : rows) {
            csv.append(csvRow(row)).append(CSV_LINE);
        }
        if (truncated) {
            csv.append(quoted(TRUNCATION_NOTE)).append(CSV_LINE);
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ filters and scope

    /**
     * The resolved criteria, or {@code null} when the caller cannot see any row (empty scope or an
     * explicit unit outside the scope). The null answer is the fail-closed short circuit: no
     * statement runs and the caller gets an empty page/CSV.
     */
    private static AuditLogCriteria criteriaOf(AuditQuery raw) {
        AuditQuery query = raw == null
            ? new AuditQuery(null, null, null, null, null, null, null) : raw;
        AuditObjectType objectType = parseObjectType(query.objectType());
        AuditAction action = parseAction(query.action());
        Instant from = query.from();
        Instant to = query.to();
        if (from != null && to != null && from.isAfter(to)) {
            throw new BizException(BizError.PARAM_INVALID, "起始时间不能晚于结束时间",
                Map.of("from", from.toString(), "to", to.toString()));
        }
        String objectId = trimToNull(query.objectId());
        String actor = trimToNull(query.actor());
        Long unitId = query.unitId();
        CurrentUser user = UserContext.current();
        if (unitId != null) {
            if (unitId <= 0) {
                throw new BizException(BizError.PARAM_INVALID, "部署单元不能为空",
                    Map.of("unitId", unitId));
            }
            if (!visible(user, unitId)) {
                return null;
            }
        }
        if (!user.allUnits() && user.scopeUnitIds().isEmpty()) {
            return null;
        }
        return new AuditLogCriteria(
            objectType == null ? null : objectType.name(),
            objectId,
            action == null ? null : action.name(),
            actor,
            from,
            to,
            unitId,
            user.allUnits(),
            List.copyOf(user.scopeUnitIds()));
    }

    /** The {@code ScopeGuard} visibility rule, applied as a predicate instead of a throw. */
    private static boolean visible(CurrentUser user, long unitId) {
        return user.allUnits() || user.scopeUnitIds().contains(unitId);
    }

    private static AuditObjectType parseObjectType(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        AuditObjectType parsed = AuditObjectType.parse(value);
        if (parsed == null) {
            throw new BizException(BizError.PARAM_INVALID,
                "对象类型不合法：" + value, Map.of("objectType", value));
        }
        return parsed;
    }

    private static AuditAction parseAction(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        AuditAction parsed = AuditAction.parse(value);
        if (parsed == null) {
            throw new BizException(BizError.PARAM_INVALID, "动作不合法：" + value,
                Map.of("action", value));
        }
        return parsed;
    }

    private static void requirePage(int pageNo, int pageSize) {
        if (pageNo < 1) {
            throw new BizException(BizError.PARAM_INVALID, "页码必须从 1 开始",
                Map.of("pageNo", pageNo));
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new BizException(BizError.PARAM_INVALID,
                "每页条数必须在 1 到 " + MAX_PAGE_SIZE + " 之间", Map.of("pageSize", pageSize));
        }
    }

    // ------------------------------------------------------------------ mapping

    private static AuditView toView(AuditLogPO row) {
        AuditAction action = AuditAction.parse(row.getAction());
        return new AuditView(
            row.getId() == null ? 0L : row.getId(),
            row.getAction(),
            action == null ? row.getAction() : action.label(),
            row.getActor(),
            row.getObjectType(),
            row.getObjectId(),
            row.getObjectLabel(),
            row.getSummary(),
            row.getReason(),
            row.getBeforeJson(),
            row.getAfterJson(),
            row.getTraceId(),
            row.getCreatedAt());
    }

    private static String csvRow(AuditLogPO row) {
        List<String> cells = new ArrayList<>(13);
        cells.add(String.valueOf(row.getId() == null ? 0L : row.getId()));
        cells.add(row.getAction());
        AuditAction action = AuditAction.parse(row.getAction());
        cells.add(action == null ? row.getAction() : action.label());
        cells.add(row.getActor());
        cells.add(row.getObjectType());
        cells.add(row.getObjectId());
        cells.add(row.getObjectLabel());
        cells.add(row.getSummary());
        cells.add(row.getReason());
        cells.add(flatten(row.getBeforeJson()));
        cells.add(flatten(row.getAfterJson()));
        cells.add(row.getTraceId());
        cells.add(row.getCreatedAt() == null ? null : CSV_TIME.format(row.getCreatedAt()));
        StringBuilder line = new StringBuilder();
        for (int index = 0; index < cells.size(); index++) {
            if (index > 0) {
                line.append(',');
            }
            line.append(quoted(cells.get(index)));
        }
        return line.toString();
    }

    /** JSON kept in one CSV cell: newlines become spaces; the JSON itself is not reformatted. */
    private static String flatten(String json) {
        if (json == null) {
            return null;
        }
        return json.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ');
    }

    /** CSV escaping: wrap in double quotes, double every internal quote, keep other characters. */
    static String quoted(String value) {
        String text = value == null ? "" : value;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    // ------------------------------------------------------------------ read model

    /**
     * The audit filter: every field is optional. {@code action}/{@code objectType} are the API
     * strings, parsed case-insensitively by the service; {@code unitId} is the unit-detail
     * convenience filter.
     */
    public record AuditQuery(
        String objectType,
        String objectId,
        String action,
        String actor,
        Instant from,
        Instant to,
        Long unitId) {
    }

    /** One page plus the full match count. */
    public record Page(long total, long pageNo, long pageSize, List<AuditView> records) {

        public Page {
            records = records == null ? List.of() : List.copyOf(records);
        }
    }
}