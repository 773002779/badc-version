# SDD ledger — plan: docs/superpowers/plans/2026-11-21-release-pipeline-p1-foundation.md

Started: 2026-11-21  Branch: feat/p1-foundation
Spec: docs/requirements/发版流水线-需求规格说明书.md (v1.4)
Tech: docs/tech-design/发版流水线-技术方案-v1.0.md (v1.0)

## Preflight
- Env: JDK 21 (compile --release 17), Maven 3.6.1, local MySQL 8.0.16 root/root @127.0.0.1:3306, no Docker, no Redis, no pnpm, no bash.
- Ruling: tests run against local MySQL schema `release_pipeline_test`; no H2 / no Testcontainers.
- Ruling: `DistributedLock` interface; in-memory impl for tests, Redisson impl conditional on release.redis.enabled=true.
- Ruling: no bash -> PowerShell helpers under tools/sdd/.
- Ruling: Java 17 language level via `--release 17` on JDK 21; no toolchain switch.
- Conflict scan (plan vs spec): pipeline has 4 nodes (no baseline in phase 1), LIB pipeline out of P1 scope, DONE == production success. No contradictions found.

## Tasks

Task 1: dispatched (implementer impl-t1), BASE=bcbbfe2
Ruling: Flyway version follows Spring Boot 3.2 BOM (not forced to 10.x) — avoids version conflict with Boot-managed dependency; costs nothing if wrong.

Task 1: complete (commits bcbbfe2..8596c60, review clean after 1 fix round)
Task 1: fix round 1/5 (1 addressed, 0 open; commits b991a21..8596c60)
Task 1: minor (deferred): no trailing newline on created files
Task 1: minor (deferred): Lombok annotation-processing warning noise on JDK 21; surefire dumpstream warning
Task 1: minor (deferred): no committed DB smoke test — test profile + Flyway clean/migrate exercised only by a deleted temp test
Task 1: minor (deferred): package-info.json satisfies ArchUnit empty-guard; rules check no real domain types yet
Task 1: minor (deferred): rule name says "may_only_depend_on_application_and_common" but implements a blacklist
Task 1: carry → Task 4: add a committed Spring context smoke test (or @ActiveProfiles on DB tests) so the MySQL test profile stops being unverified
Task 2: dispatched (impl-t2), BASE=8596c60

Task 2: fix round 1/5 dispatched (4 important findings + 1 ruling)
Task 2: Ruling: framework 4xx (404/405/415) keep their HTTP status instead of being folded into 50000 — the plan's "其余 50000" was shorthand and a 500 for a client 404 would pollute alerts for every later controller; cost if wrong is a small revert in GlobalExceptionHandler.
Task 2: minor (deferred): validation details drop global (class-level) errors and collapse duplicate field messages
Task 2: minor (deferred): traceId resolution triplicated (ApiResponse / handler / filter) with independent UUID fallbacks
Task 2: minor (deferred): masking test only covers `token` with `.contains`, could not detect the punctuation leak
Task 2: minor (deferred): 50000 test does not assert no-leak in message/data nor the log line
Task 2: minor (deferred): client X-Trace-Id accepted verbatim (no charset/length allowlist)
Task 2: minor (deferred): test-only DummyController sits in a component-scanned package
Task 2: minor (deferred): Masker over-masks key-like prose inside values
Task 2: complete (commits 8596c60..b7465a7, review clean after 1 fix round)
Task 2: fix round 1/5 (4 addressed, 0 open; commits 64a2739..b7465a7)
Task 2: minor (deferred): Masker no longer masks unterminated quoted values (latent, no path emits truncate JSON)
Task 2: minor (deferred): handleMethodValidationException override never exercised by a test
Task 2: minor (deferred): MethodArgumentNotValidException messages still echo `rejected value [...]` (unmaskable by key-based Masker)
Task 3: dispatched (impl-t3), BASE=b7465a7

Task 3: fix round 1/5 dispatched (2 important + 1 ruling)
Task 3: Ruling: data scope flips to fail-closed (empty scope denies); "all units" is an explicit ADMIN/`scope_type='ALL'` marker seeded for ADMIN — least privilege beats convenience; cost is one seed row at bootstrap.
Task 3: Ruling: add a `@Public` seam now (no webhook controller yet) — a global interceptor with no machine path would block the webhook task; `@Public` endpoints must self-authenticate.
Task 3: Ruling: add `@ActiveProfiles("test")` to PermissionInterceptorTest — the test deletes auth tables and would wipe the dev DB when run from an IDE.
Task 3: minor (deferred): redundant index idx_user_scope_lookup duplicates PK prefix
Task 3: minor (deferred): seed drift only checked for ADMIN
Task 3: minor (deferred): pass-through and /error exclusion untested
Task 3: minor (deferred): @DataScope inert until Task 5 consumer
Task 3: minor (deferred): no audit columns on user_role/role_permission/user_scope
Task 3: minor (deferred): user_role.role unconstrained string could evade C20 via alias
Task 3: complete (commits b7465a7..249a025, review clean after 1 fix round)
Task 3: fix round 1/5 (3 addressed, 0 open; commits 654042e..249a025)
Task 3: minor (deferred): CurrentUser Javadoc still claims scopes "only narrow access" (superseded by fail-closed)
Task 3: Ruling: migration numbering shifts by one — Task 3 used V2__auth.sql, so Task 4+ use V3, V4, ... (plan text says V2/V3/V4; follow the next free number instead).
Task 3: carry → Task 5: V2__auth.sql documents scope_type as UNIT/SUBSYSTEM but code now also honors ALL(scope_id=0); Task 5 must document/constrain it.
Task 4: dispatched (impl-t4), BASE=249a025, HEAD=7327e92

Task 4: complete (commits 249a025..7327e92, review clean, 0 fix rounds)
Task 4: minor (deferred): only ADMIN holds setting:manage, so DEV/OWNER cannot create/update subsystems — product decision needed
Task 4: minor (deferred): DuplicateKeyException race branch untested
Task 4: minor (deferred): MAX_PAGE_SIZE duplicated in service and interceptor
Task 4: minor (deferred): trimToNull duplicated in domain and application
Task 4: minor (deferred): status read case-sensitive unguarded (would 50000 on bad data)
Task 4: minor (deferred): LIKE keyword not escaping % and _
Task 4: minor (deferred): smoke test does not assert DATABASE() = release_pipeline_test
Task 4: minor (deferred): application layer imports MyBatis-Plus wrappers and PO directly
Task 4: minor (deferred): no trailing newline in new files
Task 4: Ruling: Task 5 implements @DataScope as an explicit per-query filter on the unit list (not a generic MyBatis SQL rewriter) — a global rewriter is unverifiable at this stage and per-query filtering is testable; cost is that each new query must opt in.
Task 5: dispatched (impl-t5), BASE=7327e92, HEAD=164374b

Task 5: complete (commits 7327e92..164374b, review clean, 0 fix rounds)
Task 5: minor (deferred): disabled-binding paths untested
Task 5: minor (deferred): update() can turn a UNIT with DEs into a LIB
Task 5: minor (deferred): create() does not consult data scope (safe only because setting:manage is ADMIN-only)
Task 5: minor (deferred): scope_type='SUBSYSTEM' rows remain inert; V2 comment says Task 5 activates them
Task 5: minor (deferred): PageResult/PageQuery duplicated across app services; trimToNull duplicated 5x
Task 5: minor (deferred): UnitAppService 676 lines — move command/view records out before it grows
Task 5: minor (deferred): ScopeGuard javadoc still promises a generic MyBatis rewriter that the ruling forbids
Task 5: minor (deferred): duplicate DE name mapped to 40010 but untested
Task 5: Ruling: unspecified errors use PARAM_INVALID(40000) since the 14-code catalogue has no NOT_FOUND — consistent with Task 4; revisit if the catalogue grows.
Task 6: dispatched (impl-t6), BASE=164374b, HEAD=e9d3d9a

Task 6: fix round 1/5 dispatched (1 important finding)
Task 6: Ruling: application boundary types must be web-importable (String/enum-free) — the ArchUnit rule makes domain enums un-callable from controllers; parsing stays inside application services. Cost: a little parsing code per boundary.
Task 6: minor (deferred): token hint reveals first 4 chars for short tokens
Task 6: minor (deferred): get() decrypts to build the hint (read path depends on key)
Task 6: minor (deferred): PO coerces corrupt rows to GLOBAL/0 instead of failing loudly
Task 6: minor (deferred): duplicate-key fallback ignores update count and re-reads Instant.now()
Task 6: minor (deferred): trimToNull duplicated; test key duplicated in yml and test
Task 6: minor (deferred): probe failure message forwarded verbatim into API response
Task 6: minor (deferred): no AAD binding of ciphertext to (scope, subsystem_id)
Task 6: minor (deferred): ObjectProvider.getIfAvailable breaks with two probe beans
Task 6: minor (deferred): coverage gaps (URL scheme, length limits, malformed ciphertext, GLOBAL normalisation)
Task 6: minor (deferred): V5 header claims Flyway cannot read non-ASCII comments on Windows (inaccurate)
Task 6: complete (commits 164374b..572e319, review clean after 1 fix round)
Task 6: fix round 1/5 (1 addressed, 0 open; commits e9d3d9a..572e319)
Task 6: minor (deferred): CodingScope still public; report overstates it as internal
Task 6: minor (deferred): public-API-free-of-domain guard only scans declared methods, not record components
Task 6: minor (deferred): CodingProbeView.ok() hardcodes "OK"
Task 6: carry → T7+: application boundary signatures must be primitives/String only (no domain/infra types) — ArchUnit forbids web→domain.
Task 7: dispatched (impl-t7), BASE=572e319, HEAD=6dcfb76

Task 7: complete (commits 572e319..6dcfb76, review clean, 0 fix rounds)
Task 7: minor (deferred): ItemStatus/TaskProgress created here but owned by Task 8 — Task 8 must extend, not recreate
Task 7: minor (deferred): isTerminal() excludes DONE — misleading name, document rollback exception
Task 7: minor (deferred): ReleaseTaskMemberPO defaults blank role to DEV instead of failing
Task 7: minor (deferred): {"members":[null]} yields 500 instead of 40000
Task 7: minor (deferred): 40009 not tested on add-members; duplicate-name 40010 untested over HTTP
Task 7: minor (deferred): member roles accept only 5 names (OPS_LEAD/DEPT_MANAGER/ADMIN rejected)
Task 7: minor (deferred): idx_task_subsystem duplicates unique-key prefix
Task 8: hand-off — extend domain/task/ItemStatus.java (15 states already correct), do not recreate or renumber.
Task 8: dispatched (impl-t8), BASE=6dcfb76, HEAD=104ec70

Task 8: fix round 1/5 dispatched (3 important + 3 ruled-in additions)
Task 8: Ruling: split GATE_DECIDE into GATE_PASS/GATE_FAIL — one event with two targets is unrepresentable and the positive-only reading admits a failed gate (false admission). Cost: one extra event, no callers yet.
Task 8: Ruling: split RERUN into RERUN (failure-node, ->TESTING) and RERUN_FULL (commit changed, ->BUILDING) — full rerun must go through BUILDING so engine callbacks stay legal.
Task 8: Ruling: add CHECK_INVALID edges from SUBMITTED and READY -> GATE_FAILED per requirements 4.4/4.6 — without them a READY item with an invalidated gate can still EXECUTE.
Task 8: Ruling: TESTING+TEST_DEPLOY_OK is a self-loop (keeps engine success callbacks legal); CAS exhaustion payload carries currentStatus.
Task 8: minor (deferred): 4 extra ItemEvent constants vs the shared type (NEED_APPROVAL/NO_APPROVAL/GATE_INVALID/RESUBMIT)
Task 8: minor (deferred): matrix test mirrored the implementation instead of enumerating all pairs
Task 8: minor (deferred): V7 timestamps lack DB defaults (app always supplies them)
Task 8: minor (deferred): DUPLICATE_NAME reused for (task,unit,branch) duplicate
Task 8: minor (deferred): create-item accepts INACTIVE unit and request-supplied sourceBranch
Task 8: minor (deferred): progress not cached (design says 5s)
Task 8: complete (commits 6dcfb76..1c1cfe5 + docs b831167, review clean after 1 fix round)
Task 8: fix round 1/5 (3 important + 3 ruled-in addressed, 0 open; commits 104ec70..1c1cfe5)
Task 8: minor (deferred): READY-invalidated-before-execute -> PENDING_APPROVAL re-approval branch has no event (deferred to the review/approval task)
Task 8: minor (deferred): TEST_DEPLOY_OK self-loop only from TESTING (late duplicate after GATE_PASS still 40001)
Task 8: minor (deferred): CAS exhaustion untested; payload currentStatus may be one version stale
Task 8: minor (deferred): ItemEvent javadoc calls the split events "shared type set of the plan"
Task 8: fixed in docs — tech design 3.2 and plan shared type now carry GATE_PASS/GATE_FAIL/RERUN_FULL and the SUBMITTED/READY CHECK_INVALID rows.
Task 9: dispatched (impl-t9), BASE=b831167, HEAD=6c6c861

