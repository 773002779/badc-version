package com.sf.release.application;

import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.infra.repo.ReleaseRecordsMapper;
import com.sf.release.infra.repo.po.ReleaseRecordRow;
import com.sf.release.infra.repo.po.ReleaseRecordsCriteria;
import com.sf.release.infra.security.CurrentUser;
import com.sf.release.infra.security.ScopeGuard;
import com.sf.release.infra.security.UserContext;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static java.util.Map.entry;

/**
 * The global 发布记录 page (GAP-10, P4-A Task 2): one page of release records across tasks and
 * systems, newest order first.
 *
 * <p><strong>One row per release order.</strong> The order keeps its publish number across reruns
 * and a rollback does not create an order (rollback design section 3), so the page lists orders, not
 * executions or rollbacks. The newest pipeline instance of the order is joined in the same
 * statement by its largest id; it is {@code null} for an order that was never executed.
 *
 * <p><strong>Query budget.</strong> One count and one page statement; no per-row follow-up. When the
 * count is zero the page query is skipped and an empty page is returned.
 *
 * <p><strong>Data scope is fail-closed.</strong> {@code allUnits} sees every unit, otherwise the
 * scope units are ANDed into the statement; a caller with no unit scope gets an empty page without
 * touching the data. The {@code unitId}/{@code systemId}/keyword/status filters are always ANDed
 * with the scope, so no filter can widen it; an explicit {@code unitId} outside the scope is
 * refused with 40009 (the same rule as the rollback record page).
 *
 * <p><strong>Labels.</strong> The page sends the machine status value plus its Chinese label, so the
 * page renders without owning a copy of the vocabulary and the web package never imports a domain
 * enum. The label map mirrors the item lifecycle (the order status mirrors it); an unknown stored
 * value falls back to the raw value instead of being invented.
 */
@Service
public class ReleaseRecordsService {

    /** Page size ceiling, shared with the other paged read services. */
    static final int MAX_PAGE_SIZE = 100;

    /** The keyword column budget of the page (publish number / CRID contains). */
    static final int MAX_KEYWORD_LENGTH = 64;

    /**
     * Chinese labels of the release item / release order lifecycle. {@code EXECUTING} is the
     * order-only production execution marker; {@code INIT} is the order's creation marker before
     * admission. Kept here (application) so the web DTO carries only strings.
     */
    private static final Map<String, String> STATUS_LABELS = Map.ofEntries(
        entry("INIT", "已创建"),
        entry("PENDING", "待发布"),
        entry("BUILDING", "构建中"),
        entry("TESTING", "测试中"),
        entry("GATE_PENDING", "待生产准入"),
        entry("SUBMITTED", "已提交"),
        entry("PENDING_APPROVAL", "待审批"),
        entry("READY", "待执行"),
        entry("EXECUTING", "执行中"),
        entry("PROD_DEPLOYING", "生产部署中"),
        entry("DONE", "已完成"),
        entry("GATE_FAILED", "门禁未通过"),
        entry("FAILED", "失败"),
        entry("REJECTED", "已驳回"),
        entry("CLOSED", "已关闭"),
        entry("TERMINATED", "已终止"),
        entry("ROLLED_BACK", "已回滚"));

    private final ReleaseRecordsMapper recordsMapper;

    public ReleaseRecordsService(ReleaseRecordsMapper recordsMapper) {
        this.recordsMapper = Objects.requireNonNull(recordsMapper, "recordsMapper must not be null");
    }

