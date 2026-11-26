<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  approveWaiver,
  failItemTest,
  getItemGate,
  getMe,
  passItemTest,
  refreshGateCheck,
  rejectWaiver,
  requestWaiver,
  revokeItemTest
} from '../api'
import { ApiError } from '../api/http'
import type { GateCheck, ItemGate, MeView } from '../api/types'
import StatusDot from './StatusDot.vue'
import EmptyState from './EmptyState.vue'
import { formatDateTime } from '../utils/format'

const props = defineProps<{ itemId: number; running?: boolean }>()
const emit = defineEmits<{ (e: 'changed'): void }>()

const gate = ref<ItemGate | null>(null)
const me = ref<MeView | null>(null)
const loading = ref(true)
const error = ref<string | null>(null)
const busy = ref(false)

async function load(silent = false) {
  if (!props.itemId) {
    loading.value = false
    return
  }
  if (!silent) loading.value = true
  error.value = null
  try {
    gate.value = await getItemGate(props.itemId)
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '加载门禁失败'
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await Promise.all([load(), getMe().then((r) => (me.value = r)).catch(() => undefined)])
})
watch(() => props.itemId, () => load())
// The parent polls the release while it is busy; refresh the panel with it.
watch(() => props.running, (busyNow) => busyNow && load(true))

/** Gate conclusions and check statuses share the item vocabulary; reuse the dot component. */
const toneOfConclusion = computed(() => {
  switch (gate.value?.conclusion) {
    case 'PASS':
    case 'WAIVED':
      return 'success'
    case 'BLOCK':
      return 'danger'
    case 'INVALID':
      return 'danger'
    default:
      return 'accent'
  }
})

const testStatusForDot = computed(() => {
  switch (gate.value?.testStatus) {
    case 'TEST_PASS':
      return 'SUCCESS'
    case 'TEST_FAIL':
    case 'TEST_REVOKED':
      return 'FAILED'
    default:
      return 'PENDING'
  }
})

const canPass = computed(
  () => !!gate.value && gate.value.itemStatus === 'TESTING' && gate.value.testStatus !== 'TEST_PASS' &&
    (me.value?.perms.includes('gate:test-pass') ?? false)
)
const passHint = computed(() => {
  if (!gate.value) return '未加载'
  if (gate.value.testStatus === 'TEST_PASS') return '测试已通过'
  if (gate.value.itemStatus !== 'TESTING') return `当前状态（${gate.value.itemStatus}）不能标记测试通过`
  if (!me.value?.perms.includes('gate:test-pass')) return '需要测试人员权限（gate:test-pass）'
  return '测试人员标记通过后，两道门禁同时满足才可提交生产'
})

const canFail = computed(
  () => !!gate.value && gate.value.itemStatus === 'TESTING' && gate.value.testStatus !== 'TEST_PASS' &&
    (me.value?.perms.includes('gate:test-pass') ?? false)
)
const failHint = computed(() => {
  if (!gate.value) return '未加载'
  if (gate.value.testStatus === 'TEST_PASS') return '测试已通过：先撤回，才能改判不通过'
  if (gate.value.itemStatus !== 'TESTING') return `当前状态（${gate.value.itemStatus}）不能标记测试不通过`
  if (!me.value?.perms.includes('gate:test-pass')) return '需要测试人员权限（gate:test-pass）'
  return '填写原因后标记不通过'
})

const canRevoke = computed(
  () => !!gate.value && gate.value.testStatus === 'TEST_PASS' &&
    (me.value?.perms.includes('gate:test-revoke') ?? false) &&
    (me.value?.allUnits || gate.value.testPassBy === me.value?.userId || (me.value?.roles.includes('ADMIN') ?? false))
)
const revokeHint = computed(() => {
  if (!gate.value) return '未加载'
  if (gate.value.testStatus !== 'TEST_PASS') return '没有可撤回的测试通过'
  if (!me.value?.perms.includes('gate:test-revoke')) return '需要测试权限（gate:test-revoke）'
  if (gate.value.testPassBy && gate.value.testPassBy !== me.value?.userId) {
    return `只有通过测试的人（${gate.value.testPassBy}）可以撤回`
  }
  return '撤回后生产准入立即失效，需要重新测试'
})

