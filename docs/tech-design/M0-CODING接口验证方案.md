# M0 · CODING 接口验证方案（1 周 PoC）

| 项 | 内容 |
|---|---|
| 目的 | 用**证据**确认 CODING 侧能否支撑一期核心链路，产出可签字的能力矩阵 |
| 时间盒 | 5 个工作日 |
| 产出 | ① 能力矩阵（12 项）② 请求/响应留档 ③ `CicdEngine` 签名调整建议 ④ Go / No-Go 结论 |
| 依据 | 《技术方案 v1.0》第 6 章「能力契约」 |

---

## 1. 判定标准：三条红线

只要有一条不满足，技术方案第 5、8 章就要改型，因此必须在写代码前验证。

| 红线 | 能力 | 不满足的后果 |
|---|---|---|
| **R1** | 能用 API **触发构建**并拿到可查询的 `externalId` | 无法自动化，只能退回人工点构建 |
| **R2** | 能用 API **触发部署**并**查询部署最终状态** | 生产部署无法闭环，状态机退化为人工确认 |
| **R3** | 能获得**实际部署的镜像 digest**（或可经 registry/容器平台等价获得） | 「生产必须与测试同镜像」无法强制校验（J27 失效） |

**Go 条件**：R1–R3 全部满足 → 按 v1.0 方案实施。
**Conditional Go**：R3 需绕道 registry/容器平台 → 方案保留，第 8.4 节增加 digest 反查模块。
**No-Go**：R1 或 R2 不满足 → 引擎层改为「人工确认推进」，重估 M2/M4 工期。

---

## 2. 前置准备（Day 0，半天）

| 项 | 说明 | 谁提供 |
|---|---|---|
| CODING 站点地址 | 公有云 `*.coding.net` 还是私有化域名；是否只在内网 | 运维 |
| 团队 / 项目标识 | team、project id 或 slug | 项目管理员 |
| 凭证 | **Personal Access Token**（记录 scope：项目读、构建触发、部署触发）；若支持 OAuth，记录 client 模式 | 项目管理员 |
| 测试用构建任务 | 建议挑**构建耗时最短**的一个（Maven 示例 < 3 min），避免验证时等待 | 研发 |
| 测试用部署任务 | 指向**测试环境**、可接受被重启的服务 | 运维 |
| Webhook 配置权限 | 能改项目/仓库 webhook | 项目管理员 |
| 网络 | 验证机器能访问 CODING API 域名（私有化注意白名单） | 运维 |

> 所有验证在**独立测试项目**上做，不要拿生产项目试点。

---

## 3. 三条发现路径（同时走）

### A. 官方文档 / 控制台 API 页
最快。看是否提供 OpenAPI 列表、鉴权说明、示例。**记录版本与访问日期**，文档可能滞后。

### B. 抓包（最可靠，包治百病）

1. 浏览器打开 CODING 项目页 → F12 → Network，勾选 `Preserve log`、过滤 `Fetch/XHR`；
2. 在页面上**真实点一次**「构建」→ 等它跑完 → 再点一次「部署」；
3. 逐个看请求：URL 路径、Method、`Authorization` 头、请求体、响应体；
4. 对每个请求右键 **Copy as cURL**，存到 `poc/evidence/<能力>/request.curl`；
5. 响应体存 `response.json`，页面截图存 `screenshot.png`。

> 抓包得到的真实端点与字段，是本次验证的**唯一真相**；文档与抓包冲突时以抓包为准。

### C. 参数试探（补 B 的盲区）
抓到端点后，改参数验证可选能力：
- 构建：`branch` / `commit` / `env` / `params`
- 部署：`envId` / `deId` / `imageTag` / `imageDigest` / `replicas`
- 分页：`page` / `pageSize` / `cursor`

每次只改一个参数，记录「改了之后行为是否变化」，避免误判。

---

## 4. 十二项能力逐项验证

| # | 能力 | 验证方法 | 通过判据 | 证据 |
|---|---|---|---|---|
| 1 | 鉴权 | 用 token 请求「项目信息」；再用错误 token 请求 | 正确 200、错误 401；记录 token scope 边界 | `auth/*.json` |
| 2 | 构建触发 | 触发指定任务，带分支；再试带 commit | 返回可查询的任务 id；**commit 可指定**是加分项 | `build/trigger.*` |
| 3 | 构建状态 | 触发后立即查 → 30s 再查 → 完成后查 → 故意造一次失败再查 | 能区分 排队/运行/成功/失败/取消 | `build/status-*.json` |
| 4 | 构建日志 | 取日志，看是否分页、是否有截断 | 能拿到失败堆栈；记录最大长度与分页参数 | `build/log.*` |
| 5 | 构建产物 | 列产物、下载、确认与本次 commit 关联 | 能下载且可定位到产物 URL | `build/artifact.*` |
| 6 | **镜像 digest** | 见下方专节 | 拿到与本次构建对应的 digest | `image/digest.*` |
| 7 | 部署触发 | 触发测试环境部署，指定镜像 tag | 返回部署 id；**能否指定镜像而非分支**是关键 | `deploy/trigger.*` |
| 8 | **部署状态** | 轮询到终态；造一次失败（如镜像不存在） | 能拿到成功/失败 + 可读失败原因 | `deploy/status-*.json` |
| 9 | Webhook | 配置 webhook → 触发构建 → 看回调 | 回调 body 含 `externalId` 与状态；记录验签方式 | `webhook/*.json` |
| 10 | 取消 | 触发后 10s 内取消 | 任务转为取消态，或返回可预期错误 | `build/cancel.json` |
| 11 | 制品库 | 上传/查询一个 jar | 能上传并拿到坐标 URL | `artifact/*.json` |
| 12 | 限流 | 连续并发 20 次触发/查询 | 记录限流阈值与错误码（429？） | `ratelimit.md` |

