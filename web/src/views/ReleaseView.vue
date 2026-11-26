<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  emergencyRelease,
  exitRelease,
  getAdmission,
  getItemGate,
  getMe,
  getRelease,
  getReleaseRecords,
  getTaskLogs,
  getUnit,
  redeployTask,
  resumeRelease,
  stopRelease
} from '../api'
import type {
  AdmissionView,
  GateCheck,
  ItemGate,
  MeView,
  ReleaseDetail,
  ReleasePersonView,
  ReleaseRecord,
  ReleaseTask,
  UnitView
} from '../api/types'
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
import ReasonDialog from '../components/ReasonDialog.vue'
import {
  formatDateTime,
  formatDuration,
  isStoppableItemStatus,
  isTerminalItemStatus,
  shortSha,
  statusLabel
} from '../utils/format'

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
const admission = ref<AdmissionView | null>(null)
const admissionLoaded = ref(false)
const stopOpen = ref(false)
const exitOpen = ref(false)
const controlBusy = ref(false)
const emergencyOpen = ref(false)
const emergencyBusy = ref(false)
let timer: number | undefined

// ---- second-party library (P6-A) ------------------------------------------
// A LIB release has a three-node pipeline (BUILD → LIB_SECURITY → LIB_PUBLISH) and no production
// gate, testers, image or rollback. The unit is fetched only for a LIB to show its coordinates;
// the node types themselves already identify the pipeline (they exist even while PENDING).
const unitType = ref<string | null>(null)
const libUnit = ref<UnitView | null>(null)
const libGate = ref<ItemGate | null>(null)

const nodesAreLib = computed(() =>
  (detail.value?.nodes ?? []).some(
    (node) => node.nodeType === 'LIB_SECURITY' || node.nodeType === 'LIB_PUBLISH'
  )
)
const isLib = computed(() => unitType.value === 'LIB' || nodesAreLib.value)

/** The three artifact checks of LIB_SECURITY; the other gate checks do not apply to a library. */
const LIB_CHECK_KINDS = ['SONAR', 'COMPONENT_VULN', 'VERSION_DEFECT']

const libChecks = computed<GateCheck[]>(() =>
  (libGate.value?.groups ?? [])
    .flatMap((group) => group.checks)
    .filter((check) => LIB_CHECK_KINDS.includes(check.kind))
)

// A check row stays FAIL when waived (the backend has no WAIVED check status); the approved waiver
// lifts the aggregate conclusion to WAIVED, which is what the 豁免入库 marker reports. The
// conclusion is the exact predicate: an active waiver that no longer matches the snapshot does not
// count, so `waiver.active` alone would over-report.
const libWaived = computed(() => libGate.value?.conclusion === 'WAIVED')
const libWaiver = computed(() => libGate.value?.waiver ?? null)

const libCoordinateText = computed(() => {
  const group = libUnit.value?.artifactGroupId
  const artifact = libUnit.value?.artifactArtifactId
  return group && artifact ? `${group}:${artifact}` : '—'
})

/** Gate status words to the shared status dot vocabulary (PASS/FAIL are not item statuses). */
function libCheckDotStatus(status: string): string {
  if (status === 'PASS') return 'SUCCESS'
  if (status === 'FAIL') return 'FAILED'
  if (status === 'WAIVED') return 'PAUSED'
  if (status === 'RUNNING') return 'RUNNING'
  return 'PENDING'
}

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

/**
 * The 变更项 status drives the two stop-loss doors (design §1); the instance status does not (a
 * PAUSED instance is still PROD_DEPLOYING on the item). `GET .../admission` answers `itemStatus`
 * even when the release was never submitted, so this is the one source for the header.
 */
const itemStatus = computed(() => admission.value?.itemStatus ?? null)

const hasPerm = (perm: string) => me.value?.perms.includes(perm) ?? false

// ---- release people (P6-F design §2) ---------------------------------------
// Read-only member lists of the item's task: role TEST → 测试人员, role OPS → 部署人员. They say
// nothing about permissions; the buttons above still gate on `release:*` / `control:*`.
function peopleText(people?: ReleasePersonView[] | null): string {
  if (!people?.length) return '—'
  return people.map((person) => person.name || person.userId).join('、')
}

