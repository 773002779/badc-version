<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  approveRelease,
  checkAdmission,
  executeRelease,
  getAdmission,
  getMe,
  rejectRelease,
  resubmitProd,
  submitProd
} from '../api'
import type { AdmissionView, ReviewView } from '../api/types'
import { ApiError } from '../api/http'
import { formatDateTime, shortSha } from '../utils/format'

const props = defineProps<{
  publishNo: string
  running: boolean
  paused: boolean
}>()

const emit = defineEmits<{ (e: 'changed'): void }>()

const admission = ref<AdmissionView | null>(null)
const me = ref<{ perms: string[]; roles: string[]; userId: string } | null>(null)
const loading = ref(true)
const error = ref<string | null>(null)
const busy = ref(false)

const dialog = ref(false)
const submitting = ref(false)
const draft = reactive({
  planned: '' as string | Date,
  oa: '',
  steps: null as AdmissionView['steps'] | null,
  admitted: null as boolean | null,
  nextAction: '' as string | null
})
let draftTimer: number | undefined

const itemStatus = computed(() => admission.value?.itemStatus ?? 'GATE_PENDING')
const snapshot = computed(() => admission.value?.snapshot ?? null)
const chain = computed(() => admission.value?.chain ?? [])
const review = computed<ReviewView | null>(() => admission.value?.review ?? null)
const pendingStep = computed(() => chain.value.find((s) => s.status === 'PENDING') ?? null)

const canSubmit = computed(() => has('control:submit-prod'))
const canApprove = computed(() => has('control:approve'))
const canExecute = computed(() => has('control:execute-prod'))

const isSubmitter = computed(
  () => !!me.value && !!snapshot.value?.submittedBy && me.value.userId === snapshot.value.submittedBy
)

/** Why the approve button is disabled; empty when the caller may sign now. */
const approveBlock = computed(() => {
  if (!canApprove.value) return '缺少审批权限（control:approve）'
  const step = pendingStep.value
  if (!step) return '审批链已无待签步骤'
  if (isSubmitter.value) return '不能审批自己提交的发布（禁止自审）'
  if (!me.value?.roles.includes(step.role)) return `当前步骤需要「${step.roleLabel}」角色`
  return ''
})

const executeBlock = computed(() => {
  if (!canExecute.value) return '只有运维可以执行生产发布（control:execute-prod）'
  if (itemStatus.value === 'READY') return ''
  if (itemStatus.value === 'PROD_DEPLOYING' && props.paused) return ''
  if (itemStatus.value === 'PROD_DEPLOYING') return '生产部署进行中'
  return `当前状态「${itemStatus.value}」不可执行`
})

const showSubmit = computed(
  () => canSubmit.value && !snapshot.value && itemStatus.value === 'GATE_PENDING'
)
const showResubmit = computed(
  () => canSubmit.value && itemStatus.value === 'REJECTED' && !!snapshot.value
)
const showExecute = computed(
  () => !!snapshot.value && (itemStatus.value === 'READY' ||
    (itemStatus.value === 'PROD_DEPLOYING' && props.paused))
)

const statusChip = computed(() => {
  const status = itemStatus.value
  if (status === 'GATE_PENDING') return { label: '待提交', tone: 'neutral' }
  if (status === 'SUBMITTED') return { label: '准入判定中', tone: 'warn' }
  if (status === 'PENDING_APPROVAL') return { label: '待审批', tone: 'warn' }
  if (status === 'READY') return { label: '待执行', tone: 'accent' }
  if (status === 'PROD_DEPLOYING') return props.paused
    ? { label: '生产已暂停', tone: 'warn' }
    : { label: '生产部署中', tone: 'accent' }
  if (status === 'DONE') return { label: '已完成', tone: 'success' }
  if (status === 'REJECTED') return { label: '已驳回', tone: 'danger' }
  if (status === 'FAILED') return { label: '生产失败', tone: 'danger' }
  return { label: status, tone: 'neutral' }
})

