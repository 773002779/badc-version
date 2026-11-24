<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { listReleases, listSubsystems, listUnits } from '../api'
import type { ReleaseRecordRow, SubsystemView, UnitView } from '../api/types'
import { ApiError } from '../api/http'
import PageHeader from '../components/PageHeader.vue'
import DataSection from '../components/DataSection.vue'
import EmptyState from '../components/EmptyState.vue'
import StatusDot from '../components/StatusDot.vue'
import { formatDateTime, statusTone } from '../utils/format'

/**
 * The order status vocabulary the page can filter on. The backend sends the machine value with its
 * Chinese label on every row; this list only feeds the select, so an unknown stored status still
 * renders its server label instead of being dropped.
 */
const ORDER_STATUSES: Array<{ value: string; label: string }> = [
  { value: 'INIT', label: '已创建' },
  { value: 'PENDING', label: '待发布' },
  { value: 'BUILDING', label: '构建中' },
  { value: 'TESTING', label: '测试中' },
  { value: 'GATE_PENDING', label: '待生产准入' },
  { value: 'SUBMITTED', label: '已提交' },
  { value: 'PENDING_APPROVAL', label: '待审批' },
  { value: 'READY', label: '待执行' },
  { value: 'EXECUTING', label: '执行中' },
  { value: 'PROD_DEPLOYING', label: '生产部署中' },
  { value: 'DONE', label: '已完成' },
  { value: 'GATE_FAILED', label: '门禁未通过' },
  { value: 'FAILED', label: '失败' },
  { value: 'REJECTED', label: '已驳回' },
  { value: 'CLOSED', label: '已关闭' },
  { value: 'TERMINATED', label: '已终止' },
  { value: 'ROLLED_BACK', label: '已回滚' }
]

const rows = ref<ReleaseRecordRow[]>([])
const total = ref(0)
const pageNo = ref(1)
const pageSize = ref(20)
const loading = ref(false)
const error = ref<string | null>(null)

const subsystems = ref<SubsystemView[]>([])
const units = ref<UnitView[]>([])

const filters = reactive({
  keyword: '',
  status: '',
  systemId: undefined as number | undefined,
  unitId: undefined as number | undefined
})

