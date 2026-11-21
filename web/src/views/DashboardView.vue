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
</style>