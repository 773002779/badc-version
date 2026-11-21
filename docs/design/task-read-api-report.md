# P1 task read API — implementation report

Status: complete, committed on `main` as `2917647` (server/ only).
Scope: three read-only endpoints for the task page; authentication only, no new permissions,
no migration, no `web/` change. This report itself is not committed (`docs/` stays out of the
code commit).

## 1. Final endpoints

### `GET /tasks`

Query parameters:

| name | default | meaning |
|---|---|---|
| `pageNo` | `1` | 1-based page number; `< 1` → 40000 with `{pageNo}` |
| `pageSize` | `10` | 1..100; out of range → 40000 with `{pageSize}` |
| `subsystemId` | absent | optional equality filter; empty string = absent |
| `status` | absent | optional `ACTIVE` / `ARCHIVED` (case-insensitive); empty = absent; other value → 40000 with `{status}` |
| `keyword` | absent | optional `LIKE %keyword%` on `release_task.name`; trimmed; empty = absent |

Ordering: `release_task.id DESC` (newest first). The exact frontend URL works:
`/tasks?pageNo=1&pageSize=10&subsystemId=&status=&keyword=`.

Response:

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "total": 3,
    "pageNo": 1,
    "pageSize": 10,
    "records": [
      {
        "id": 12,
        "name": "GAMMA release",
        "subsystemId": 8,
        "subsystemName": "SCC-READ-B",
        "creator": "t-read-http-user",
        "planFinishDate": "2026-12-01",
        "purpose": "gamma purpose",
        "status": "ACTIVE",
        "createdAt": "2026-11-03T08:00:00Z",
        "done": 1,
        "total": 1,
        "rate": 1.0,
        "hasBlocking": false
      }
    ]
  },
  "traceId": "..."
}
```

`PageResult` is the existing `SubsystemAppService.PageResult` record — no second paging type.
`TaskView` field order/types: `id` (number), `name` (string), `subsystemId` (number),
`subsystemName` (string | null), `creator` (string), `planFinishDate` (string `yyyy-MM-dd` |
null), `purpose` (string | null), `status` (string), `createdAt` (ISO-8601 instant string),
`done` (number), `total` (number), `rate` (number 0..1), `hasBlocking` (boolean).

### `GET /tasks/{id}`

Response: `ApiResponse<TaskDetailView>` where

```json
{
  "task": { "...": "TaskView exactly as above" },
  "members": [
    { "userId": "t-read-member-1", "name": "Member One", "role": "DEV" }
  ],
  "itemCount": 4
}
```

`MemberView` fields: `userId` (string), `name` (string | null — `LEFT JOIN app_user`),
`role` (string). Members are ordered by `user_id`. `itemCount` is the number of 变更项 of the
task (all item statuses; not only done).

### `GET /tasks/{id}/items`

Response: `ApiResponse<ItemView[]>` (a bare JSON array in `data`), ordered `release_item.id DESC`.

`ItemView` fields: `id` (number), `crid` (string), `unitId` (number), `unitName` (string | null
— `LEFT JOIN deploy_unit`), `type` (string), `version` (string), `sourceBranch` (string),
`planReleaseDate` (string `yyyy-MM-dd`), `status` (string), `gateStatus` (string),
`createdAt` (ISO-8601 instant string), `updatedAt` (ISO-8601 instant string).

### Errors and authentication

All three endpoints require the regular authenticated caller (`X-User-Id`); none carries a
`@RequirePerm` / new permission. Missing/inactive identity → 40009 (handled by
`PermissionInterceptor`). Unknown task id on detail/items → HTTP 200 with `code = 40000`,
`data = { "taskId": 999999 }`.

## 2. Query strategy

Implementation lives in `TaskAppService` (`page`, `detail`, `items`) with read SQL in
`TaskQueryMapper`. The application boundary keeps the web layer on `application` view records
only.

- `GET /tasks` — **4 statements per request, independent of page size**, no N+1:
  1. `SELECT COUNT(*) FROM release_task ...` (filters);
  2. `SELECT ... FROM release_task ... ORDER BY id DESC LIMIT offset,size` (MyBatis-Plus
     `Page` with `searchCount=false`, so the count is not duplicated);
  3. one `subsystem` batch lookup (`selectBatchIds`) for the distinct subsystem ids of the page;
  4. one grouped `release_item` query
     `SELECT task_id, status, COUNT(*) ... WHERE task_id IN (...) GROUP BY task_id, status`,
     grouped in memory per task and fed to `TaskProgress.ofCounts`.
  The test asserts exactly 4 statements for a 3-task page and for a 1-task page.
- `GET /tasks/{id}` — 4 statements: task row, subsystem name, grouped status counts (reused for
  both the task progress and `itemCount`), members `LEFT JOIN app_user`.
- `GET /tasks/{id}/items` — 2 statements: task existence check, then one
  `release_item LEFT JOIN deploy_unit ... ORDER BY i.id DESC`.

Progress rule: `TaskProgress.ofCounts(Map<ItemStatus, Long>)` applies the existing
done/blocking rule (`DONE` is done; in-flight non-terminal states block; terminal/closed states
and `DONE` do not). `rate = done / total`, `0` for a task without items. No progress value is
persisted.

All values are bound through MyBatis `#{}`; the keyword uses a parameterized `LIKE`. No SQL
string concatenation of request values.

