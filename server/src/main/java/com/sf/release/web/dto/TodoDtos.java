package com.sf.release.web.dto;

import com.sf.release.application.TodoQueryService;

import java.util.List;

/**
 * Response bodies of {@code GET /api/v1/todos} (P5-C design section 1).
 *
 * <p>The four groups keep the design's keys ({@code test / approval / execute / mine}). Every item
 * field is a string, including the ids and {@code waitingHours}: the page only renders and navigates,
 * and the application layer already resolved the status label through the domain enums, so the web
 * package never imports one. {@code count} is the group's full count (the number of matching rows,
 * or the number of blocking change items for {@code mine}), not the length of {@code items}.
 *
 * <p>{@code waitingSince} is an ISO-8601 instant; {@code waitingHours} the whole hours elapsed since
 * then, computed server-side with the injected clock.
 */
public final class TodoDtos {

    private TodoDtos() {
    }

    public static TodoBoardResponse boardOf(TodoQueryService.TodoBoard board) {
        return new TodoBoardResponse(
            groupOf(board.test()),
            groupOf(board.approval()),
            groupOf(board.execute()),
            groupOf(board.mine()));
    }

    private static TodoGroupResponse groupOf(TodoQueryService.TodoGroup group) {
        return new TodoGroupResponse(group.count(), group.items().stream()
            .map(TodoDtos::itemOf)
            .toList());
    }

    private static TodoItemResponse itemOf(TodoQueryService.TodoItem item) {
        return new TodoItemResponse(
            item.objectType(),
            item.objectId(),
            item.label(),
            item.status(),
            item.statusLabel(),
            item.waitingSince() == null ? null : item.waitingSince().toString(),
            String.valueOf(item.waitingHours()),
            item.unitId() == null ? null : String.valueOf(item.unitId()),
            item.link());
    }

    /** The four groups; every group is present even when it is empty. */
    public record TodoBoardResponse(
        TodoGroupResponse test,
        TodoGroupResponse approval,
        TodoGroupResponse execute,
        TodoGroupResponse mine) {
    }

    /** One group: the full count plus at most {@code limit} items. */
    public record TodoGroupResponse(long count, List<TodoItemResponse> items) {

        public TodoGroupResponse {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    /** One row: the design's fields, all of them strings. */
    public record TodoItemResponse(
        String objectType,
        String objectId,
        String label,
        String status,
        String statusLabel,
        String waitingSince,
        String waitingHours,
        String unitId,
        String link) {
    }
}