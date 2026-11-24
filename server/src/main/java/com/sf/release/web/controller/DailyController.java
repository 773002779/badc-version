package com.sf.release.web.controller;

import com.sf.release.application.DailyReleaseService;
import com.sf.release.common.ApiResponse;
import com.sf.release.common.security.DataScope;
import com.sf.release.common.security.Permissions;
import com.sf.release.common.security.RequirePerm;
import com.sf.release.web.dto.DailyDtos;
import com.sf.release.web.dto.DailyDtos.DailyDetailView;
import com.sf.release.web.dto.DailyDtos.DailyPageView;
import com.sf.release.web.dto.DailyDtos.StartView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 日常发布 endpoints (GAP-04, P5-A design §5):
 *
 * <ul>
 *   <li>{@code POST /api/v1/items/{itemId}/daily} — creates and auto-starts one daily release;
 *       requires {@code release:daily} plus the unit's data scope. Answers
 *       {@code {pipelineNo, instanceId}}. It never takes the unit lock, never changes the item
 *       status and never creates a 发布单, so it may be called while an online release of the same
 *       unit is running;</li>
 *   <li>{@code GET /api/v1/items/{itemId}/daily?page=&size=} — the item's daily records, newest
 *       first, data scoped;</li>
 *   <li>{@code GET /api/v1/daily/{pipelineNo}} — one daily instance with its nodes, tasks and
 *       engine log links.</li>
 * </ul>
 *
 * <p><strong>Permissions.</strong> Starting is the only decision point, so only the POST needs a
 * button permission; the two reads need authentication plus data scope (the page greys the button,
 * it does not hide the history). The service re-checks {@code release:daily}, so a non-HTTP caller
 * gets exactly the same rules.
 *
 * <p><strong>Scope.</strong> The item's owning deployment unit is checked inside
 * {@link DailyReleaseService} ({@code ScopeGuard} → 40009), which is also why the GET handlers
 * carry {@link DataScope}: the marker documents the requirement next to the route.
 *
 * <p>The controller is a thin translation layer and imports no domain or infrastructure enum; the
 * DTOs carry the application views with machine values as strings.
 */
@RestController
@RequestMapping("/api/v1")
public class DailyController {

    private final DailyReleaseService dailyReleaseService;

    public DailyController(DailyReleaseService dailyReleaseService) {
        this.dailyReleaseService = dailyReleaseService;
    }

    /** Starts a daily release of the item; the creator is the authenticated caller. */
    @RequirePerm(Permissions.RELEASE_DAILY)
    @PostMapping("/items/{itemId}/daily")
    public ApiResponse<StartView> start(@PathVariable long itemId) {
        return ApiResponse.ok(DailyDtos.of(dailyReleaseService.start(itemId)));
    }

    /** The daily records of the item, newest first. */
    @DataScope
    @GetMapping("/items/{itemId}/daily")
    public ApiResponse<DailyPageView> records(
            @PathVariable long itemId,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "10") int size) {
        return ApiResponse.ok(DailyDtos.of(dailyReleaseService.page(itemId, page, size)));
    }

    /** One daily instance: its row, its nodes and their tasks (with the engine log links). */
    @DataScope
    @GetMapping("/daily/{pipelineNo}")
    public ApiResponse<DailyDetailView> detail(@PathVariable String pipelineNo) {
        return ApiResponse.ok(DailyDtos.of(dailyReleaseService.detail(pipelineNo)));
    }
}