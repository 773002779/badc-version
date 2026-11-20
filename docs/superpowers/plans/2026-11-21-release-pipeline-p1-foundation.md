# 发版流水线平台 · 实施计划 P1：基座 / 资源 / 任务 / 发布流水线

> **For agentic workers:** REQUIRED SUB-SKILL: Use subagent-driven-development (recommended) or executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付一个可运行的平台骨架：能配置子系统与部署单元、建发布任务与变更项、点发布后经 CODING 引擎完成构建与测试环境部署，并在重复/乱序回调下保持状态正确。

**Architecture:** 单 Maven 模块的模块化单体（`web → application → domain`，`infra` 实现 domain 定义的端口，由 boot 装配）。状态机用静态迁移矩阵校验；流水线推进由事件驱动、以「实例锁 + 幂等键」保证同一实例只被推进一次；外部引擎通过 `CicdEngine` SPI 与**可配置的端点模板**接入，端点错误只改配置不改代码。

**Tech Stack:** Java 17（JDK 21 上以 `--release 17` 编译）· Spring Boot 3.2 · MyBatis-Plus 3.5 · Flyway · MySQL 8（本地实例，测试用独立 schema）· Redis 7 · Redisson · JUnit 5 · MockWebServer · ArchUnit

**Spec:** `docs/requirements/发版流水线-需求规格说明书.md` v1.4 ／ `docs/tech-design/发版流水线-技术方案-v1.0.md`

## Global Constraints

- Java 17；Spring Boot 3.2.x；MyBatis-Plus 3.5.x；Flyway 10.x；Redis 7；MySQL 8.0。
- **执行环境（已实测）**：JDK 21（编译参数 `--release 17`）、Maven 3.6.1、**本地 MySQL 8.0.16（127.0.0.1:3306，root/root）**、**无 Docker**、**无本地 Redis**、无 pnpm。
  因此：测试直接打本地 MySQL 的独立 schema `release_pipeline_test`，**禁止引入 Testcontainers / H2**；锁抽象为 `DistributedLock` 接口，测试注入内存实现，Redis 实现由 `release.redis.enabled=true` 条件装配（默认 false，避免启动失败）。
- **测试隔离**：每个测试自造数据（不依赖表内既有行、不与其它用例共享固定主键）；验证并发的用例必须真实提交事务，不得用 `@Transactional` 回滚；test profile 启动时执行 `Flyway.clean()` 再 `migrate()`，保证 schema 确定性。
- **DDL 面向 MySQL 8**：多列唯一键中的可空列一律 `NOT NULL DEFAULT 0`（MySQL 唯一键对 NULL 不去重）。
- 包根：`com.sf.release`。分层包名固定：`web` / `application` / `domain` / `infra` / `common`。
- 依赖方向硬约束：`domain` 不得依赖 `infra`、`web`、Spring Web；`web` 只能依赖 `application`；跨模块禁止直接调 Mapper（ArchUnit 强制）。
- 时间：应用内统一 `Instant`；DB 列 `DATETIME(3)` 存 **UTC**；API 收发光 ISO-8601 带偏移（`+08:00`）。
- 时区常量：`ZoneId.of("Asia/Shanghai")`，只出现在 `WindowEvaluator` 与展示层。
- 枚举持久化为大写字符串（`VARCHAR`），不得存序号。
- 表名 snake_case **（单数）**；Java 字段 camelCase；REST 路径 kebab-case 复数。
- 所有外部 HTTP 调用必须有连接超时 5s、读超时 30s；禁止无超时调用。
- 所有写接口接受 `Idempotency-Key` 头，Redis TTL 24h。
- 日志禁止输出 token / secret / 完整请求体（脱敏函数 `Masker.mask()`）。
- 每个任务结束时 `mvn -q verify` 必须全绿；一次提交只做本任务的事。

## Review Focus

以下五类问题最容易在生产里咬人，每个都在对应任务的测试里钉住：

1. **Webhook 重复投递**：CODING 可能重发同一事件。期望：同一 `event_id` 只推进一次，第二次静默忽略，不发通知。
2. **Webhook 乱序**（先收到 SUCCESS 再收到 RUNNING）：期望：状态**不回退**，RUNNING 事件被忽略并记 WARN。
3. **窗口跨零点**（`22:00–02:00`）：期望：`23:30` 与 `01:30` 都判定为窗口内，`03:00` 为窗口外。
4. **共库 commit 误判**：同仓库其它模块提交。期望：`module_path` 无变更时视为「未变」，不得作废测试通过。
5. **并发双击发布**：同一变更项 200ms 内两次点击。期望：只生成一个发布单、一个实例，第二次返回 40001 或 40006。

---

## 文件结构（本计划结束时）

```
version-pipeline/
├── server/
│   ├── pom.xml
│   └── src/
│       ├── main/
│       │   ├── java/com/sf/release/
│       │   │   ├── ReleaseApplication.java
│       │   │   ├── common/         # ApiResponse, BizError, BizException, Masker, Idempotency
│       │   │   ├── domain/
│       │   │   │   ├── resource/   # Subsystem, DeployUnit, EnvDe, CodingTaskBinding
│       │   │   │   ├── task/       # ReleaseTask, ReleaseItem, ItemStatus, ItemEvent, ItemStateMachine
│       │   │   │   ├── pipeline/   # ReleaseOrder, PipelineInstance, PipelineNode, NodeTask, PipelineOrchestrator
│       │   │   │   └── engine/     # CicdEngine, BuildCmd, DeployCmd, TriggerResult, EngineStatus
│       │   │   ├── application/    # SubsystemAppService, UnitAppService, TaskAppService, PublishAppService, ReleaseQueryService
│       │   │   ├── infra/
│       │   │   │   ├── repo/       # PO + Mapper
│       │   │   │   ├── engine/     # CodingEngine, EndpointTemplateResolver, CodingProperties
│       │   │   │   ├── lock/      # UnitLockService, PipelineLockService
│       │   │   │   ├── job/       # ReconcileJob, LockExpireJob
│       │   │   │   └── security/  # UserContext, RequirePerm, DataScope, PermissionInterceptor
│       │   │   └── web/           # controllers, dto, GlobalExceptionHandler, TraceIdFilter
│       │   └── resources/
│       │       ├── application.yml
│       │       ├── db/migration/V1__init.sql
│       │       └── coding-endpoints.yml   # 端点模板（文档可能不准，改这里）
│       └── test/java/com/sf/release/...
├── web/                            # 前端（P5 交付，本计划只留空壳 README）
└── docs/
```

