<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  getCodingConn,
  getMe,
  getNotificationSetting,
  listSubsystems,
  saveCodingConn,
  saveNotificationSetting,
  testCodingConn
} from '../api'
import { ApiError, currentUser, setCurrentUser } from '../api/http'
import { applyTheme, currentTheme } from '../theme'
import type { Theme } from '../theme'
import type { CodingConnView, CodingProbeView, NotificationSettingView } from '../api/types'
import { formatDateTime } from '../utils/format'
import PageHeader from '../components/PageHeader.vue'
import DataSection from '../components/DataSection.vue'
import KeyValue from '../components/KeyValue.vue'

const theme = ref<Theme>(currentTheme())
const userId = ref(currentUser())
const checking = ref(false)
const backend = ref<{ ok: boolean; message: string; subsystems?: number } | null>(null)

// ---------------------------------------------------------------- CODING 对接
const canManage = ref(false)
const conn = ref<CodingConnView | null>(null)
const connLoading = ref(false)
const connError = ref<string | null>(null)
const saving = ref(false)
const testing = ref(false)
const probe = ref<CodingProbeView | null>(null)
const form = reactive({ baseUrl: '', team: '', project: '', token: '' })

const configured = computed(() => conn.value?.configured === true)

/** The probe box is green when the freshest evidence is a pass, red when it is a failure. */
const probeTone = computed(() => {
  const ok = probe.value ? probe.value.ok : conn.value?.lastProbeOk === true
  return ok ? 'is-ok' : 'is-fail'
})

/** Chip text: unconfigured, last probe failed, or simply configured. */
const connStatus = computed(() => {
  if (!configured.value) return { label: '未配置', tone: 'neutral' as const }
  if (conn.value?.lastProbeOk === false) return { label: '上次校验失败', tone: 'danger' as const }
  if (conn.value?.lastProbeOk === true) return { label: '已验证', tone: 'success' as const }
  return { label: '已配置 · 未验证', tone: 'warn' as const }
})

function onTheme(value: Theme) {
  theme.value = value
  applyTheme(value)
}

function saveUser() {
  setCurrentUser(userId.value)
  userId.value = currentUser()
  ElMessage.success('身份已保存，刷新页面后生效')
}

async function checkBackend() {
  checking.value = true
  try {
    const page = await listSubsystems(1, 1)
    backend.value = { ok: true, message: '连接正常', subsystems: Number(page.total) }
  } catch (e) {
    backend.value = { ok: false, message: e instanceof ApiError ? e.message : '连接失败' }
  } finally {
    checking.value = false
  }
}

function fillForm(view: CodingConnView) {
  form.baseUrl = view.baseUrl ?? ''
  form.team = view.team ?? ''
  form.project = view.project ?? ''
  form.token = ''
}

async function loadConn() {
  connLoading.value = true
  connError.value = null
  try {
    const me = await getMe()
    canManage.value = me.perms.includes('setting:manage')
    if (!canManage.value) {
      conn.value = null
      return
    }
    const view = await getCodingConn()
    conn.value = view
    if (view.configured) fillForm(view)
  } catch (e) {
    connError.value = e instanceof ApiError ? e.message : '读取 CODING 对接配置失败'
  } finally {
    connLoading.value = false
  }
}

async function saveConn() {
  if (!form.baseUrl.trim() || !form.team.trim() || !form.project.trim()) {
    ElMessage.warning('地址、团队与默认项目都是必填项')
    return
  }
  saving.value = true
  try {
    const view = await saveCodingConn({
      baseUrl: form.baseUrl.trim(),
      team: form.team.trim(),
      project: form.project.trim(),
      token: form.token.trim() || undefined
    })
    conn.value = view
    fillForm(view)
    probe.value = null
    ElMessage.success('CODING 连接已保存')
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}

async function testConn() {
  testing.value = true
  probe.value = null
  try {
    probe.value = await testCodingConn({
      baseUrl: form.baseUrl.trim() || undefined,
      team: form.team.trim() || undefined,
      project: form.project.trim() || undefined,
      token: form.token.trim() || undefined
    })
    if (probe.value.ok) {
      ElMessage.success('连接可用')
    } else {
      ElMessage.error('连接不可用，原因见下方')
    }
    if (probe.value.persisted) {
      // The backend recorded this as "last verified"; refresh so the header chip agrees.
      const view = await getCodingConn()
      conn.value = view
    }
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '测试连接失败')
  } finally {
    testing.value = false
  }
}