const canRefresh = computed(() => me.value?.perms.includes('gate:test-pass') ?? false)
const canRequestWaiver = computed(() => !!gate.value && gate.value.waivable && !gate.value.waiver)
const canDecideWaiver = computed(
  () =>
    (me.value?.perms.includes('gate:waive') ?? false) &&
    gate.value?.waiver?.status === 'PENDING' &&
    gate.value?.waiver?.requestedBy !== me.value?.userId
)
const waiverDecideHint = computed(() => {
  const w = gate.value?.waiver
  if (!w || w.status !== 'PENDING') return '没有待审批的豁免单'
  if (!me.value?.perms.includes('gate:waive')) return '审批豁免需要安全审核权限（gate:waive）'
  if (w.requestedBy === me.value?.userId) return '不能审批自己提交的豁免单'
  return '批准后中危检查项计入通过'
})

async function act(fn: () => Promise<ItemGate>, success: string) {
  if (busy.value) return
  busy.value = true
  try {
    gate.value = await fn()
    ElMessage.success(success)
    emit('changed')
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '操作失败')
  } finally {
    busy.value = false
  }
}

function doPass() {
  act(() => passItemTest(props.itemId), '已标记测试通过')
}

async function askReason(title: string, placeholder: string): Promise<string | null> {
  try {
    const { value } = await ElMessageBox.prompt(title, '需要填写原因（会记入留痕）', {
      inputPlaceholder: placeholder,
      inputValidator: (v: string) => (v && v.trim() ? true : '原因不能为空'),
      confirmButtonText: '确定',
      cancelButtonText: '取消'
    })
    return value
  } catch {
    return null
  }
}

async function doFail() {
  const reason = await askReason('标记测试不通过', '例如：接口返回 500，阻塞发布')
  if (reason) await act(() => failItemTest(props.itemId, reason), '已标记测试不通过')
}

async function doRevoke() {
  const reason = await askReason('撤回测试通过', '例如：测试环境被复用，需要重测')
  if (reason) await act(() => revokeItemTest(props.itemId, reason), '已撤回测试通过')
}

function refresh(check: GateCheck) {
  if (!canRefresh.value) return
  act(() => refreshGateCheck(props.itemId, check.kind, gate.value?.instanceId ?? 0), `已重新触发${check.label}`)
}

const waiverDialog = ref(false)
// `days` is nullable (P6-F PART-06): empty means "use the platform default validity"
// (`WAIVER.defaultDays`, 7 days unless changed in 设置); an explicit 1–90 window keeps its
// pre-P6-F behaviour of being computed client-side.
const waiverForm = ref({ reason: '', impact: '', days: 7 as number | null })

async function submitWaiver() {
  const reason = waiverForm.value.reason.trim()
  if (!reason) {
    ElMessage.warning('请填写豁免原因')
    return
  }
  const days = waiverForm.value.days
  const expireAt =
    days === null || days === undefined
      ? null
      : new Date(Date.now() + days * 24 * 3600 * 1000).toISOString()
  waiverDialog.value = false
  await act(
    () =>
      requestWaiver(props.itemId, {
        reason,
        impact: waiverForm.value.impact.trim() || undefined,
        expireAt: expireAt ?? undefined
      }),
    '已提交豁免申请，等待审核'
  )
}

async function decide(approve: boolean) {
  const w = gate.value?.waiver
  if (!w) return
  if (approve) {
    try {
      const { value } = await ElMessageBox.prompt('批准豁免', '审批意见（可选）', {
        inputPlaceholder: '同意，风险可控',
        confirmButtonText: '批准',
        cancelButtonText: '取消'
      })
      await act(() => approveWaiver(props.itemId, w.id, value), '已批准豁免')
    } catch {
      /* cancelled */
    }
    return
  }
  const note = await askReason('驳回豁免', '例如：风险不可接受，请先修复')
  if (note) await act(() => rejectWaiver(props.itemId, w.id, note), '已驳回豁免')
}

function severityClass(severity: string): string {
  switch (severity) {
    case 'S0':
    case 'S1':
      return 'sev-danger'
    case 'S2':
      return 'sev-warn'
    case 'S3':
      return 'sev-muted'
    default:
      return 'sev-none'
  }
}

