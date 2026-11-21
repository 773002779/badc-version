# 发版流水线 · 前端（P1）

Vue 3 + TypeScript + Vite + Element Plus，明暗双主题，直连本地后端。

## 运行

```powershell
# 1. 后端（另开一个终端）
powershell -ExecutionPolicy Bypass -File ..\scripts\start.ps1

# 2. 前端
powershell -ExecutionPolicy Bypass -File ..\scripts\web-dev.ps1
# 或
npm install
npm run dev
```

默认地址 http://127.0.0.1:5173 ，通过 Vite 代理转发 `/api`、`/subsystems`、`/units`、`/tasks` 到 `http://127.0.0.1:8080`。
换后端地址：`$env:VITE_BACKEND='http://host:port'; npm run dev`。

## 构建

```powershell
npm run build    # vue-tsc 类型检查 + vite build，产物在 dist/
```

## 结构

```
src/
  api/        http 封装、类型、接口
  components/ StatusDot / PageHeader / DataSection / EmptyState / StepBar / KeyValue
  styles/     tokens.css（设计令牌 + Element Plus 变量映射）、base.css（布局与基础样式）
  views/      总览、任务、任务详情、发布内容、子系统、部署单元、设置
  theme.ts    明暗切换（localStorage 持久化）
```

## 设计约定

- 一种强调色（青绿）只承担语义；其余全部灰阶。
- 状态用点 + 文字，不用彩色块；进行中状态点缓慢呼吸。
- 数字、commit、分支用等宽字体。
- 明暗切换只过渡 `background-color / color / border-color`。

## 已知限制

- 后端未提供 CODING 连接管理接口，设置页只展示对接方式。
- 门禁、生产管控、回滚、日常发布在 P2/P3/P4，前端暂不呈现。
- `el-input` 的 `maxlength` 与当前 Element Plus + Volar 组合存在类型冲突，改用后端校验兜底。