// ---------------------------------------------------------------- 消息通知 (P4-B)
//
// The second `setting:manage` block on this page, after CODING: the WeCom robot switch and webhook
// (design §5/§6). It fetches `/me` on its own because `loadConn` owns `canManage` only for its own
// request: reading the flag from that block would race on page load.
const notifyCanManage = ref(false)
const notifyView = ref<NotificationSettingView | null>(null)
const notifyLoading = ref(false)
const notifyError = ref<string | null>(null)
const notifySaving = ref(false)
const notifyEnabled = ref(false)
const notifyWebhook = ref('')

const notifyStatus = computed(() => {
  if (notifyLoading.value) return { label: '读取中…', tone: 'neutral' as const }
  if (!notifyCanManage.value) return { label: '只读', tone: 'neutral' as const }
  if (!notifyView.value) return { label: '默认关闭', tone: 'neutral' as const }
  return notifyView.value.wecomEnabled
    ? { label: '已开启', tone: 'success' as const }
    : { label: '已关闭', tone: 'neutral' as const }
})

const notifySubtitle = computed(() => {
  if (notifyLoading.value) return '读取中…'
  if (!notifyCanManage.value) return '仅站内通知 · 企微配置仅管理员可见'
  const view = notifyView.value
  if (!view) return '未保存过，默认仅站内通知'
  const state = view.wecomEnabled ? '企微已开启' : '企微已关闭'
  return view.updatedBy
    ? `${state} · ${view.updatedBy} 更新于 ${formatDateTime(view.updatedAt)}`
    : state
})

async function loadNotify() {
  notifyLoading.value = true
  notifyError.value = null
  try {
    const me = await getMe()
    notifyCanManage.value = me.perms.includes('setting:manage')
    if (!notifyCanManage.value) {
      // No permission means no read either: the endpoint answers 403, so do not call it.
      notifyView.value = null
      return
    }
    const view = await getNotificationSetting()
    notifyView.value = view
    notifyEnabled.value = view.wecomEnabled
    notifyWebhook.value = view.wecomWebhook ?? ''
  } catch (e) {
    notifyError.value = e instanceof ApiError ? e.message : '读取消息通知配置失败'
  } finally {
    notifyLoading.value = false
  }
}

async function saveNotify() {
  if (notifyEnabled.value && !notifyWebhook.value.trim()) {
    ElMessage.warning('开启企微通知时必须填写机器人地址')
    return
  }
  notifySaving.value = true
  try {
    const view = await saveNotificationSetting({
      wecomEnabled: notifyEnabled.value,
      wecomWebhook: notifyWebhook.value.trim() || null
    })
    notifyView.value = view
    notifyEnabled.value = view.wecomEnabled
    notifyWebhook.value = view.wecomWebhook ?? ''
    ElMessage.success('消息通知配置已保存')
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '保存失败')
  } finally {
    notifySaving.value = false
  }
}

onMounted(() => {
  checkBackend()
  loadConn()
  loadNotify()
})
</script>