## 3. Tests and commands

New tests:

- `server/src/test/java/com/sf/release/web/controller/TaskReadControllerTest.java` (13 cases):
  - newest-first ordering and every `TaskView` field;
  - paging (`pageNo`/`pageSize`, total, page contents);
  - the exact frontend URL with empty filter values;
  - each filter (`subsystemId`, `status`, `keyword`) and their combination;
  - keyword injection string is treated as data (0 hits);
  - invalid status → 40000 `{status}`;
  - progress aggregation (`1/4`, rate 0.25, `hasBlocking = true`) and the statement count
    proving no N+1 (exactly 4, unchanged for `pageSize=1`);
  - detail: task fields, progress, members (ordered by user id), `itemCount`;
  - member whose `app_user` row is gone keeps `name: null`;
  - items: joined unit name, item ordering, timestamps; empty item list;
  - unknown id → 40000 `{taskId}` for detail and items;
  - all three reads require authentication (40009).
- `server/src/test/java/com/sf/release/task/TaskProgressTest.java` (3 cases): `ofCounts`
  zero-item rule, DONE-only completion, non-blocking terminal states.
- The existing `ArchitectureTest` stays green: `web` does not import `domain`/`infra`.

Commands and results (from `D:\develop\workspace\version-pipeline\server`):

- `mvn -q "-Dtest=TaskProgressTest,TaskReadControllerTest" test`
  → `Tests run: 16, Failures: 0, Errors: 0, Skipped: 0`.
- `mvn -q verify`
  → exit code `0`; full suite aggregated from `target/surefire-reports`:
  `309 tests, 0 failures, 0 errors` across 36 test-class reports (includes ArchUnit and the
  Spring context/wiring tests).
- Live smoke check against the rebuilt jar on `127.0.0.1:8080`:
  `GET /tasks?pageNo=1&pageSize=2` → `code: 0`, correct `total/pageNo/pageSize/records` and
  the full field set; `GET /tasks/999999` → `code: 40000`, `data.taskId = 999999`.

Note for reruns on Windows: the Spring Boot `repackage` step cannot rename the jar while an
older instance of that jar is running. One such orphaned process was stopped before the green
`mvn -q verify`, then the backend was restarted from the rebuilt jar.

## 4. Files

Committed in `2917647`:

- `server/src/main/java/com/sf/release/application/TaskAppService.java`
- `server/src/main/java/com/sf/release/domain/task/TaskProgress.java`
- `server/src/main/java/com/sf/release/web/controller/TaskController.java`
- `server/src/main/java/com/sf/release/infra/repo/TaskQueryMapper.java`
- `server/src/main/java/com/sf/release/infra/repo/po/TaskItemRow.java`
- `server/src/main/java/com/sf/release/infra/repo/po/TaskMemberRow.java`
- `server/src/main/java/com/sf/release/infra/repo/po/TaskStatusCountRow.java`
- `server/src/test/java/com/sf/release/task/TaskProgressTest.java`
- `server/src/test/java/com/sf/release/web/controller/TaskReadControllerTest.java`

No migration, no permission seed, no `web/` or `docs/` file was part of the commit.

## 5. Open items / notes

- **No data-scope filter** on these reads, per the target contract (authentication only). If
  the frontend later needs unit/subsystem scoping, it must be added explicitly.
- `keyword` is parameterized against injection, but `%` / `_` inside the keyword are still
  treated as SQL LIKE wildcards (same behavior as the existing subsystem page). Escape them
  if literal matching becomes a requirement.
- A `release_item.status` value outside the `ItemStatus` enum is ignored by the grouped
  aggregation (defensive read), so such dirty data would be left out of `total`/`itemCount`
  rather than failing the page. The schema/state machine should prevent it.
- `pageSize` is capped at 100 (mirrors `SubsystemAppService`); the contract's default `10`
  is used when the parameter is absent.
- `TaskProgress.of(Collection)` was kept and now delegates to `ofCounts`, so the existing
  `/tasks/{id}/progress` behavior is unchanged.
- The task read endpoints return HTTP 200 for business errors (40000), consistent with the
  existing exception handler.