function checkDot(check: GateCheck): string {
  switch (check.status) {
    case 'PASS':
      return 'SUCCESS'
    case 'FAIL':
    case 'TIMEOUT':
      return 'FAILED'
    case 'RUNNING':
      return 'RUNNING'
    default:
      return 'PENDING'
  }
}
</script>

<template>
  <div class="verify">
    <el-skeleton v-if="loading" :rows="4" animated />
    <el-alert v-else-if="error" type="error" :closable="false" show-icon :title="error" />

    <template v-else-if="gate">
      <!-- Two orthogonal gates, side by side, like the requirement's "UI 分列展示" -->
      <div class="gate-grid">
        <div class="gate-card">
          <div class="gate-card-head">
            <span class="weak small">检查门禁</span>
            <StatusDot
              :status="gate.conclusion === 'BLOCK' || gate.conclusion === 'INVALID' ? 'FAILED' : gate.conclusion === 'RUNNING' ? 'RUNNING' : 'SUCCESS'"
              :label="gate.conclusionLabel"
              :pulse="gate.conclusion === 'RUNNING'"
            />
          </div>
          <div class="gate-figure">
            <span class="figure">{{ gate.passed }}<span class="figure-total">/{{ gate.total }}</span></span>
            <span class="weak small">项通过</span>
          </div>
          <div class="gate-meta">
            <span class="weak small">最高等级</span>
            <span class="sev" :class="severityClass(gate.maxSeverity)">{{ gate.maxSeverityLabel }}</span>
          </div>
        </div>

        <div class="gate-card">
          <div class="gate-card-head">
            <span class="weak small">测试门禁</span>
            <StatusDot :status="testStatusForDot" :label="gate.test" />
          </div>
          <div class="gate-figure">
            <span class="figure">{{ gate.testStatus === 'TEST_PASS' ? '已通过' : '未通过' }}</span>
          </div>
          <div class="gate-meta">
            <span class="weak small">{{ gate.testPassBy ? `${gate.testPassBy}` : '尚无通过人' }}</span>
            <span class="weak small mono">{{ formatDateTime(gate.testPassAt) }}</span>
          </div>
          <p v-if="gate.testFailReason" class="small reason-line">{{ gate.testFailReason }}</p>
        </div>
      </div>

      <!-- The conclusion with its arithmetic: "why can't this go to production" -->
      <div class="conclusion" :class="`tone-${toneOfConclusion}`">
        <div class="conclusion-head">
          <i class="mark" aria-hidden="true" />
          <span class="conclusion-title">{{ gate.summary }}</span>
        </div>
        <ul v-if="gate.reasons.length" class="reasons">
          <li v-for="(reason, i) in gate.reasons" :key="i">{{ reason }}</li>
        </ul>
      </div>

      <div class="gate-actions">
        <el-button size="small" :disabled="!canPass || busy" :title="passHint" @click="doPass">
          测试通过
        </el-button>
        <el-button size="small" :disabled="!canFail || busy" :title="failHint" @click="doFail">
          测试不通过
        </el-button>
        <el-button size="small" :disabled="!canRevoke || busy" :title="revokeHint" @click="doRevoke">
          撤回通过
        </el-button>
        <span class="topbar-spacer" />
        <span class="weak small">
          任一人通过即通过；撤回仅限通过人（C18/C19）
        </span>
      </div>

      <!-- Checks, grouped by the stage that triggers them -->
      <div v-for="group in gate.groups" :key="group.stage" class="check-group">
        <div class="check-group-head">
          <span class="panel-title">{{ group.stageLabel }}</span>
          <span class="weak small">{{ group.passed }}/{{ group.total }}</span>
          <span class="topbar-spacer" />
          <span class="weak small">{{ group.stage === 'IMAGE' ? '构建成功后跑' : '测试部署成功后跑' }}</span>
        </div>
        <div v-for="check in group.checks" :key="check.kind" class="check-row">
          <StatusDot :status="checkDot(check)" :label="check.statusLabel" />
          <span class="check-name">{{ check.label }}</span>
          <span class="sev" :class="severityClass(check.severity)">{{ check.severityLabel }}</span>
          <span v-if="check.issues" class="weak small num">{{ check.issues }} 项</span>
          <span class="topbar-spacer" />
          <span v-if="check.summary" class="weak small truncate check-summary" :title="check.summary">
            {{ check.summary }}
          </span>
          <span class="weak small mono">{{ formatDateTime(check.finishedAt) }}</span>
          <a v-if="check.reportUrl" :href="check.reportUrl" target="_blank" rel="noreferrer" class="small">报告</a>
          <el-button
            link
            size="small"
            :disabled="!canRefresh || busy"
            :title="canRefresh ? '只重跑这一项检查' : '需要测试权限（gate:test-pass）'"
            @click="refresh(check)"
          >
            刷新
          </el-button>
        </div>
      </div>

      <!-- Waiver flow: only medium findings can be waived -->
      <div v-if="gate.waiver" class="waiver">
        <div class="waiver-head">
          <span class="panel-title">豁免单 #{{ gate.waiver.id }}</span>
          <span class="target" :class="`target-${gate.waiver.status.toLowerCase()}`">{{ gate.waiver.statusLabel }}</span>
          <span class="topbar-spacer" />
          <span class="weak small">到期 {{ formatDateTime(gate.waiver.expireAt) }}</span>
        </div>
        <p class="small">{{ gate.waiver.reason }}</p>
        <p v-if="gate.waiver.impact" class="weak small">影响评估：{{ gate.waiver.impact }}</p>
        <p v-if="gate.waiver.decideReason" class="weak small">审批意见：{{ gate.waiver.decideReason }}</p>
        <div v-if="gate.waiver.status === 'PENDING'" class="waiver-actions">
          <span class="weak small">申请人 {{ gate.waiver.requestedBy }}</span>
          <span class="topbar-spacer" />
          <el-button size="small" :disabled="!canDecideWaiver || busy" :title="waiverDecideHint" @click="decide(true)">
            批准
          </el-button>
          <el-button size="small" :disabled="!canDecideWaiver || busy" :title="waiverDecideHint" @click="decide(false)">
            驳回
          </el-button>
        </div>
      </div>
      <div v-else-if="gate.waivable" class="waiver waiver-invite">
        <span class="small">中危检查项可申请豁免（高危/严重不可豁免）</span>
        <span class="topbar-spacer" />
        <el-button size="small" plain :disabled="!canRequestWaiver || busy" @click="waiverDialog = true">
          申请豁免
        </el-button>
      </div>

      <!-- Admission: the answer to "may I submit for production" -->
      <div class="admission" :class="gate.admission.eligible ? 'admission-ok' : 'admission-blocked'">
        <div class="admission-head">
          <span class="panel-title">生产准入</span>
          <span class="small strong">{{ gate.admission.eligible ? '已满足' : '未满足' }}</span>
        </div>
        <p v-if="gate.admission.eligible" class="small">
          两道门禁均已满足、镜像与检查一致，可提交生产发布（OA 工单、窗口与审批由 P3 交付）。
        </p>
        <ul v-else class="missing">
          <li v-for="(item, i) in gate.admission.missing" :key="i">{{ item }}</li>
        </ul>
      </div>

      <el-dialog v-model="waiverDialog" title="申请豁免" width="520">
        <el-form label-position="top">
          <el-form-item label="豁免原因（必填）">
            <el-input
              v-model="waiverForm.reason"
              type="textarea"
              :rows="3"
              placeholder="例如：5 个失败均为已知用例，已排期修复"
            />
          </el-form-item>
          <el-form-item label="影响评估">
            <el-input v-model="waiverForm.impact" placeholder="例如：仅影响报表页，不影响交易主链路" />
          </el-form-item>
          <el-form-item label="有效期（天）">
            <el-input-number v-model="waiverForm.days" :min="1" :max="90" placeholder="留空使用平台默认" />
            <span class="weak small waiver-days-hint">留空则使用平台默认有效期（设置 → 门禁规则可配）。</span>
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="waiverDialog = false">取消</el-button>
          <el-button type="primary" :disabled="busy" @click="submitWaiver">提交申请</el-button>
        </template>
      </el-dialog>
    </template>

    <EmptyState v-else title="没有门禁数据" hint="该发布单尚未关联变更项。" />
  </div>
