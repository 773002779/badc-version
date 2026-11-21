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