function peopleTitle(people?: ReleasePersonView[] | null): string {
  return people?.length ? people.map((person) => person.userId).join('、') : '任务成员中没有该角色'
}

/** Why 停止发布 is unavailable; empty when enabled. */
const stopBlock = computed(() => {
  if (!meLoaded.value) return '正在读取权限'
  if (!me.value) return '无法读取当前身份（GET /me）'
  if (!hasPerm('control:execute-prod')) {
    return '只有运维可以停止生产发布（control:execute-prod）'
  }
  if (!admissionLoaded.value) return '正在读取变更项状态'
  if (!itemStatus.value) return '无法读取变更项状态（GET admission）'
  if (!isStoppableItemStatus(itemStatus.value)) {
    return `当前状态「${statusLabel(itemStatus.value)}」不在生产流程中，停止只适用于生产流程；请使用「退出流水线」`
  }
  return ''
})

/** Why 退出流水线 is unavailable; empty when enabled. */
const exitBlock = computed(() => {
  if (!meLoaded.value) return '正在读取权限'
  if (!me.value) return '无法读取当前身份（GET /me）'
  if (!hasPerm('release:publish') && !hasPerm('control:execute-prod')) {
    return '退出流水线需要 release:publish 或 control:execute-prod'
  }
  if (!admissionLoaded.value) return '正在读取变更项状态'
  if (!itemStatus.value) return '无法读取变更项状态（GET admission）'
  if (isTerminalItemStatus(itemStatus.value)) {
    return `变更项已是终态（${statusLabel(itemStatus.value)}），不能退出`
  }
  return ''
})

const busy = computed(() => {
  const s = detail.value?.status
  return s === 'RUNNING' || s === 'PENDING'
})

// ------------------------------------------------------- emergency release (P6-D)
// J11 is narrower than any permission point: only the ADMIN role may use it (design §1/§3), so the
// button is absent — not disabled — for everyone else. A caller whose status is not eligible still
// sees the door with the reason in its title, because a missing button reads as "no such feature".
const isAdmin = computed(() => me.value?.roles.includes('ADMIN') ?? false)
const isEmergency = computed(() => detail.value?.emergencyRelease === 'true')

const EMERGENCY_ITEM_STATUSES = new Set(['SUBMITTED', 'PENDING_APPROVAL'])

/** Why 紧急放行 is unavailable; empty when the status allows it. */
const emergencyBlock = computed(() => {
  if (!meLoaded.value) return '正在读取权限'
  if (!me.value) return '无法读取当前身份（GET /me）'
  if (!admissionLoaded.value) return '正在读取变更项状态'
  if (!itemStatus.value) return '无法读取变更项状态（GET admission）'
  if (isEmergency.value) return '该发布单已紧急放行；不可撤销，再次发布请走重跑'
  if (itemStatus.value === 'READY') return '已经是待执行状态，无需紧急放行'
  if (!EMERGENCY_ITEM_STATUSES.has(itemStatus.value)) {
    return `当前状态「${statusLabel(itemStatus.value)}」不可紧急放行；仅「已提交」「待审批」可用，其它状态请先正常提交或重跑`
  }
  return ''
})