## 共享类型（后续任务全部以此为准）

```java
// domain/task
enum ItemStatus { PENDING, BUILDING, TESTING, GATE_PENDING, SUBMITTED,
                  PENDING_APPROVAL, READY, PROD_DEPLOYING, DONE,
                  GATE_FAILED, FAILED, REJECTED, CLOSED, TERMINATED, ROLLED_BACK }
enum ItemEvent  { PUBLISH, BUILD_OK, BUILD_FAIL, TEST_DEPLOY_OK, TEST_DEPLOY_FAIL,
                  GATE_DECIDE, SUBMIT, APPROVE_ALL, REJECT, EXECUTE,
                  PROD_OK, PROD_FAIL, FREEZE_HIT, CHECK_INVALID, TEST_REVOKE,
                  RERUN, CLOSE, EXIT, ROLLBACK }
enum GateStatus { TEST_PENDING, TEST_PASS, TEST_FAIL, TEST_REVOKED,
                  CHECK_RUNNING, CHECK_PASS, CHECK_WAIVED, CHECK_FAIL, CHECK_INVALID }

// domain/pipeline
enum NodeType   { BUILD, TEST_DEPLOY, VERIFY, PROD_DEPLOY, DAILY_DEPLOY,
                  LIB_BUILD, LIB_SECURITY, LIB_PUBLISH }
enum NodeTaskType { CODE_CHECK, PKG_BUILD, IMG_BUILD, DEPLOY, CHECK, AUTO_TEST }
enum TaskStatus { PENDING, RUNNING, WAITING_EXTERNAL, SUCCESS, FAILED, TIMEOUT, SKIPPED }

// domain/engine
record TriggerResult(String externalId, String externalUrl) {}
record BuildCmd(long unitId, String planId, String branch, String commitSha,
                String pipelineNo, int attempt) {}
record DeployCmd(long unitId, String planId, long deId, String imageTag,
                 String imageDigest, String pipelineNo, int attempt) {}
enum EngineStatus { QUEUED, RUNNING, SUCCESS, FAILED, CANCELED, UNKNOWN }
record EngineResult(EngineStatus status, String imageDigest, String message) {}
```

---

## Phase 1 · 基座

### Task 1: 工程骨架与分层约束

**Files:**
- Create: `server/pom.xml`
- Create: `server/src/main/java/com/sf/release/ReleaseApplication.java`
- Create: `server/src/main/resources/application.yml`
- Create: `server/src/main/resources/db/migration/V1__init.sql`
- Test: `server/src/test/java/com/sf/release/ArchitectureTest.java`

**Interfaces:**
- Consumes: 无
- Produces: Spring Boot 启动类 `com.sf.release.ReleaseApplication`；Flyway 迁移基线；包结构约定。

- [ ] **Step 1: 写分层约束测试**

```java
@AnalyzeClasses(packages = "com.sf.release")
class ArchitectureTest {
    @ArchTest static final ArchRule domain_must_not_depend_on_infra =
        noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage("..infra..", "..web..");

    @ArchTest static final ArchRule domain_must_not_use_spring_web =
        noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.springframework.web..", "org.springframework.stereotype..");
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `cd server && mvn -q -Dtest=ArchitectureTest test`
Expected: FAIL（`ReleaseApplication` 与包均不存在 / 编译失败）

- [ ] **Step 3: 建工程与最小骨架**

`pom.xml` 关键依赖：`spring-boot-starter-web`、`spring-boot-starter-validation`、`mybatis-plus-spring-boot3-starter` 3.5.x、`flyway-core` + `flyway-mysql`、`mysql-connector-j`、`lombok`；测试依赖 `spring-boot-starter-test`、`archunit-junit5`、`mockwebserver`；`redisson` 依赖保留但只在 `release.redis.enabled=true` 时生效。`maven-compiler-plugin` 配置 `<release>17</release>`。**不引入 H2 / Testcontainers / flyway-database-h2**。

`application-test.yml` 指向 `jdbc:mysql://127.0.0.1:3306/release_pipeline_test?createDatabaseIfNotExist=true&useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true`，用户名/密码 `root/root`；并提供一个 test profile 的 `FlywayMigrationStrategy`（先 `clean()` 再 `migrate()`）。

`V1__init.sql` 建两张最基础的表（子系统 + 本地用户，认证方式待 SSO 确认前先用本地用户）：

```sql
CREATE TABLE app_user (
  id VARCHAR(32) PRIMARY KEY,          -- 工号
  name VARCHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME(3) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE subsystem (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(128) NOT NULL,
  owner_id VARCHAR(32) NOT NULL,
  agent_id VARCHAR(32) NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

`ReleaseApplication` 为标准 `@SpringBootApplication`，`application.yml` 配置 datasource、flyway、redis 占位。

- [ ] **Step 4: 运行，确认通过**

Run: `cd server && mvn -q -Dtest=ArchitectureTest test`
Expected: PASS（2 条规则通过）

- [ ] **Step 5: 提交**

```bash
git add server/pom.xml server/src
git commit -m "chore: scaffold server with layered architecture rules"
```

---

### Task 2: 统一响应、业务异常、traceId 与脱敏

**Files:**
- Create: `server/src/main/java/com/sf/release/common/ApiResponse.java`
- Create: `server/src/main/java/com/sf/release/common/BizError.java`
- Create: `server/src/main/java/com/sf/release/common/BizException.java`
- Create: `server/src/main/java/com/sf/release/common/Masker.java`
- Create: `server/src/main/java/com/sf/release/web/support/GlobalExceptionHandler.java`
- Create: `server/src/main/java/com/sf/release/web/support/TraceIdFilter.java`
- Test: `server/src/test/java/com/sf/release/web/support/ErrorHandlingTest.java`

**Interfaces:**
- Consumes: Task 1 骨架
- Produces: `ApiResponse.ok(T)`、`ApiResponse.fail(BizError, String, Object payload)`；`BizError` 枚举含 `PARAM_INVALID(40000)`、`STATE_NOT_ALLOWED(40001)`、`GATE_BLOCKED(40002)`、`OUT_OF_WINDOW(40003)`、`FREEZE_REQUIRES_DOUBLE_SIGN(40004)`、`OA_TICKET_INVALID(40005)`、`UNIT_LOCKED(40006)`、`DIGEST_MISMATCH(40007)`、`SELF_APPROVAL_FORBIDDEN(40008)`、`FORBIDDEN(40009)`、`DUPLICATE_NAME(40010)`、`UNIT_NOT_CONFIGURED(40011)`、`ENGINE_UNAVAILABLE(50001)`、`ENGINE_TIMEOUT(50002)`。

- [ ] **Step 1: 写测试**

```java
@WebMvcTest(controllers = DummyController.class)
class ErrorHandlingTest {
    @Autowired MockMvc mvc;

