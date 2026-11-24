<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getMe, getRelease, getReleaseRecords, resumeRelease } from '../api'
import type { MeView, ReleaseDetail, ReleaseRecord } from '../api/types'
import { ApiError } from '../api/http'
import PageHeader from '../components/PageHeader.vue'
import DataSection from '../components/DataSection.vue'
import EmptyState from '../components/EmptyState.vue'
import StatusDot from '../components/StatusDot.vue'
import StepBar from '../components/StepBar.vue'
import KeyValue from '../components/KeyValue.vue'
import VerifyPanel from '../components/VerifyPanel.vue'
import AdmissionPanel from '../components/AdmissionPanel.vue'
import RollbackDialog from '../components/RollbackDialog.vue'
import { formatDateTime, formatDuration, shortSha, statusLabel } from '../utils/format'

const route = useRoute()
const publishNo = String(route.params.publishNo)

const detail = ref<ReleaseDetail | null>(null)
const records = ref<ReleaseRecord[]>([])
const loading = ref(true)
const error = ref<string | null>(null)
const tab = ref<'content' | 'records'>('content')
const me = ref<MeView | null>(null)
const meLoaded = ref(false)
const rollbackOpen = ref(false)
let timer: number | undefined

/**
 * The rollback entry is a header door, always visible: a caller without `rollback:execute` (or a
 * release without a unit) sees why it cannot open instead of a missing button.
 */
const rollbackBlock = computed(() => {
  if (!meLoaded.value) return '正在读取权限'
  if (!me.value) return '无法读取当前身份（GET /me）'
  if (!me.value.perms.includes('rollback:execute')) {
    return '只有运维可以执行生产回滚（rollback:execute）'
  }
  if (!detail.value) return '发布单尚未加载'
  if (!detail.value.unitId) return '该发布单没有关联部署单元，暂不可回滚'
  return ''
})

const busy = computed(() => {
  const s = detail.value?.status
  return s === 'RUNNING' || s === 'PENDING'
})

const gateStatusHint = computed(() => detail.value?.statusReason ?? '（未记录原因）')

/** A pause at PROD_DEPLOY is resumed by 「执行生产发布」 (with the review), not by the generic resume. */
const isProdPause = computed(() => {
  const prod = detail.value?.nodes.find((n) => n.nodeType === 'PROD_DEPLOY')
  return detail.value?.status === 'PAUSED' && prod?.status === 'RUNNING'
})

/**
 * A rerun that resumes at PROD_DEPLOY keeps the item TESTING and reuses the admission; the execute
 * endpoint starts it with EXECUTE_RERUN. The panel needs this to offer「执行生产重跑」instead of
 * claiming TESTING is not executable (independent review I1).
 */
const isProductionRerun = computed(() => {
  const trigger = detail.value?.triggerType ?? ''
  return isProdPause.value && (trigger === 'RERUN_FROM_NODE' || trigger === 'RERUN_FULL')
})