async function onEmergency(reason: string) {
  emergencyBusy.value = true
  try {
    const result = await emergencyRelease(publishNo, reason)
    ElMessage.warning(
      `已紧急放行：变更项 ${statusLabel(result.itemStatus)}，审批链已作废；执行时门禁、镜像与豁免仍复核`
    )
    emergencyOpen.value = false
    await load(true)
  } catch (e) {
    // A 40001 means the item moved while the dialog was open: close it and re-read the status, like
    // the other stop-loss actions, so the stale action is not left clickable.
    if (e instanceof ApiError && e.code === 40001) {
      ElMessage.error(e.message)
      emergencyOpen.value = false
      await load(true)
    } else {
      ElMessage.error(e instanceof ApiError ? e.message : '紧急放行失败')
    }
  } finally {
    emergencyBusy.value = false
  }
}

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
    const [d, r, a] = await Promise.all([
      getRelease(publishNo),
      getReleaseRecords(publishNo).catch(() => []),
      getAdmission(publishNo).catch(() => null)
    ])
    detail.value = d
    records.value = r
    admission.value = a
    // LIB-only read models: the artifact coordinates of the unit and the three security checks.
    // Both are best-effort — the node list alone still proves the pipeline shape.
    if (nodesAreLib.value) {
      const [unit, gate] = await Promise.all([
        d.unitId ? getUnit(d.unitId).catch(() => null) : Promise.resolve(null),
        d.itemId ? getItemGate(d.itemId).catch(() => null) : Promise.resolve(null)
      ])
      libUnit.value = unit
      unitType.value = unit?.type ?? null
      libGate.value = gate
    } else {
      libUnit.value = null
      unitType.value = null
      libGate.value = null
    }
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '加载失败'
  } finally {
    admissionLoaded.value = true
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

/**
 * Shared stop/exit submit. A 40001 means the item moved while the dialog was open, so the page
 * closes the dialog and re-reads the status instead of leaving a stale action clickable; the server
 * sentence (which names `currentStatus`) is shown as-is.
 */
async function submitControl(kind: 'stop' | 'exit', reason: string) {
  controlBusy.value = true
  try {
    const result = kind === 'stop'
      ? await stopRelease(publishNo, reason)
      : await exitRelease(publishNo, reason)
    const terminated = result.terminatedPipelineNo
      ? `，已终止实例 ${result.terminatedPipelineNo}`
      : '，没有在途实例'
    if (kind === 'stop') {
      ElMessage.success(`已停止发布：变更项 ${statusLabel(result.itemStatus)}${terminated}；已上线批次保留`)
    } else {
      ElMessage.success(`已退出流水线：变更项 ${statusLabel(result.itemStatus)}${terminated}`)
    }
    stopOpen.value = false
    exitOpen.value = false
    await load(true)
  } catch (e) {
    if (e instanceof ApiError && e.code === 40001) {
      ElMessage.error(e.message)
      stopOpen.value = false
      exitOpen.value = false
      await load(true)
    } else {
      ElMessage.error(e instanceof ApiError ? e.message : kind === 'stop' ? '停止失败' : '退出失败')
    }
  } finally {
    controlBusy.value = false
  }
}

const onStop = (reason: string) => submitControl('stop', reason)
const onExit = (reason: string) => submitControl('exit', reason)

// ------------------------------------------------------- task actions (P6-G)
// 生产部署任务行 offers 重新部署 (a failed DE's whole batch is retried on a new
// RERUN_FROM_NODE instance that reuses the previous image); every engine job row offers the log
// proxy. Both server actions address the node_task id, which the row carries since P6-G.
const RETRYABLE_TASK_STATUS = new Set(['FAILED', 'TIMEOUT'])
const REDEPLOYABLE_ITEM_STATUS = new Set(['FAILED', 'PROD_DEPLOYING'])
const LOGGABLE_TASK_TYPES = new Set(['CODE_CHECK', 'PKG_BUILD', 'IMG_BUILD', 'DEPLOY', 'LIB_PUBLISH'])

function isRedeployRow(nodeType: string, task: ReleaseTask): boolean {
  return nodeType === 'PROD_DEPLOY' && task.taskType === 'DEPLOY'
}

/** Why 重新部署 is unavailable on this task row; empty when enabled. */
function redeployBlock(task: ReleaseTask): string {
  if (!meLoaded.value) return '正在读取权限'
  if (!me.value) return '无法读取当前身份（GET /me）'
  if (!hasPerm('control:execute-prod')) {
    return '只有运维可以重新部署生产任务（control:execute-prod）'
  }
  if (!RETRYABLE_TASK_STATUS.has(task.status)) {
    return '只有失败或超时的任务可以重新部署；成功批次请走重跑或回滚'
  }
  if (!admissionLoaded.value || !itemStatus.value) return '正在读取变更项状态'
  if (!REDEPLOYABLE_ITEM_STATUS.has(itemStatus.value)) {
    return `变更项当前状态「${statusLabel(itemStatus.value)}」不允许重新部署`
  }
  return ''
}

const redeployBusy = ref(false)

async function onRedeploy(task: ReleaseTask) {
  const target = task.targetId ? `#${task.targetId}` : ''
  try {
    await ElMessageBox.confirm(
      `将重新部署批次 ${task.batchNo}（目标 DE ${target}）：同批次 DE 一起重试，其余批次跳过；沿用上一实例镜像，不重新构建；未完成检查将失效，需要重新检查。`,
      '单 DE 重新部署',
      { type: 'warning', confirmButtonText: '重新部署', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  redeployBusy.value = true
  try {
    const result = await redeployTask(publishNo, task.id)
    ElMessage.success(
      `已创建重跑实例 ${result.pipelineNo}：批次 ${result.batchNo} 整批重试（目标 ${result.targetDeName}）`
    )
    await load(true)
  } catch (e) {
    if (e instanceof ApiError && e.code === 40001) {
      ElMessage.error(e.message)
      await load(true)
    } else {
      ElMessage.error(e instanceof ApiError ? e.message : '重新部署失败')
    }
  } finally {
    redeployBusy.value = false
  }
}

// ---- the task-log drawer: one engine page at a time, 加载更多 follows `truncated`.
const LOG_PAGE = 4000
const logOpen = ref(false)
const logTask = ref<ReleaseTask | null>(null)
const logText = ref('')
const logOffset = ref(0)
const logTruncated = ref(false)
const logLoading = ref(false)
const logError = ref<string | null>(null)

function openLog(task: ReleaseTask) {
  logTask.value = task
  logOpen.value = true
  loadLog(true)
}

async function loadLog(reset: boolean) {
  if (!logTask.value) return
  if (reset) {
    logText.value = ''
    logOffset.value = 0
    logTruncated.value = false
    logError.value = null
  }
  logLoading.value = true
  try {
    const page = await getTaskLogs(publishNo, logTask.value.id, logOffset.value, LOG_PAGE)
    logText.value += page.content
    logOffset.value = page.offset + page.limit
    logTruncated.value = page.truncated
  } catch (e) {
    logError.value = e instanceof ApiError ? e.message : '读取日志失败'
    logTruncated.value = false
  } finally {
    logLoading.value = false
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
        v-if="isAdmin"
        class="emergency-release"
        type="danger"
        :disabled="!!emergencyBlock || emergencyBusy"
        :title="emergencyBlock || '仅管理员：作废待签审批并置为「待执行」；不绕过门禁、镜像与豁免复核'"
        @click="emergencyOpen = true"
      >
        紧急放行
      </el-button>
      <el-button
        v-if="!isLib"
        class="stop-release"
        type="danger"
        plain
        :disabled="!!stopBlock || controlBusy"
        :title="stopBlock || '终止在途生产流水线；已上线批次保留，不回滚'"
        @click="stopOpen = true"
      >
        停止发布
      </el-button>
      <el-button
        class="exit-release"
        :disabled="!!exitBlock || controlBusy"
        :title="exitBlock || '退出任意非终态流水线，作废待签审批与未完成检查'"
        @click="exitOpen = true"
      >
        退出流水线
      </el-button>
      <el-button
        v-if="detail?.status === 'PAUSED' && !isProdPause"
        :title="`当前挂起原因：${gateStatusHint}`"
        @click="doResume"
      >
        恢复执行
      </el-button>
      <el-button :loading="loading" @click="load()">刷新</el-button>
      <el-button
        v-if="!isLib"
        :disabled="!!rollbackBlock"
        :title="rollbackBlock || '按部署单元回滚生产镜像'"
        @click="rollbackOpen = true"
      >
        进入生产回滚
      </el-button>
    </PageHeader>

    <!-- J11 trail: the release page must keep saying that the approvals no longer exist (P6-D §4). -->
    <div v-if="isEmergency && detail" class="emergency-banner" role="alert">
      <span class="emergency-badge">已紧急放行</span>
      <span class="emergency-item">
        <span class="weak small">理由</span>
        <span class="strong">{{ detail.emergencyReason || '—' }}</span>
      </span>
      <span class="emergency-item">
        <span class="weak small">操作人</span>
        <span class="mono">{{ detail.emergencyBy || '—' }}</span>
      </span>
      <span class="emergency-item">
        <span class="weak small">时间</span>
        <span class="mono">{{ formatDateTime(detail.emergencyAt) }}</span>
      </span>
    </div>

    <el-alert v-if="error" type="error" :closable="false" show-icon :title="error" style="margin-bottom: 16px" />

    <el-tabs v-model="tab">
      <el-tab-pane label="发布内容" name="content">
        <div v-if="detail" class="stack" :class="{ 'lib-release': isLib }">
          <section class="panel panel-pad">
            <StepBar :nodes="detail.nodes" />
            <div class="summary">
              <div class="summary-item">
                <span class="weak small">实例状态</span>
                <StatusDot :status="detail.status" pulse />
              </div>
              <div class="summary-item">
                <span class="weak small">变更项状态</span>
                <StatusDot :status="itemStatus" pulse />
              </div>
              <div class="summary-item">
                <span class="weak small">触发方式</span>
                <span class="small">{{ statusLabel(detail.triggerType) }}</span>
              </div>
              <div class="summary-item release-testers">
                <span class="weak small">测试人员</span>
                <span class="small" :title="peopleTitle(detail.testers)">{{ peopleText(detail.testers) }}</span>
              </div>
              <div class="summary-item release-deployers">
                <span class="weak small">部署人员</span>
                <span class="small" :title="peopleTitle(detail.deployers)">{{ peopleText(detail.deployers) }}</span>
              </div>
              <template v-if="isLib">
                <div class="summary-item lib-kind">
                  <span class="weak small">发布类型</span>
                  <span class="small">二方库 · 制品发布</span>
                </div>
                <div class="summary-item lib-coordinates-summary">
                  <span class="weak small">制品坐标</span>
                  <span class="mono">{{ libCoordinateText }}</span>
                </div>
              </template>
              <template v-else>
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
              </template>
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
              <el-tag
                v-if="isLib && node.nodeType === 'LIB_SECURITY' && libWaived"
                class="lib-waiver"
                size="small"
                type="warning"
                effect="plain"
                :title="libWaiver?.expireAt ? `豁免到期 ${formatDateTime(libWaiver.expireAt)}；到期不自动下架，仅标注` : '存在已批准的安全检查豁免'"
              >
                豁免入库
              </el-tag>
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
                :key="isEmergency ? 'admission-emergency' : 'admission-normal'"
                :publish-no="publishNo"
                :running="busy || detail.status === 'PAUSED'"
                :paused="detail.status === 'PAUSED'"
                :rerun="isProductionRerun"
                :emergency="isEmergency"
                @changed="load(true)"
              />
            </div>
            <div v-else-if="node.nodeType === 'LIB_SECURITY'" class="lib-checks">
              <div class="lib-check-head">
                <span class="weak small">制品安全检测：三条检查通过或豁免后才允许入库</span>
                <span class="weak small mono">gate_check</span>
              </div>
              <div v-if="libChecks.length" class="lib-check-list">
                <div v-for="check in libChecks" :key="check.kind" class="lib-check-row">
                  <StatusDot :status="libCheckDotStatus(check.status)" :label="check.statusLabel" />
                  <span class="lib-check-label">{{ check.label }}</span>
                  <span class="weak small mono">{{ check.kind }}</span>
                  <span v-if="libWaived && check.status === 'FAIL'" class="weak small lib-waived-check">已豁免</span>
                  <span class="topbar-spacer" />
                  <span class="weak small">{{ check.severityLabel }}</span>
                  <span v-if="check.summary" class="lib-check-summary truncate" :title="check.summary">
                    {{ check.summary }}
                  </span>
                  <a v-if="check.reportUrl" :href="check.reportUrl" target="_blank" rel="noreferrer" class="small">
                    报告
                  </a>
                </div>
              </div>
              <EmptyState
                v-else
                title="安全检查尚未回传"
                hint="BUILD 完成后进入本节点，三项制品检查结果会显示在这里。"
              />
              <p v-if="libWaived" class="lib-waiver-note small">
                存在有效豁免（豁免入库），允许制品入库；豁免到期不自动下架，只在此标注。
                <span v-if="libWaiver?.expireAt" class="weak">到期 {{ formatDateTime(libWaiver.expireAt) }}</span>
              </p>
            </div>
            <!-- P6-G: the engine task rows are no longer hidden behind the node panels — the
                 production node renders its AdmissionPanel AND its deployment task rows (the
                 重新部署/日志 actions live on those rows). VERIFY/LIB_SECURITY keep their own
                 panels instead of raw task rows. -->
            <div
              v-if="node.nodeType !== 'VERIFY' && node.nodeType !== 'LIB_SECURITY' && node.tasks.length"
              :class="node.status !== 'PENDING' ? 'task-list' : 'task-pending'"
            >
              <template v-if="node.status !== 'PENDING'">
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
                  <el-button
                    v-if="isRedeployRow(node.nodeType, t)"
                    class="task-action"
                    link
                    type="primary"
                    size="small"
                    :disabled="!!redeployBlock(t) || redeployBusy"
                    :title="redeployBlock(t) || '只重试该任务所在批次，沿用镜像；未完成检查将失效'"
                    @click="onRedeploy(t)"
                  >
                    重新部署
                  </el-button>
                  <el-button
                    v-if="LOGGABLE_TASK_TYPES.has(t.taskType)"
                    class="task-action"
                    link
                    size="small"
                    title="读取引擎侧的任务执行日志（仅最新实例）"
                    @click="openLog(t)"
                  >
                    日志
                  </el-button>
                </div>
              </template>
              <template v-else>
                <span class="weak small">{{ node.tasks.length }} 项任务</span>
                <span class="weak small">等待执行</span>
              </template>
            </div>
            <EmptyState
              v-else-if="node.nodeType !== 'VERIFY' && node.nodeType !== 'LIB_SECURITY'"
              title="该节点没有任务"
              hint="空节点的实例不会自动完成。"
            />
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
        <template v-if="!isLib">
          <KeyValue label="重跑起点" :value="detail.rerunFromNode ? statusLabel(detail.rerunFromNode) : null" />
          <KeyValue label="全量重跑" :value="detail.fullRerun ? '是' : '否'" />
        </template>
      </div>
      <p v-if="isLib" class="weak small" style="margin: 12px 0 0">
        二方库流水线：构建（CODE_CHECK → PKG_BUILD，不产镜像）→ 安全检测（SONAR / 组件漏洞 / 版本缺陷）
        → 入库（制品上传）。不进入生产准入，也没有测试部署、生产部署与回滚。
        没有配置 CODING 连接时，构建会以 <code class="mono">ENGINE_UNAVAILABLE</code> 失败，这是预期结果。
      </p>
      <p v-else class="weak small" style="margin: 12px 0 0">
        没有配置 CODING 连接时，构建会以 <code class="mono">ENGINE_UNAVAILABLE</code> 失败，这是预期结果。
        接入真实 CODING 后，流水线会继续构建与测试部署，进入「验证确认」：七项检查回传、测试人员判定，
        两道门禁同时满足才允许提交生产；生产部署由 P3 交付，所以当前会停在
        <code class="mono">PROD_DEPLOY / PAUSED</code>。
      </p>
    </div>

    <ReasonDialog
      v-model="emergencyOpen"
      title="紧急放行"
      :message="`将紧急放行发布单 ${publishNo}：作废审批链上全部待签步骤（留痕「紧急放行：理由」），变更项置为「待执行」。`"
      warning="仅管理员可用且不可撤销：会写红色标识、审计与通知。执行时只跳过审批与封板等待，门禁检查、镜像一致性与豁免有效期仍会复核。"
      confirm-text="确认紧急放行"
      placeholder="说明为什么紧急放行，例如：线上支付故障，需立即修复上线"
      danger
      warning-type="error"
      :busy="emergencyBusy"
      @confirm="onEmergency"
    />

    <ReasonDialog
      v-model="stopOpen"
      title="停止发布"
      :message="`将停止发布单 ${publishNo}：终止在途流水线与后续批次，变更项置为「已终止」，待签审批作废。`"
      warning="已上线（成功）批次保留，不回滚；镜像回滚是独立的运维动作。"
      confirm-text="停止发布"
      placeholder="说明为什么停止，例如：生产批次 2 发现接口 5xx 上升"
      danger
      :busy="controlBusy"
      @confirm="onStop"
    />

    <ReasonDialog
      v-model="exitOpen"
      title="退出流水线"
      :message="`将退出发布单 ${publishNo} 的流水线：终止在途实例，变更项置为「已终止」，未完成检查置为失效，待签审批作废。`"
      warning="已上线批次保留；退出后该发布单不可恢复执行，如需回退请使用生产回滚。"
      confirm-text="退出流水线"
      placeholder="说明为什么退出，例如：需求取消，不再继续本次发布"
      :busy="controlBusy"
      @confirm="onExit"
    />

    <RollbackDialog
      v-model="rollbackOpen"
      :unit-id="detail?.unitId ?? null"
      :publish-no="publishNo"
      @changed="load(true)"
    />

    <!-- P6-G: the engine log proxy drawer. One page at a time; 加载更多 only while truncated. -->
    <el-drawer v-model="logOpen" class="log-drawer" size="640px" :close-on-click-modal="false">
      <template #header>
        <div class="log-head">
          <span class="log-title">任务日志</span>
          <span class="weak small mono">
            {{ logTask?.taskType }}{{ logTask?.batchNo ? ` · 批次 ${logTask.batchNo}` : '' }}{{ logTask?.targetId ? ` · #${logTask.targetId}` : '' }}
          </span>
        </div>
      </template>
      <div v-loading="logLoading" class="log-body-wrap">
        <el-alert v-if="logError" type="error" :closable="false" show-icon :title="logError" />
        <pre v-else class="log-body">{{ logText || '（暂无日志内容）' }}</pre>
        <div class="log-foot">
          <el-button size="small" :disabled="logLoading" @click="loadLog(true)">刷新</el-button>
          <el-button
            size="small"
            :disabled="!logTruncated || logLoading"
            title="只读下一页：引擎侧按 offset/limit 追加"
            @click="loadLog(false)"
          >
            加载更多
          </el-button>
          <span class="weak small">{{ logTruncated ? '可能还有更多内容' : '已到末尾' }}</span>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
/* J11: the permanent red trail of an emergency release. Only the existing --danger tokens. */
.emergency-banner {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 10px 22px;
  margin-bottom: 16px;
  padding: 12px 16px;
  border: 1px solid color-mix(in srgb, var(--danger) 40%, var(--border));
  border-left: 3px solid var(--danger);
  border-radius: var(--radius);
  background: var(--danger-weak);
}
.emergency-badge {
  padding: 1px 8px;
  border-radius: 999px;
  background: var(--danger);
  color: var(--bg);
  font-size: 12px;
  font-weight: 600;
  line-height: 20px;
}
.emergency-item {
  display: inline-flex;
  align-items: baseline;
  gap: 6px;
  font-size: 13px;
  color: var(--danger);
  min-width: 0;
}
.emergency-item .strong {
  color: var(--danger);
}
.emergency-item .weak {
  color: var(--danger);
  opacity: 0.75;
}

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

/* LIB release (P6-A): the security node's three checks and the waiver marker. */
.lib-checks {
  padding: 6px 18px 14px;
}
.lib-check-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 4px 0 6px;
}
.lib-check-list {
  display: flex;
  flex-direction: column;
}
.lib-check-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 0;
  border-bottom: 1px dashed var(--border);
  font-size: 13px;
}
.lib-check-row:last-child {
  border-bottom: none;
}
.lib-check-label {
  color: var(--text);
  min-width: 96px;
}
.lib-check-summary {
  max-width: 320px;
  color: var(--text-3);
  font-size: 11px;
}
.lib-waived-check {
  color: var(--warn);
}
.lib-waiver-note {
  margin: 10px 0 0;
  color: var(--warn);
  line-height: 1.6;
}

/* P6-G: the engine log drawer and the task-row actions. */
.log-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
}
.log-title {
  font-weight: 600;
}
.log-body-wrap {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 100%;
}
.log-body {
  margin: 0;
  padding: 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface-3);
  color: var(--text);
  font-family: ui-monospace, Consolas, monospace;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
  overflow: auto;
  max-height: 70vh;
}
.log-foot {
  display: flex;
  align-items: center;
  gap: 10px;
}
.task-action {
  padding: 0;
}
</style>