    /**
     * One page of release records, newest first.
     *
     * @throws BizException 40000 an invalid page request or an oversized keyword; 40009 the
     *                      explicit {@code unitId} filter is outside the caller's data scope
     */
    public PageResult page(ReleaseRecordsQuery query) {
        if (query == null) {
            throw new BizException(BizError.PARAM_INVALID, "分页参数不能为空");
        }
        requirePage(query.pageNo(), query.pageSize());
        String keyword = requireKeyword(query.keyword());
        String status = trimToNull(query.status());
        if (query.unitId() != null) {
            ScopeGuard.assertUnitInScope(query.unitId());
        }

        CurrentUser user = UserContext.current();
        if (!user.allUnits() && user.scopeUnitIds().isEmpty()) {
            return new PageResult(0, query.pageNo(), query.pageSize(), List.of());
        }
        ReleaseRecordsCriteria criteria = new ReleaseRecordsCriteria(keyword, status,
            query.subsystemId(), query.unitId(), user.allUnits(), List.copyOf(user.scopeUnitIds()));

        long total = recordsMapper.countRecords(criteria);
        if (total == 0) {
            return new PageResult(0, query.pageNo(), query.pageSize(), List.of());
        }
        long offset = (long) (query.pageNo() - 1) * query.pageSize();
        List<ReleaseRecordView> records = recordsMapper
            .selectRecords(criteria, offset, query.pageSize())
            .stream()
            .map(ReleaseRecordsService::toView)
            .toList();
        return new PageResult(total, query.pageNo(), query.pageSize(), records);
    }

    // ------------------------------------------------------------------ validation and mapping

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

    /** The trimmed keyword, or {@code null}; longer than 64 characters is a 40000. */
    private static String requireKeyword(String raw) {
        String keyword = trimToNull(raw);
        if (keyword == null) {
            return null;
        }
        if (keyword.length() > MAX_KEYWORD_LENGTH) {
            throw new BizException(BizError.PARAM_INVALID,
                "关键字长度不能超过 " + MAX_KEYWORD_LENGTH, Map.of("keyword", "长度超限"));
        }
        return keyword;
    }

    private static ReleaseRecordView toView(ReleaseRecordRow row) {
        return new ReleaseRecordView(
            row.getPublishNo(),
            row.getItemId(),
            row.getCrid(),
            row.getVersion(),
            row.getSubsystemId(),
            row.getSubsystemName(),
            row.getUnitId(),
            row.getUnitName(),
            row.getOrderStatus(),
            labelOf(row.getOrderStatus()),
            row.getItemStatus(),
            labelOf(row.getItemStatus()),
            row.getPlannedReleaseTime(),
            row.getCreatedAt(),
            row.getExecutedBy(),
            row.getPipelineNo(),
            row.getPipelineStatus(),
            emergencyFlagOf(row.getEmergencyRelease()),
            row.getEmergencyReason(),
            row.getEmergencyBy(),
            instantOf(row.getEmergencyAt()));
    }

    /**
     * The J11 marker as {@code "true"}/{@code "false"} (P6-D design §3: the page paints the red chip
     * from this string). A pre-V32 {@code null} column means the same as the 0 default.
     */
    private static String emergencyFlagOf(Integer flag) {
        return flag != null && flag != 0 ? "true" : "false";
    }

    /** A stored instant as an ISO-8601 string (the emergency fields travel as strings). */
    private static String instantOf(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    /** The Chinese label of a stored status; an unknown value degrades to the raw value. */
    private static String labelOf(String status) {
        if (status == null) {
            return null;
        }
        return STATUS_LABELS.getOrDefault(status, status);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    // ------------------------------------------------------------------ read model

    /** Page request of the global records list; every filter except the page is optional. */
    public record ReleaseRecordsQuery(int pageNo, int pageSize, String keyword, String status,
                                      Long subsystemId, Long unitId) {
    }

    /** One release order as the page shows it. */
    public record ReleaseRecordView(
        String publishNo,
        Long itemId,
        String crid,
        String version,
        Long subsystemId,
        String subsystemName,
        Long unitId,
        String unitName,
        String orderStatus,
        String orderStatusLabel,
        String itemStatus,
        String itemStatusLabel,
        Instant plannedReleaseTime,
        Instant createdAt,
        String executedBy,
        String pipelineNo,
        String pipelineStatus,
        /**
         * J11 marker as {@code "true"}/{@code "false"}; the other three are null unless an emergency
         * release happened (P6-D design §2/§3).
         */
        String emergencyRelease,
        String emergencyReason,
        String emergencyBy,
        String emergencyAt) {
    }

    /** Page response shape shared by the paged read services. */
    public record PageResult(long total, long pageNo, long pageSize,
                             List<ReleaseRecordView> records) {

        public PageResult {
            records = records == null ? List.of() : List.copyOf(records);
        }
    }
}