</template>

<style scoped>
.verify {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 4px 18px 16px;
}

.gate-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}
.gate-card {
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: 12px 14px;
  background: var(--surface);
}
.gate-card-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.gate-figure {
  display: flex;
  align-items: baseline;
  gap: 6px;
  margin: 8px 0 6px;
}
.figure {
  font-size: 22px;
  font-weight: 600;
  letter-spacing: -0.02em;
  font-variant-numeric: tabular-nums;
}
.figure-total {
  font-size: 13px;
  font-weight: 400;
  color: var(--text-3);
}
.gate-meta {
  display: flex;
  align-items: center;
  gap: 8px;
}
.reason-line {
  margin: 6px 0 0;
  color: var(--text-2);
}

.conclusion {
  border: 1px solid var(--border);
  border-left: 3px solid var(--text-3);
  border-radius: var(--radius-sm);
  padding: 12px 14px;
  background: var(--surface);
}
.conclusion.tone-success {
  border-left-color: var(--accent);
  background: var(--accent-weak);
}
.conclusion.tone-danger {
  border-left-color: var(--danger);
  background: var(--danger-weak);
}
.conclusion.tone-accent {
  border-left-color: var(--accent);
}
.conclusion-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.mark {
  width: 6px;
  height: 6px;
  border-radius: 999px;
  background: currentColor;
  color: var(--text-3);
}
.tone-success .mark {
  color: var(--accent);
}
.tone-danger .mark {
  color: var(--danger);
}
.conclusion-title {
  font-size: 13px;
  font-weight: 500;
}
.reasons {
  margin: 6px 0 0;
  padding-left: 18px;
  color: var(--text-2);
  font-size: 12px;
}