function has(perm: string) {
  return !!me.value?.perms.includes(perm)
}

async function load(silent = false) {
  if (!silent) loading.value = true
  try {
    const [view, myself] = await Promise.all([getAdmission(props.publishNo), getMe().catch(() => null)])
    admission.value = view
    if (myself) me.value = myself
    error.value = null
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '读取生产准入失败'
  } finally {
    loading.value = false
  }
}

function isoOf(value: string | Date): string | null {
  if (!value) return null
  const d = value instanceof Date ? value : new Date(value)
  return Number.isNaN(d.getTime()) ? null : d.toISOString()
}

function openDialog(forResubmit = false) {
  draft.planned = snapshot.value?.plannedReleaseTime
    ? new Date(snapshot.value.plannedReleaseTime)
    : new Date()
  draft.oa = snapshot.value?.oaTicketNo ?? ''
  draft.steps = null
  draft.admitted = null
  draft.nextAction = null
  dialog.value = true
  if (forResubmit) queueDraftCheck()
}

function queueDraftCheck() {
  if (draftTimer) window.clearTimeout(draftTimer)
  draftTimer = window.setTimeout(draftCheck, 450)
}

async function draftCheck() {
  if (!draft.oa.trim()) {
    draft.steps = null
    draft.admitted = null
    return
  }
  try {
    const view = await checkAdmission(props.publishNo, {
      plannedReleaseTime: isoOf(draft.planned),
      oaTicketNo: draft.oa.trim()
    })
    draft.steps = view.steps
    draft.admitted = view.admitted ?? null
    draft.nextAction = view.nextAction ?? null
  } catch (e) {
    draft.steps = null
    if (e instanceof ApiError) {
      // A refusal still carries the six steps; show them instead of only the message.
      const payload = e.detail as { steps?: AdmissionView['steps'] } | undefined
      draft.steps = payload?.steps ?? null
      draft.nextAction = e.message
    }
  }
}

async function submit(forResubmit = false) {
  if (!draft.oa.trim()) {
    ElMessage.warning('OA 工单号必填')
    return
  }
  if (!draft.planned) {
    ElMessage.warning('计划生产时间必填')
    return
  }
  submitting.value = true
  try {
    const body = {
      plannedReleaseTime: isoOf(draft.planned),
      oaTicketNo: draft.oa.trim()
    }
    const view = forResubmit
      ? await resubmitProd(props.publishNo, body)
      : await submitProd(props.publishNo, body)
    admission.value = view
    dialog.value = false
    ElMessage.success(forResubmit ? '已重新提交生产' : '已提交生产')
    emit('changed')
  } catch (e) {
    if (e instanceof ApiError) {
      const payload = e.detail as { steps?: AdmissionView['steps'] } | undefined
      if (payload?.steps) draft.steps = payload.steps
      ElMessage.error(e.message)
    } else {
      ElMessage.error('提交失败')
    }
  } finally {
    submitting.value = false
  }
}

async function approve() {
  let comment = ''
  try {
    const result = await ElMessageBox.prompt('通过当前审批步骤（意见可选）', '通过审批', {
      confirmButtonText: '通过',
      cancelButtonText: '取消',
      inputType: 'textarea',
      inputPlaceholder: '可填写意见'
    })
    comment = result.value ?? ''
  } catch {
    return
  }
  busy.value = true
  try {
    admission.value = await approveRelease(props.publishNo, comment || undefined)
    ElMessage.success('已通过当前步骤')
    emit('changed')
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '审批失败')
  } finally {
    busy.value = false
  }
}

async function reject() {
  let reason: string
  try {
    const result = await ElMessageBox.prompt('驳回原因（必填）', '驳回', {
      confirmButtonText: '驳回',
      cancelButtonText: '取消',
      inputType: 'textarea',
      inputValidator: (v: string) => (v && v.trim() ? true : '必须填写原因')
    })
    reason = (result.value ?? '').trim()
  } catch {
    return
  }
  busy.value = true
  try {
    admission.value = await rejectRelease(props.publishNo, reason)
    ElMessage.success('已驳回')
    emit('changed')
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '驳回失败')
  } finally {
    busy.value = false
  }
}