    @Test void state_not_allowed_returns_40001_with_payload() throws Exception {
        mvc.perform(get("/dummy/state")).andExpect(status().isOk())
           .andExpect(jsonPath("$.code").value(40001))
           .andExpect(jsonPath("$.data.currentStatus").value("DONE"))
           .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test void masker_hides_token_and_secret() {
        assertThat(Masker.mask("{\"token\":\"abc123\",\"name\":\"x\"}"))
            .contains("\"token\":\"***\"").contains("\"name\":\"x\"");
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd server && mvn -q -Dtest=ErrorHandlingTest test`
Expected: FAIL（`DummyController`/`Masker` 不存在）

- [ ] **Step 3: 实现**

- `ApiResponse<T>{int code; String message; T data; String traceId;}` + 静态工厂。
- `BizException extends RuntimeException {BizError error; Object payload;}`。
- `GlobalExceptionHandler`：`BizException → HTTP 200 + code`；`MethodArgumentNotValidException → 40000 + 字段明细`；其余 `50000`，日志带 traceId。
- `TraceIdFilter`：读 `X-Trace-Id`，无则生成 UUID，写 MDC 与响应头。
- `Masker`：正则替换 `token|secret|password|authorization` 的值为 `***`。

- [ ] **Step 4: 运行确认通过**

Run: `cd server && mvn -q -Dtest=ErrorHandlingTest test`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add server/src && git commit -m "feat: unified response, biz errors, trace id and masking"
```

---

### Task 3: 用户上下文、权限点与数据范围

**Files:**
- Create: `server/src/main/java/com/sf/release/infra/security/UserContext.java`
- Create: `server/src/main/java/com/sf/release/infra/security/RequirePerm.java`
- Create: `server/src/main/java/com/sf/release/infra/security/DataScope.java`
- Create: `server/src/main/java/com/sf/release/infra/security/PermissionInterceptor.java`
- Create: `server/src/main/java/com/sf/release/infra/security/ScopeGuard.java`
- Test: `server/src/test/java/com/sf/release/infra/security/PermissionInterceptorTest.java`

**Interfaces:**
- Consumes: Task 2 异常
- Produces: `UserContext.current()` → `CurrentUser(userId, roles, perms, scopeUnitIds)`；`@RequirePerm("release:publish")`；`ScopeGuard.assertUnitInScope(long unitId)`。

- [ ] **Step 1: 写测试**

```java
class PermissionInterceptorTest {
    @Test void missing_permission_returns_40009() { /* MockMvc: 无 release:publish 调用 → code=40009 */ }
    @Test void out_of_scope_unit_returns_40009() { /* 用户 scope 只有 unit 1，访问 unit 2 → 40009 */ }
    @Test void ops_cannot_create_code_release() { /* 角色 OPS 持有 release:publish 仍被拒 → 40009 */ }
}
```

- [ ] **Step 2: 运行确认失败**
Run: `cd server && mvn -q -Dtest=PermissionInterceptorTest test` → FAIL

- [ ] **Step 3: 实现**

- 一期认证先用 `X-User-Id` 头 + 用户表（SSO 待定，留 `AuthProvider` 接口）。
- `PermissionInterceptor` 读注解与 `UserContext`，缺权限抛 `FORBIDDEN`。
- 数据范围：`ScopeGuard.assertUnitInScope`；同时提供 MyBatis 拦截器注入 `unit_id IN (...)`。
- **硬规则**：`OPS` / `OPS_LEAD` 角色调用 `release:publish` 时直接拒绝（C20）。

- [ ] **Step 4: 运行确认通过**
Run: `cd server && mvn -q -Dtest=PermissionInterceptorTest test` → PASS

- [ ] **Step 5: 提交**
```bash
git add server/src && git commit -m "feat: permission points and data scope guard"
```

---

## Phase 2 · 资源

### Task 4: 子系统管理

**Files:**
- Create: `domain/resource/Subsystem.java`
- Create: `infra/repo/po/SubsystemPO.java`, `infra/repo/SubsystemMapper.java`
- Create: `application/SubsystemAppService.java`
- Create: `web/controller/SubsystemController.java`, `web/dto/SubsystemDtos.java`
- Modify: `db/migration/V1__init.sql` → 新增 `server/src/main/resources/db/migration/V2__subsystem_extra.sql`
- Test: `server/src/test/java/com/sf/release/resource/SubsystemServiceTest.java`

**Interfaces:**
- Consumes: Task 3 权限
- Produces: `SubsystemAppService.create(CreateCmd): long`、`update(...)`、`get(long): SubsystemView`、`page(...)`。

- [ ] **Step 1: 写测试**

```java
@Test void create_trims_and_rejects_duplicate_name() {
    long id = service.create(new CreateCmd(" SCC-PORTAL-CORE-OS ", "01388007", "01399596"));
    assertThat(service.get(id).name()).isEqualTo("SCC-PORTAL-CORE-OS");
    assertThatThrownBy(() -> service.create(new CreateCmd("SCC-PORTAL-CORE-OS", "1", null)))
        .isInstanceOf(BizException.class)
        .extracting("error").isEqualTo(BizError.DUPLICATE_NAME);
}

@Test void owner_and_agent_must_exist() { /* 不存在的工号 → 40000 */ }
```

- [ ] **Step 2: 运行确认失败** → FAIL
- [ ] **Step 3: 实现**（PO/Mapper/Service/Controller，`V2` 增加 `description`、`git_url`、`coding_project` 等列）
- [ ] **Step 4: 运行确认通过** → PASS
- [ ] **Step 5: 提交** `feat: subsystem management`

---

### Task 5: 部署单元与环境 DE

**Files:**
- Create: `domain/resource/DeployUnit.java`, `domain/resource/EnvDe.java`, `domain/resource/CodingTaskBinding.java`
- Create: `infra/repo/po/DeployUnitPO.java`, `EnvDePO.java`, `CodingTaskBindingPO.java` + 对应 Mapper
- Create: `application/UnitAppService.java`
- Create: `web/controller/UnitController.java`
- Create: `db/migration/V3__unit.sql`
- Test: `server/src/test/java/com/sf/release/resource/UnitServiceTest.java`

**Interfaces:**
- Consumes: Task 4
- Produces: `UnitAppService.create/update`、`bindCodingTasks(long unitId, List<BindingCmd>)`、`assertPublishable(long unitId)`（三项必填齐全）。

- [ ] **Step 1: 写测试**

```java
@Test void publishable_requires_build_and_two_deploy_tasks() {
    long unitId = unitService.create(cmd("quota-api"));
    unitService.bindCodingTasks(unitId, List.of(build("P-BUILD"), testDeploy("P-TEST")));
    assertThatThrownBy(() -> unitService.assertPublishable(unitId))
        .isInstanceOf(BizException.class)
        .extracting("error").isEqualTo(BizError.UNIT_NOT_CONFIGURED);

    unitService.bindCodingTasks(unitId, List.of(prodDeploy("P-PROD")));
    assertThatCode(() -> unitService.assertPublishable(unitId)).doesNotThrowAnyException();
}

@Test void daily_deploy_is_optional_and_defaults_to_test_deploy() {
    // 未绑 DAILY_DEPLOY 时，resolveDailyDeployPlan(unitId) 返回 test 的 planId
}
```

- [ ] **Step 2: 运行确认失败** → FAIL
- [ ] **Step 3: 实现**：`CodingTaskBinding` 唯一键 `(unit_id, task_kind)`；`task_kind ∈ BUILD/TEST_DEPLOY/PROD_DEPLOY/DAILY_DEPLOY`；DE 表含 `env_kind(TEST/DAILY/PROD/DR/BLUE)`、`deploy_batch_no`。
- [ ] **Step 4: 运行确认通过** → PASS
- [ ] **Step 5: 提交** `feat: deploy unit, env de and coding task binding`

---

### Task 6: CODING 连接配置与凭证加密

**Files:**
- Create: `infra/engine/CodingProperties.java`
- Create: `domain/resource/CodingConn.java`
- Create: `infra/repo/po/CodingConnPO.java` + Mapper
- Create: `infra/crypto/CredentialCipher.java`
- Create: `application/CodingConnAppService.java`
- Create: `db/migration/V4__coding_conn.sql`
- Test: `server/src/test/java/com/sf/release/engine/CredentialCipherTest.java`, `CodingConnServiceTest.java`

**Interfaces:**
- Consumes: Task 5
- Produces: `CodingConnAppService.save(scope, subsystemId, baseUrl, team, project, token)`；`CredentialCipher.encrypt(String): String` / `decrypt(String): String`；`@Component CodingProperties`（端点模板，见 Task 10）。

- [ ] **Step 1: 写测试**

```java
@Test void encrypt_decrypt_roundtrip_and_ciphertext_differs() {
    var a = cipher.encrypt("tok-123"); var b = cipher.encrypt("tok-123");
    assertThat(a).isNotEqualTo(b);                 // 随机 IV
    assertThat(cipher.decrypt(a)).isEqualTo("tok-123");
}

@Test void token_never_appears_in_db_or_logs() {
    connService.save(GLOBAL, null, "https://x", "team", "proj", "SUPER-SECRET");
    assertThat(jdbc.queryForObject("select credential_cipher from coding_conn", String.class))
        .doesNotContain("SUPER-SECRET");
    assertThat(logCapture.output()).doesNotContain("SUPER-SECRET");
}
```

- [ ] **Step 2: 运行确认失败** → FAIL
- [ ] **Step 3: 实现**：AES-GCM（12B IV，128bit tag），密钥来自 `CODING_CRED_KEY` 环境变量（Base64 32 字节）；启动时缺失则 fail-fast。
- [ ] **Step 4: 运行确认通过** → PASS
- [ ] **Step 5: 提交** `feat: coding connection with encrypted credential`

---

## Phase 3 · 任务与发布

### Task 7: 发布任务与成员

**Files:**
- Create: `domain/task/ReleaseTask.java`, `domain/task/TaskMember.java`
- Create: `infra/repo/po/ReleaseTaskPO.java`, `ReleaseTaskMemberPO.java` + Mapper
- Create: `application/TaskAppService.java`
- Create: `web/controller/TaskController.java`
- Create: `db/migration/V5__task.sql`
- Test: `server/src/test/java/com/sf/release/task/TaskServiceTest.java`

**Interfaces:**
- Consumes: Task 4
- Produces: `TaskAppService.create(CreateTaskCmd): long`、`addMembers(long taskId, List<MemberCmd>)`、`progress(long taskId): ProgressView{done,total,rate,hasBlocking}`。

- [ ] **Step 1: 写测试**

```java
@Test void progress_is_done_over_total_and_zero_when_empty() {
    long taskId = svc.create(new CreateTaskCmd("202609-V6.10.12", subsystemId, "01388007",
                                   LocalDate.of(2026,10,12), "新功能"));
    assertThat(svc.progress(taskId).rate()).isZero();

    svc.addMembers(taskId, List.of(new MemberCmd("01388007", "DEV")));
    assertThatThrownBy(() -> svc.addMembers(taskId, List.of(new MemberCmd("01388007", "PRODUCT"))))
        .isInstanceOf(BizException.class); // 同人不同角色 → 幂等覆盖，不报错（见实现）
}

@Test void product_role_is_supported_for_freeze_notification() {
    svc.addMembers(taskId, List.of(new MemberCmd("01380484", "PRODUCT")));
    assertThat(svc.productsOf(taskId)).containsExactly("01380484");
}
```

- [ ] **Step 2: 运行确认失败** → FAIL
- [ ] **Step 3: 实现**：角色枚举 `DEV/TEST/OPS/PRODUCT/OWNER`；成员唯一键 `(task_id, user_id)`，重复添加为覆盖角色。
- [ ] **Step 4: 运行确认通过** → PASS
- [ ] **Step 5: 提交** `feat: release task and members`

---

### Task 8: 变更项与状态机

**Files:**
- Create: `domain/task/ReleaseItem.java`, `ItemStatus.java`, `ItemEvent.java`, `ItemStateMachine.java`, `IllegalTransitionException.java`
- Create: `infra/repo/po/ReleaseItemPO.java` + Mapper
- Create: `application/ItemAppService.java`
- Create: `db/migration/V6__release_item.sql`
- Test: `server/src/test/java/com/sf/release/task/ItemStateMachineTest.java`

**Interfaces:**
- Consumes: Task 7
- Produces: `ItemStateMachine.transit(ReleaseItem item, ItemEvent event): ItemStatus`（非法抛 `IllegalTransitionException`）；`ItemAppService.create(long taskId, CreateItemCmd): long`。

- [ ] **Step 1: 写测试（矩阵全枚举 + Review Focus 第 4 类的前置）**

```java
@ParameterizedTest
@EnumSource(ItemStatus.class)
void every_status_has_defined_transitions(ItemStatus from) {
    assertThat(ItemStateMachine.allowedEvents(from)).isNotNull(); // 不允许 null，终态返回空集合
}

@Test void pending_to_publish_to_testing() {
    assertThat(transit(PENDING, PUBLISH)).isEqualTo(BUILDING);
    assertThat(transit(BUILDING, BUILD_OK)).isEqualTo(TESTING);
}

@Test void invalid_transition_throws_with_current_status_payload() {
    BizException e = assertThrows(BizException.class, () -> transit(DONE, PUBLISH));
    assertThat(e.getError()).isEqualTo(BizError.STATE_NOT_ALLOWED);
    assertThat(e.getPayload()).asInstanceOf(InstanceOfAssertFactories.MAP)
        .containsEntry("currentStatus", "DONE");
}

@Test void test_revoke_moves_testing_back_to_gate_failed() {
    assertThat(transit(TESTING, TEST_REVOKE)).isEqualTo(GATE_FAILED);
}

@Test void terminal_states_have_no_outgoing_events() {
    assertThat(ItemStateMachine.allowedEvents(CLOSED)).isEmpty();
    assertThat(ItemStateMachine.allowedEvents(TERMINATED)).isEmpty();
}
```

- [ ] **Step 2: 运行确认失败** → FAIL
- [ ] **Step 3: 实现迁移矩阵**（严格按技术方案 3.2 表的每一行；`RERUN` 目标由 `RerunPlanner` 决定，状态机只允许 `GATE_FAILED/FAILED → TESTING`）
- [ ] **Step 4: 运行确认通过** → PASS
- [ ] **Step 5: 提交** `feat: release item state machine`

---

### Task 9: 发布单、实例预生成与单元锁

**Files:**
- Create: `domain/pipeline/ReleaseOrder.java`, `PipelineInstance.java`, `PipelineNode.java`, `NodeTask.java`
- Create: `domain/pipeline/PipelineFactory.java`
- Create: `domain/pipeline/port/DistributedLock.java`
- Create: `infra/lock/InMemoryDistributedLock.java`（默认，测试与本地）
- Create: `infra/lock/RedisDistributedLock.java`（`@ConditionalOnProperty(release.redis.enabled=true)`）
- Create: `infra/lock/UnitLockService.java`, `infra/lock/PipelineLockService.java`
- Create: `infra/repo/po/ReleaseOrderPO.java` 等 + Mapper + `DeployUnitLockMapper`（含 `forceExpire(unitId)`）
- Create: `application/PublishAppService.java`
- Create: `db/migration/V7__pipeline.sql`
- Test: `server/src/test/java/com/sf/release/pipeline/PublishServiceTest.java`

**Interfaces:**
- Consumes: Task 5、Task 8
- Produces:
```java
public interface DistributedLock {
    boolean tryLock(String key, java.time.Duration ttl);
    void unlock(String key);
}
```
`PublishAppService.publish(long itemId): PublishResult{publishNo, pipelineNo}`；`PipelineFactory.createOnline(ReleaseOrder, ReleaseItem, List<EnvDe>): PipelineInstance`；`UnitLockService.acquire(long unitId, long instanceId): boolean` / `release(long unitId)`。

- [ ] **Step 1: 写测试（Review Focus 第 5 类）**

```java
@Test void concurrent_double_click_creates_one_instance() throws Exception {
    var pool = Executors.newFixedThreadPool(2);
    CountDownLatch go = new CountDownLatch(1);
    List<Future<?>> fs = List.of(
        pool.submit(() -> { go.await(); return publishService.publish(itemId); }),
        pool.submit(() -> { go.await(); return publishService.publish(itemId); }));
    go.countDown();
    long success = fs.stream().filter(f -> ok(f)).count();
    assertThat(success).isEqualTo(1);
    assertThat(jdbc.count("select count(*) from pipeline_instance")).isEqualTo(1);
}

@Test void instance_has_four_nodes_and_expected_tasks() {
    var inst = publishService.publish(itemId);
    var nodes = pipelineRepo.nodesOf(inst.pipelineNo());
    assertThat(nodes).extracting(PipelineNode::getNodeType)
        .containsExactly(BUILD, TEST_DEPLOY, VERIFY, PROD_DEPLOY);
}

@Test void locked_unit_rejects_second_publish_with_40006() {
    locks.acquire(unitId, 999L);
    assertThatThrownBy(() -> publishService.publish(itemId))
        .isInstanceOf(BizException.class).extracting("error").isEqualTo(BizError.UNIT_LOCKED);
}

@Test void expired_lock_can_be_reacquired() {
    lockRepo.forceExpire(unitId);
    assertThat(locks.acquire(unitId, 1000L)).isTrue();
}
```

- [ ] **Step 2: 运行确认失败** → FAIL
- [ ] **Step 3: 实现**：`publish` 顺序为 `ScopeGuard → assertPublishable → acquire lock → 事务内创建 order/instance/nodes/tasks`；Redis 锁 `lock:unit:{id}` TTL 4h + `deploy_unit_lock` 兜底；`PipelineLockService` 为实例级短锁（30s）。
- [ ] **Step 4: 运行确认通过** → PASS
- [ ] **Step 5: 提交** `feat: publish order, pipeline instance and unit lock`

---

### Task 10: 引擎 SPI 与端点模板适配器

**Files:**
- Create: `domain/engine/CicdEngine.java`, `BuildCmd.java`, `DeployCmd.java`, `TriggerResult.java`, `EngineStatus.java`, `EngineResult.java`, `EngineException.java`
- Create: `infra/engine/CodingEngine.java`
- Create: `infra/engine/EndpointTemplateResolver.java`
- Create: `server/src/main/resources/coding-endpoints.yml`
- Create: `infra/engine/EngineRegistry.java`
- Test: `server/src/test/java/com/sf/release/engine/CodingEngineTest.java`

**Interfaces:**
- Consumes: Task 6 凭证
- Produces:
```java
public interface CicdEngine {
    String name();                                   // "CODING"
    TriggerResult triggerBuild(BuildCmd cmd);
    TriggerResult triggerDeploy(DeployCmd cmd);
    EngineResult query(String externalId);
    String logs(String externalId, int offset, int limit);
    void cancel(String externalId);
}
```
- `EndpointTemplateResolver.resolve(String action, Map<String,String> vars): ResolvedCall{method, url, headers, body}`

- [ ] **Step 1: 写测试（用 MockWebServer，端点来自模板而非硬编码）**

```java
@Test void trigger_build_uses_template_and_maps_external_id() throws Exception {
    server.enqueue(new MockResponse().setBody("{\"Response\":{\"Data\":{\"JobId\":\"j-123\"}}}"));
    TriggerResult r = engine.triggerBuild(new BuildCmd(unitId,"P-BUILD","feature/x","sha1","PL-1",1));
    assertThat(r.externalId()).isEqualTo("j-123");
    var req = server.takeRequest();
    assertThat(req.getPath()).isEqualTo("/open-api/build/start");
    assertThat(req.getBody().readUtf8()).contains("\"CommitSha\":\"sha1\"");
}

@Test void digest_is_extracted_from_response_jsonpath() {
    server.enqueue(new MockResponse().setBody("{\"Data\":{\"Image\":\"repo/x\",\"Digest\":\"sha256:ab\"}}"));
    assertThat(engine.query("j-1").imageDigest()).isEqualTo("sha256:ab");
}

@Test void engine_5xx_is_retried_three_times_then_fails() { /* enqueue 3×500 → EngineException(ENGINE_UNAVAILABLE) */ }

@Test void engine_4xx_is_not_retried() { /* enqueue 400 → 立即 EngineException，请求次数=1 */ }

@Test void timeout_maps_to_engine_timeout() { /* setBodyDelay(35s) → ENGINE_TIMEOUT */ }

@Test void template_change_in_config_alters_url_without_code_change() {
    resolver.override("build.start.url", "/v2/build");
    engine.triggerBuild(...);
    assertThat(server.takeRequest().getPath()).isEqualTo("/v2/build");
}
```

- [ ] **Step 2: 运行确认失败** → FAIL
- [ ] **Step 3: 实现**：`coding-endpoints.yml` 结构如下（**文档不准时只改这个文件**）：

```yaml
coding:
  endpoints:
    build.start:   { method: POST, url: "/open-api/build/start",   responseId: "$.Response.Data.JobId" }
    build.query:   { method: GET,  url: "/open-api/build/{externalId}", responseStatus: "$.Data.Status", responseDigest: "$.Data.Digest" }
    build.logs:    { method: GET,  url: "/open-api/build/{externalId}/logs" }
    deploy.start:  { method: POST, url: "/open-api/deploy/start",  responseId: "$.Data.DeployId" }
    deploy.query:  { method: GET,  url: "/open-api/deploy/{externalId}", responseStatus: "$.Data.Status" }
    cancel:        { method: POST, url: "/open-api/job/{externalId}/cancel" }
  statusMapping:
    "#{QUEUED}": [QUEUEING, PENDING]
    "#{RUNNING}": [RUNNING, DEPLOYING]
    "#{SUCCESS}": [SUCCESS, FINISHED]
    "#{FAILED}":  [FAILED, ERROR]
```

- [ ] **Step 4: 运行确认通过** → PASS
- [ ] **Step 5: 提交** `feat: cicd engine spi and templated coding adapter`

---

### Task 11: 流水线推进器

**Files:**
- Create: `domain/pipeline/PipelineOrchestrator.java`
- Create: `domain/pipeline/NodeTaskCompletionService.java`
- Create: `domain/pipeline/event/NodeTaskCompletedEvent.java`, `InstanceFinishedEvent.java`
- Create: `application/event/PipelineEventListener.java`
- Test: `server/src/test/java/com/sf/release/pipeline/OrchestratorTest.java`

**Interfaces:**
- Consumes: Task 9、Task 10
- Produces: `PipelineOrchestrator.onTaskCompleted(long taskId, TaskStatus status, String digest, String message)`；`PipelineOrchestrator.advance(long instanceId)`。

- [ ] **Step 1: 写测试（Review Focus 第 1、2 类）**

```java
@Test void duplicate_completion_event_advances_only_once() throws Exception {
    // 10 线程同时回调同一 node_task SUCCESS
    // 断言：该节点只完成一次（finished_at 唯一），下一节点只启动一次
}

@Test void out_of_order_success_then_running_does_not_regress() {
    orchestrator.onTaskCompleted(t1, SUCCESS, "sha256:ab", null);
    orchestrator.onTaskCompleted(t1, RUNNING, null, null);   // 迟到事件
    assertThat(repo.task(t1).getStatus()).isEqualTo(SUCCESS);
}

@Test void node_completes_only_when_all_tasks_succeed() {
    // 3 个任务，2 成功 1 RUNNING → 节点仍 RUNNING，不推进
}

@Test void failure_skips_pending_tasks_in_same_node() {
    orchestrator.onTaskCompleted(t1, FAILED, null, "boom");
    assertThat(repo.tasksOf(node).filter(PENDING)).allMatch(SKIPPED);
    assertThat(repo.instance(inst).getStatus()).isEqualTo(FAILED);
}
```

- [ ] **Step 2: 运行确认失败** → FAIL
- [ ] **Step 3: 实现**：`advance` 先取 `lock:pipe:{instanceId}`（拿不到直接返回），再事务内做「完成判定 → 推进 → 发事件」；状态前置校验用 CAS（`update ... where status in (...)`）。
- [ ] **Step 4: 运行确认通过** → PASS
- [ ] **Step 5: 提交** `feat: pipeline orchestrator with idempotent advance`

---

### Task 12: 构建节点

**Files:**
- Create: `domain/pipeline/handler/BuildNodeHandler.java`
- Modify: `domain/pipeline/PipelineOrchestrator.java`
- Test: `server/src/test/java/com/sf/release/pipeline/BuildNodeHandlerTest.java`

**Interfaces:**
- Consumes: Task 11
- Produces: `BuildNodeHandler.start(PipelineInstance)`；成功后写 `instance.image_digest`、`instance.commit_sha`。

- [ ] **Step 1: 写测试**

```java
@Test void build_chain_runs_code_check_then_pkg_then_image() {
    handler.start(inst);
    assertThat(engine.calls()).containsExactly("triggerBuild:CODE_CHECK","triggerBuild:PKG_BUILD","triggerBuild:IMG_BUILD");
}

@Test void image_build_failure_fails_instance_and_keeps_partial_results() {
    engine.failNext("IMG_BUILD","disk full");
    handler.start(inst);
    assertThat(inst.status()).isEqualTo(FAILED);
    assertThat(repo.task(inst, IMG_BUILD).getResultSummary()).contains("disk full");
}

@Test void digest_is_persisted_for_production_consistency_check() {
    engine.successWithDigest("sha256:cd");
    handler.start(inst);
    assertThat(inst.imageDigest()).isEqualTo("sha256:cd");
}
```

- [ ] **Step 2: 运行确认失败** → FAIL
- [ ] **Step 3: 实现**：三任务串行；每个任务 `WAITING_EXTERNAL`，回调后推进；digest 只在 IMG_BUILD 成功时落库。
- [ ] **Step 4: 运行确认通过** → PASS
- [ ] **Step 5: 提交** `feat: build node chain`

---

### Task 13: 测试部署节点

**Files:**
- Create: `domain/pipeline/handler/TestDeployNodeHandler.java`
- Create: `domain/pipeline/event/TestEnvReadyEvent.java`
- Modify: `PipelineOrchestrator`
- Test: `server/src/test/java/com/sf/release/pipeline/TestDeployNodeHandlerTest.java`

**Interfaces:**
- Consumes: Task 12
- Produces: `TestDeployNodeHandler.start(PipelineInstance)`；发布 `TestEnvReadyEvent(publishNo, readyAt)`。

- [ ] **Step 1: 写测试**

```java
@Test void deploys_to_all_test_des_in_parallel() {
    // 2 个 TEST DE → 两个 DEPLOY 任务同为 RUNNING
}

@Test void one_de_failure_fails_instance_but_keeps_successful_deploy_record() {
    // de1 成功、de2 失败 → 实例 FAILED，de1 记录仍为 SUCCESS
}

@Test void ready_event_published_only_when_all_test_de_succeed() {
    // 全成功 → 事件 1 次；部分成功 → 0 次
}
```

- [ ] **Step 2: 运行确认失败** → FAIL
- [ ] **Step 3: 实现**：按 `env_de.env_kind='TEST'` 取 DE；调 `triggerDeploy`；全成功发事件。
- [ ] **Step 4: 运行确认通过** → PASS
- [ ] **Step 5: 提交** `feat: test deploy node with parallel des`

---

### Task 14: Webhook 接收与对账

**Files:**
- Create: `web/controller/CodingWebhookController.java`
- Create: `infra/engine/WebhookVerifier.java`
- Create: `infra/repo/po/EngineEventDedupPO.java` + Mapper
- Create: `infra/job/ReconcileJob.java`
- Create: `db/migration/V8__dedup.sql`
- Test: `server/src/test/java/com/sf/release/web/CodingWebhookTest.java`, `server/src/test/java/com/sf/release/job/ReconcileJobTest.java`

**Interfaces:**
- Consumes: Task 11
- Produces: `POST /api/v1/hooks/coding`；`ReconcileJob.run()`（30s）。

- [ ] **Step 1: 写测试**

```java
@Test void duplicated_event_is_ignored_silently() {
    post(webhook("evt-1","SUCCESS")); post(webhook("evt-1","SUCCESS"));
    assertThat(jdbc.count("select count(*) from engine_event_dedup where event_id='evt-1'")).isEqualTo(1);
    assertThat(orchestrator.calls()).isEqualTo(1);
}

@Test void invalid_signature_is_rejected_with_401() { /* 篡改 body → 401，不入库 */ }

@Test void reconcile_fetches_stale_waiting_tasks() {
    // 把 WAITING_EXTERNAL 任务 updated_at 置为 5 分钟前 → run() 后调用了一次 query
}

@Test void reconcile_marks_timeout_after_three_consecutive_failures() {}
```

- [ ] **Step 2: 运行确认失败** → FAIL
- [ ] **Step 3: 实现**：HMAC-SHA256 验签（密钥配置）；`event_id` 唯一键；对账扫 `WAITING_EXTERNAL` 且 `updated_at < now-90s`。
- [ ] **Step 4: 运行确认通过** → PASS
- [ ] **Step 5: 提交** `feat: coding webhook with dedup and reconcile job`

---

### Task 15: 重新发布判定

**Files:**
- Create: `domain/pipeline/RerunPlanner.java`
- Create: `domain/pipeline/RerunPlan.java`
- Create: `domain/pipeline/port/GitCommitProbe.java`（端口，infra 实现）
- Create: `infra/git/CodingCommitProbe.java`
- Create: `application/RerunAppService.java`
- Create: `web/controller/RerunController.java`
- Test: `server/src/test/java/com/sf/release/pipeline/RerunPlannerTest.java`

**Interfaces:**
- Consumes: Task 9、Task 12
- Produces:
```java
record RerunPlan(boolean full, NodeType fromNode, boolean revokeTestPass, boolean degraded) {}
public interface GitCommitProbe {
    String headSha(long unitId, String branch);
    boolean hasChangesUnder(long unitId, String branch, String modulePath, String baseSha);
}
```
`RerunPlanner.decide(ReleaseItem item, String headSha): RerunPlan`

- [ ] **Step 1: 写测试（Review Focus 第 4 类）**

```java
@Test void same_commit_resumes_from_failed_node_and_keeps_test_pass() {
    var plan = planner.decide(itemWith(baseSha, lastBuildSha=baseSha), baseSha);
    assertThat(plan.full()).isFalse();
    assertThat(plan.fromNode()).isEqualTo(PROD_DEPLOY);
    assertThat(plan.revokeTestPass()).isFalse();
}

@Test void changed_commit_triggers_full_rerun_and_revokes_test_pass() {
    var plan = planner.decide(itemWith(baseSha), otherSha);
    assertThat(plan.full()).isTrue();
    assertThat(plan.revokeTestPass()).isTrue();
}

@Test void gongku_other_module_commit_does_not_count_as_change() {
    // module_path = "outbound-os"；git 变更只在 "api-os/" → 视为未变
    var plan = planner.decide(itemWith(baseSha, modulePath("outbound-os")), baseSha);
    assertThat(plan.full()).isFalse();
}

@Test void missing_module_path_degrades_with_warning_flag() {
    var plan = planner.decide(itemWith(baseSha, null), otherSha);
    assertThat(plan.degraded()).isTrue();
}
```

- [ ] **Step 2: 运行确认失败** → FAIL
- [ ] **Step 3: 实现**：commit 比对通过 `GitCommitProbe` 端口（一期实现可用 CODING 接口，测试用桩）。
- [ ] **Step 4: 运行确认通过** → PASS
- [ ] **Step 5: 提交** `feat: rerun planning by commit and module path`

---

### Task 16: 发布内容查询接口

**Files:**
- Create: `application/ReleaseQueryService.java`
- Create: `web/controller/ReleaseController.java`
- Create: `web/dto/ReleaseDtos.java`
- Test: `server/src/test/java/com/sf/release/web/ReleaseQueryTest.java`

**Interfaces:**
- Consumes: Task 9–15
- Produces: `GET /api/v1/releases/{publishNo}` → `{publishNo, pipelineNo, status, gateStatus, commitSha, imageDigest, nodes[], order{oaTicketNo, plannedReleaseTime}}`。

- [ ] **Step 1: 写测试**

```java
@Test void release_detail_aggregates_nodes_in_order() throws Exception {
    mvc.perform(get("/api/v1/releases/PL-1"))
       .andExpect(jsonPath("$.data.nodes[0].nodeType").value("BUILD"))
       .andExpect(jsonPath("$.data.nodes[3].nodeType").value("PROD_DEPLOY"))
       .andExpect(jsonPath("$.data.imageDigest").value("sha256:cd"));
}

@Test void query_uses_at_most_three_sql_statements() {
    // 用 p6spy/拦截器计数，断言 <=3，防 N+1
}
```

- [ ] **Step 2: 运行确认失败** → FAIL
- [ ] **Step 3: 实现**：一次 join 取节点与任务，内存组装；门禁字段先返回 `null`（P2 接入）。
- [ ] **Step 4: 运行确认通过** → PASS
- [ ] **Step 5: 提交** `feat: release detail query api`

---

## 完成定义（P1 验收）

- [ ] `mvn verify` 全绿，ArchUnit 分层规则通过。
- [ ] 能通过 API 完成：建子系统 → 建单元 + 绑定 3 个 CODING 任务 → 建任务 → 加变更项 → 发布 → 引擎（Mock）完成构建与测试部署 → 查询到 4 节点与 digest。
- [ ] 以下用例有自动化覆盖：重复 webhook、乱序 webhook、跨零点窗口（留待 P3，此处仅骨架）、共库 commit 误判、并发双击发布。
- [ ] `coding-endpoints.yml` 可独立修改并生效（有测试证明）。

## 自检记录

| 检查项 | 结果 |
|---|---|
| 规格覆盖 | P1 覆盖需求 5.1–5.4（资源/任务/流水线/引擎）与 4.5 重跑；门禁、管控、回滚、日常、通知在 P2–P5 |
| 步骤质量 | 每个任务 5 步，均为「写测试→跑红→实现→跑绿→提交」 |
| 类型一致性 | 共享类型集中在文首，后续任务仅引用签名 |
| 评审焦点 | 5 类风险各有归属任务：重复/乱序→T11、T14；跨零点→P3；共库→T15；并发双击→T9 |
| 篇幅比 | 计划约 1.1 倍于 P1 范围内的规格篇幅，未堆砌实现代码 |

---

## 后续计划（不与本计划同时开工）

| 计划 | 范围 | 关键任务（待展开为 TDD 步骤） |
|---|---|---|
| **P2 门禁与安全** | 检查采集、等级判定、豁免、测试通过/撤回 | 检查结果采集与幂等 · 等级判定函数 · 豁免签发与失效 · 测试通过/撤回 · 门禁账接口 |
| **P3 生产管控** | 窗口、封板、OA、审批、快照、批次部署 | 窗口规则与跨零点判定 · 封板生效与在途处理 · OA 登记 · 审批链与自审批拦截 · 准入快照 · 执行前复核 · 批次串行部署 |
| **P4 回滚 / 日常 / 通知** | 镜像回滚、日常发布、企微、待办、终止级联 | 版本归档与回滚 · 日常两节点 · 企微机器人寻址 · 锁过期回收 · 退出/关闭级联 |
| **P5 前端** | 全部页面 | 基座与路由 · 任务/详情 · 发布内容（步骤条+门禁账+批次看板） · 管控三页 · 回滚 · 设置 |

每份计划在开工前单独评审；P1 未合入主分支前不启动 P2。

---

## 执行交接

计划已保存。P1 共 16 个任务，任务之间**接口耦合较强**（状态机、引擎 SPI、推进器的签名被后续任务反复引用），一次误用会连锁返工，因此建议：

- **Subagent-driven**：每个任务由独立 subagent 实现、独立 reviewer 校验，接口漂移在任务边界就被挡住。适合本计划。
- **Native**：我在本会话内逐任务实现，最后一个 reviewer 整体检查。更快，但接口漂移要到整体评审才暴露。

**推荐 Subagent-driven**，因为 16 个任务里有 6 个直接依赖前序任务的精确签名，且状态机与推进器一旦写歪，P2–P4 全部要返工。