### 6 号能力专节：镜像 digest 的三种取证方式

“生产与测试同镜像”如果不能验证，整个 J27 校验就是空的。按优先级取证：

| 方式 | 做法 | 适用 |
|---|---|---|
| ① 构建/部署接口直接返回 | 看响应里有没有 `image` / `digest` / `imageId` 字段 | 最优 |
| ② Registry API 反查 | 用 tag 请求 Harbor/CODING 制品库的 `manifests/{tag}`，读 `Docker-Content-Digest` | 有制品库读权限时 |
| ③ 部署后查容器平台 | 部署完成后查 K8s Pod 的 `status.containerStatuses[].imageID`（`docker-pullable://...@sha256:...`） | 有 K8s API 权限时 |

**必须回答的两个问题**
- 部署接口接受 **tag 还是 digest**？若只接受 tag，则存在“同 tag 不同镜像”的风险，需要在部署后用 ③ 反查实际 digest 并与测试环境比对；
- 同一 tag 重复推送时 digest 是否变化（判断能否用 tag 作为一致性依据）。

---

## 5. 验证脚手架

建议建一个独立小工程 `poc/coding-poc/`（不进入正式代码库），一个配置文件 + 一个执行脚本：

```
poc/coding-poc/
├── config.yaml          # base_url / team / project / token / 各任务 id
├── run.sh               # 顺序执行 12 项检查
├── probes/              # 每项一个 curl 或 .http 文件
└── report.md            # 自动汇总成能力矩阵
```

`config.yaml` 示例：

```yaml
base_url: https://<team>.coding.net
project: <project-id>
token: ${CODING_TOKEN}
build_task: <build-task-id>
deploy_task_test: <test-deploy-task-id>
deploy_task_prod: <prod-deploy-task-id>
artifact_repo: <repo>
```

执行方式：`./run.sh` 逐项打点，输出 `PASS / PARTIAL / FAIL` 与证据路径。**允许其中 3 项手工验证**（webhook、digest、限流），其余必须脚本化，否则不算证据。

---

## 6. 时间盒（5 天）

| 天 | 内容 | 交付 |
|---|---|---|
| D1 | 前置准备 + 文档阅读 + 抓包，确定真实端点与鉴权 | 端点清单、鉴权方式 |
| D2 | 能力 1–5（构建链路） | 构建可用性结论 |
| D3 | 能力 6–8（镜像、部署链路） | **R3 / R2 结论** |
| D4 | 能力 9–12（webhook、取消、制品、限流） | 回调与限制结论 |
| D5 | 汇总矩阵、对照方案给出改型建议、评审会 | 能力矩阵 + Go/No-Go |

---

## 7. 失败时的替代路径（先想好，不临场拍脑袋）

| 缺失能力 | 替代方案 | 代价 |
|---|---|---|
| 无「查询部署状态」 | 轮询 K8s Deployment 的 `observedGeneration` / Pod ready | +1 个集成点，环境需暴露 K8s API |
| 无「指定 commit」触发构建 | 用分支触发，重跑一致性下降 | C15「按 commit 判定重跑」精度下降，需记录为已知限制 |
| 无 webhook | 10s 轮询 | 实时性下降，CODING 侧压力上升 |
| 无构建日志 API | 仅跳转 CODING 页面 | 排障多一跳，可接受 |
| 无 digest | 见第 4 节 ②③ | 增加 registry 或 K8s 依赖 |
| 无制品库 API | 二方库发布改为「构建 + 安全检测 + 人工上传」 | 二方库链路半自动化，需产品确认 |
| 部署接口只接受 tag | 部署后反查实际 digest 比对 | J27 从「前置阻断」降为「部署后校验 + 告警」 |

**注意**：J27 若从「前置阻断」降级为「事后告警」，必须回到需求文档改口径——这属于产品决策，不是技术兜底。

---

## 8. 产出物清单

1. **能力矩阵**（12 项 × [可用/降级/不可用] × 证据路径），评审签字；
2. `CicdEngine` 接口调整建议（哪些方法签名要变、是否需要新增 `resolveDigest`）；
3. 引擎配置项清单（实际字段名、必填/可选、默认值）；
4. Go / No-Go 结论与会改动的方案章节；
5. 二期判断：自研引擎时哪些能力可以不再依赖 CODING。

---

## 9. 需要你提供的材料

| # | 材料 | 用途 |
|---|---|---|
| 1 | CODING 站点地址 + 团队/项目标识（测试项目即可） | 打通鉴权 |
| 2 | 一个测试用的构建任务 ID、一个测试用部署任务 ID | 验证 R1/R2 |
| 3 | Personal Access Token（只读+触发权限即可） | 调用 API |
| 4 | 若有：CODING 开放接口文档链接或截图 | 减少抓包时间 |
| 5 | 是否有 Harbor / K8s API 的只读权限 | 决定 R3 的取证路径 |
| 6 | 网络策略：验证机能否访问 CODING API 域名 | 排除环境问题 |

拿到 1–3 后，第 2–4 节可以在 3 天内跑完。若你把抓到的 curl 或文档片段贴给我，我可以直接写 `CodingEngine` 适配器与这套探测脚手架。