async function execute() {
  const reviewText = review.value?.blocked
    ? `复核未通过：${review.value.reasons.join('；')}`
    : '复核通过（封板、豁免、镜像一致）'
  try {
    await ElMessageBox.confirm(
      `${reviewText}。确认执行生产发布？将按批次串行部署。`,
      props.paused ? '继续生产执行' : '执行生产发布',
      { confirmButtonText: '执行', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  busy.value = true
  try {
    const result = await executeRelease(props.publishNo)
    ElMessage.success(`已触发，当前状态 ${result.status}${result.node ? ` · ${result.node}` : ''}`)
    emit('changed')
    await load(true)
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '执行失败')
  } finally {
    busy.value = false
  }
}

watch(() => [draft.planned, draft.oa], queueDraftCheck)
onMounted(load)
</script>

<template>
  <div class="admission">
    <div class="admission-head">
      <span class="admission-title">生产准入</span>
      <span class="chip" :class="`chip-${statusChip.tone}`">{{ statusChip.label }}</span>
      <span class="topbar-spacer" />
      <span class="weak small" v-if="snapshot">快照 #{{ snapshot.id }} · {{ formatDateTime(snapshot.createdAt) }}</span>
    </div>

    <el-alert
      v-if="error"
      type="error"
      :closable="false"
      show-icon
      :title="error"
      style="margin: 0 18px 12px"
    />

    <div v-if="loading" style="padding: 12px 18px 18px"><el-skeleton :rows="3" animated /></div>

    <template v-else>
      <el-alert
        v-if="review?.frozen || snapshot?.frozen"
        type="warning"
        :closable="false"
        show-icon
        :title="review?.frozen ? '封板生效中：生产执行已暂停在批次边界' : '提交时处于封板期：本单需要运维组长 + 部门经理双签'"
        style="margin: 0 18px 12px"
      />
      <div v-if="!snapshot" class="empty-block">
        <div>
          <div class="strong">尚未提交生产</div>
          <p class="weak small" style="margin: 4px 0 0">
            门禁与测试都通过后，由开发填写计划生产时间与 OA 工单提交；窗口内免审批，窗口外需要运维组长，
            封板期需要运维组长 + 部门经理双签。
          </p>
        </div>
        <el-button v-if="showSubmit" type="primary" @click="openDialog(false)">提交生产发布</el-button>
        <span v-else-if="!canSubmit" class="weak small">需要权限 control:submit-prod</span>
      </div>

      <template v-else>
        <div class="snapshot-grid">
          <div class="field">
            <span class="weak small">计划生产时间</span>
            <span class="mono strong">{{ formatDateTime(snapshot.plannedReleaseTime) }}</span>
          </div>
          <div class="field">
            <span class="weak small">窗口判定</span>
            <span class="small">
              <span :style="{ color: snapshot.inWindow ? 'var(--accent)' : 'var(--warn)' }">
                {{ snapshot.inWindow ? '窗口内 · 免审批' : '窗口外 · 需审批' }}
              </span>
            </span>
          </div>
          <div class="field">
            <span class="weak small">封板</span>
            <span class="small" :style="{ color: snapshot.frozen ? 'var(--danger)' : 'var(--text-2)' }">
              {{ snapshot.frozen ? '提交时处于封板期 · 双签' : '无' }}
            </span>
          </div>
          <div class="field">
            <span class="weak small">OA 工单</span>
            <span class="mono">{{ snapshot.oaTicketNo || '—' }}</span>
          </div>
          <div class="field">
            <span class="weak small">检查 / 测试</span>
            <span class="small">
              {{ snapshot.checkConclusion || '—' }} · {{ snapshot.testConclusion || '—' }}
              <span v-if="snapshot.testPassBy" class="weak">（{{ snapshot.testPassBy }}）</span>
            </span>
          </div>
          <div class="field">
            <span class="weak small">镜像</span>
            <span class="mono">{{ shortSha(snapshot.imageDigest) }}</span>
          </div>
          <div class="field">
            <span class="weak small">提交人</span>
            <span class="small">{{ snapshot.submittedBy || '—' }}</span>
          </div>
        </div>

        <div v-if="chain.length" class="chain">
          <div class="chain-label weak small">审批链</div>
          <div v-for="step in chain" :key="step.stepNo" class="chain-row" :class="{ 'is-current': step.status === 'PENDING' }">
            <span class="chain-no mono">{{ step.stepNo }}</span>
            <span class="chain-role">{{ step.roleLabel }}</span>
            <span class="chain-status" :class="`st-${step.status.toLowerCase()}`">{{ step.statusLabel }}</span>
            <span class="weak small">{{ step.decidedBy || '' }}</span>
            <span class="weak small mono">{{ step.decidedAt ? formatDateTime(step.decidedAt) : '' }}</span>
            <span class="chain-comment weak small truncate" :title="step.comment || ''">{{ step.comment || '' }}</span>
          </div>
        </div>
        <div v-else-if="snapshot.chain.length === 0" class="chain-none">
          <span class="weak small">免审批（窗口内且未封板），提交即待执行。</span>
        </div>

        <div v-if="review" class="review" :class="review.blocked ? 'is-blocked' : 'is-ok'">
          <div class="review-head">
            <span class="strong">{{ review.blocked ? '执行前复核未通过' : '执行前复核通过' }}</span>
            <span class="weak small mono">{{ formatDateTime(review.checkedAt) }}</span>
          </div>
          <div class="review-items">
            <span :class="review.frozen ? 'bad' : 'good'">封板 {{ review.frozen ? '有生效封板' : '无变化' }}</span>
            <span :class="review.waiverInvalid ? 'bad' : 'good'">豁免 {{ review.waiverInvalid ? '已失效' : '有效' }}</span>
            <span :class="review.digestMatched ? 'good' : 'bad'">镜像 {{ review.digestMatched ? '一致' : '不一致' }}</span>
          </div>
          <ul v-if="review.reasons.length" class="review-reasons">
            <li v-for="(r, i) in review.reasons" :key="i">{{ r }}</li>
          </ul>
        </div>

        <div class="actions">
          <el-button
            v-if="pendingStep"
            type="primary"
            :disabled="!!approveBlock || busy"
            :title="approveBlock || '通过当前步骤'"
            @click="approve"
          >通过</el-button>
          <el-button
            v-if="pendingStep"
            :disabled="!!approveBlock || busy"
            :title="approveBlock || '驳回'"
            @click="reject"
          >驳回</el-button>
          <span v-if="pendingStep && approveBlock" class="weak small">{{ approveBlock }}</span>

          <span class="topbar-spacer" />

          <el-button v-if="showExecute" type="primary" :disabled="!!executeBlock || busy" :title="executeBlock || '执行生产发布'" @click="execute">
            {{ itemStatus === 'READY' ? '执行生产发布' : '继续生产执行' }}
          </el-button>
          <span v-if="showExecute && executeBlock" class="weak small">{{ executeBlock }}</span>

          <el-button v-if="showResubmit" :disabled="busy" @click="openDialog(true)">重新提交</el-button>
          <el-button v-if="props.running" :loading="loading" size="small" @click="load(true)">刷新准入</el-button>
        </div>

        <p v-if="admission?.nextAction" class="next-action weak small">{{ admission.nextAction }}</p>
      </template>
    </template>

    <el-dialog v-model="dialog" :title="showResubmit ? '重新提交生产发布' : '提交生产发布'" width="560px">
      <el-form label-position="top">
        <el-form-item label="计划生产时间" required>
          <el-date-picker
            v-model="draft.planned"
            type="datetime"
            placeholder="选择日期与时间"
            format="YYYY-MM-DD HH:mm"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="OA 工单号" required>
          <el-input v-model="draft.oa" placeholder="例如 OA20261121003" maxlength="64" spellcheck="false" />
        </el-form-item>
      </el-form>

      <div v-if="draft.steps" class="draft">
        <div class="draft-head">
          <span class="strong">提交前校验</span>
          <span class="chip" :class="draft.admitted ? 'chip-success' : 'chip-danger'">
            {{ draft.admitted ? '可提交' : '暂不可提交' }}
          </span>
        </div>
        <div v-for="s in draft.steps" :key="s.key" class="draft-row">
          <span class="draft-mark" :class="s.passed ? 'good' : 'bad'">{{ s.passed ? '✓' : '✗' }}</span>
          <span class="draft-label">{{ s.label }}</span>
          <span class="weak small draft-reason">{{ s.reason || '' }}</span>
        </div>
        <p v-if="draft.nextAction && !draft.admitted" class="weak small" style="margin: 8px 0 0">
          下一步：{{ draft.nextAction }}
        </p>
      </div>

      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit(showResubmit)">
          {{ showResubmit ? '重新提交' : '提交' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.admission {
  border-top: 1px dashed var(--border);
}
.admission-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 18px;
}
.admission-title {
  font-weight: 600;
}
.chip {
  display: inline-flex;
  align-items: center;
  height: 22px;
  padding: 0 8px;
  border-radius: 999px;
  border: 1px solid var(--border);
  font-size: 12px;
  color: var(--text-2);
  background: var(--surface-3);
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
.empty-block {
  display: flex;
  align-items: center;
  gap: 16px;
  justify-content: space-between;
  padding: 4px 18px 16px;
}
.snapshot-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px 18px;
  padding: 4px 18px 14px;
}
.field {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}
.chain {
  padding: 4px 18px 10px;
}
.chain-label {
  margin-bottom: 6px;
}
.chain-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 7px 10px;
  border: 1px solid transparent;
  border-bottom: 1px dashed var(--border);
  font-size: 13px;
}
.chain-row:last-child {
  border-bottom: none;
}
.chain-row.is-current {
  border: 1px solid color-mix(in srgb, var(--warn) 45%, var(--border));
  border-radius: var(--radius-sm);
  background: var(--warn-weak);
}
.chain-no {
  width: 18px;
  color: var(--text-3);
  font-size: 12px;
}
.chain-role {
  min-width: 88px;
}
.chain-status {
  min-width: 64px;
  font-size: 12px;
}
.st-approved {
  color: var(--accent);
}
.st-pending {
  color: var(--warn);
}
.st-rejected {
  color: var(--danger);
}
.st-canceled {
  color: var(--text-3);
}
.chain-comment {
  max-width: 260px;
  margin-left: auto;
}
.chain-none {
  padding: 0 18px 12px;
}
.review {
  margin: 0 18px 12px;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-left: 3px solid var(--text-3);
  border-radius: var(--radius-sm);
  background: var(--surface);
}
.review.is-ok {
  border-left-color: var(--accent);
  background: var(--accent-weak);
}
.review.is-blocked {
  border-left-color: var(--danger);
  background: var(--danger-weak);
}
.review-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
}
.review-items {
  display: flex;
  gap: 16px;
  margin-top: 6px;
  font-size: 12px;
  color: var(--text-2);
}
.good {
  color: var(--accent);
}
.bad {
  color: var(--danger);
}
.review-reasons {
  margin: 6px 0 0;
  padding-left: 18px;
  color: var(--danger);
  font-size: 12px;
}
.actions {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 18px 14px;
  flex-wrap: wrap;
}
.next-action {
  padding: 0 18px 14px;
}
.draft {
  margin-top: 4px;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--surface);
}
.draft-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 6px;
}
.draft-row {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 4px 0;
  font-size: 13px;
}
.draft-mark {
  width: 14px;
}
.draft-label {
  min-width: 92px;
}
.draft-reason {
  flex: 1;
  min-width: 0;
}
@media (max-width: 1100px) {
  .snapshot-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>