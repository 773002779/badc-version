package com.sf.release.web.controller;

import com.sf.release.application.RerunAppService;
import com.sf.release.application.RerunAppService.RerunResult;
import com.sf.release.common.ApiResponse;
import com.sf.release.common.security.Permissions;
import com.sf.release.common.security.RequirePerm;
import com.sf.release.web.dto.RerunDtos.RerunRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 重新发布 endpoint (tech design §5.3, requirements §4.5 C15).
 *
 * <p>{@code POST /api/v1/items/{itemId}/rerun} decides the scope from the branch head commit and
 * creates a new pipeline instance under the existing 发布单. It requires the
 * {@code release:publish} permission point. The optional {@code confirmRevokeTestPass} flag is
 * the explicit second confirmation required when a changed commit revokes the previous test
 * pass; without it the service answers with a business error telling the caller to confirm.
 */
@RestController
@RequestMapping("/api/v1/items")
public class RerunController {

    private final RerunAppService rerunAppService;

    public RerunController(RerunAppService rerunAppService) {
        this.rerunAppService = rerunAppService;
    }

    @RequirePerm(Permissions.RELEASE_PUBLISH)
    @PostMapping("/{itemId}/rerun")
    public ApiResponse<RerunResult> rerun(@PathVariable long itemId,
                                          @RequestBody(required = false) RerunRequest request) {
        boolean confirm = request != null && Boolean.TRUE.equals(request.confirmRevokeTestPass());
        return ApiResponse.ok(rerunAppService.rerun(itemId, confirm));
    }
}