Task 9: fix round 1/5 dispatched (1 critical + 2 important + 4 ruled-in)
Task 9: Ruling: DistributedLock keeps unconditional unlock semantics; Redis impl uses forceUnlock, authorization stays with the DB row evidence.
Task 9: Ruling: unit lease is renewable (renew() extends Redis TTL + DB expire_at); expired row with a live owner renews instead of stealing; orchestrator must renew on advance, Task 14 adds a scheduled renewer.
Task 9: Ruling: release is ownership-scoped (unit_id + instance_id) and is skipped when bind reports the lock lost.
Task 9: minor (deferred): CHECK type encoded in node_task.target_id (codes 1..7)
Task 9: minor (deferred): ReleaseOrder.status raw String with provisional INIT
Task 9: minor (deferred): publish-number collision surfaces as DuplicateKeyException (500)
Task 9: minor (deferred): trimToNull and id-generation duplicated; no trailing newlines
Task 9: complete (commits b831167..c5507bf, review clean after 1 fix round)
Task 9: fix round 1/5 (1 critical + 2 important + 4 ruled-in addressed, 0 open; commits 6c6c861..c5507bf)
Task 9: Ruling: DONE counts as terminal for unit-lock reclaim purposes (design 9 "释放时机：实例进入终态 DONE/FAILED/CLOSED/TERMINATED"); Task 11 must not flip this.
Task 9: minor (deferred): CHECK type encoded in node_task.target_id (codes 1..7)
Task 9: minor (deferred): ReleaseOrder.status raw String with provisional INIT
Task 9: minor (deferred): publish-number collision surfaces as DuplicateKeyException (500)
Task 9: minor (deferred): forceExpire javadoc wrongly says a live owner becomes reclaimable
Task 9: carry → Task 11: bindInstance is not ownership-scoped (unit_id only) — orchestrator must bind/renew with its own instance id.
Task 9: carry → Task 14: admin force-release must be a real steal, not forceExpire (a live owner's lease is renewed on every attempt).
Task 10: dispatched (impl-t10), BASE=c5507bf, HEAD=c7bc22e

Task 10: fix round 1/5 dispatched (1 critical + 4 important)
Task 10: Ruling: CicdEngine gains JobKind {BUILD, DEPLOY} on query/logs and the global coding.query-action is deleted — one process serves both pipeline kinds, so a single global action cannot be correct.
Task 10: Ruling: unknown vendor status maps to EngineStatus.UNKNOWN (raw word kept in message) instead of ENGINE_UNAVAILABLE, which the reconcile loop would turn into a false TIMEOUT.
Task 10: Ruling: retry timeouts only for GET actions; POST triggers never retry on timeout (may have created a job).
Task 10: Ruling: outbound HTTPS enforced, with release.engine.allow-insecure-http=true as the test-only escape hatch.
Task 10: Ruling: JSONPath mini-parser supports dotted and bracket-quoted keys, validates all configured expressions at startup, and documents unsupported wildcards/filters.
Task 10: minor (deferred): CodingEngine 402 lines mixes SPI, path parsing, status mapping, redaction
Task 10: minor (deferred): trimToNull duplicated 4x; 9s backoff constant unreachable; per-poll INFO log
Task 10: minor (deferred): logs maps null body to ""; offset/limit unvalidated; URL-encoding untested
Task 10: carry → Task 14: Supplier<Connection> cannot choose GLOBAL vs SUBSYSTEM connection
Task 10: carry → docs: tech design §6.1 SPI signature must record the JobKind amendment
Task 10: complete (commits c5507bf..04b1840 + docs b6e53ce, review clean after 1 fix round)
Task 10: fix round 1/5 (1 critical + 4 important addressed, 0 open; commits c7bc22e..04b1840)
Task 10: minor (deferred): CodingEngine 402 lines mixes SPI, path parsing, status mapping, redaction
Task 10: minor (deferred): trimToNull duplicated 4x; 9s backoff constant unreachable; per-poll INFO log
Task 10: minor (deferred): logs maps null body to ""; offset/limit unvalidated; URL-encoding untested
Task 10: carry → Task 14: Supplier<Connection> cannot choose GLOBAL vs SUBSYSTEM; register the CodingEngine bean so startup JSONPath validation runs at context refresh.
Task 10: carry → Task 14: EngineStatus.UNKNOWN must stay non-terminal in reconcile (never treated as success).
Task 11: dispatched (impl-t11), BASE=b6e53ce, HEAD=fe1d297

Task 11: fix round 1/5 dispatched (4 important)
Task 11: Ruling: unit lock released (ownership-scoped) after terminal commit, outside the transaction; also on already-terminal early return.
Task 11: Ruling: node handlers run AFTER commit but before pipe-lock release; handlers own their own short transaction; post-commit handler throw fails node/instance in a new transaction.
Task 11: Ruling: bounded re-advance (<=3 attempts ~100ms) when the pipe lock is busy; Task 14 reconcile remains the durable backstop.
Task 11: Ruling: completion scoped to latest attempt per (task_type,batch_no,target_id) so retried rows cannot permanently block a node.
Task 11: Ruling: CHECK tasks do not gate VERIFY completion (design 5.2 rule 4); production admission is enforced by the production handler, not by node completion.
Task 11: minor (deferred): unitId=0 bypass for broken online orders; events published before instance CAS; lock-lost leaves node RUNNING; skipped rows lack duration; redundant reads; package placement; registry clear() public; stale aggregate to handler; EventRecorder not tx-aware; fixtures delete 11 tables.
Task 12: hand-off — NodeHandler runs post-commit, must open its own transaction for node_task writes.
Task 11: complete (commits b6e53ce..726c769, review clean after 1 fix round)
Task 11: fix round 1/5 (4 important + CHECK ruling addressed, 0 open; commits fe1d297..726c769)
Task 11: minor (deferred): crash window between entry commit and handler; pipe lease not extended for slow handlers; ~200ms retry window does not cover a long handler; FAILED CHECK no longer fails VERIFY (gate must surface it); release is delete-then-unlock.
Task 11: carry → Task 14: reconcile must re-invoke RUNNING instances whose handler never fired (not just WAITING_EXTERNAL scans).
Task 12: hand-off — NodeHandler runs post-commit and owns its own transaction; add an optional onTaskCompleted hook for within-node chaining.
Task 12: dispatched (impl-t12), BASE=726c769, HEAD=edb00b7

Task 12: fix round 1/5 dispatched (2 important + 3 ruled-in)
Task 12: Ruling: TriggerResult gains optional commitSha (YAML responseCommit); base_commit is never written as the built commit; unknown commit persists NULL + WARN.
Task 12: Ruling: a deferred advance must re-run once after the handler returns instead of being dropped (liveness, not duplication).
Task 12: Ruling: hook exceptions fail node/instance like handler-entry failures; engine failures route through NodeTaskCompletionService; handler lookups fold to the latest attempt.
Task 12: minor (deferred): BuildCmd.taskId naming vs planId; port javadoc overstates config reporting; orphaned engine job on CAS miss; digest write-back race; test fixture duplication; trailing newlines.
Task 13: hand-off — latest-attempt folding must also apply to deploy task lookups.
Task 12: complete (commits 726c769..5a390a4, review clean after 1 fix round)
Task 12: fix round 1/5 (2 important + 3 ruled-in addressed, 0 open; commits edb00b7..5a390a4)
Task 12: minor (deferred): orchestrator javadoc still says handler advance "ignored"/exception "swallowed"; deferred slot dropped if the deferred advance falls through; recordCommit writes even when the task row was already terminal.
Task 13: hand-off — deploy task lookups must fold to the latest attempt; TestEnvReadyEvent must fire only when every TEST DE deploy succeeded.
Task 13: dispatched (impl-t13), BASE=5a390a4, HEAD=5891f73

Task 13: fix round 1/5 dispatched (2 important + 2 ruled-in)
Task 13: Ruling: ready event uses a durable DB CAS claim (pipeline_instance.test_ready_at, V9) instead of an in-memory set — cross-JVM exactly-once and crash-safe.
Task 13: Ruling: missing plan id or missing image digest fails the node's tasks with a business reason and never calls the engine.
Task 13: Ruling: latest-attempt SKIPPED deploy tasks count as neutral for readiness, mirroring the orchestrator completion fold.
Task 13: minor (deferred): no pre-trigger claim; throwing ready listener fails a green pipeline; start() ignores node status; latest-attempt fold duplicated; non-EngineException leaves PENDING; fixture duplication; trailing newlines; stale event-bus javadoc.
Task 14: hand-off — reconcile must not call start() concurrently with an advance without a row-level claim; listeners must not throw into the handler path.
Task 13: complete (commits 5a390a4..e47dfb2, review clean after 1 fix round)
Task 13: fix round 1/5 (2 important + 2 ruled-in addressed, 0 open; commits 5891f73..e47dfb2)
Task 13: minor (deferred): claim commits before the synchronous publish, so a throwing publish permanently suppresses the ready event (Task 14 outbox/async scope); design doc DDL lacks test_ready_at; no direct 40011 assertion.
Task 14: hand-off — wire BuildNodeHandler/TestDeployNodeHandler/CodingEngine/EngineRegistry as Spring beans; keep UNKNOWN non-terminal; do not let listeners throw into the handler path.
Task 14: dispatched (impl-t14), BASE=e47dfb2, HEAD=7e5eaad

Task 14: fix round 1/5 dispatched (4 important)
Task 14: Ruling: UNKNOWN is a successful observation for bookkeeping (reset query_failures, refresh updated_at) but changes no task status.
Task 14: Ruling: reconcile round is bounded (10 min hard stop) and renews its lease; the final unlock is skipped if the last renew failed.
Task 14: Ruling: listener failures persist to pipeline_item_failure (V12) and ReconcileJob repairs them, 3 attempts then ERROR.
Task 14: Ruling: unauthenticated webhook path is capped at 64KB with 413 before reading the body.
Task 14: minor (deferred): payload_hash write-only; no dedup retention; ack inaccuracy on non-WAITING tasks; masked reason but raw exception logged; isTerminal via valueOf; duplicate CodingStatusMapper; missing (status,updated_at) index; ReconcileJob 363 lines.
Task 14: complete (commits e47dfb2..a164d6c, review clean after 1 fix round)
Task 14: fix round 1/5 (4 important addressed, 0 open; commits 7e5eaad..a164d6c)
Task 14: minor (deferred): chunked over-limit body yields 500 not 413 (filter catch unreachable through MVC advice); drain/repair phases share the round budget with the scan; item_id=0 failure rows when order lookup throws; attempts not incremented atomically; UNKNOWN past deadline never escalates.
Task 15: hand-off — RerunPlanner must consume the item's base_commit (never the instance commit) and the module_path; full rerun maps to RERUN_FULL, from-node to RERUN.
Task 15: dispatched (impl-t15), BASE=a164d6c, HEAD=83da061

Task 15: fix round 1/5 dispatched (3 important + 2 ruled-in)
Task 15: Ruling: base_commit is written by a best-effort head probe inside the publish transaction (failure -> NULL + WARN, never blocks a release) and refreshed on a full rerun.
Task 15: Ruling: revocation only when a test pass exists; confirmation required only then; revokedTestPass must be truthful.
Task 15: Ruling: production resume copies previous SUCCESS batch tasks as SKIPPED instead of redeploying them.
Task 15: minor (deferred): RerunResult lacks fromNode/reason; revocation trail log-only; gate reset deferred; approval re-adjudication deferred; CodingCommitProbe untested; controller test package; fixture realism; GATE_FAILED/LIB untested.
Task 16: hand-off — release detail must expose fromNode/rerun reason and the gate fields once available.
Task 15: complete (commits a164d6c..4be6398, review clean after 1 fix round)
Task 15: fix round 1/5 (3 important + 2 ruled-in addressed, 0 open; commits 83da061..4be6398)
Task 15: minor (deferred): batch carry-over keyed only on batchNo (a DE added to a succeeded batch would be skipped); chained resume re-deploys carried batches; NULL baseline never heals; report drift; probe latency under the publish transaction.
Task 16: hand-off — release detail must expose fromNode/rerun reason; gate fields stay null until the gate task lands; keep the query to <=3 statements.
Task 16: dispatched (impl-t16), BASE=4be6398, HEAD=2051902

Task 16: complete (commits 4be6398..2051902, review clean, 0 fix rounds)
Task 16: minor (deferred): no end-to-end publish->query test (fixtures hand-inserted); budget assertion is exact not <=3; counter process-global; task grouping keyed by node-type string; within-node order by id only; order-without-instance branch untested; scope tests use ADMIN/empty-scope only; DTO duplication; detail not read-only-transactional; ownership join duplicated; fixture truncates 15 tables.
ALL 16 TASKS COMPLETE. Next: final whole-branch review.
FINAL REVIEW: 3 Critical + 9 Important. Fix wave dispatched.
Ruling C1: handler availability is checked BEFORE finishing the current node; if the next node has no handler, finish the current node and set the instance to an explicit PAUSED state with reason (never roll back a legitimate completion). Placeholder handlers that fake SUCCESS are rejected — they would let a release reach DONE without deploying.
Ruling C2: add ItemController (POST /items, POST /items/{id}/publish) only; no generic /transit endpoint (would allow posting APPROVE_ALL/EXECUTE).
Ruling C3: publish requires >=1 TEST DE and >=1 PROD-family DE (40011 with missing set); node completion requires >=1 counted task in Java and SQL.
Ruling I8: Idempotency-Key is deferred to P2 — P1 writes are already protected by unique keys and conditional updates; recorded here rather than silently dropped.
Ruling I1: webhook payload mapping must carry an explicit job kind (BUILD/DEPLOY), required; lookup filters by it.
Ruling I3: base-commit probe moves OUTSIDE the publish transaction (Task 15 ruling amended: best-effort observation must not hold a pooled connection).
Ruling I4: stuck-node detection is age-based ERROR logging in ReconcileJob (RUNNING node, all counted tasks PENDING, older than 10 min); full row-level entry claim stays P3.FINAL RESIDUALS (adjudicated, no second fix wave):
Ruling R1: release_item.base_commit stays CHAR(40) in P1 — real defect only for SHA-256 repos; record as the first follow-up commit (V15 MODIFY VARCHAR(64) + length validation). Cost if wrong: a 64-char head aborts the publish transaction.
Ruling R2: POST /items returns 40011 BUILD_BRANCH instead of 40000 for an unknown unit — an error-precedence cosmetic; fold into R1's follow-up.
Ruling R3: C20 hard-block covers RELEASE_PUBLISH but not ITEM_CREATE — the V2 seed already denies OPS/ops_lead; extend the hard rule in the follow-up. Cost if wrong: a misconfigured role mapping could let OPS create a code item.
Ruling R4: webhook `kind` is mandatory with no compatibility switch — confirm the real CODING payload carries it before enabling; otherwise map kindField or add kindRequired.
Ruling R5: PAUSED is terminal and has no resume/alert — P2 must add resume and an age alert; P1 treats it as the explicit phase boundary.
Ruling R6: params_json.branch is the reserved read-only-branch key — ratify at bind time / add a dedicated column in P2.
Ruling R7: benign duplicate callbacks that lose the item CAS are recorded and count toward attempts — resolve NOT_ALLOWED replays instead of consuming attempts in P2.
Ruling R8: taskTypesOf has no AUTO_TEST family — extend with the gate task.
POST-P1 FOLLOW-UP LOG
R1/R2/R3 applied in e99f181: V15 widens release_item.base_commit to VARCHAR(64); the publish auto-start + reconcile fix rides the same commit; C20 covers ITEM_CREATE.
Follow-up c8ed3d2 (R6 partial): GET /units/{id}/coding-tasks exposes the unit's bindings so the settings form reads before it writes. Scope is fail-closed (40009 for an unscoped caller), ordering is by task_kind, and enabled is a primitive boolean. R6 itself stays open: the branch key is still a convention inside params_json, not a column.
Ruling R9: params_json is a MySQL JSON column, so the server never stores the posted text verbatim — reads come back normalized (spacing, key order). Callers must treat it as a document (parse and read params.branch); no test may assert byte equality with the posted string.
Follow-up 212ead5: P1 frontend (web/) delivered in the graphite style with light/dark themes, screenshots in docs/design/shots. Design note: Element Plus ships its dark palette on html.dark (0,1,1), so the token block must use :root[data-theme=...] or dark mode silently falls back to Element Plus blue.
Environment note: on Windows `mvn verify` fails at spring-boot:repackage while a service started from target/*.jar is running (file lock). Stop the service first, or use scripts/start.ps1 -Rebuild which builds before starting. This is a tooling artifact, not a build defect: 308 tests / BUILD SUCCESS with the service stopped.
Still open after P1: R4 (webhook kind must be confirmed against the real CODING payload), R5 (PAUSED has no resume/alert), R6 (branch key is a convention), R7 (replayed callbacks consume attempts), R8 (taskTypesOf lacks AUTO_TEST), I8 (Idempotency-Key deferred to P2).

OPTIMISATION ROUND 2 (2026-11-22)
Terminology: the product term is 系统 (it was 子系统 in the UI and docs). Scope chosen by the user: user-visible copy and living docs only — the DB table `subsystem`, the `subsystem_id` columns, the `Subsystem*` classes and the `/subsystems` route keep their names. That is a recorded naming debt: one rename, when it happens, must touch the table, the classes, the API and the frontend route together.
Ruling R10: `deploy_unit.module` and `deploy_unit.tag` (V16) are labels only — exact-match list filters and display, never build/deploy input. They are deliberately NOT `module_path`, which the commit comparison reads; both the column comments and the DeployUnit javadoc state the difference.
Ruling R11: unit edit is a full replacement of the editable fields (subsystem link immutable). A field left blank is cleared, so the edit dialog sends every field it shows and the rule is printed in the dialog.
Ruling R12: the CODING task picker asks the engine (`job.list`) instead of trusting the user's memory of an id. The project comes from the system (`subsystem.coding_project`), never from the request; a system without a project fails with 40000 before any network call, and an unreachable connection propagates as 50001 so the UI can fall back to typing. An empty list therefore always means "the project has no jobs".
Ruling R13: `job.list` is an optional engine capability (default method on CicdEngine throwing ENGINE_UNAVAILABLE). `responseList` + `itemId` are required for that action; a response without an array is an error, not an empty list.

P2 ROUND (2026-11-22): 门禁 / 验证确认 / 生产准入
Scope: P2 计划 T1-T10. Both servers started with scripts\dev.ps1; 349 backend tests green; web build green; all 22 screenshots console-error free.

Decisions D1-D6 were adopted with their suggested defaults, with three refinements the implementation forced:
Ruling R14: 检查能力挂在 CicdEngine.startCheck/queryCheck（默认实现抛 ENGINE_UNAVAILABLE），不另开 GateEngine 端口。检查与构建/部署用同一 CODING 租户、同一套鉴权/脱敏/HTTPS 校验；复制一条 HTTP 管线只会多一份要同步维护的实现。动作名 check.start / check.env.start / check.query；两个 start 是因为占位符缺失在 resolver 里是硬错误，一个 body 同时渲染镜像级与 DE 级不可能。
Ruling R15: 判定用「最高等级」而不是逐项 pass/fail；一个检查报 PASS 但等级是 S1 仍然阻断（等级是事实，状态词是引擎的意见）。未映射的等级返回「无等级」，绝不退化成 NONE。
Ruling R16: 中危阻断不结束变更项（停在测试中、VERIFY 节点继续等待），高危/失效/测试不通过才进 GATE_FAILED。理由：后者可挽回，前者只能靠重跑；「一有中危就整体重跑」把豁免流程变成摆设。
Ruling R17: 两道门各占一列（V19：gate_status=检查、test_status=测试）。P1 把两组值塞在一列，P2 实测发现「记录检查结果会覆盖测试不通过」——这是建模错误，不是实现瑕疵。迁移把历史 TEST_* 值映射为 CHECK_RUNNING，历史通过人/原因仍在。
Ruling R18: 准入不足用 40002 GATE_BLOCKED（带 missing 列表），不是计划里写的 40011：40011 是「单元未配置」，混用会让前端无法区分缺配置与门禁未过。
Ruling R19: P2 不开放「提交生产发布」入口，只给准入判定。提交后没有审批流（P3）会把状态推到待执行却无路可走；计划 T5 的意图（准入判定 + 缺项可解释）已由 GET /items/{id}/gate/admission 覆盖。
Ruling R20: 检查在 VERIFY 节点进入时一次性触发（草案是构建成功即触发镜像级检查）。提前触发是性能优化，不影响判定时机；记为 P3 优化项。
Ruling R21: 豁免审批人 = gate:waive（V18 补 OPS_LEAD 映射），且不能自审（40008），管理员也不例外；有效期必填、上限 90 天；同一变更项同时只允许一张生效/待审单。到期由每日 00:05 的扫描处理，且把检查结果一并置 INVALID（§4.6「豁免到期 → 视为 CHECK_INVALID」）。
Ruling R22: 测试通过/不通过需要 gate:test-pass 且是任务内 TEST 成员（ADMIN 可 break-glass）；撤回需要 gate:test-revoke 且只有通过人本人；通过前要求 test_ready_at 已置位（测试环境就绪）。不通过的原因落 test_fail_reason，所有人可见。
Ruling R23: 节点处理器新增两个可选钩子 mayComplete / failureReason（默认 false/null，P1 行为不变）。VERIFY 因此可以在门禁满足时完成、在终局阻断时失败：一个卡在 RUNNING 且没人能处理的节点比一个明确失败、释放单元锁、可以重跑的节点更糟。
Ruling R24: 实例 FAILED 也写 status_reason（新增 failInstance 端口）。P1 只有 PAUSED 有原因，失败的实例只能去翻日志——这直接违反「按钮灰着也要给理由」。
Ruling R25: 新增 GET /api/v1/me（身份 + 生效权限点）。没有它，页面只能「隐藏按钮」或「点完再报 40009」，两者都不满足「禁用并说明原因」。

Deviations / residuals carried forward:
- 生产批次执行与「门禁失效 → 后续批次暂停」的批次级动作仍是 P3：P2 的失效只作用于未发生的动作（撤回准入、把已完成的检查置 INVALID），从不碰已 SUCCESS 的任务行。
- resume 只能恢复「门禁重新满足」的暂停；P2 里唯一的暂停是 PROD_DEPLOY 无处理器，请求会被明确拒绝（40001 「尚未交付」），不静默失败。
- R5（PAUSED 无恢复入口）在接口层关闭；「有告警」仍待 P3 的通知能力。
- R7（重放的 NOT_ALLOWED 事件消耗 attempts）未动；R8（taskTypesOf 缺 AUTO_TEST）仍开着；I8（Idempotency-Key）仍留给 P3。
- 检查明细的环境级检查目前只针对第一个 TEST DE（行模型是一检查一行的聚合），多 DE 逐环境检查属 P3。

CODING 对接配置 ROUND (2026-11-22)
Scope: 用户选 A（设置页全局连接 + 测试连接 + 系统项目行内编辑），短设计见 docs/superpowers/specs/2026-11-22-coding-integration-config-design.md。落地 V20 + CodingController + HttpCodingProbe + 设置页/系统详情。
Ruling R26: 只做 scope=GLOBAL 的连接配置。coding_conn 表与 CodingConn 领域早已支持 SUBSYSTEM 覆盖，但引擎只解析 GLOBAL 一行（GlobalCodingConnectionProvider），页面不暴露，避免「配了不生效」。每系统连接要改引擎的连接解析，随 P3 一起做。
Ruling R27: 「连接测试」= job.list（先 BUILD 再 DEPLOY）一次调用同时证明地址可达、凭证有效、项目可达，并返回构建/部署任务数。失败原样返回引擎消息（引擎已脱敏），不把「地址 / 凭证 / 项目」硬拆成三行去猜——引擎只给一句话（HTTP 401、连接超时、响应不是数组），拆行等于编造。
Ruling R28: 只有被测值恰好等于已保存连接时才写 last_probe_*（V20：时间 / 成败 / 消息）；草稿测试不写，响应带 persisted 让页面说明「草稿测试：结果不写入配置」。让调用方传 persist 标志会允许「测 A 记 B」。
Ruling R29: 保存时 token 留空 = 保留原凭证（页面只有 tok-***），首次配置必须填；凭证仍是 AES-GCM 落库，明文不进日志/响应。
Ruling R30: 连接的 project 是「默认项目」；subsystem.coding_project 优先，为空时继承；两处都空时任务查询 40000 并点名系统。此前两处各存一个 project，只让系统那处生效会让人填了没反应。
Ruling R31: 凭证轮换不做「双凭证并存 24h / DEPRECATED」（技术方案 §6.2 草案）；失效告警与双凭证一起留 P3。当前轮换 = 填新值覆盖。
Ruling R32: P1 计划的 POST /units/{id}/coding-bindings/test（单元级：任务存在 + 所属项目一致）没有实现；本轮只做平台级连接测试，单元级绑定校验记为后续。技术方案 §12.2.2 已按实际落地路径更新。
Ruling R33: 测试基础设施修正：Flyway clean 会清库，但缓存的测试上下文仍持有 InMemoryDistributedLock 的租约；deploy_unit 的 AUTO_INCREMENT 重置后 id 复用，publish 误报 40006（DB 行已被清、内存 key 还在，UnitLockService 拒绝抢占）。FlywayTestConfig 在 clean 后调用 InMemoryDistributedLock.clearAllLeases()：租约守护的库已不存在，清掉是正确语义，不是掩盖问题。
Follow-up: scripts/mock-coding.mjs 是本地假租户（只实现 job.list），配 --release.engine.allow-insecure-http=true 使用；生产禁用。
Follow-up: 系统详情「CODING 项目」可编辑（P1 只有创建表单有该字段）；设置页原来的「暂无管理接口」两处文案已删除。

## R34–R52（P3–P4 补写，2026-11-25）

> **这是补写，不是新裁决。** 正式台账此前停在 R33；R34–R52 是 P3、P4-A、P4-B 三轮执行中已经作出的
> 裁决，此前只写在各自的 progress 文件里。下面逐条转写（英文原文压缩成中文一句，未改结论；编号与
> 结论以来源文件为准），每条一句话 + 该文件记录/落地的提交，不新增编号。来源文件都在 `.gitignore`
> 的 `.superpowers/` 下（未进提交），行号为 2026-11-25 的文件状态：
>
> - R34–R38：`.superpowers/sdd/p3-prod-control/progress.md`
> - R39–R46：`.superpowers/sdd/p4-rollback/progress.md`
> - R47–R52：`.superpowers/sdd/p4b-notification/progress.md`

**R34–R38（P3 生产管控）**

- Ruling R34: 封板一律意味着 `OPS_LEAD` 后接 `DEPT_MANAGER`（设计 4.4）；只有窗口、没有封板时只加 `OPS_LEAD`。`cb1e617`（`p3-prod-control/progress.md:38`）
- Ruling R35: 解除封板的重算只在「快照在窗口内且已无封板生效」时撤掉待签的 `OPS_LEAD` 步骤，否则第二次封板的 `OPS_LEAD` 步骤会消失。`cb1e617`（`p3-prod-control/progress.md:38`）
- Ruling R36: 所有封板路径（提交、在途声明、执行前拉回）都以同一顺序要求同两个签名；只有已无封板生效时才撤步骤。`2e3ad45`（同节记录的 pull-back 提交；该条在 `p3-prod-control/progress.md:44` 未直接给号）
- Ruling R37: Task 3 修复与 web 轮的独立复评由 lead 的带证据评审替代，因为 provider 对每个子 agent 都返回 429 配额错误；之后可对 `2a4e389..HEAD` 重跑。无代码提交（流程裁决，`p3-prod-control/progress.md:45`）
- Ruling R38: 封板发现两个签名都已给出时不再要求第三个——发布单保持 `READY`，执行前复核在封板生效期阻断执行，拒绝信息写明原因。`494597d`（P3 independent-review 修复轮；该条在 `p3-prod-control/progress.md:50` 未直接给号）

**R39–R46（P4-A 回滚与发布记录）**

- Ruling R39: 回滚在 `rollback:execute`（运维/管理员）下一步执行，owner 请运维操作；J13 的「发起 owner/运维、执行运维」读作权限声明，不是两步流程。实现落点 `90d64da`（preflight 裁决，`p4-rollback/progress.md:10-12` 未给提交号）
- Ruling R40: 版本列表由成功的生产部署记录推导（无制品库，Q4 未定）；从未部署过的 digest 不能作为回滚目标。实现落点 `90d64da`（`p4-rollback/progress.md:13-15`）
- Ruling R41: 回滚不新建发布单，复用来源发布单并写 `rollback_record`，发布记录列表因此不会被回滚刷屏。实现落点 `90d64da`（`p4-rollback/progress.md:16-18`）
- Ruling R42: 「当前线上版本」= 任意触发方式（含 `ROLLBACK`）的最新成功生产部署；历史 = 完整部署成功的前向发布，排除当前 digest。`8c2199b`（`p4-rollback/progress.md:38-39`）
- Ruling R43: 生产节点未成功的发布不是回滚目标；它仍可能是当前线上镜像，此时回滚目标是上一次完整发布。`8c2199b`（`p4-rollback/progress.md:40-41`）
- Ruling R44: 全局发布记录响应为 `{total,pageNo,pageSize,records}`，请求 `page`/`size` 缺省 1/10（设计 §4 参数名），状态是机器值 + 中文标签。`e18317b`（`p4-rollback/progress.md:42-43`）
- Ruling R45: 显式 `unitId` 在调用者范围外回 40009（与回滚记录页一致）；`systemId`/`keyword`/`status` 与范围 AND，越界系统给空页。`e18317b`（`p4-rollback/progress.md:44-46`）
- Ruling R46: web 只在 `api/index.ts#listReleases` 映射 `page`/`size`；更早的列表请求与响应都保留 `pageNo`/`pageSize`。分页口径按端点分裂（R44），不改旧列表调用。`8c8962a`（文档记录；映射代码在 `a6a631e` 及同轮 `8c2199b`）（`p4-rollback/progress.md:47-50`）

**R47–R52（P4-B 通知）**

- Ruling R47: 通知列表沿用 R44 分页口径——请求 `page`/`size`、响应 `pageNo`/`pageSize`，`unreadOnly` 是服务端筛选；`read-all` 的计数字段命名 `updated`，旧列表不动。`74ce8f0` / `74001da`（`p4b-notification/progress.md:79-80`）
- Ruling R48: 已存在的 `PENDING` 企微行遇到通道关闭/地址清空时，由派发任务标 `FAILED`（原因写明未启用或地址未配置），不每 15s 无限重试。`74ce8f0`（`p4b-notification/progress.md:81-82`）
- Ruling R49: `notify` 用 `TransactionTemplate(PROPAGATION_REQUIRES_NEW)`，不是方法级 `@Transactional`——提交失败也要落在吞异常的 `try` 内；接受「业务回滚后可能留一条通知」的窗口，outbox 留 P7。`74ce8f0`（`p4b-notification/progress.md:83-84`）
- Ruling R50: webhook 校验 = 非空 + ≤512 + `https://`；内网 `http://` 仅由 `release.notification.allow-insecure-http=true` 放开（测试 profile 打开，默认 false）；开关打开而地址为空 → 40000；配置是平台级单行（单租户）。`74ce8f0`（`p4b-notification/progress.md:85-87`）
- Ruling R51: 接收人按平台角色（`user_role`/`role_permission`）解析，不按 `user_scope` 过滤；企微发送为 at-least-once（单实例按 id 取行，无分布式锁）。两者均接受，二期/P7 再收紧。`74ce8f0`（`p4b-notification/progress.md:88-89`）
- Ruling R52: 他人行与不存在的通知统一回 40000（HTTP 200），不采用设计 §8 的 403/404——沿用平台统一响应包装，「与不存在同码」即防枚举。`74ce8f0`（`p4b-notification/progress.md:90-91`）

## R53+（P4-C 审计，2026-11-25）

- Ruling R53: 显式 `unitId` 在调用者范围外回空页/仅表头 CSV，不回 40009；无单元范围的调用者同样直接空结果、不执行 SQL——审计页不暴露「某个单元是否存在」，与发布记录页相反。`b4bbb81`
- Ruling R54: 平台级行（两个 scope 列皆空）只对 `ALL` 可见；系统级设置（CODING / 窗口的 SUBSYSTEM 范围）写 `scope_subsystem_id`，按「调用者任一单元所属系统」可见。`b4bbb81`
- Ruling R55: 审计写入用 `TransactionTemplate(REQUIRES_NEW)` 并吞掉一切异常，审计失败不改变业务结果；`actor` 不接受触发点参数，无 `UserContext` 时兜底 `SYSTEM`；行是流水，重复调用不去重。`b4bbb81`
- Ruling R56: 20 个动作、24 处触发点一次接线（`RESOURCE_UPDATE` 覆盖系统/单元的 create+update 与任务 create，`SETTING_UPDATE` 覆盖 CODING 与通知，`WINDOW_UPDATE` 单独一码）；P5 的 6 个动作只占位、不写行。`b4bbb81`
- Ruling R57: 导出取 5001 行以区分「恰好 5000」与「被截断」，截断追加末行中文提示；导出沿用当前筛选、不带分页，web 用 `fetch` + blob（`window.open` 会丢 `X-User-Id`），文件名取 `Content-Disposition`。`b4bbb81` / `ceeba6a`
- Ruling R58: 单元详情的「操作日志」用通栏 `DataSection` 而非 Tab（6 列在约 570px 右栏必然横向滚动）；`before/after` 只在全局页用展开行展示，单元页严格 6 列。`ceeba6a`
- Ruling R59: 审计动作下拉只列 13 个常用动作（三组），未列动作仍可经「全部动作」检索并显示服务端 `actionLabel`；对象类型 9 个在前端镜像（行里没有 `objectTypeLabel`），改枚举时两处同步。`ceeba6a`

## R67+（P5-B 停止/退出/关闭/归档 + P5-C 待办中心，2026-11-25）

> **编号说明**：R60–R66 是 P5-A 的裁决，当时只写进 `.superpowers/sdd/p5a-daily/progress.md`（正式台账未追加），
> 该文件已注明「后续 P5-B 从 R67 起」。本节按该约定从 R67 续号，不重写 P5-A 的 7 条；P5-A 原文见其 progress
> 文件的「裁决 R60+（P5-A）」一节。来源文件（P5-B/P5-C 的 progress）同样在 `.gitignore` 的 `.superpowers/` 下，
> 未进提交，行号为 2026-11-25 的文件状态。

**R67–R73（P5-B 停止/退出/关闭/归档）**

- Ruling R67: 四个动作**共用一条终止路径**（`PipelineTerminationService`，`ACTIVE = PENDING/RUNNING/PAUSED`），
  差别只在对象、权限、允许状态与额外级联；业务状态在一个 `TransactionTemplate` 里提交，`InstanceFinishedEvent(TERMINATED)`
  在事务内发布（回滚记录收敛依赖它），引擎取消与锁释放在提交后 best-effort（失败只 WARN）。`3adeb8e`
- Ruling R68: 发布单状态被**镜像**（stop/exit → `TERMINATED`，close/归档每关一项 → `CLOSED`，`row_version` CAS）；
  `{status,itemStatus,terminatedPipelineNo}` 里 `status` 是发布单镜像（未发布过的变更项 close 为 `null`），
  `terminatedPipelineNo` 在无活动实例时为 `null`（动作仍成功，不是 40001）。`3adeb8e`
- Ruling R69: 归档级联 = `ItemStatus.isBlocking()`——`DONE` 保留、`GATE_FAILED/FAILED/REJECTED` 原样不动；
  `closedItems` 只数真正 `APPLIED` 的关闭、`terminatedInstances` 只数真正被终止的实例；归档范围按该任务变更项的
  去重单元校验，权限是 `task:create` + 创建人或 `ADMIN`。`3adeb8e`
- Ruling R70: 审批/检查作废在**业务事务内且不吞异常**（参与事务的 `@Transactional` 抛错会标 rollback-only，捕获无益），
  逻辑 no-op 返回 0（无快照/没有 PENDING 步）；只有引擎取消、锁释放、审计、通知是提交后/独立事务且失败不阻断。
  `3adeb8e`
- Ruling R71: **stop 也作废待签审批**（保留已记录检查，只有 exit/close 才把未完成检查置 `INVALID`）；评审发现 stop 后
  审批人仍会收到一个已终止发布的待签，修复为 stop 也 `CANCELED`，设计 §1 同步，回归测试
  `stop_voids_the_signatures_it_still_waits_for_and_keeps_the_recorded_checks`。`dae9057`
- Ruling R72: 归档的 `releaseTaskLocks` **不抢活锁**（owner 实例仍 `PENDING/RUNNING/PAUSED` 则跳过，状态读不出按
  「仍活动」）；归档对并发移动的变更项记日志跳过而不是 40001，只有任务/发布单 CAS 失手才 40001；四个端点都放
  `ReleaseControlController`（`TaskController` 不加归档）。`3adeb8e`
- Ruling R73: 前端四处共用新组件 `ReasonDialog.vue`（原因 1–200、每次打开清空、服务端拒绝保留文字）；停止/退出在
  发布页页头，状态取 `GET .../admission` 的 `itemStatus`（PAUSED 实例仍是 `PROD_DEPLOYING`）；归档预览的 N 用前端
  「非终态」集合（比后端 `isBlocking()` 宽，可能多算）、M 由客户端从发布记录数出，响应计数才权威；`demo-control.sql`
  给 `ops-lead` 幂等加 `OPS` 角色（C20 保证 OPS 无 `release:publish`）。`518d37e`

**R74–R79（P5-C 待办中心）**

- Ruling R74: `execute` 的 `waitingSince` 用 `release_order.created_at`：设计 §2 写 `updated_at`，但该表自 V8 起只有
  `created_at`（V25 只加 `executed_by`），本切片禁止迁移与写路径改动；`mine` 用真实存在的 `release_task.updated_at`。
  `2bdff21`
- Ruling R75: `mine.count` 是**未完成变更项数**、`items` 是任务行（≤ `limit`），两者维度不同是有意的；前端徽标求和
  因此按「待办项数」计，可能大于列表行数；要按任务数只需改 `countMineItems` 一条 SQL。`2bdff21`
- Ruling R76: `approval` 只读**最新快照**的最小 `PENDING` 步骤，`role ∈ 调用者角色`，服务层再要求 `control:approve`；
  自审批排除用字面 `s.submitted_by != #{me}`，`submitted_by` 为 NULL 的 V23 前旧快照 **fail-closed 不出现**（未写断言）。
  `2bdff21`
- Ruling R77: `test` 组的「ADMIN 全量」只实现为范围 `allUnits`，仍须是任务 `TEST` 成员（与设计 §4.1「非成员不命中」
  一致）；`execute` 用权限点 `control:execute-prod`（非写死 ADMIN 角色）与 `OPS` 成员做 OR 分支。`2bdff21`
- Ruling R78: 排序取**等待最久优先**（`waitingSince ASC, id ASC`，设计未规定）；`waitingHours` 向下取整、负数归零，
  `waitingSince` 以 ISO-8601 UTC 字符串下发；`link` 三种形态由服务端拼；标签走 `ItemStatus.label()`/`TaskStatus.label()`
  （代码里**没有 `OrderStatus` 枚举**，ORDER 行复用 ItemStatus 镜像标签，TaskStatus.label 本次新增）。`2bdff21`
- Ruling R79: 新增 `ClockConfig` bean（`Clock` 构造注入，生产 systemDefaultZone、测试可冻结）；`limit` 数值越界
  40000（HTTP 200）、非数字 `limit=abc` 由框架转 HTTP 400 + 40000；8 条 SQL（4 明细 + 4 计数，`count=0` 跳过明细、
  无 N+1）。前端在文档收口期间随后提交 `c805568`（`/todos` + 侧栏徽标 + 四段 + 探针 + 2 张截图）：页面请求
  `limit=20`、徽标请求 `limit=1`，`mine` 段文案写「N 个未完成项」，等待文案 0→「刚刚」、只到整点；页面只渲染
  服务端字段，不拼路由。`2bdff21` / `c805568`

## R80+（P6-A 二方库，2026-11-26）

> **编号说明**：正式台账此前停在 R79（P5-C），本节按「接续最大编号」从 R80 起。来源文件在 `.gitignore` 的
> `.superpowers/` 下，未进提交；代码以 `d81b980`（后端）、`ef87f0f`（修复）、`7032bf9`（前端）为准。
>
> **实现历史（不是裁决、也不是产品偏差）**：Task 1 的后端实现者中途退出，lead 接手并把 6 个回归测试修复并入
> `d81b980`——状态机真值表（`ItemStateMachineTest` 补 `LIB_OK`/`LIB_FAIL` 两条边）、handler 注册
> （`P2BoundaryEndToEndTest` 期望 7 个 handler）、引擎端点清单（`CodingEngineTest` 13→15）、两条 unit-service
> fixture（`UnitServiceTest` 的 LIB 创建/更新补坐标）、监听器 item 查找（`ItemTransitionFailureTest` 夹具补
> `release_item`）。这 6 处是既有测试随新契约更新期望/夹具，不是产品行为偏差；随后 `ef87f0f` 的 order 状态镜像
> 按 R82 记为 review 修复。

- Ruling R80: LIB 流水线是独立三节点 `BUILD`（仅 `CODE_CHECK`/`PKG_BUILD`）→ `LIB_SECURITY`（零 `node_task`）
  → `LIB_PUBLISH`（一个 `LIB_PUBLISH` 任务），但仍是**真实发布单**（`order.id`）、`exec_type=LIB`、
  `trigger=INITIAL`、`currentNode=BUILD`，并**继续占单元锁**——同一库的并发发布必须串行化，防止制品版本
  冲突；不新建表。`d81b980`
- Ruling R81: 安全检查复用 P2 的 `gate_check` 与豁免，不建 `CHECK` 任务、不进 VERIFY：节点进入时只为**尚无检查行**
  的类型调 `GateCheckService.refresh`（不重跑已完成项），完成条件是三行**齐全**且 `GateEvaluator` 结论
  `admits()`（PASS/WAIVED，含等级/失效规则），只有不可豁免/失效才 `failureReason`；`WAIVED` 是**聚合结论**，
  `gate_check.status` 仍为 `FAIL`。`d81b980`
- Ruling R82: 发布单状态**镜像**变更项：`release_order.status` 在监听器每次 `APPLIED` 后写成变更项新状态，不带
  `row_version` CAS（状态是变更项的派生；stop/exit/close、提交/审批/执行等显式动作仍按既有写法用发布单
  `row_version` CAS）；否则 BUILD 失败/入库完成的发布记录页永远停在 `INIT`。该修复对 UNIT 同样生效，属全局读模型
  修正，不是 LIB 专属。`ef87f0f`
- Ruling R83: 依赖影响面是文档化启发式——**同系统 + `type=UNIT` + `module_path == artifactId`**，按名称/id 排序，
  响应固定 `source:"MODULE_PATH"`；同 `module_path` 的另一个 LIB 不算依赖；Q4/CODING 依赖 API 未定，替换实现
  不改契约。`d81b980`
- Ruling R84: 枚举只**追加在末尾**：`NodeType` 第 6/7（`LIB_SECURITY`/`LIB_PUBLISH`）、`TaskType` 第 9
  （`LIB_PUBLISH`）、`TaskKind` 第 5（`LIB_PUBLISH`）、`ExecType` 第 3（`LIB`）；设计未列 `JobKind`，代码另加
  `JobKind.PUBLISH`（第 3 个命名空间），否则 `publish.query`/webhook 会误落到 build/deploy。
  `CheckType` 不新增值，只加 `libSecurityChecks()` 选择器。`d81b980`
- Ruling R85: LIB **不发通知**：FR-N-01 未列二方库，节点失败与实例完成两条通知路径都按 `isLibOrder`/`item.type`
  守卫；LIB 的历史 = 发布页节点/任务 + 实例 + 一行 `PUBLISH` 审计。`d81b980`
- Ruling R86: 两个 LIB handler 放**应用层**（`application/LibSecurityNodeHandler`、`LibPublishNodeHandler`），
  不进 `domain/pipeline/handler`：它们需要 `GateCheckService`/`GateService` 与 `CicdEngine`/事务/完成服务；
  domain 只保留 `NodeHandler` 契约与 `NodeHandlerRegistry`，注册由
  `PipelineConfig#nodeHandlerRegistry(ObjectProvider<NodeHandler>)` 自动收集 `@Component`。`d81b980`
- Ruling R87: 入库端点：`CicdEngine#startPublish` 是 **default** 可选能力（其他引擎得到
  `ENGINE_UNAVAILABLE: 当前引擎不支持制品入库`）；`publish.start`/`publish.query` 的 URL、body、响应 JSONPath
  是**占位模板**（`/open-api/artifact/publish/start`、PascalCase body、`$.Response.Data.*`），真实租户未验证；
  `WAITING_EXTERNAL` 期限 60min，webhook `kind=PUBLISH` 与 30s reconcile 的 `LIB_PUBLISH→PUBLISH` 是收敛路径。
  `d81b980`
- Ruling R88: 坐标校验：LIB 的 `artifactGroupId`/`artifactArtifactId` 必填、trim、`^[A-Za-z0-9._-]{1,128}$`，
  UNIT 忽略入参并落 **`NULL`**；错误码 **40000 `PARAM_INVALID`**（设计未规定码），payload 带字段名。发布前置校验
  是坐标 + **BUILD** + **LIB_PUBLISH** 三个绑定，缺一项 40001 且**不取锁、不建单/实例**。`d81b980`
- Ruling R89: 发布入口复用既有 `PUBLISH` 审计动作（不新增动作码，after 带 `type=LIB`），响应
  `PublishResult(publishNo,pipelineNo,type)` 对 UNIT 也回 `type`；ENGINE 失败不回滚已提交的发布单/实例，失败原因
  落任务 `result_summary` 与实例 `status_reason`，HTTP 仍按发布成功返回。`d81b980`

## R90+（P6-C 单元流水线配置，2026-11-26）

> **编号说明**：正式台账此前停在 R89（P6-A），本节按「接续最大编号」从 R90 起。P6-B 的计划曾写「台账 R90+」，
> 但 P6-B 的文档收口至今未执行（没有 §8.14/§12.2.11/§19，也没有台账节）；**P6-B 若补写，从本节最后一条之后
> 续号**，不重排本节。来源文件 `.superpowers/sdd/p6c-pipeline/{progress.md,task-1-report.md,task-2-report.md}`
> 在 `.gitignore` 的 `.superpowers/` 下，未进提交；代码以 `40b2b27`（后端）、`9d047f7`（前端）为准。
>
> **实现历史（不是裁决、也不是产品偏差）**：本轮同样两轮实现——Task 1 的首个后端实现者中途因 provider 故障退出，
> 磁盘上留下可编译的半成品（V31、领域/PO/mapper、部分服务与工厂改动）；lead 以 continuation brief 接手续做，
> 补齐 controller/DTO、五个 handler 的超时读取、发布/重跑的空交集拒绝、门禁/LIB 一致性、28 个测试与读失败降级，
> 一次提交 `40b2b27`（25 文件，+2286/−39）。Task 2 的 web 轮独立提交 `9d047f7`（6 文件，含两张截图）。lead 复核：
> 全量 937 绿（surefire 96 个当前测试类，0 失败；两条 11-22 的陈旧报告不计）、web 独立 `npm run build` 绿、
> `scripts/verify-p6c-ui.mjs` 双主题通过、探针结束删行（`unit 2` / `lib 91001` 均为 `configured:false`）。

- Ruling R90: **无行 = 默认**是唯一默认机制：建单元不插行，`GET` 对无行单元返回默认值（开关开、60、8、`FORWARD`）
  加 `configured:false` 与 `updatedBy/updatedAt:null`；保存按主键 upsert（先 `UPDATE`，0 行再 `INSERT`），
  `updated_at` 服务端 `Instant.now()`，`updated_by` 取调用者。**没有 DELETE/重置端点**，恢复默认只能 PUT 默认值或
  删行——探针因此用 dev MySQL 删行。`40b2b27`
- Ruling R91: 环境级检查开关只作用于 `CheckStage.ENV` 的三项（`SECURITY_DYNAMIC`/`AUTO_TEST`/`DB_NORM`）：
  平台 `GATE.enabledChecks`（无行=七项全开）∩ 单元开关；镜像四项与 LIB 的三项 `libSecurityChecks()` 不受影响。
  交集为空时**在线发布/重跑 40011 `UNIT_NOT_CONFIGURED`**（设计未写该情形；复用既有码，事务内拒绝、不落实例），
  不预生成空 `VERIFY`——否则会卡住或凭零证据放行。`40b2b27`
- Ruling R92: 超时在**任务进入引擎**时经 `PipelineRepository#pipelineConfigOf(instanceId)` 读取（订单→变更项→单元；
  日常用实例自身 `unit_id`），`deadline_at = now + 配置分钟`；无行/读失败回退**P6-C 前常量**：BUILD 60、LIB_PUBLISH 60、
  TEST/PROD 8、DAILY_DEPLOY **60**（设计文字说日常用 8，但「无行 = P6-C 前行为」优先，P5-A 常量是 60；有行时用表中
  `deploy_timeout_minutes`，其默认 8）。在途 `deadline_at` 永不重算，下一任务才读新值。`40b2b27`
- Ruling R93: `REVERSE` 只把**新建在线实例**的生产族任务（`PROD/DR/BLUE`）重编号为
  `maxBatch + 1 - originalBatch`（与回滚同款变换），并按生效批号排序后预生成；测试部署 `batch 0` 不变，已有实例
  保存后不重编号；`createRollback` **完全不读**该配置（自带逆序）。`40b2b27`
- Ruling R94: 重跑的已完成生产批次改按 **DE target** 承接（`completedProductionBatches(Set<Integer>)` →
  `completedProductionTargets(Set<Long>)`）：逆序会重编号，按批号承接会跳过错误任务；FORWARD 下与旧行为等价。
  同批「最新 attempt 全部 SUCCESS」才算完成，`SKIPPED` 中性。`40b2b27`
- Ruling R95: PUT 四字段**全是字符串**（`envChecksEnabled`/`buildTimeoutMinutes`/`deployTimeoutMinutes`/`batchOrder`），
  因为 web DTO 不得 import domain 枚举；服务端 trim/解析/范围校验（5–240、1–120、两值），错误 40000；响应同时给
  机器值 `batchOrder` 与中文 `batchOrderLabel`，页面不翻译枚举。`40b2b27`
- Ruling R96: 读写都要求 `setting:manage` + `ScopeGuard`；**GET 无权限也是 40009**（HTTP 200 信封、payload `perm`），
  不是 403——页面据此在无权限时连 GET 都不发、只显示原因。保存写**一行** `SETTING_UPDATE`（object `UNIT`、
  objectId=unitId、before/after 整段五键含 `configured`、`scope_unit_id=unitId`、`scope_subsystem_id=NULL`）。`40b2b27`
- Ruling R97: 评估侧与面板一致：`GateCheckService#startChecks`、`GateService#decide/view/libSecuritySatisfied/
  libSecurityBlockedReason`、`LibSecurityNodeHandler#start` 都套同一交集；单元停用的检查在面板
  `CheckView.disabled/disabledLabel` 标「已停用」，历史 `gate_check` 行**保留不删、不补跑**（重新启用后重新参与评估）。
  配置**无缓存**（每次读表，保存下一次调用生效），与 P6-B 同口径。`40b2b27`
- Ruling R98: 存量行的缺列/越界值**降级为默认常量**（build 60、deploy 8、批次 `FORWARD`、开关 null=开），
  **不是夹到 5/240、1/120 边界**；`UnitPipelineConfig` 构造器里的 `clamp` 因此名不副实（范围内保留、否则回退默认），
  目的是外来行不能安装荒谬 deadline。写入侧仍严格 40000，只有手工改库才会触发降级。`40b2b27`

## R99+（P6-B 平台设置，补写，2026-11-26）

> **编号与补写说明**：P6-B 的代码先于文档合并（后端 `5827141`、web `9a13915`），其文档收口一直未执行；P6-C 的
> R90–R98 已占用 R90+，其编号说明写明「P6-B 若补写，从本节最后一条之后续号」。本次补写因此从 **R99** 续号，
> 不改写 R90–R98，也不改动 P6-C 的三节正文（技术方案 §8.15、§12.2.12、前端设计 §20）。
>
> **来源**：代码以 `5827141`（后端 18 文件，+1994/−37，全量 909 绿 = 基线 892 + 17）与 `9a13915`（前端 6 文件，
> 含两张截图；`npm run build` 绿、`verify-p6b-ui.mjs` 双主题通过）为准；任务报告在
> `.superpowers/sdd/p6b-settings/{task-1-report.md,task-2-report.md}`（`.gitignore` 的 `.superpowers/` 下，未进
> 提交）；契约与计划为 `docs/superpowers/{specs,plans}/2026-11-26-p6b-settings*.md`（`9f188e1`）。lead 复核：909 绿、
> build 绿、探针双主题绿并恢复默认（7 项 / 空名单 / 168）；探针会清空 `user_role` 的种子审批角色，复核时已手工
> 种回 `ops-lead`/`dept-manager`（见 R110）。

- Ruling R99: 平台设置用**一张通用 `app_setting`（V30）**、一段一行（`setting_key` 主键 + `value_json` JSON +
  `updated_by`/`updated_at`），不建 per-section 表；三段为 `GATE`（`enabledChecks`）、`APPROVER`
  （`opsLead`/`deptManager`）、`LOCK`（`pauseBoundHours`）。没有 DICT/`role_permission` 行，因为无消费者/属权限模型
  变更；JSON 在 PO 层保持 `String`（同 `AuditLogPO`），由应用 `ObjectMapper` 解析。`5827141`
- Ruling R100: GATE 的**写入严格、读取容错**：写入经 `CheckType.fromName`（trim、大小写不敏感）校验，去重后按枚举
  顺序落库，空数组/未知项/缺字段 **40000**（payload 带 `enabledChecks`）；读取时行缺失、JSON 非法、无数组、空集或
  全是本版本不认识的检查名，一律回退**七项全开**并 WARN——配置读失败只能让门禁更严，不能更松。`5827141`
- Ruling R101: GATE 的**效果边界**：只影响新建实例（`createOnline`）与重跑（`createRerun`，重跑也是新实例），
  `VERIFY` 只预生成启用项的 CHECK 任务；停用项的 `gate_check` 历史行**保留不删**，评估前过滤、面板显示并标
  「已停用」（`disabled`/`disabledLabel` 追加在 `CheckView` 末尾）；与在途实例立即相关（评估忽略），在新实例上
  不补跑。手动 `refresh` 仍允许（保留历史证据入口），结果在评估时被忽略。P6-C 之后实际集合是「平台启用集 ∩
  单元环境检查开关」，见 R90–R98 与 §8.15；`imageMismatches` 仍只按平台集合。`5827141`
- Ruling R102: LIB 的**三项制品检查也受平台开关过滤**（`LibSecurityNodeHandler#start` 只触发启用项，
  `libSecuritySatisfied` 的完整性守卫只要求启用项）。三项全停用时 `libSecurityBlockedReason` 直接给出
  「二方库安全检查已全部停用…」让 `LIB_SECURITY` **快速失败**，而不是零行完整性守卫把发布挂死。这不是 **40011**：
  40011 是 P6-C 在发布/重跑时对「平台 ∩ 单元」空交集的拒绝（R91），两者不在同一路径。`5827141`
- Ruling R103: `APPROVER` 的权威仍是 `user_role`，保存是「角色成员名单的整体替换」：在同一事务里写 JSON 并
  `DELETE`/重插**仅** `OPS_LEAD`、`DEPT_MANAGER` 两角色行，其他角色行不动；id 需 trim、去重、≤32 字符、用户存在且
  `ACTIVE`，空数组合法 = 清空该角色。`JdbcAuthProvider` 每请求读表，保存后新审批人下一请求即获得
  `control:approve`、被移除者立即失去（用真实用户验证）；不写 `role_permission`、不做按系统/单元指定审批人（Q2 未定）。
  GET 返回**生效名单**：存储列表为空时回退读 `user_role` 当前成员；但 PUT 空名单同时删角色行，所以保存后立即
  GET 通常为空，只有角色行被种回时才重新回显。`5827141`
- Ruling R104: `LOCK.pauseBoundHours` 1–8760（缺/越界 40000），GET 对缺失/非法行回 **168**；消费侧
  `pauseBoundHours(fallback)` 用部署的 `release.unit-lease.max-paused-hours`（默认 168）作回退（fallback 自身越界时
  再用 168）。`UnitLeaseRenewJob` **每轮**读配置，保存下一轮生效；租约 **TTL 4h 仍是代码常量**，本切片不改锁契约，
  通知与日志使用当轮 bound。`5827141`
- Ruling R105: 接口是 **6 条固定路径** `GET/PUT /api/v1/settings/{gate,approvers,lock}`，没有通用
  `/settings/{key}`；请求只带值（actor/time 由服务端补），PUT 返回保存后的视图（GATE 已按规范顺序排列）；
  响应统一 `ApiResponse`，三段均带 `updatedBy`/`updatedAt`。设计 §5.1 的「未知 key 40000」没有对应请求形状：未知
  检查名 40000、未知 URL 由框架 **404**（实测 `/api/v1/settings/nope` → 404）。种子 `updated_by=NULL`、
  `updated_at=NOW(3)`，因此 GET 会回种子时间戳；页面用 `updatedBy` 是否为空区分「默认/已保存」。计划里的
  `ApplicationSettings` 聚合记录未落地（三个视图各服务一个端点，无消费者）。`5827141`
- Ruling R106: 6 个端点（**含 GET**）全部 `@RequirePerm(setting:manage)`；无身份/无权限是 **40009**（HTTP 200 信封，
  payload `perm:"setting:manage"`），不是 403——页面据此在无权限时**连 GET 都不发**。平台级设置**没有数据范围**：
  任何 `setting:manage` 账号可读写全平台三段；数据范围只约束审计可见性（R107）。`5827141`
- Ruling R107: 每次**成功保存**写**一行** `SETTING_UPDATE`：`object_type=SETTING`、`object_id=GATE|APPROVER|LOCK`、label 为
  「门禁规则设置/审批人名单设置/锁超时设置」、`before_json`/`after_json` 是**整段** section（APPROVER 的 before 是
  生效视图，可能来自 `user_role` 回退）、两个 `scope_*` 为空 = 平台级，只有 `ALL` 范围审计读者可见。APPROVER 的
  JSON + `user_role` 在同一事务，审计仍走既有 `REQUIRES_NEW` 吞异常口径（审计失败不改保存结果）；GATE/LOCK 的写库
  与审计不在同一事务。`5827141`
- Ruling R108: **明确不做**（设计 §6）：字典 `DICT`（无消费者）、`role_permission` 页面/编辑（权限模型变更）、租约
  TTL 可配（4h 常量）、按系统/单元指定审批人（Q2 未定）、检查等级阈值（GAP-08 原文提到，但本切片只做启用/停用）。
  通知渠道、发布窗口/封板、CODING 连接/任务映射**已在前序切片交付**，本切片不改。`5827141`
- Ruling R109: 前端三块在设置页「消息通知」之后，共享**一次** `/me` + `Promise.allSettled` 并发三段 GET；无
  `setting:manage` 时三块只读、无控件/保存按钮且**零设置请求**。门禁保存只在「停用已保存项」时二次确认；检查中文名
  是页面本地只读映射（机器值是契约，未知值原样保留/提交）；锁超时用 `el-input`（`inputmode=numeric`）而非数字框，
  1–8760 在发 PUT 前客户端拒绝；审批人空名单合法，页面提示按 API 实际行为写「保存空名单后通常回显也为空」。
  `9a13915`
- Ruling R110: 证据口径与坑：`verify-p6b-ui.mjs` 的恢复默认是**经 API** PUT 7 项/空名单/168，因此会删掉
  `user_role` 的 `OPS_LEAD`/`DEPT_MANAGER` 种子行（`ops-lead` 只剩 `OPS`，无 `setting:manage`/`control:approve`）；
  联调前需重新保存审批人或种回角色行。PUT 还会把 `updatedBy/updatedAt` 写成 `dev` + 本次时间，配置值虽恢复默认，
  元数据无法通过 API 还原为 `null`。前端**无单测**、截图未人眼复核；没有「改配置 → 真发布 → 观察新实例」的端到端
  取证，效果由 17 个后端测试（真实 DB + stub 引擎）覆盖。`9a13915` / `5827141`

## R111+（P6-D 紧急放行，2026-11-26）

> **编号说明**：正式台账此前停在 R110（P6-B 补写）。P6-D 的代码先于文档合并、在本次收口，按「接续最大编号」从
> **R111** 起，不改写 R80–R110，也不改动 P6-A/B/C 的三节正文（技术方案 §8.13–§8.15、§12.2.10–§12.2.12，
> 前端设计 §18–§20）。
>
> **来源**：契约与计划为 `docs/superpowers/{specs,plans}/2026-11-26-p6d-emergency-release*.md`（`ad5fb97`）；代码以
> `10b9d6d`（后端 23 文件，+2145/−19，全量 964 绿 = 基线 937 + 27）与 `7b44de2`（前端/脚本/截图 10 文件，971 插入；
> `npm run build` 绿、`verify-p6d-ui.mjs` 双主题真实放行通过并重播种恢复）为准；任务报告在
> `.superpowers/sdd/p6d-emergency/{task-1-report.md,task-2-report.md}`（`.gitignore` 的 `.superpowers/` 下，未进提交），
> 进度见同目录 `progress.md`。lead 复核：964 绿（本轮按 surefire 报告逐类累计：100 个当前测试类、
> 0 失败/0 错误/0 跳过；目录里另有两条 2026-11-22 的陈旧报告，累加会得到 974，**不计**）、独立 `npm run build` 绿
> （`web/dist` 产物时间 2026-11-26 02:34:38）、探针输出见前端设计 §21 与技术方案 §12.2.13、dev 库已恢复
> `PO-DEMO-EMERGENCY` 为 `PENDING_APPROVAL`/0 历史。

- Ruling R111: 模型是 **`release_order` 四列 + `emergency_release` 历史表（V32）**：订单列
  `emergency_release TINYINT NOT NULL DEFAULT 0`/`emergency_reason VARCHAR(512)`/`emergency_by VARCHAR(32)`/
  `emergency_at DATETIME(3)` 是**读标记**（详情/记录不出新 join，直接在既有 `release_order` 关联上加四列），历史表
  `(id, order_id, publish_no, reason, actor, created_at, KEY(order_id, created_at))` 是**每次一行**的 append-only
  证据（`EmergencyReleaseMapper` 只有 `INSERT`）。四列**永不清除**、**没有撤销端点**；重跑后再次放行追加第二行历史，
  订单列保留最近一次。历史表**没有读接口**（设计未要求；探针直接读 MySQL）。`10b9d6d`
- Ruling R112: 权限是**平台角色 `ADMIN`**，不是权限点：`EmergencyReleaseService#requireAdmin` 用
  `UserContext.current().hasRole(Roles.ADMIN)`，`EmergencyReleaseController` **无** `@RequirePerm`、无 `@DataScope`
  （端点因此不是 `ReleaseController` 的新方法，而是独立控制器）；非 ADMIN 在**读单号之前** 40009（payload
  `roles`），持 `control:approve` 的 `OPS_LEAD` 同样被拒；未登录由拦截器 40009。服务**不做数据范围校验**：ADMIN 在
  `JdbcAuthProvider` 中即 allUnits，设计未要求 SCOPE；将来若允许「受限 ADMIN」需补 `ScopeGuard`。`10b9d6d`
- Ruling R113: 状态矩阵**只判变更项状态**（`AdmissionService.statusOf(item)`，外层预检 + 事务内重读；`release_order.status`
  不参与，只被镜像）：`PENDING_APPROVAL → APPROVE_ALL → READY`、`SUBMITTED → NO_APPROVAL → READY`；`READY`（专用文案
  「发布单已经是待执行状态（READY），无需紧急放行」）与**其它全部状态**（含 `GATE_PENDING/GATE_FAILED/FAILED/REJECTED/
  DONE/TESTING/BUILDING/PENDING/PROD_DEPLOYING/CLOSED/TERMINATED/ROLLED_BACK`）都是 40001，payload
  `{publishNo, itemId, currentStatus}`；成功后重复调用 40001；单号不存在/变更项缺失 40000。一个事务内顺序：
  `selectByIdForUpdate`（订单行）→ 重读变更项 → 作废审批 → `ItemTransitions.apply`（`row_version` CAS）→ 订单镜像
  `READY` + 四列（`row_version` CAS）→ 追加历史；订单 CAS 失手 → 40001 且整事务回滚。`10b9d6d`
- Ruling R114: **审批作废复用 P5-B 的 `ApprovalService#cancelPendingOfLatestSnapshot`**，不走新路径：最新快照链上所有
  `PENDING` 步骤 → `CANCELED`、`decided_by=NULL`、`decided_at=now`、comment「紧急放行：<reason>」（全角冒号）；
  已决策步骤不动；无快照或没有待签返回 **0**（不是错误）。同一把订单行锁保证签名与放行不交错。`10b9d6d`
- Ruling R115: **封板跳过必须改两处**（设计只点名了 `ProductionBoundaryProbeService`）：执行入口
  `ReviewService#reviewOf`（`ExecuteProdService` 的 40002 来源）只把封板从 `reasons`/`blocked` 中移除，批次边界
  `ProductionBoundaryProbeService#blockedReason`（`ProdDeployNodeHandler` 的暂停原因）跳过 `FreezeService#active`；
  两处都以 `order.emergency_release != 0` 判定。**只跳过封板等待**：`frozen`/`freezeIds` 仍如实上报（页面同时显示
  封板告警与「审批链已作废」）；镜像 digest 不一致仍 40007、豁免过期仍 40002、门禁 `INVALID`/不可豁免 `BLOCK`
  仍暂停、状态机与审批照旧；窗口不重算（J5）。连带效果：`ExecuteProdService` 的「快照后新封板把 READY 拉回
  `PENDING_APPROVAL`」分支对紧急单不触发。只改 probe 会让 J11 在冻结期不可用。`10b9d6d`
- Ruling R116: 理由校验**分两层、web 在前**：请求体复用 `ControlDtos.ReasonRequest`（`@NotBlank` +
  `@Size(max=200)`），空/超长在进入 controller 前被 `GlobalExceptionHandler` 拒成 **40000**（message「参数不合法」、
  `data.reason` 为校验文案）；服务层再 trim、按 `MAX_REASON_LENGTH=200` 兜底（「紧急放行理由不能为空」/
  「紧急放行理由不能超过 200」）。因此**非 ADMIN + 非法 body 返回 40000 而不是 40009**；长度按原始字符串（trim
  之前）算，`"  "` 由 `@NotBlank` 拦、`" x "` 放行后 trim 落库。`10b9d6d`
- Ruling R117: **响应与读模型的类型不同**：`POST` 响应 `EmergencyResponse{status,itemStatus,emergency}` 的
  `emergency` 是 **JSON boolean `true`**（`EmergencyResult`/`EmergencyResponse` 字段都是 `boolean`），
  `status`/`itemStatus` 是字符串 `READY`（服务返回常量，不回读）；详情/记录四字段**都是字符串**——
  `emergencyRelease` 恒 `"true"`/`"false"`（列默认 0 与 pre-V32 `null` 都算 `"false"`；`emergencyFlagOf`），
  `emergencyReason`/`emergencyBy` 原文，`emergencyAt` 是 `Instant.toString()` 的 ISO-8601；未放过行后三者
  `null`。`ReleaseQueryMapper`/`ReleaseRecordsMapper` 各加四列，**无新 join、无 N+1**。`10b9d6d`
- Ruling R118: **审计与通知在事务提交后写、失败不回流**：`AuditAction.EMERGENCY_RELEASE("紧急放行", ORDER, false)`
  写**一行**（object_id=object_label=publishNo、reason=理由、summary「紧急放行：发布单 X（变更项 CRID）」、
  `before_json={itemStatus, emergencyRelease:false}`、`after_json` 7 键含 `approvalsCancelled`、
  `scope_unit_id`/`scope_subsystem_id` 取变更项与其单元）；`NotificationEventKey.EMERGENCY_RELEASE("紧急放行", ORDER)`
  发给 `NotificationAudiences.taskMembers(orderId, {OPS, DEV})` = 任务 OPS/DEV 成员 + 创建人（`TreeSet` 去重，
  同一人一行，可能 0 行），dedupe key `emergency:<orderId>:<epochMilli>`，body 写明「待签审批已作废，封板等待已跳过；
  镜像/门禁/豁免复核仍照常执行」「不可撤销」。两者沿用 `REQUIRES_NEW` + 吞异常（P4-B/P4-C 口径），
  审计表/通知表故障时放行仍成功（各有测试）。设计 §1 的「复核结果仍写审计」**未实现**（`ReviewService` 只读，
  执行只多 `EXECUTE_PROD` 一行）。`10b9d6d`
- Ruling R119: 前端口径：发布页 `PageHeader` 第一颗按钮 `.emergency-release`（`type="danger"`）**只对 `/me` 的
  `roles` 含 `ADMIN` 渲染**，非 ADMIN 连按钮都不存在（`ops-lead` 验证）；**状态不合格是 disabled + title 原因**
  （顺序：`/me` 未回 → 身份失败 → admission 未回 → admission 失败 → 已放行 → READY → 其它状态），可放行时 title
  是能力说明；确认框复用 `ReasonDialog` 并新增可选 `warningType: 'warning'|'error'`（只有紧急放行传 `error`，
  停止/退出/归档/关闭仍是黄色），空理由/超长禁用确认，`40001` 关框并刷新、其它错误保留输入；已放行时页头
  `.emergency-banner`（理由/操作人/时间）+ 准入面板 `emergency` prop 的「审批链已作废」红色 alert + 步骤 `CANCELED`；
  父组件用 `:key="isEmergency ? 'admission-emergency' : 'admission-normal'"` 重挂面板以重拉 admission；记录页在
  「发布单」列（190→210）加 `chip-danger emergency-chip` + hover tooltip（理由/操作人/时间）。红色全部来自既有
  `--danger`/`--danger-weak`：`ReasonDialog`/`AdmissionPanel` 把 Element Plus 的 `--el-color-error*` **局部**绑到
  `--danger`，不改全局色板。**截图是全页 1440×2351**（横幅 y≈157、作废链 y≈1572–2116，900 高视口无法同图呈现）。`7b44de2`
- Ruling R120: 证据口径与坑：探针 `verify-p6d-ui.mjs` 依赖 **dev MySQL 客户端**重播种 `scripts/demo-emergency.sql`
  （`root/root`、幂等、每次运行前/每主题前/finally 各一次），必须从仓库根目录运行（`--shots` 与 SQL 路径都相对
  CWD），且 `puppeteer-core` 只在 scratch 目录可解析（拷到 `%TEMP%\rp-shot-tools` 执行）；脚本对 demo 单
  `PO-DEMO-EMERGENCY` **真实放行两次**（每主题一次），结束时断言恢复 `emergencyRelease=false /
  itemStatus=PENDING_APPROVAL / chain=PENDING/PENDING`（否则退出码非 0），历史行随重播种清除、审计/通知行保留为证据。
  前端**无单测**、截图未人眼复核（本轮文档模型无图像输入，只核实文件尺寸 1440×2351）；执行侧只在真实 DB + stub
  引擎上验证，没有真实 CODING 租户端到端。`7b44de2` / `10b9d6d`

## R121+（P6-E 审批时效提醒，2026-11-26）

> **编号说明**：正式台账此前停在 R120（P6-D 收口）。P6-E 的代码先于文档合并、在本次收口，按「接续最大编号」从
> **R121** 起，不改写 R80–R120，也不改动 P6-A/B/C/D 的章节正文（技术方案 §8.13–§8.16、§12.2.10–§12.2.13，
> 前端设计 §18–§21）。
>
> **来源**：契约与计划为 `docs/superpowers/{specs,plans}/2026-11-26-p6e-approval-reminder*.md`（`eb869c8`）；
> 代码以 `78bc195`（后端 18 文件，+1645/−16，全量 994 绿 = 基线 964 + 30）与 `516a680`（前端/脚本/截图 6 文件，
> +743/−5；`npm run build` 绿、`verify-p6e-ui.mjs` 双主题通过并恢复 `enabled/12/24`）为准；任务报告在
> `.superpowers/sdd/p6e-reminder/{task-1-report.md,task-2-report.md}`（`.gitignore` 的 `.superpowers/` 下，未进提交），
> 进度见同目录 `progress.md`。lead 复核：994 绿（`server/target/surefire-reports` 当前 **103 个测试类**逐类累计 =
> 994，0 失败/0 错误/0 跳过；目录里另有两条 2026-11-22 的陈旧报告共 10 条，累加会得到 1004，**不计**）、独立
> `npm run build` 绿、探针输出见前端设计 §22 与技术方案 §12.2.14、live 端点取证见 §12.2.14。

- Ruling R121: 配置是 **V33 给通用 `app_setting` 加第四段 `APPROVAL`**（无新表、无新索引），
  `{"enabled":true,"remindHours":12,"escalateHours":24}`；`SettingsService` 的 `SECTION_APPROVAL`、
  `DEFAULT_REMIND_HOURS=12`/`DEFAULT_ESCALATE_HOURS=24`、保存范围 `MIN/MAX_REMIND_HOURS=1/72`、
  `MIN/MAX_ESCALATE_HOURS=2/168` 且 `escalateHours > remindHours`；保存整段覆盖（`UPDATE` 未命中再 `INSERT`）。
  `enabled=false` 时扫描在 **due 查询之前**返回 `ScanResult.none()`（不发消息也不查待签行）；每次扫描读表、无缓存。
  种子行 `updated_by=NULL`，保存后为当前账号。`78bc195`
- Ruling R122: 扫描口径是**每单最新准入代**：`ApprovalReminderMapper#selectDue` 用
  `a.snapshot_id = (SELECT MAX(snap.id) FROM admission_snapshot snap WHERE snap.order_id=a.order_id)`，**不是**
  `MAX(approval.snapshot_id)`——重提后不需要签字的新代没有 `approval` 行，按 approval 取 MAX 会把旧代 `PENDING`
  行复活；`only_the_newest_snapshot...` 与 `a_new_generation_without_a_pending_step_does_not_revive_the_old_one`
  钉住。只取 `a.status='PENDING'` + `i.status='PENDING_APPROVAL'`；`created_at <= now-remindHours`（**边界含等号**，
  恰好 12h/24h 就发），Java 再以 `Duration.compareTo >= 0` 判级；`ORDER BY created_at, order_id, step_no`，
  `LIMIT` 由 `scan(now,limit)` 传入、`limit<=0` 回退 `DEFAULT_SCAN_LIMIT=200`，job 固定 200；等待小时数用
  `Duration.toHours()` 向下取整。`78bc195`
- Ruling R123: 两级**先判升级、再判提醒**：`waited >= escalateAfter` → `APPROVAL_ESCALATE`（ADMIN + 系统负责人），
  `else if waited >= remindAfter` → `APPROVAL_REMIND`（该角色生效审批人）。因此首扫已 ≥24h **只升级不补提醒**，
  12h 已提醒的步骤到 24h 只多一组升级行，25h 再扫不新增（升级抑制不依赖历史发送记录，由分支顺序保证）。
  `ScanResult{scanned,reminded,escalated}`：`scanned` 是 due 行数，`reminded`/`escalated` 是**本轮尝试发送数**，
  不是插入行数；幂等由通知唯一键承担。`78bc195`
- Ruling R124: 接收人：提醒用 `SettingsService#approvers()` 的该角色生效名单（P6-B：存储名单为空 → `user_role`
  的 `OPS_LEAD`/`DEPT_MANAGER`），最终为空 → 系统负责人（正文追加「（角色无人，已转系统负责人）」）；未知角色名
  不臆造名单，同样转负责人（`ApprovalRole` 只有两个角色，`roleLabel` 对未知角色原样显示机器名）。升级用
  `user_role` 的 `ADMIN`（`SELECT DISTINCT user_id`，无 ACTIVE 过滤）+ 扫描行的 `subsystem.owner_id`，
  `TreeSet` 去重（负责人兼 ADMIN 只一行）；owner 取自 due 查询 join 出的行，resolver 不再二次读 subsystem
  （负责人变更下一轮生效）。`subsystemId`/`creator` 随行查出但当前规则不用。`78bc195`
- Ruling R125: 去重与通知：ref = `approval:{snapshotId}:{stepNo}:REMIND|ESCALATE`；唯一键
  `event|objectType|objectId|recipient|channel|ref`（`INSERT IGNORE`），服务端**不预读通知表**。因此「同一代同一步骤
  只发一次」的实际粒度是 **per recipient/channel/level**：12h 后新加入该角色的审批人下一轮会收到同一 ref 的提醒；
  一次升级 = 每个接收人一行站内（测试 fixture 为 2 ADMIN + 负责人 = 3 行）。事件键
  `APPROVAL_REMIND("审批待处理提醒", ORDER)`、`APPROVAL_ESCALATE("审批超时升级", ORDER)`（事件键 19→21）；正文含
  发布单号、角色中文名、已等待小时数、`/releases/{publishNo}` 直达链接，升级追加「已升级通知系统管理员与系统
  负责人」；渠道沿用 P4-B（INAPP 即时 `SENT` + 配置企微时 `PENDING` 由 15s 任务发送/重试一次），扫描本身不发 HTTP。
  **扫描不写审计**（`a_scan_writes_no_audit_rows` 断言 `audit_log=0`），通知行即留痕。`78bc195`
- Ruling R126: 端点与审计：`GET/PUT /api/v1/settings/approval` 加在既有 `SettingsController`（不是新控制器），
  两端点都 `@RequirePerm(setting:manage)`——**读也 40009**（payload `perm:"setting:manage"`，message「缺少权限点：
  setting:manage」，不是 403）；请求三个字段都 `@NotNull`（**具备权限时**缺字段先被 web 校验拒成 40000「参数不合法」，
  `data.<field>`「不能为空」——权限检查先于请求体校验，非管理员即使 body 非法也 40009），服务再校验范围与交叉
  （40000，`data.remindHours/escalateHours="超出范围"` 或 `"必须大于提醒阈值"`）；响应
  `ApprovalSettingsResponse{enabled,remindHours,escalateHours,updatedBy,updatedAt}`
  （`updatedAt` ISO-8601）。每次成功保存写**一行** `SETTING_UPDATE`：`object_type=SETTING`、`object_id=APPROVAL`、
  label「审批时效设置」、`before_json`/`after_json` 为整段、两个 scope 列为 `NULL`（平台级，仅 `ALL` 读者可见）。
  无 DELETE/重置端点，恢复默认只能 PUT；`updatedBy` 无法通过 API 还原为 `null`。`78bc195`
- Ruling R127: 任务、回退与证据口径：`ApprovalReminderJob` `@Scheduled(fixedDelay=600_000L)`，注入 `Clock`
  （`ClockConfig`）调 `scan(clock.instant(),200)`，整轮异常只记日志；`JobConfig` 用
  `@ConditionalOnProperty(name="release.approval.reminder-enabled", havingValue="true", matchIfMissing=true)`，
  `application.yml` 显式 true、`application-test.yml` false（开关关闭时 bean 不注册，不是空跑）；**无分布式锁/leader**，
  多实例靠唯一键幂等。读失败回退比设计更宽：缺行/JSON 非法/字段缺失或类型不对/越界/**阈值交叉**一律回退
  `enabled=true / 12 / 24`（交叉当坏段，不「修正」），能读到行时回退值仍带 `updatedBy/updatedAt`；service 再兜
  `RuntimeException`；`APPROVER` 读失败 → 空名单 → 负责人，ADMIN 读失败 → 只发负责人。证据：30 个测试（22 + 6 + 2，
  真实库 + 固定时钟 `CREATED=2026-11-26T00:00:00Z`）、全量 994 绿（103 个当前测试类）、探针
  `verify-p6e-ui.mjs` 双主题绿（`guard=0/12eq/169 puts=3 saved=6/12 off=false restored=true/12/24 readonly=yes
  approvalRequests=0 errors=0`）、截图 1440×900 视口（浅 94,463 B / 深 93,685 B）。探针只走 HTTP API、不依赖 MySQL、
  不改 `user_role`，但 `finally` PUT 恢复默认会把 `updatedBy/updatedAt` 写成 `dev` + 本次时间。前端**无单测**、
  截图未人眼复核；没有真实等 12 小时/真实企微收消息的端到端取证。`516a680` / `78bc195`
## P6-F/P6-G 裁决（2026-11-26，页面补齐 + 单 DE 重部署与日志）

- Ruling R128: 批量部署是**前端串行**，无后端批量端点（PART-02）：任务详情多选（只 `PENDING` 可勾——状态机
  唯一可发起 `PUBLISH` 的状态，比设计原文「DONE 不可选」更窄）逐项调既有 `POST /items/{itemId}/publish`；
  失败不中断、`ElMessageBox.alert` 成败摘要。逐项的锁/绑定/审计语义与单条发布完全一致；中途刷新丢批次进度
  （已建发布单不丢）。`e67a4f9` / `ec4689a`
- Ruling R129: 豁免默认有效期（PART-06）：`expireAt` 可空 → 服务端 `now + WAIVER.defaultDays`（V34 只插
  `app_setting` 种子行 `{"defaultDays":7}`）；`GET/PUT /api/v1/settings/waiver`（`setting:manage` 读也 40009，
  1–90 越界 40000，保存一行 `SETTING_UPDATE` object `WAIVER`）；显式有效期仍原样存、上限 90 天不变；前端
  对话框不预读具体天数只提示「平台默认」。`e67a4f9` / `ec4689a`
- Ruling R130: 发布单人员列（PART-03）：`ReleaseDetail` 常驻 `testers/deployers`（任务成员 TEST/OPS join
  `app_user`，`LEFT JOIN` 保名、按 userId 排序、空数组），纯只读展示。`e67a4f9` / `ec4689a`
- Ruling R131: 重部署资格（PART-04）：任务须属于该单**最新实例**、`PROD_DEPLOY/DEPLOY`、`FAILED/TIMEOUT`、该
  `(batch,target)` 最新 attempt、目标 DE 仍在该单元生产环境、实例有可复用 `image_digest`；变更项与发布单须
  `FAILED/PROD_DEPLOYING`。成功任务不提供（避免绕过准入）；历史实例只读。错误码：40000 不存在、40009 权限/范围、
  40001 不合格、40006 锁。`b49f6f9`
- Ruling R132: **整批重试**：新实例 = `PipelineFactory.createSingleDeRedeploy`（基于 `createRerun` 的
  `RERUN_FROM_NODE@PROD_DEPLOY`，仅目标批次 DE 集合保持 PENDING，其余批次与更早节点 SKIPPED）。批次用 **DE id
  集合**表达而非 batchNo——P6-C `REVERSE` 会重编号批次，DE 身份不变。沿用上一实例镜像/commit 不重建（J17 口径）。
  变更项走既有 `RERUN→TESTING→EXECUTE_RERUN→PROD_DEPLOYING` 两边，无新状态/事件；`invalidateUnfinished`
  失效未完成检查；审计 `RERUN`（ORDER，summary「单 DE 重部署（目标 <deName>）」）。`b49f6f9`
- Ruling R133: 日志代理：`GET .../tasks/{taskId}/logs?offset=&limit=`（登录 + 范围，`@DataScope` 标记 +
  `ScopeGuard` 在业务状态之前）。kind 映射 `CODE_CHECK/PKG_BUILD/IMG_BUILD→BUILD`、`DEPLOY→DEPLOY`、
  `LIB_PUBLISH→PUBLISH`；`CHECK/AUTO_TEST`/`ROLLBACK/DAILY_DEPLOY` 40001（无引擎作业）；无 `external_id` 40001；
  `offset>=0`、`limit 1–8000`（默认 8000）越界 40000；引擎异常（50001/50002）**原样透传不包装**；
  `truncated = content.length() >= limit`（整页即可能有下一页）。不缓存不落库。`b49f6f9`
- Ruling R134: `ReleaseTaskView` 增加 `id`（`node_task` 行 id）：发布内容任务行此前的 `taskType/batchNo/targetId`
  无法唯一定位一行，两个新动作按 id 寻址；`batchNo/targetId` 保留用于展示。`b49f6f9`
- Ruling R135: 发布页节点模板重构：`PROD_DEPLOY` 节点渲染「生产准入面板 + 部署任务行」并排（此前
  AdmissionPanel 独占该节点、任务行不可见——P6-G 的必要重构）；`VERIFY`/`LIB_SECURITY` 仍只渲染各自面板；
  「重新部署」按钮用 reason 字符串模式（权限/任务状态/变更项状态），「日志」按钮按任务类型集合渲染。
  日志抽屉一次一页（`offset += limit`，「加载更多」仅 `truncated` 时可用；引擎失败显示服务端文案红色 alert）。
  `ec4689a`
- Ruling R136: 证据口径：27 个 redeploy 测试（真实库 + fake engine，覆盖设计 §4 的 1–3/5）、全量 1038 绿；
  探针 `verify-p6g-ui.mjs`（种子 `demo-redeploy.sql`：批 1 成功 + 批 2 失败，种子按 order 清理自身全部实例与
  旧锁）双主题绿、0 console error——断言按钮条件、确认文案、一次真实 POST（详情 API 回 `RERUN_FROM_NODE`、
  批 1 `SKIPPED`、批 2 重试）、日志抽屉引擎文案（本机无 CODING 连接，真实成功路径只有 fake engine 单测）；
  `verify-p6f-ui.mjs`（种子 `demo-batchpublish.sql`，失败项走真实服务端拒绝）双主题绿。截图
  `redeploy-{light,dark}.png`、`batch-publish-{light,dark}.png`、`settings-waiver-{light,dark}.png`。`b49f6f9` /
  `ec4689a`

## PART-07 裁决（2026-11-27，兼容模式语义）

- Ruling R137: 语义与「不删字段」：`COMPATIBLE` = 兼容（非标）流水线——**代码变更由团队自管，平台只投递镜像**；
  回滚仅切镜像（一期统一行为）。需求原文「跳过构建节点的合并校验并提示」在一期无可跳过之物（合并校验从未实现），
  语义收敛为提示。`branch_mode` 不参与任何后端判定、无迁移无端点；`framework_template` 保留为描述字段
  （FR-I-04 二期才有消费者）。不删字段：字段已被列表/详情/表单消费，删除是破坏性迁移且语义已落。
- Ruling R138: 实现与证据：纯前端——发布页（LIB 与非 LIB 都 best-effort 读单元）与回滚抽屉在
  `branchMode==='COMPATIBLE'` 时渲染 info 横幅/提示，不阻断任何操作；探针 `verify-p7-ui.mjs`（复用
  `demo-redeploy.sql`，临时置 `COMPATIBLE`、`finally` 还原 `STANDARD`）双主题绿、0 console error；截图
  `compatible-{light,dark}.png`；后端零改动（全量 1038 绿回归确认）。

## PART-01 裁决（2026-11-27，页面信息结构）

- Ruling R139: 单元摘要端点：`GET /units/{unitId}/release-summary`（`@DataScope` 标记 + `ScopeGuard`，
  未知单元 40000、范围外 40009）。`latest` 复用 `RollbackRecordMapper.selectCurrent`——与回滚抽屉「当前线上
  版本」同一查询定义（最近一次成功生产部署，任何触发类型），列表列与回滚目标不会口径漂移；`lock` 直读
  `deploy_unit_lock` 行原样快照（过期未回收的租照样显示，与巡检回收行为一致）。无新表无迁移，读端点不写审计。
- Ruling R140: 列表读取是前端逐行 best-effort：当前页 ≤100 行各一次小读（`Promise.allSettled`），失败行显示
  `—`；不做后端批量聚合端点。系统详情「变更明细」由发布记录块（`listReleases({systemId})` 行含 CRID/版本）
  承接，不做按系统列变更项的新端点。
- Ruling R141: 管控配置入口：系统详情页头「管控配置」按钮跳 `/control?subsystemId=`；`ControlView` 读
  `route.query.subsystemId` 正数即预选 `targetSubsystem`（窗口与检查按它过滤；未知 id 只回显 `#id` 不报错）。
  **单元详情「消息通知」块降级不做**（通知是发布单维度、列表接口只按 page/unreadOnly 过滤，按单元聚合需新
  join；通知中心已可查）——已接受残留。证据：5 个 `part01` 测试 + 全量 1043 绿；探针
  `verify-p1b-ui.mjs` 双主题绿（种子 `demo-redeploy.sql` + 插锁行验证占用态、管控入口跳转预选）。
