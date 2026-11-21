package com.sf.release.web.controller;

import com.sf.release.application.ItemAppService;
import com.sf.release.application.ItemAppService.ItemCreated;
import com.sf.release.application.PublishAppService;
import com.sf.release.application.PublishAppService.PublishResult;
import com.sf.release.common.ApiResponse;
import com.sf.release.common.security.Permissions;
import com.sf.release.common.security.RequirePerm;
import com.sf.release.web.dto.ItemDtos.CreateItemRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 变更项 write endpoints (C2): the two HTTP entry points of the P1 release flow.
 *
 * <ul>
 *   <li>{@code POST /api/v1/items} — creates a {@code PENDING} change item for a deployment unit
 *       and returns {@code {itemId, crid}}. Requires {@code item:create}. The branch is
 *       <strong>read-only</strong>: it is taken from the unit's CODING build binding (C21), so a
 *       client-supplied {@code sourceBranch} is ignored rather than trusted.</li>
 *   <li>{@code POST /api/v1/items/{itemId}/publish} — publishes the item: one 发布单, one
 *       pre-generated pipeline, and the response {@code {publishNo, pipelineNo}}. Requires
 *       {@code release:publish} (and the C20 hard rule keeps OPS/OPS_LEAD out of it).</li>
 * </ul>
 *
 * <p><strong>No generic event endpoint.</strong> The item state machine is advanced by the
 * pipeline/engine events, not by an HTTP "transit" call: exposing a generic event endpoint would
 * let a client drive states the pipeline did not produce. The only write paths are the two above.
 *
 * <p>Every handler is authenticated by {@code PermissionInterceptor}; the data scope of the
 * owning unit is enforced inside the application services (40009).
 */
@RestController
@RequestMapping("/api/v1/items")
public class ItemController {

    private final ItemAppService itemAppService;
    private final PublishAppService publishAppService;

    public ItemController(ItemAppService itemAppService, PublishAppService publishAppService) {
        this.itemAppService = itemAppService;
        this.publishAppService = publishAppService;
    }

    /** Creates the change item; the body's {@code sourceBranch} is ignored (binding branch). */
    @RequirePerm(Permissions.ITEM_CREATE)
    @PostMapping
    public ApiResponse<ItemCreated> create(@Valid @RequestBody CreateItemRequest request) {
        return ApiResponse.ok(itemAppService.createFromBinding(
            request.taskId(),
            request.unitId(),
            request.version(),
            request.planReleaseDate()));
    }

    /** Publishes the item and returns its 发布单 number and pipeline number. */
    @RequirePerm(Permissions.RELEASE_PUBLISH)
    @PostMapping("/{itemId}/publish")
    public ApiResponse<PublishResult> publish(@PathVariable long itemId) {
        return ApiResponse.ok(publishAppService.publish(itemId));
    }
}