async function load(silent = false) {
  if (!silent) loading.value = true
  error.value = null
  try {
    const [d, r] = await Promise.all([getRelease(publishNo), getReleaseRecords(publishNo).catch(() => [])])
    detail.value = d
    records.value = r
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

async function doResume() {
  try {
    const result = await resumeRelease(publishNo)
    ElMessage.success(`已恢复，从 ${statusLabel(result.resumeNode)} 继续`)
    await load(true)
  } catch (e) {
    ElMessage.warning(e instanceof ApiError ? e.message : '恢复失败')
  }
}

function schedule() {
  if (timer) window.clearInterval(timer)
  if (busy.value) {
    timer = window.setInterval(() => load(true), 5000)
  }
}

onMounted(async () => {
  await load()
  schedule()
  getMe()
    .then((view) => {
      me.value = view
    })
    .catch(() => {
      me.value = null
    })
    .finally(() => {
      meLoaded.value = true
    })
})
onBeforeUnmount(() => timer && window.clearInterval(timer))
</script>

<template>
  <div>
    <PageHeader :title="`发布内容 ${publishNo}`" :description="detail ? `流水单 ${detail.pipelineNo}` : ''">
      <el-button
        v-if="detail?.status === 'PAUSED' && !isProdPause"
        :title="`当前挂起原因：${gateStatusHint}`"
        @click="doResume"
      >
        恢复执行
      </el-button>
      <el-button :loading="loading" @click="load()">刷新</el-button>
      <el-button
        :disabled="!!rollbackBlock"
        :title="rollbackBlock || '按部署单元回滚生产镜像'"
        @click="rollbackOpen = true"
      >
        进入生产回滚
      </el-button>
    </PageHeader>

    <el-alert v-if="error" type="error" :closable="false" show-icon :title="error" style="margin-bottom: 16px" />

    <el-tabs v-model="tab">
      <el-tab-pane label="发布内容" name="content">
        <div v-if="detail" class="stack">
          <section class="panel panel-pad">
            <StepBar :nodes="detail.nodes" />
            <div class="summary">
              <div class="summary-item">
                <span class="weak small">实例状态</span>
                <StatusDot :status="detail.status" pulse />
              </div>
              <div class="summary-item">
                <span class="weak small">触发方式</span>
                <span class="small">{{ statusLabel(detail.triggerType) }}</span>
              </div>
              <div class="summary-item">
                <span class="weak small">门禁（检查）</span>
                <StatusDot
                  :status="detail.gateStatus === 'CHECK_FAIL' || detail.gateStatus === 'CHECK_INVALID' ? 'FAILED' : detail.gateStatus === 'CHECK_RUNNING' || !detail.gateStatus ? 'PENDING' : 'SUCCESS'"
                  :pulse="detail.gateStatus === 'CHECK_RUNNING'"
                />
              </div>
              <div class="summary-item">
                <span class="weak small">门禁（测试）</span>
                <span class="small">{{ statusLabel(detail.testStatus ?? 'TEST_PENDING') }}</span>
              </div>
              <div class="summary-item">
                <span class="weak small">镜像摘要</span>
                <span class="mono">{{ shortSha(detail.imageDigest) }}</span>
              </div>
              <div class="summary-item">
                <span class="weak small">commit</span>
                <span class="mono">{{ shortSha(detail.commitSha) }}</span>
              </div>
            </div>
          </section>

          <section
            v-for="node in detail.nodes"
            :key="node.nodeType"
            class="panel"
          >
            <div class="panel-head">
              <span class="node-index mono">{{ String(node.seqNo + 1).padStart(2, '0') }}</span>
              <span class="panel-title">{{ statusLabel(node.nodeType) }}</span>
              <StatusDot :status="node.status" domain="node" pulse />
              <span class="topbar-spacer" />
              <span class="weak small mono">
                {{ formatDateTime(node.startedAt) }}
                <template v-if="node.finishedAt"> → {{ formatDateTime(node.finishedAt) }}</template>
              </span>
            </div>

            <div v-if="node.nodeType === 'VERIFY' && detail.itemId" class="verify-slot">
              <VerifyPanel :item-id="detail.itemId" :running="busy" @changed="load(true)" />
            </div>
            <div v-else-if="node.nodeType === 'PROD_DEPLOY' && detail.itemId" class="verify-slot">
              <AdmissionPanel
                :publish-no="publishNo"
                :running="busy || detail.status === 'PAUSED'"
                :paused="detail.status === 'PAUSED'"
                :rerun="isProductionRerun"
                @changed="load(true)"
              />
            </div>
            <div v-else-if="node.status !== 'PENDING' && node.tasks.length" class="task-list">
              <div v-for="(t, i) in node.tasks" :key="`${t.taskType}-${t.batchNo}-${t.targetId}-${i}`" class="task-row">
                <StatusDot :status="t.status" domain="task" :dot-only="false" />
                <span class="task-name mono">{{ t.taskType }}</span>
                <span class="weak small">{{ t.batchNo ? `批次 ${t.batchNo}` : '' }}</span>
                <span class="task-target mono weak small">
                  {{ t.targetId ? `#${t.targetId}` : '' }}
                </span>
                <span class="topbar-spacer" />
                <span class="weak small mono">{{ formatDuration(t.durationMs) }}</span>
                <span v-if="t.resultSummary" class="task-summary truncate" :title="t.resultSummary">
                  {{ t.resultSummary }}
                </span>
                <a v-if="t.externalUrl" :href="t.externalUrl" target="_blank" rel="noreferrer" class="small">
                  查看
                </a>
              </div>
            </div>
            <div v-else-if="node.tasks.length" class="task-pending">
              <span class="weak small">{{ node.tasks.length }} 项任务</span>
              <span class="weak small">等待执行</span>
            </div>
            <EmptyState v-else-if="node.nodeType !== 'VERIFY'" title="该节点没有任务" hint="空节点的实例不会自动完成。" />
          </section>
        </div>
        <div v-else-if="!loading" class="panel panel-pad">
          <EmptyState title="找不到发布单" :hint="`发布单 ${publishNo} 不存在或无权查看。`" />
        </div>
        <div v-else style="padding: 24px"><el-skeleton :rows="6" animated /></div>
      </el-tab-pane>

      <el-tab-pane label="发布记录" name="records">
        <DataSection title="执行记录" flush>
          <el-table v-if="records.length" :data="records" style="width: 100%">
            <el-table-column prop="pipelineNo" label="流水单号" min-width="200">
              <template #default="{ row }"><span class="mono">{{ row.pipelineNo }}</span></template>
            </el-table-column>
            <el-table-column label="触发" width="160">
              <template #default="{ row }">{{ statusLabel(row.triggerType) }}</template>
            </el-table-column>
            <el-table-column label="状态" width="140">
              <template #default="{ row }"><StatusDot :status="row.status" /></template>
            </el-table-column>
            <el-table-column label="开始" width="170">
              <template #default="{ row }"><span class="mono weak">{{ formatDateTime(row.startAt) }}</span></template>
            </el-table-column>
            <el-table-column label="结束" width="170">
              <template #default="{ row }"><span class="mono weak">{{ formatDateTime(row.endAt) }}</span></template>
            </el-table-column>
            <el-table-column prop="operator" label="执行人" width="120" />
          </el-table>
          <EmptyState v-else title="暂无执行记录" />
        </DataSection>
      </el-tab-pane>
    </el-tabs>

    <div v-if="detail" class="panel panel-pad" style="margin-top: 16px">
      <div class="grid grid-3">
        <KeyValue label="发布单号" :value="detail.publishNo" mono />
        <KeyValue label="流水单号" :value="detail.pipelineNo" mono />
        <KeyValue label="OA 工单" :value="detail.order?.oaTicketNo" mono />
        <KeyValue label="计划发布时间" :value="formatDateTime(detail.order?.plannedReleaseTime)" mono />
        <KeyValue label="重跑起点" :value="detail.rerunFromNode ? statusLabel(detail.rerunFromNode) : null" />
        <KeyValue label="全量重跑" :value="detail.fullRerun ? '是' : '否'" />
      </div>
      <p class="weak small" style="margin: 12px 0 0">
        没有配置 CODING 连接时，构建会以 <code class="mono">ENGINE_UNAVAILABLE</code> 失败，这是预期结果。
        接入真实 CODING 后，流水线会继续构建与测试部署，进入「验证确认」：七项检查回传、测试人员判定，
        两道门禁同时满足才允许提交生产；生产部署由 P3 交付，所以当前会停在
        <code class="mono">PROD_DEPLOY / PAUSED</code>。
      </p>
    </div>

    <RollbackDialog
      v-model="rollbackOpen"
      :unit-id="detail?.unitId ?? null"
      :publish-no="publishNo"
      @changed="load(true)"
    />
  </div>
</template>

<style scoped>
.summary {
  display: flex;
  flex-wrap: wrap;
  gap: 24px;
  padding-top: 12px;
  margin-top: 8px;
  border-top: 1px dashed var(--border);
}
.summary-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 120px;
}
.node-index {
  color: var(--accent);
  font-size: 12px;
}
.task-list {
  padding: 6px 18px 12px;
}
.task-pending {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 18px 14px;
}
.task-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 0;
  border-bottom: 1px dashed var(--border);
  font-size: 13px;
}
.task-row:last-child {
  border-bottom: none;
}
.task-name {
  color: var(--text);
  min-width: 96px;
}
.task-target {
  min-width: 40px;
}
.task-summary {
  max-width: 340px;
  color: var(--text-3);
  font-family: ui-monospace, Consolas, monospace;
  font-size: 11px;
}
</style>