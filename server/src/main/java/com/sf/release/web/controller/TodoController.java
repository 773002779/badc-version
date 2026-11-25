package com.sf.release.web.controller;

import com.sf.release.application.TodoQueryService;
import com.sf.release.common.ApiResponse;
import com.sf.release.common.security.DataScope;
import com.sf.release.web.dto.TodoDtos;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The 待办中心 endpoint (P5-C design section 1): {@code GET /api/v1/todos?limit=}.
 *
 * <p>Any authenticated caller may read; the four groups are already reduced to "my work" and to the
 * caller's data scope by {@link TodoQueryService}. An unauthenticated request is rejected by the
 * permission interceptor with 40009, like every other endpoint. The response is the usual
 * {@link ApiResponse} envelope; a {@code limit} outside 1–20 is a 40000.
 *
 * <p>An empty database answers 200 with four zero-count groups.
 */
@RestController
@RequestMapping("/api/v1/todos")
public class TodoController {

    private final TodoQueryService todoQueryService;

    public TodoController(TodoQueryService todoQueryService) {
        this.todoQueryService = todoQueryService;
    }

    @DataScope
    @GetMapping
    public ApiResponse<TodoDtos.TodoBoardResponse> board(
            @RequestParam(name = "limit", required = false) Integer limit) {
        return ApiResponse.ok(TodoDtos.boardOf(todoQueryService.board(limit)));
    }
}