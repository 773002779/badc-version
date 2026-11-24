<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { exportAudit, listAudit } from '../api'
import type { AuditFilter, AuditRow } from '../api/types'
import { ApiError } from '../api/http'
import PageHeader from '../components/PageHeader.vue'
import DataSection from '../components/DataSection.vue'
import EmptyState from '../components/EmptyState.vue'
import { formatDateTime } from '../utils/format'
import {
  AUDIT_ACTION_GROUPS,
  AUDIT_OBJECT_TYPES,
  objectTypeLabel,
  prettyJson,
  shortTraceId
} from '../utils/audit'

const rows = ref<AuditRow[]>([])
const total = ref(0)
const pageNo = ref(1)
const pageSize = ref(20)
const loading = ref(false)
const exporting = ref(false)
const error = ref<string | null>(null)

const filters = reactive({
  action: '',
  objectType: '',
  objectId: '',
  actor: '',
  /** The picker hands back local Date objects; they become ISO-8601 Instants on the wire. */
  range: null as Date[] | null
})

/** The current filters as API parameters; paging is added by `load()`, never by the export. */
function filterParams(): AuditFilter {
  return {
    action: filters.action || undefined,
    objectType: filters.objectType || undefined,
    objectId: filters.objectId.trim() || undefined,
    actor: filters.actor.trim() || undefined,
    from: filters.range?.[0]?.toISOString(),
    to: filters.range?.[1]?.toISOString()
  }
}