<template>
  <div>
    <PageHeader title="设置" description="本地偏好、身份，以及 CODING 对接与消息通知配置。" />

    <DataSection
      title="CODING 对接"
      :subtitle="connLoading ? '读取中…' : configured ? `已配置 · 更新于 ${formatDateTime(conn?.updatedAt)}` : '未配置'"
      style="margin-bottom: 16px"
    >
      <template #actions>
        <span class="chip" :class="`chip-${connStatus.tone}`">{{ connStatus.label }}</span>
        <template v-if="canManage">
          <el-button :disabled="testing" :loading="testing" @click="testConn">
            {{ testing ? '测试中…' : '测试连接' }}
          </el-button>
          <el-button type="primary" :disabled="saving" :loading="saving" @click="saveConn">
            保存
          </el-button>
        </template>
      </template>

      <el-alert
        v-if="connError"
        type="error"
        :closable="false"
        show-icon
        :title="connError"
        style="margin-bottom: 12px"
      />

      <div v-if="!canManage" class="restricted">
        <span class="strong">需要平台管理员权限</span>
        <span class="weak small">
          CODING 连接是全平台共用的一条租户连接，只有具备 <code class="mono">setting:manage</code>
          的账号可以查看和修改。当前状态：{{ configured ? '已配置' : '未配置' }}。
        </span>
      </div>

      <template v-else>
        <div class="conn-grid">
          <label class="field span-2">
            <span class="field-label">服务地址</span>
            <el-input v-model="form.baseUrl" placeholder="https://coding.example.com" spellcheck="false" />
          </label>
          <label class="field">
            <span class="field-label">团队</span>
            <el-input v-model="form.team" placeholder="团队标识" spellcheck="false" />
          </label>
          <label class="field">
            <span class="field-label">默认项目</span>
            <el-input v-model="form.project" placeholder="项目标识" spellcheck="false" />
          </label>
          <label class="field span-2">
            <span class="field-label">
              访问凭证
              <span v-if="configured" class="weak">（已保存 {{ conn?.tokenHint }}，留空不修改）</span>
            </span>
            <el-input
              v-model="form.token"
              type="password"
              show-password
              autocomplete="new-password"
              :placeholder="configured ? '留空保留原凭证，填写则轮换' : '首次配置必须填写'"
            />
          </label>
          <div class="field span-2 hint">
            <span>凭证 AES-GCM 加密落库，接口只回显前四位；明文不落库、不进日志。</span>
            <span>子系统未填「CODING 项目」时，任务查询使用这里的默认项目。</span>
            <span>
              端点路径与状态词表在 <code class="mono">coding-endpoints.yml</code>，改动需重启生效。
            </span>
          </div>
        </div>

        <div v-if="probe || conn?.lastProbeAt" class="probe" :class="probeTone">
          <div class="probe-head">
            <span class="probe-title">
              {{ probe ? (probe.ok ? '连接可用' : '连接不可用') : conn?.lastProbeOk ? '上次校验可用' : '上次校验失败' }}
            </span>
            <span class="weak small">{{ formatDateTime(probe?.at ?? conn?.lastProbeAt) }}</span>
          </div>
          <p class="probe-message">{{ probe?.message ?? conn?.lastProbeMessage ?? '—' }}</p>
          <div v-if="probe?.ok" class="probe-counts">
            <span class="count"><b>{{ probe.buildJobs }}</b> 个构建任务</span>
            <span class="count"><b>{{ probe.deployJobs }}</b> 个部署任务</span>
          </div>
          <p v-if="probe" class="weak small" style="margin: 6px 0 0">
            {{ probe.persisted ? '已记录为上次校验结果。' : '草稿测试：结果不写入配置。' }}
          </p>
        </div>
      </template>
    </DataSection>

    <DataSection
      class="settings-notify"
      title="消息通知"
      :subtitle="notifySubtitle"
      style="margin-bottom: 16px"
    >
      <template #actions>
        <span class="chip" :class="`chip-${notifyStatus.tone}`">{{ notifyStatus.label }}</span>
        <el-button
          v-if="notifyCanManage"
          type="primary"
          :disabled="notifySaving"
          :loading="notifySaving"
          @click="saveNotify"
        >
          保存
        </el-button>
      </template>

      <el-alert
        v-if="notifyError"
        type="error"
        :closable="false"
        show-icon
        :title="notifyError"
        style="margin-bottom: 12px"
      />

      <div v-if="!notifyCanManage" class="restricted">
        <span class="strong">需要平台管理员权限</span>
        <span class="weak small">
          企微机器人是全平台共用的一条配置，只有具备 <code class="mono">setting:manage</code>
          的账号可以查看和修改；当前账号无权读取该配置。站内通知始终开启，无需配置。
        </span>
      </div>

      <template v-else>
        <div class="notify-grid">
          <label class="field">
            <span class="field-label">企微机器人通知</span>
            <span class="switch-row">
              <el-switch
                v-model="notifyEnabled"
                active-text="开启"
                inactive-text="关闭"
              />
              <span class="weak small">关闭时只发站内通知，不创建企微发送任务。</span>
            </span>
          </label>
          <label class="field">
            <span class="field-label">机器人 Webhook</span>
            <el-input
              v-model="notifyWebhook"
              placeholder="https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=..."
              spellcheck="false"
            />
          </label>
          <div class="field hint">
            <span>
              机器人地址内嵌密钥，属于敏感配置：接口只对具备
              <code class="mono">setting:manage</code> 的账号原样回显，请勿转发或截图外传。
            </span>
            <span>开启时必须填写 <code class="mono">https://</code> 地址；发送失败会记录在该条通知上，不阻塞发版流程。</span>
            <span>站内通知始终开启，无需配置。</span>
          </div>
        </div>
        <p v-if="notifyView?.updatedBy" class="weak small notify-audit">
          最近保存：{{ notifyView.updatedBy }} · {{ formatDateTime(notifyView.updatedAt) }}
        </p>
      </template>
    </DataSection>

    <div class="grid">
      <div class="stack">
        <DataSection title="外观">
          <el-radio-group :model-value="theme" @change="onTheme($event as Theme)">
            <el-radio-button label="light">浅色</el-radio-button>
            <el-radio-button label="dark">深色</el-radio-button>
          </el-radio-group>
          <p class="weak small" style="margin: 10px 0 0">
            默认跟随系统；手动切换后会记住选择（<code class="mono">localStorage.rp.theme</code>）。
          </p>
        </DataSection>

        <DataSection title="身份">
          <el-input v-model="userId" placeholder="工号，例如 dev" style="max-width: 260px" />
          <div class="row" style="margin-top: 10px">
            <el-button type="primary" @click="saveUser">保存</el-button>
            <span class="weak small">请求头 X-User-Id；需存在于 app_user 且有角色。</span>
          </div>
        </DataSection>
      </div>

      <div class="stack">
        <DataSection title="后端连通性">
          <template #actions>
            <el-button :loading="checking" @click="checkBackend">重新检测</el-button>
          </template>
          <KeyValue label="开发代理" value="Vite → 127.0.0.1:8080" mono />
          <KeyValue label="状态">
            <span v-if="backend" :style="{ color: backend.ok ? 'var(--accent)' : 'var(--danger)' }">
              {{ backend.message }}
            </span>
            <span v-else class="weak">检测中…</span>
          </KeyValue>
          <KeyValue v-if="backend?.ok" label="系统数" :value="backend.subsystems" mono />
        </DataSection>

        <DataSection title="运行时">
          <KeyValue label="Webhook" value="POST /api/v1/hooks/coding（HMAC-SHA256）" mono />
          <KeyValue label="门禁 / 验证确认" value="P2 已交付" />
          <KeyValue label="生产管控" value="P3（审批 / 窗口 / 封板）" />
        </DataSection>
      </div>
    </div>
  </div>
