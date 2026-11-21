<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { listTasks, listUnits } from '../api'
import type { PageResult, TaskView, UnitView } from '../api/types'
import { ApiError } from '../api/http'
import PageHeader from '../components/PageHeader.vue'
import DataSection from '../components/DataSection.vue'
import EmptyState from '../components/EmptyState.vue'

const router = useRouter()
const tasks = ref<PageResult<TaskView> | null>(null)
const units = ref<PageResult<UnitView> | null>(null)
const loading = ref(true)
const error = ref<string | null>(null)

async function load() {
  loading.value = true
  error.value = null
  try {
    const [t, u] = await Promise.all([listTasks({ pageNo: 1, pageSize: 6 }), listUnits({ pageNo: 1, pageSize: 1 })])
    tasks.value = t
    units.value = u
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)

const flow = [
  { no: '01', name: '构建', desc: '拉分支、跑 CODING 构建、产出镜像', tag: '已交付', live: true },
  { no: '02', name: '测试部署', desc: '构建成功后自动部署到测试环境', tag: '已交付', live: true },
  { no: '03', name: '验证确认', desc: '安全扫描与测试验收，通过后才可上生产', tag: 'P2', live: false },
  { no: '04', name: '生产部署', desc: '按 DE 批次独立发布，支持回滚', tag: 'P3', live: false }
]

function openTask(id: number) {
  router.push(`/tasks/${id}`)
}
</script>

<template>
  <div>
    <PageHeader title="总览" description="P1：构建与测试部署。门禁、生产管控与回滚在后续阶段交付。">
      <el-button :loading="loading" @click="load">刷新</el-button>
    </PageHeader>

    <el-alert
      v-if="error"
      type="error"
      :closable="false"
      show-icon
      :title="error"
      description="确认后端已启动：scripts\\start.ps1。"
      style="margin-bottom: 16px"
    />

    <div class="grid grid-3" style="margin-bottom: 16px">
      <div class="panel panel-pad stat">
        <div class="stat-label">进行中的任务</div>
        <div class="stat-value num">{{ tasks?.records.filter((t) => t.done < t.total).length ?? '—' }}</div>
        <div class="stat-hint">按变更项完成度统计</div>
      </div>
      <div class="panel panel-pad stat">
        <div class="stat-label">部署单元</div>
        <div class="stat-value num">{{ units?.total ?? '—' }}</div>
        <div class="stat-hint">已登记的部署单元与二方库</div>
      </div>
      <div class="panel panel-pad stat">
        <div class="stat-label">最近任务数</div>
        <div class="stat-value num">{{ tasks?.total ?? '—' }}</div>
        <div class="stat-hint">全部发布任务</div>
      </div>
    </div>

    <DataSection title="最近任务" flush>
      <template #actions>
        <el-button text @click="router.push('/tasks')">全部任务 →</el-button>
      </template>

      <el-table
        v-if="tasks && tasks.records.length"
        :data="tasks.records"
        style="width: 100%"
        :show-header="true"
        row-key="id"
        @row-click="(row: TaskView) => openTask(row.id)"
      >
        <el-table-column prop="name" label="任务" min-width="180">
          <template #default="{ row }">
            <div class="strong">{{ row.name }}</div>
            <div class="weak small">{{ row.subsystemName ?? '—' }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="creator" label="创建人" width="120" />
        <el-table-column prop="planFinishDate" label="预计完成" width="120" />
        <el-table-column label="完成度" width="200">
          <template #default="{ row }">
            <div class="row" style="gap: 10px">
              <el-progress
                :percentage="Math.round((row.rate ?? 0) * 100)"
                :show-text="false"
                :stroke-width="4"
                style="flex: 1"
              />
              <span class="mono weak">{{ row.done }}/{{ row.total }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="阻塞" width="110">
          <template #default="{ row }">
            <span class="small">{{ row.hasBlocking ? '有阻塞' : '—' }}</span>
          </template>
        </el-table-column>
      </el-table>

      <EmptyState v-else-if="!loading" title="还没有发布任务" hint="从「任务」页新建一个任务开始。" />
      <div v-else style="padding: 24px"><el-skeleton :rows="4" animated /></div>
    </DataSection>

    <div class="split">
      <DataSection title="发布流水线" subtitle="一次发布 = 一条流水线；部署单元各自独立推进">
        <div class="flow">
          <div v-for="step in flow" :key="step.no" class="flow-item" :class="{ on: step.live }">
            <div class="flow-no">{{ step.no }}</div>
            <div class="flow-name">
              {{ step.name }}
              <span class="flow-tag" :class="step.live ? 'live' : 'later'">{{ step.tag }}</span>
            </div>
            <div class="flow-desc">{{ step.desc }}</div>
          </div>
        </div>
      </DataSection>

      <DataSection title="开始使用">
        <ol class="howto">
          <li>
            <span>登记系统与部署单元</span>
            <router-link to="/units">部署单元 →</router-link>
          </li>
          <li>
            <span>绑定 CODING 构建 / 测试部署 / 生产部署任务</span>
            <span class="weak small">单元详情页</span>
          </li>
          <li>
            <span>新建任务 → 添加变更项（选单元与版本）</span>
            <router-link to="/tasks">任务 →</router-link>
          </li>
          <li>
            <span>在变更项上点「发布」，右侧查看流水线进度</span>
            <span class="weak small">发布内容页</span>
          </li>
        </ol>
        <p class="weak small" style="margin: 10px 0 0">
          未配置 CODING 连接时发布会以 ENGINE_UNAVAILABLE 失败，这是预期结果。
        </p>
      </DataSection>
    </div>
  </div>
</template>

<style scoped>
.stat-label {
  font-size: 12px;
  color: var(--text-3);
}
.stat-value {
  font-size: 28px;
  font-weight: 600;
  letter-spacing: -0.02em;
  margin: 6px 0 2px;
}
.stat-hint {
  font-size: 12px;
  color: var(--text-3);
}
:deep(.el-table__row) {
  cursor: pointer;
}

.split {
  display: grid;
  grid-template-columns: minmax(0, 1.75fr) minmax(0, 1fr);
  gap: 16px;
  margin-top: 16px;
}

.flow {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
}
.flow-item {
  position: relative;
  padding-top: 14px;
}
.flow-item::before {
  content: '';
  position: absolute;
  inset: 0 0 auto;
  height: 2px;
  border-radius: 2px;
  background: var(--border);
}
.flow-item.on::before {
  background: var(--accent);
}
.flow-no {
  font-family: ui-monospace, 'JetBrains Mono', Consolas, 'Courier New', monospace;
  font-size: 11px;
  color: var(--text-3);
}
.flow-name {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 3px 0 5px;
  font-size: 13px;
  font-weight: 600;
  color: var(--text);
}
.flow-tag {
  padding: 0 6px;
  border-radius: 999px;
  font-size: 10px;
  font-weight: 400;
  line-height: 16px;
}
.flow-tag.live {
  color: var(--accent);
  background: var(--accent-weak);
}
.flow-tag.later {
  color: var(--text-3);
  background: var(--surface-3);
}
.flow-desc {
  font-size: 12px;
  line-height: 1.65;
  color: var(--text-2);
}

.howto {
  margin: 0;
  padding: 0;
  list-style: none;
  counter-reset: howto;
}
.howto li {
  counter-increment: howto;
  display: grid;
  grid-template-columns: 22px 1fr auto;
  align-items: baseline;
  gap: 10px;
  padding: 9px 0;
  border-bottom: 1px dashed var(--border);
  font-size: 13px;
}
.howto li:last-child {
  border-bottom: none;
}
.howto li::before {
  content: counter(howto);
  font-size: 11px;
  color: var(--text-3);
  border: 1px solid var(--border);
  border-radius: 50%;
  width: 20px;
  height: 20px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

@media (max-width: 1100px) {
  .split {
    grid-template-columns: 1fr;
  }
  .flow {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>