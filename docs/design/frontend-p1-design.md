# P1 前端设计定稿 · 石墨中性（Graphite）

| 项 | 内容 |
|---|---|
| 风格 | 简约商务，中性灰阶 + 单一强调色，信息密度中高，状态用点状标记 |
| 技术 | Vue 3 + TypeScript + Vite + Vue Router + Element Plus（全量主题定制） |
| 主题 | 明/暗两套 CSS 变量，跟随系统，可手动切换并持久化 |
| 数据 | 直连本地后端（Vite proxy → `http://127.0.0.1:8080`），身份用 `X-User-Id` |

## 1. 设计原则

1. **颜色只承担语义**：强调色只有一种（青绿），用于主操作、当前态与链接；红/琥珀只用于失败/阻塞；其余全是灰阶。不做彩色渐变、不做大面积色块。
2. **分组靠空间与细线**：默认无阴影，卡片 1px 边框 + 8px 圆角；只有浮层（弹窗、抽屉、下拉）带阴影。
3. **状态用点不用块**：状态 = 6px 圆点 + 文字，避免表格里一片彩色标签。
4. **数字用等宽**：耗时、进度、ID、分支名一律 `font-variant-numeric: tabular-nums` 或等宽字体，保证纵向对齐。
5. **动效克制**：仅 `opacity/transform` 的 120–180ms 过渡；进行中的状态点有缓慢呼吸，其余不动。

## 2. 设计令牌

| 令牌 | Light | Dark | 用途 |
|---|---|---|---|
| `--bg` | `#FFFFFF` | `#0B0D10` | 页面底色 |
| `--surface` | `#F7F8FA` | `#14171A` | 侧栏、表头、卡片底 |
| `--surface-2` | `#FFFFFF` | `#1A1E24` | 卡片、弹窗 |
| `--border` | `#E6E8EB` | `#23272E` | 1px 分隔线 |
| `--border-strong` | `#D5D9DE` | `#2E343C` | 输入框、可交互边界 |
| `--text` | `#14171A` | `#E6E8EB` | 主文本 |
| `--text-2` | `#5B6570` | `#9BA1A6` | 次文本 |
| `--text-3` | `#8A939C` | `#6B7280` | 弱文本、占位 |
| `--accent` | `#0E9F6E` | `#2DD4A7` | 主操作、当前态 |
| `--accent-weak` | `#E6F7F0` | `#0F2A22` | 强调底（选中行、当前步骤） |
| `--danger` | `#D92D20` | `#F97066` | 失败 |
| `--warn` | `#B54708` | `#FDB022` | 阻塞/PAUSED |
| `--info` | `#175CD3` | `#84ADFF` | 中性信息 |
| `--radius` | `8px` | `8px` | 卡片/输入框 |
| `--shadow` | `0 1px 2px rgba(16,24,40,.06)` | `0 1px 2px rgba(0,0,0,.4)` | 浮层 |

字号：12 / 13 / 14（正文）/ 16（标题）/ 20（页面标题）。行高 1.6。
字体：`Inter, "PingFang SC", "Microsoft YaHei", system-ui`；数字与分支用 `ui-monospace, "JetBrains Mono", Consolas`。

## 3. 页面结构

```
┌──────────────────────────────────────────────────────────┐
│ 侧栏 220px          │  顶栏 56px：面包屑 · 主题切换 · 身份  │
│ · 总览               ├───────────────────────────────────┤
│ · 任务               │                                   │
│ · 系统             │  内容区 max-width 1280，padding 24  │
│ · 部署单元           │                                   │
│ · 设置               │                                   │
└──────────────────────────────────────────────────────────┘
```
窄屏（<960px）侧栏收成图标栏；<720px 内容单列。

## 4. 页面与关键组件

| 路由 | 页面 | 关键内容 |
|---|---|---|
| `/` | 总览 | 三个统计卡（进行中任务、待处理变更项、最近发布）+ 最近任务表 |
| `/tasks` | 任务列表 | 筛选（系统/状态/关键字）+ 表格 + 新建任务弹窗 |
| `/tasks/:id` | 任务详情 | 进度条 + 变更项表格（状态点/门禁列/发布按钮）+ 成员 + 新建变更项 |
| `/releases/:publishNo` | 发布内容 | 四节点步骤条、节点任务卡、状态点、发布记录 Tab |
| `/subsystems` | 系统 | 列表 + 新建 |
| `/subsystems/:id` | 系统详情 | 基本信息 + 部署单元列表 |
| `/units/:id` | 部署单元 | 概述、CODING 任务绑定（三必填 + 日常可选）、环境 DE 列表 |
| `/settings` | 设置 | 主题、身份（X-User-Id）、后端连通性、CODING 对接状态（只读提示） |