async function load() {
  loading.value = true
  error.value = null
  try {
    const page = await listReleases({
      page: pageNo.value,
      size: pageSize.value,
      keyword: filters.keyword.trim() || undefined,
      status: filters.status || undefined,
      systemId: filters.systemId,
      unitId: filters.unitId
    })
    rows.value = page.records
    total.value = page.total
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '加载发布记录失败'
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

async function loadSubsystems() {
  try {
    subsystems.value = (await listSubsystems(1, 100)).records
  } catch {
    subsystems.value = []
  }
}

/** Unit options follow the selected system; cleared system lists every unit in scope. */
async function loadUnits() {
  try {
    units.value = (
      await listUnits({ pageNo: 1, pageSize: 100, subsystemId: filters.systemId })
    ).records
  } catch {
    units.value = []
  }
}

function search() {
  pageNo.value = 1
  load()
}

function reset() {
  filters.keyword = ''
  filters.status = ''
  filters.systemId = undefined
  filters.unitId = undefined
  pageNo.value = 1
  loadUnits()
  load()
}

function onSystemChange() {
  filters.unitId = undefined
  pageNo.value = 1
  loadUnits()
  load()
}

function onSizeChange() {
  pageNo.value = 1
  load()
}

onMounted(() => {
  load()
  loadSubsystems()
  loadUnits()
})
</script>

<template>
  <div>
    <PageHeader
      title="发布记录"
      description="跨任务的发布记录；数据范围由服务端按账号收敛，页面只展示当前账号可见的部分。"
    >
      <el-button :loading="loading" @click="load">刷新</el-button>
    </PageHeader>

    <el-alert
      v-if="error"
      type="error"
      :closable="false"
      show-icon
      :title="error"
      style="margin-bottom: 16px"
    />

    <DataSection title="发布记录" :subtitle="`${total} 条`" flush>
      <div class="filter-bar">
        <el-input
          v-model="filters.keyword"
          class="filter-keyword"
          placeholder="发布单 / CRID"
          clearable
          @keyup.enter="search"
          @clear="search"
        />
        <el-select v-model="filters.status" class="filter-select" clearable placeholder="全部状态">
          <el-option
            v-for="s in ORDER_STATUSES"
            :key="s.value"
            :label="s.label"
            :value="s.value"
          />
        </el-select>
        <el-select
          v-model="filters.systemId"
          class="filter-select"
          clearable
          placeholder="全部系统"
          @change="onSystemChange"
        >
          <el-option v-for="s in subsystems" :key="s.id" :label="s.name" :value="s.id" />
        </el-select>
        <el-select
          v-model="filters.unitId"
          class="filter-select"
          clearable
          placeholder="全部单元"
          @change="search"
        >
          <el-option
            v-for="u in units"
            :key="u.id"
            :label="u.name"
            :value="u.id"
          />
        </el-select>
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </div>

      <el-table v-if="rows.length" :data="rows" row-key="publishNo" style="width: 100%">
        <el-table-column label="发布单" min-width="190">
          <template #default="{ row }">
            <router-link :to="`/releases/${row.publishNo}`" class="mono strong">
              {{ row.publishNo }}
            </router-link>
          </template>
        </el-table-column>
        <el-table-column prop="crid" label="CRID" min-width="170">
          <template #default="{ row }"><span class="mono">{{ row.crid }}</span></template>
        </el-table-column>
        <el-table-column prop="version" label="版本" min-width="120" />
        <el-table-column label="系统" min-width="160">
          <template #default="{ row }">
            {{ row.subsystemName || `#${row.subsystemId}` }}
          </template>
        </el-table-column>
        <el-table-column label="单元" min-width="150">
          <template #default="{ row }">{{ row.unitName || `#${row.unitId}` }}</template>
        </el-table-column>
        <el-table-column label="发布状态" width="120">
          <template #default="{ row }">
            <span class="status-chip" :class="`chip-${statusTone(row.orderStatus)}`">
              {{ row.orderStatusLabel || row.orderStatus }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="变更项状态" width="120">
          <template #default="{ row }">
            <span class="status-chip" :class="`chip-${statusTone(row.itemStatus)}`">
              {{ row.itemStatusLabel || row.itemStatus }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="计划生产时间" width="170">
          <template #default="{ row }">
            <span class="mono weak">{{ formatDateTime(row.plannedReleaseTime) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="executedBy" label="执行人" width="110">
          <template #default="{ row }">{{ row.executedBy || '—' }}</template>
        </el-table-column>
        <el-table-column label="最新流水线" min-width="210">
          <template #default="{ row }">
            <div v-if="row.pipelineNo" class="pipeline-cell">
              <span class="mono">{{ row.pipelineNo }}</span>
              <StatusDot v-if="row.pipelineStatus" :status="row.pipelineStatus" domain="node" />
            </div>
            <span v-else class="weak">尚未执行</span>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">
            <span class="mono weak">{{ formatDateTime(row.createdAt) }}</span>
          </template>
        </el-table-column>
      </el-table>

      <EmptyState
        v-else-if="!loading"
        title="没有符合条件的发布记录"
        hint="调整筛选条件，或确认当前账号的数据范围。"
      />
      <div v-else class="loading-block"><el-skeleton :rows="5" animated /></div>

      <div v-if="total > 0" class="pager">
        <el-pagination
          v-model:current-page="pageNo"
          v-model:page-size="pageSize"
          :total="total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          background
          @current-change="load"
          @size-change="onSizeChange"
        />
      </div>
    </DataSection>
  </div>
</template>

<style scoped>
.filter-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  padding: 14px 18px;
  border-bottom: 1px solid var(--border);
}
.filter-keyword {
  width: 200px;
}
.filter-select {
  width: 160px;
}
.pipeline-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}
.status-chip {
  display: inline-flex;
  align-items: center;
  height: 20px;
  padding: 0 8px;
  border-radius: 999px;
  border: 1px solid var(--border);
  background: var(--surface-3);
  color: var(--text-2);
  font-size: 11px;
  line-height: 1;
  white-space: nowrap;
}
.chip-success {
  color: var(--accent);
  border-color: color-mix(in srgb, var(--accent) 35%, var(--border));
  background: var(--accent-weak);
}
.chip-danger {
  color: var(--danger);
  border-color: color-mix(in srgb, var(--danger) 35%, var(--border));
  background: var(--danger-weak);
}
.chip-warn {
  color: var(--warn);
  border-color: color-mix(in srgb, var(--warn) 35%, var(--border));
  background: var(--warn-weak);
}
.chip-accent {
  color: var(--accent);
  border-color: color-mix(in srgb, var(--accent) 35%, var(--border));
  background: var(--accent-weak);
}
.loading-block {
  padding: 24px;
}
.pager {
  display: flex;
  justify-content: flex-end;
  padding: 12px 18px;
  border-top: 1px solid var(--border);
}
@media (max-width: 960px) {
  .filter-keyword,
  .filter-select {
    width: 100%;
  }
}
</style>