async function load() {
  loading.value = true
  error.value = null
  try {
    const page = await listAudit({ page: pageNo.value, size: pageSize.value, ...filterParams() })
    rows.value = page.records
    total.value = page.total
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '加载审计日志失败'
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function search() {
  pageNo.value = 1
  load()
}

function reset() {
  filters.action = ''
  filters.objectType = ''
  filters.objectId = ''
  filters.actor = ''
  filters.range = null
  pageNo.value = 1
  load()
}

function onSizeChange() {
  pageNo.value = 1
  load()
}

/**
 * Downloads the current filter as CSV. The blob comes from the authenticated `fetch` in the API
 * layer (a `window.open` would lose the `X-User-Id` header); the filename is the server's.
 */
async function exportCsv() {
  exporting.value = true
  try {
    const { blob, filename } = await exportAudit(filterParams())
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = filename
    document.body.appendChild(link)
    link.click()
    link.remove()
    URL.revokeObjectURL(url)
    ElMessage.success(`已导出 ${filename}`)
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '导出 CSV 失败')
  } finally {
    exporting.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="audit-page">
    <PageHeader
      title="审计日志"
      description="谁在何时对哪个对象做了什么；按账号的数据范围收敛，可按动作/对象/操作人筛选并导出 CSV。"
    >
      <el-button :loading="loading" @click="load">刷新</el-button>
      <el-button type="primary" :loading="exporting" @click="exportCsv">导出 CSV</el-button>
    </PageHeader>

    <el-alert
      v-if="error"
      type="error"
      :closable="false"
      show-icon
      :title="error"
      style="margin-bottom: 16px"
    />

    <DataSection title="审计记录" :subtitle="`${total} 条`" flush>
      <div class="filter-bar">
        <el-select v-model="filters.action" class="filter-select filter-action" clearable placeholder="全部动作">
          <el-option label="全部动作" value="" />
          <el-option-group
            v-for="group in AUDIT_ACTION_GROUPS"
            :key="group.label"
            :label="group.label"
          >
            <el-option
              v-for="action in group.actions"
              :key="action.value"
              :label="action.label"
              :value="action.value"
            />
          </el-option-group>
        </el-select>
        <el-select
          v-model="filters.objectType"
          class="filter-select filter-object"
          clearable
          placeholder="全部对象类型"
        >
          <el-option label="全部对象类型" value="" />
          <el-option
            v-for="type in AUDIT_OBJECT_TYPES"
            :key="type.value"
            :label="type.label"
            :value="type.value"
          />
        </el-select>
        <el-input
          v-model="filters.objectId"
          class="filter-id filter-object-id"
          placeholder="对象 ID"
          clearable
          @keyup.enter="search"
        />
        <el-input
          v-model="filters.actor"
          class="filter-id filter-actor"
          placeholder="操作人"
          clearable
          @keyup.enter="search"
        />
        <div class="filter-range-wrap">
          <el-date-picker
            v-model="filters.range"
            class="filter-range"
            type="datetimerange"
            unlink-panels
            start-placeholder="开始时间"
            end-placeholder="结束时间"
          />
        </div>
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </div>

      <el-table v-if="rows.length" :data="rows" row-key="id" style="width: 100%">
        <el-table-column type="expand" width="40">
          <template #default="{ row }">
            <div class="diff">
              <div class="diff-col">
                <div class="diff-head">变更前</div>
                <pre v-if="row.before" class="diff-json mono">{{ prettyJson(row.before) }}</pre>
                <p v-else class="weak small diff-none">无（新增对象）</p>
              </div>
              <div class="diff-col">
                <div class="diff-head">变更后</div>
                <pre v-if="row.after" class="diff-json mono">{{ prettyJson(row.after) }}</pre>
                <p v-else class="weak small diff-none">无（删除对象）</p>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="时间" width="150">
          <template #default="{ row }">
            <span class="mono weak">{{ formatDateTime(row.createdAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="actor" label="操作人" width="100" />
        <el-table-column label="动作" width="140">
          <template #default="{ row }">
            <el-tag size="small" effect="plain" type="info" :title="row.action">
              {{ row.actionLabel }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="对象" min-width="200">
          <template #default="{ row }">
            <div class="object-cell">
              <span class="object-name">{{ row.objectLabel || row.objectId }}</span>
              <span class="weak small">
                {{ objectTypeLabel(row.objectType) }} ·
                <span class="mono">{{ row.objectId }}</span>
              </span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="摘要" min-width="250">
          <template #default="{ row }"><span class="summary-cell">{{ row.summary }}</span></template>
        </el-table-column>
        <el-table-column label="原因" min-width="150">
          <template #default="{ row }">
            <span v-if="row.reason">{{ row.reason }}</span>
            <span v-else class="weak">—</span>
          </template>
        </el-table-column>
        <el-table-column label="traceId" width="110">
          <template #default="{ row }">
            <span
              v-if="row.traceId"
              class="mono weak trace-id"
              :title="row.traceId"
            >{{ shortTraceId(row.traceId) }}</span>
            <span v-else class="weak">—</span>
          </template>
        </el-table-column>
      </el-table>

      <EmptyState
        v-else-if="!loading"
        title="没有符合条件的审计记录"
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

    <p class="weak small footnote">
      导出沿用当前筛选条件，最多 5000 行；<code class="mono">before/after</code> 只保存变化的字段，
      展开行可见前后对比，不是整行快照。
    </p>
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
.filter-select {
  width: 160px;
}
.filter-id {
  width: 140px;
}
/*
 * The picker's root is rendered by ElTooltip and does not carry this component's scope id, so the
 * width has to come from a scoped wrapper. Element Plus also gives the range editor
 * `flex: 1 1 auto`; without `flex: none` it grows and pushes 重置 onto a second row at 1440.
 */
.filter-range-wrap {
  width: 340px;
  flex: 0 0 340px;
}
.filter-range-wrap :deep(.el-date-editor) {
  width: 100%;
  flex: none;
}
.object-cell {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}
.object-name {
  line-height: 1.45;
}
.summary-cell {
  line-height: 1.5;
}
/* The hover hint is the full trace id, so the cursor should say so. */
.trace-id {
  cursor: help;
}
.diff {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
  padding: 4px 16px 14px 48px;
}
.diff-col {
  min-width: 0;
}
.diff-head {
  margin-bottom: 6px;
  font-size: 11px;
  color: var(--text-3);
}
.diff-json {
  margin: 0;
  padding: 10px 12px;
  max-height: 180px;
  overflow: auto;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--surface-3);
  color: var(--text-2);
  font-size: 11px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}
.diff-none {
  margin: 0;
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
.footnote {
  margin: 16px 2px 0;
  line-height: 1.7;
}
.footnote code {
  color: var(--text-2);
}
@media (max-width: 960px) {
  .filter-select,
  .filter-id,
  .filter-range-wrap {
    width: 100%;
  }
  .filter-range-wrap {
    flex: 1 1 100%;
  }
  .diff {
    grid-template-columns: 1fr;
    padding-left: 16px;
  }
}
</style>