组件（自研，放在 `web/src/components`）：
`StatusDot`（状态点 + 文案）、`PageHeader`、`DataSection`（标题 + 操作 + 内容）、`EmptyState`、`KeyValue`（键值对）、`StepBar`（四节点步骤条）、`CodeText`（等宽短文本）。

## 5. 状态语义

| 域 | 值 | 颜色 |
|---|---|---|
| 变更项 | PENDING / BUILDING / TESTING / GATE_PENDING / SUBMITTED / PENDING_APPROVAL / READY | 灰 / 强调 / 强调 / 强调 / 强调 / 琥珀 / 强调 |
| 变更项 | PROD_DEPLOYING / DONE | 强调 / 绿（成功） |
| 变更项 | GATE_FAILED / FAILED / REJECTED | 红 |
| 变更项 | CLOSED / TERMINATED / ROLLED_BACK | 灰 |
| 实例 | PENDING / RUNNING / PAUSED | 灰 / 强调（呼吸）/ 琥珀 |
| 实例 | DONE / FAILED | 绿 / 红 |
| 任务节点 | PENDING / RUNNING / SUCCESS / FAILED / TIMEOUT / SKIPPED | 灰 / 强调 / 绿 / 红 / 红 / 灰 |

## 6. 明暗切换

- `data-theme="light|dark"` 挂在 `<html>`；首次加载取 `localStorage.theme`，无则跟随 `prefers-color-scheme`。
- Element Plus 通过覆盖其 CSS 变量（`--el-bg-color`、`--el-text-color-primary` 等）映射到本设计令牌，不另写一套组件样式。
- 覆盖块的选择器必须是 `:root, :root[data-theme='light'], :root[data-theme='dark']`：EP 自带深色变量挂在 `html.dark`（特异性 0,1,1），普通 `:root`（0,1,0）会被压过，深色下主色会退回 EP 的默认蓝。主色/成功/危险/警告/信息五组 light-3~9、dark-2 都用 `color-mix` 从令牌派生，保证两套主题同源。
- 切换时只过渡 `background-color / color / border-color`，避免整页闪烁。

## 7. 后端依赖（本步需要补的读接口）

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/tasks?page&size&subsystemId&status&keyword` | 任务列表 |
| GET | `/tasks/{id}` | 任务详情（含成员与进度） |
| GET | `/tasks/{id}/items` | 变更项列表（含单元名） |
| GET | `/units/{id}/coding-tasks` | 部署单元详情页回显已有 CODING 绑定 |

其余页面复用已有接口：`/subsystems`、`/units`、`/units/{id}/env-des`、`/api/v1/items`、`/api/v1/items/{id}/publish`、`/api/v1/releases/{publishNo}`。

部署单元详情页的「CODING 任务绑定」表单先读后写：读出当前绑定并回填，逐个字段用圆点标记 `已绑定`；保存按任务类型 upsert，留空的类型保留原绑定。「发布前置条件」清单把后端的准入规则（三类绑定 + 测试 DE + 生产族 DE）前置到页面上，避免用户点发布才收到 40011。

`params_json` 列是 MySQL `JSON` 类型，读写会经过服务端规范化（例如 `{"branch": "release/202611"}` 带空格、键顺序按 MySQL 规则），前端只按 JSON 语义解析 `params.branch`，不做字符串比较。

### 7.1 优化轮次（2026-11-22）

- **术语**：界面与文档统一叫「系统」，路由与接口仍是 `/subsystems`；库表与 Java 类保留 `subsystem` 前缀，作为命名债记在台账里。
- **部署单元列表**：新增「模块」「标签」两个精确筛选与「模块 / 标签」列（标签显示为 `#value` 小徽标），筛选条改为 `系统 / 类型 / 模块 / 标签 / 名称`。
- **部署单元详情**：新增「编辑」弹窗，一次改完基本信息；保存是整体替换，留空的可选字段会被清空，这条规则直接写在弹窗底部。基本信息卡新增「模块」「标签」两行，与「模块路径」并列以体现两者不同。
- **CODING 绑定改为选择**：任务输入框换成可搜索下拉，候选按所属系统的 CODING 项目分别拉取构建 / 部署两个命名空间；拉取失败时保留 `allow-create`，并在表单下方用警示色说明原因——「连不上」不能被误读成「项目没有任务」。

## 8. 验收

- `npm run build` 通过，无 TS 报错；
- 明暗两套下所有页面文字对比度 ≥ WCAG AA（正文 4.5:1）；
- 后端未启动时，页面显示明确的连通性提示，不白屏；
- 任务 → 变更项 → 发布 → 发布内容 的路径可完整走通（无 CODING 连接时发布结果为 FAILED，页面如实呈现）。