</template>

<style scoped>
.conn-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px 18px;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.field-label {
  font-size: 12px;
  color: var(--text-2);
}

.span-2 {
  grid-column: span 2;
}

.hint {
  gap: 4px;
  color: var(--text-3);
  font-size: 12px;
  line-height: 1.7;
  padding-top: 2px;
}

.restricted {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 12px 14px;
  border: 1px dashed var(--border-strong);
  border-radius: var(--radius-sm);
  background: var(--surface);
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

.probe {
  margin-top: 14px;
  padding: 12px 14px;
  border: 1px solid var(--border);
  border-left: 3px solid var(--text-3);
  border-radius: var(--radius-sm);
  background: var(--surface);
}

.probe.is-ok {
  border-left-color: var(--accent);
  background: var(--accent-weak);
}

.probe.is-fail {
  border-left-color: var(--danger);
  background: var(--danger-weak);
}

.probe-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
}

.probe-title {
  font-weight: 600;
}

.probe-message {
  margin: 6px 0 0;
  color: var(--text-2);
  font-size: 13px;
  line-height: 1.6;
  word-break: break-word;
}

.probe-counts {
  display: flex;
  gap: 18px;
  margin-top: 8px;
  color: var(--text-2);
  font-size: 12px;
}

.probe-counts b {
  color: var(--text);
  font-size: 15px;
  font-weight: 600;
  margin-right: 2px;
}

/* 「消息通知」 block: same field grid as CODING, one column so the webhook gets the full width. */
.notify-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 14px;
  max-width: 760px;
}

.switch-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
}

.notify-audit {
  margin: 12px 0 0;
}

.grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 16px;
}

.stack {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

@media (max-width: 960px) {
  .grid,
  .conn-grid {
    grid-template-columns: 1fr !important;
  }

  .span-2 {
    grid-column: span 1;
  }
}
</style>