.gate-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.check-group {
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
}
.check-group-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  border-bottom: 1px solid var(--border);
  background: var(--surface);
  border-radius: var(--radius-sm) var(--radius-sm) 0 0;
}
.check-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 14px;
  border-bottom: 1px dashed var(--border);
  font-size: 13px;
}
.check-row:last-child {
  border-bottom: none;
}
.check-name {
  min-width: 96px;
}
.check-summary {
  max-width: 240px;
}

.sev {
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 4px;
  border: 1px solid var(--border);
  color: var(--text-2);
  white-space: nowrap;
}
.sev-danger {
  border-color: color-mix(in srgb, var(--danger) 40%, var(--border));
  background: var(--danger-weak);
  color: var(--danger);
}
.sev-warn {
  border-color: color-mix(in srgb, var(--warn) 40%, var(--border));
  background: var(--warn-weak);
  color: var(--warn);
}
.sev-muted {
  color: var(--text-3);
}
.sev-none {
  color: var(--text-3);
  border-color: transparent;
}

.waiver {
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: 12px 14px;
  background: var(--surface);
}
.waiver-invite {
  display: flex;
  align-items: center;
  gap: 10px;
}
.waiver-head {
  display: flex;
  align-items: center;
  gap: 10px;
}
.waiver-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
}
.target {
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 4px;
  border: 1px solid var(--border);
  color: var(--text-2);
}
.target-pending {
  border-color: var(--warn);
  color: var(--warn);
  background: var(--warn-weak);
}
.target-approved {
  border-color: var(--accent);
  color: var(--accent);
  background: var(--accent-weak);
}
.target-expired,
.target-rejected {
  border-color: color-mix(in srgb, var(--danger) 40%, var(--border));
  color: var(--danger);
  background: var(--danger-weak);
}

.admission {
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: 12px 14px;
}
.admission-ok {
  border-color: color-mix(in srgb, var(--accent) 35%, var(--border));
  background: var(--accent-weak);
}
.admission-head {
  display: flex;
  align-items: center;
  gap: 10px;
}
.missing {
  margin: 6px 0 0;
  padding-left: 18px;
  font-size: 12px;
  color: var(--text-2);
}

/* The nullable waiver validity (P6-F PART-06): the hint names the platform default. */
.waiver-days-hint {
  margin-left: 10px;
}

@media (max-width: 960px) {
  .gate-grid {
    grid-template-columns: 1fr;
  }
}
</style>