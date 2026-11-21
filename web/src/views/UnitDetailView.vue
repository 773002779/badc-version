<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { addEnvDe, bindCodingTasks, getSubsystem, getUnit, listCodingJobs, listCodingTasks, listEnvDes, updateUnit } from '../api'
import type { BindingView, CodingJobView, EnvDeView, UnitView } from '../api/types'
import { ApiError } from '../api/http'
import PageHeader from '../components/PageHeader.vue'
import DataSection from '../components/DataSection.vue'
import EmptyState from '../components/EmptyState.vue'
import KeyValue from '../components/KeyValue.vue'
import { formatDateTime } from '../utils/format'

const route = useRoute()
const id = Number(route.params.id)

const unit = ref<UnitView | null>(null)
const subsystemName = ref<string | null>(null)
const envDes = ref<EnvDeView[]>([])
const savedBindings = ref<Record<string, BindingView>>({})
const loading = ref(true)
const error = ref<string | null>(null)
const savingBindings = ref(false)

const KINDS = ['BUILD', 'TEST_DEPLOY', 'PROD_DEPLOY', 'DAILY_DEPLOY'] as const
type Kind = (typeof KINDS)[number]

const bindings = reactive({
  BUILD: { taskId: '', branch: '' },
  TEST_DEPLOY: { taskId: '' },
  PROD_DEPLOY: { taskId: '' },
  DAILY_DEPLOY: { taskId: '' }
})

const hasBinding = (kind: Kind) => Boolean(savedBindings.value[kind]?.taskId)
const bindingSummary = computed(() => {
  const bound = KINDS.filter(hasBinding).length
  return bound ? `已绑定 ${bound} / 4` : '尚未绑定'
})

const PROD_FAMILY = ['PROD', 'DR', 'BLUE']
const checks = computed(() => [
  { label: '构建任务', hint: 'BUILD', ok: hasBinding('BUILD') },
  { label: '测试部署任务', hint: 'TEST_DEPLOY', ok: hasBinding('TEST_DEPLOY') },
  { label: '生产部署任务', hint: 'PROD_DEPLOY', ok: hasBinding('PROD_DEPLOY') },
  { label: '测试环境 DE', hint: 'envKind = TEST', ok: envDes.value.some((d) => d.envKind === 'TEST') },
  {
    label: '生产族环境 DE',
    hint: PROD_FAMILY.join(' / '),
    ok: envDes.value.some((d) => PROD_FAMILY.includes(d.envKind))
  }
])
const missing = computed(() => checks.value.filter((c) => !c.ok).length)

const deDialog = ref(false)
const deForm = reactive({ envKind: 'TEST', deName: '', envTag: '', deployBatchNo: 0 })

// ---- CODING job candidates -------------------------------------------------
// The picker asks the engine which jobs the system's project has. When the connection is
// missing the lists stay empty and the selects keep allow-create, so an id can still be typed.
const jobOptions = reactive({ BUILD: [] as CodingJobView[], DEPLOY: [] as CodingJobView[] })
const jobsLoading = ref(false)
const jobsHint = ref<string | null>(null)

async function loadJobs() {
  jobsLoading.value = true
  jobsHint.value = null
  try {
    const [build, deploy] = await Promise.all([listCodingJobs(id, 'BUILD'), listCodingJobs(id, 'TEST_DEPLOY')])
    jobOptions.BUILD = build
    jobOptions.DEPLOY = deploy
  } catch (e) {
    jobOptions.BUILD = []
    jobOptions.DEPLOY = []
    jobsHint.value =
      e instanceof ApiError
        ? `读取 CODING 任务失败：${e.message} 可先手动填写任务 ID。`
        : '读取 CODING 任务失败，可先手动填写任务 ID。'
  } finally {
    jobsLoading.value = false
  }
}

const optionLabel = (job: CodingJobView) => (job.name ? `${job.name} · ${job.taskId}` : job.taskId)

// ---- edit dialog -----------------------------------------------------------
const editDialog = ref(false)
const saving = ref(false)
const editForm = reactive({
  name: '',
  ownerId: '',
  type: 'UNIT',
  status: 'ACTIVE',
  du: '',
  frameworkTemplate: '',
  branchMode: 'STANDARD',
  description: '',
  module: '',
  tag: '',
  modulePath: '',
  deployType: '',
  imageType: '',
  imageName: ''
})

function openEdit() {
  const u = unit.value
  if (!u) return
  editForm.name = u.name ?? ''
  editForm.ownerId = u.ownerId ?? ''
  editForm.type = u.type ?? 'UNIT'
  editForm.status = u.status ?? 'ACTIVE'
  editForm.du = u.du ?? ''
  editForm.frameworkTemplate = u.frameworkTemplate ?? ''
  editForm.branchMode = u.branchMode ?? 'STANDARD'
  editForm.description = u.description ?? ''
  editForm.module = u.module ?? ''
  editForm.tag = u.tag ?? ''
  editForm.modulePath = u.modulePath ?? ''
  editForm.deployType = u.deployType ?? ''
  editForm.imageType = u.imageType ?? ''
  editForm.imageName = u.imageName ?? ''
  editDialog.value = true
}

async function submitEdit() {
  if (!editForm.name.trim()) {
    ElMessage.warning('名称必填')
    return
  }
  if (!editForm.ownerId.trim()) {
    ElMessage.warning('负责人必填')
    return
  }
  saving.value = true
  try {
    await updateUnit(id, {
      name: editForm.name.trim(),
      ownerId: editForm.ownerId.trim(),
      type: editForm.type,
      status: editForm.status,
      du: editForm.du.trim() || undefined,
      frameworkTemplate: editForm.frameworkTemplate.trim() || undefined,
      branchMode: editForm.branchMode,
      description: editForm.description.trim() || undefined,
      module: editForm.module.trim() || undefined,
      tag: editForm.tag.trim() || undefined,
      modulePath: editForm.modulePath.trim() || undefined,
      deployType: editForm.deployType.trim() || undefined,
      imageType: editForm.imageType.trim() || undefined,
      imageName: editForm.imageName.trim() || undefined
    })
    editDialog.value = false
    ElMessage.success('部署单元已保存')
    await load()
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}

function applyBindings(list: BindingView[]) {
  const map: Record<string, BindingView> = {}
  for (const b of list) map[b.taskKind] = b
  savedBindings.value = map

  for (const kind of KINDS) {
    bindings[kind].taskId = map[kind]?.taskId ?? ''
  }
  bindings.BUILD.branch = ''
  try {
    const params = map.BUILD?.paramsJson ? JSON.parse(map.BUILD.paramsJson) : null
    if (params && typeof params.branch === 'string') bindings.BUILD.branch = params.branch
  } catch {
    // params_json is free-form; a non-object payload simply yields no branch hint
  }
}

async function load() {
  loading.value = true
  error.value = null
  try {
    const [u, des, bs] = await Promise.all([getUnit(id), listEnvDes(id), listCodingTasks(id)])
    unit.value = u
    envDes.value = des
    applyBindings(bs)
    if (u.subsystemId) {
      getSubsystem(u.subsystemId)
        .then((s) => (subsystemName.value = s.name))
        .catch(() => (subsystemName.value = null))
    }
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  load()
  loadJobs()
})

async function saveBindings() {
  const list: Array<{ taskKind: string; taskId: string; paramsJson?: string }> = []
  if (bindings.BUILD.taskId.trim()) {
    const params = bindings.BUILD.branch.trim() ? JSON.stringify({ branch: bindings.BUILD.branch.trim() }) : undefined
    list.push({ taskKind: 'BUILD', taskId: bindings.BUILD.taskId.trim(), paramsJson: params })
  }
  for (const kind of ['TEST_DEPLOY', 'PROD_DEPLOY', 'DAILY_DEPLOY'] as const) {
    const taskId = bindings[kind].taskId.trim()
    if (taskId) {
      list.push({ taskKind: kind, taskId })
    }
  }
  if (!list.length) {
    ElMessage.warning('至少填写一个 CODING 任务 ID')
    return
  }
  savingBindings.value = true
  try {
    await bindCodingTasks(id, list)
    ElMessage.success('CODING 任务绑定已保存')
    await load()
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '保存失败')
  } finally {
    savingBindings.value = false
  }
}

async function submitDe() {
  if (!deForm.deName.trim()) {
    ElMessage.warning('DE 名称必填')
    return
  }
  try {
    await addEnvDe(id, {
      envKind: deForm.envKind,
      deName: deForm.deName.trim(),
      envTag: deForm.envTag || undefined,
      deployBatchNo: deForm.deployBatchNo
    })
    deDialog.value = false
    deForm.deName = ''
    ElMessage.success('环境 DE 已添加')
    await load()
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '添加失败')
  }
}
</script>

<template>
  <div>
    <PageHeader
      :title="unit?.name ?? '部署单元'"
      :description="unit ? `${unit.type} · ${subsystemName ?? '系统 #' + unit.subsystemId} · owner ${unit.ownerId}` : ''"
    >
      <el-button type="primary" @click="openEdit">编辑</el-button>
      <el-button @click="load">刷新</el-button>
    </PageHeader>

    <el-alert v-if="error" type="error" :closable="false" show-icon :title="error" style="margin-bottom: 16px" />

    <div class="grid" style="grid-template-columns: 360px minmax(0, 1fr)">
      <div class="stack">
        <DataSection title="基本信息">
          <KeyValue label="单元 ID" :value="unit?.id" mono />
          <KeyValue label="所属系统">
            <router-link :to="`/subsystems/${unit?.subsystemId}`">
              {{ subsystemName ?? `#${unit?.subsystemId}` }}
            </router-link>
          </KeyValue>
          <KeyValue label="类型" :value="unit?.type" />
          <KeyValue label="DU" :value="unit?.du" mono />
          <KeyValue label="框架模板" :value="unit?.frameworkTemplate" />
          <KeyValue label="分支模式" :value="unit?.branchMode" />
          <KeyValue label="模块" :value="unit?.module" />
          <KeyValue label="标签" :value="unit?.tag" />
          <KeyValue label="模块路径" :value="unit?.modulePath" mono />
          <KeyValue label="镜像" :value="unit?.imageName" mono />
          <KeyValue label="状态" :value="unit?.status" />
          <KeyValue label="创建时间" :value="formatDateTime(unit?.createdAt)" mono />
        </DataSection>

        <DataSection title="环境 DE" :subtitle="`${envDes.length} 个`">
          <template #actions>
            <el-button text @click="deDialog = true">添加</el-button>
          </template>
          <div v-if="envDes.length" class="stack" style="gap: 10px">
            <div v-for="de in envDes" :key="de.id" class="de-row">
              <el-tag size="small" effect="plain">{{ de.envKind }}</el-tag>
              <span class="mono truncate">{{ de.deName }}</span>
              <span class="weak small">批次 {{ de.deployBatchNo }}</span>
            </div>
          </div>
          <EmptyState v-else title="未配置 DE" hint="发布需要至少 1 个测试 DE 与 1 个生产族 DE。" />
        </DataSection>
      </div>

      <div class="stack">
        <DataSection title="CODING 任务绑定" :subtitle="bindingSummary">
          <template #actions>
            <el-button text :loading="jobsLoading" @click="loadJobs">刷新候选</el-button>
            <el-button type="primary" :loading="savingBindings" @click="saveBindings">保存</el-button>
          </template>
          <el-form label-position="top">
            <div class="grid grid-2">
              <el-form-item>
                <template #label>
                  <span class="field-label">构建任务 <span class="req">*</span>
                    <span v-if="hasBinding('BUILD')" class="bound">已绑定</span>
                  </span>
                </template>
                <el-select
                  v-model="bindings.BUILD.taskId"
                  filterable
                  allow-create
                  default-first-option
                  :loading="jobsLoading"
                  placeholder="选择 CODING 构建任务"
                  style="width: 100%"
                >
                  <el-option
                    v-for="job in jobOptions.BUILD"
                    :key="job.taskId"
                    :label="optionLabel(job)"
                    :value="job.taskId"
                  />
                </el-select>
              </el-form-item>
              <el-form-item>
                <template #label>
                  <span class="field-label">只读分支
                    <span class="hint-mono">params_json.branch</span>
                  </span>
                </template>
                <el-input v-model="bindings.BUILD.branch" placeholder="release/202611" />
              </el-form-item>
              <el-form-item>
                <template #label>
                  <span class="field-label">测试部署任务 <span class="req">*</span>
                    <span v-if="hasBinding('TEST_DEPLOY')" class="bound">已绑定</span>
                  </span>
                </template>
                <el-select
                  v-model="bindings.TEST_DEPLOY.taskId"
                  filterable
                  allow-create
                  default-first-option
                  :loading="jobsLoading"
                  placeholder="选择 CODING 部署任务"
                  style="width: 100%"
                >
                  <el-option
                    v-for="job in jobOptions.DEPLOY"
                    :key="job.taskId"
                    :label="optionLabel(job)"
                    :value="job.taskId"
                  />
                </el-select>
              </el-form-item>
              <el-form-item>
                <template #label>
                  <span class="field-label">生产部署任务 <span class="req">*</span>
                    <span v-if="hasBinding('PROD_DEPLOY')" class="bound">已绑定</span>
                  </span>
                </template>
                <el-select
                  v-model="bindings.PROD_DEPLOY.taskId"
                  filterable
                  allow-create
                  default-first-option
                  :loading="jobsLoading"
                  placeholder="选择 CODING 部署任务"
                  style="width: 100%"
                >
                  <el-option
                    v-for="job in jobOptions.DEPLOY"
                    :key="job.taskId"
                    :label="optionLabel(job)"
                    :value="job.taskId"
                  />
                </el-select>
              </el-form-item>
              <el-form-item>
                <template #label>
                  <span class="field-label">日常部署任务
                    <span v-if="hasBinding('DAILY_DEPLOY')" class="bound">已绑定</span>
                    <span v-else class="weak small">可选</span>
                  </span>
                </template>
                <el-select
                  v-model="bindings.DAILY_DEPLOY.taskId"
                  filterable
                  allow-create
                  default-first-option
                  clearable
                  :loading="jobsLoading"
                  placeholder="留空复用测试部署"
                  style="width: 100%"
                >
                  <el-option
                    v-for="job in jobOptions.DEPLOY"
                    :key="job.taskId"
                    :label="optionLabel(job)"
                    :value="job.taskId"
                  />
                </el-select>
              </el-form-item>
            </div>
          </el-form>
          <p v-if="jobsHint" class="jobs-hint small">{{ jobsHint }}</p>
          <p v-else class="weak small" style="margin: 4px 0 0">
            候选来自所属系统的 CODING 项目；列表里没有的任务可以直接输入任务 ID。
            构建唯一、部署按环境分开；保存按类型覆盖，留空的类型保留原有绑定。
          </p>
          <p class="weak small" style="margin: 4px 0 0">
            首次发布需三类绑定齐全，缺少 BUILD 分支会在创建变更项时返回 40011。
          </p>
        </DataSection>

        <DataSection title="发布前置条件" :subtitle="missing ? `缺 ${missing} 项` : '已就绪'">
          <div class="check-list">
            <div v-for="c in checks" :key="c.label" class="check-row">
              <span class="check-mark" :class="c.ok ? 'ok' : 'no'" />
              <span class="check-label">{{ c.label }}</span>
              <span class="weak small mono">{{ c.hint }}</span>
            </div>
          </div>
          <p v-if="missing" class="weak small" style="margin: 10px 0 0">
            缺少任一条件时，创建变更项会被拒绝（40011）；BUILD 的只读分支用于把变更项锁定到固定分支。
          </p>
        </DataSection>
      </div>
    </div>

    <p class="weak small footnote">
      CODING 连接（base_url / 凭证）目前没有管理接口，需要在库表 <code class="mono">coding_conn</code> 中配置；
      端点模板在 <code class="mono">coding-endpoints.yml</code>，改配置即可，不需要改代码。
    </p>

    <el-dialog v-model="editDialog" title="编辑部署单元" width="640px" top="6vh">
      <div class="dialog-scroll">
        <el-form label-position="top">
        <div class="grid grid-2">
          <el-form-item label="名称" required>
            <el-input v-model="editForm.name" placeholder="例如 quota-api" />
          </el-form-item>
          <el-form-item label="负责人" required>
            <el-input v-model="editForm.ownerId" placeholder="工号，需为在职员工" />
          </el-form-item>
          <el-form-item label="类型">
            <el-select v-model="editForm.type" style="width: 100%">
              <el-option label="部署单元" value="UNIT" />
              <el-option label="二方库" value="LIB" />
            </el-select>
          </el-form-item>
          <el-form-item label="状态">
            <el-select v-model="editForm.status" style="width: 100%">
              <el-option label="ACTIVE" value="ACTIVE" />
              <el-option label="INACTIVE" value="INACTIVE" />
            </el-select>
          </el-form-item>
          <el-form-item label="模块（仅筛选/标签）">
            <el-input v-model="editForm.module" placeholder="例如 配额、计费" />
          </el-form-item>
          <el-form-item label="标签（仅筛选/标签）">
            <el-input v-model="editForm.tag" placeholder="单个值，例如 core" />
          </el-form-item>
          <el-form-item label="DU">
            <el-input v-model="editForm.du" placeholder="DU-QUOTA" />
          </el-form-item>
          <el-form-item label="框架模板">
            <el-input v-model="editForm.frameworkTemplate" placeholder="spring-boot" />
          </el-form-item>
          <el-form-item label="分支模式">
            <el-select v-model="editForm.branchMode" style="width: 100%">
              <el-option label="STANDARD" value="STANDARD" />
              <el-option label="COMPATIBLE" value="COMPATIBLE" />
            </el-select>
          </el-form-item>
          <el-form-item label="模块路径（共库 commit 比对）">
            <el-input v-model="editForm.modulePath" placeholder="outbound-os" />
          </el-form-item>
          <el-form-item label="部署类型">
            <el-input v-model="editForm.deployType" placeholder="K8S" />
          </el-form-item>
          <el-form-item label="镜像类型">
            <el-input v-model="editForm.imageType" placeholder="IMAGE" />
          </el-form-item>
        </div>
        <el-form-item label="镜像名称">
          <el-input v-model="editForm.imageName" placeholder="quota-api:latest" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="editForm.description" type="textarea" :rows="2" placeholder="一句话说明这个单元" />
        </el-form-item>
        </el-form>
        <p class="weak small" style="margin: -4px 0 0">
          保存是整体替换：留空的可选字段会被清空。模块与标签只用于筛选和展示，不进入构建/部署参数。
        </p>
      </div>
      <template #footer>
        <el-button @click="editDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitEdit">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="deDialog" title="添加环境 DE" width="480px">
      <el-form label-position="top">
        <div class="grid grid-2">
          <el-form-item label="环境类型" required>
            <el-select v-model="deForm.envKind" style="width: 100%">
              <el-option v-for="k in ['TEST', 'DAILY', 'PROD', 'DR', 'BLUE']" :key="k" :label="k" :value="k" />
            </el-select>
          </el-form-item>
          <el-form-item label="批次号">
            <el-input-number v-model="deForm.deployBatchNo" :min="0" style="width: 100%" />
          </el-form-item>
        </div>
        <el-form-item label="DE 名称" required>
          <el-input v-model="deForm.deName" placeholder="例如 cwsp-quota-api-sit" />
        </el-form-item>
        <el-form-item label="环境标签">
          <el-input v-model="deForm.envTag" placeholder="例如 sit2 / prod2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="deDialog = false">取消</el-button>
        <el-button type="primary" @click="submitDe">添加</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.de-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.field-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.req {
  color: var(--danger);
  font-weight: 600;
}
.hint-mono {
  font-family: ui-monospace, 'JetBrains Mono', Consolas, 'Courier New', monospace;
  font-size: 11px;
  color: var(--text-3);
  font-weight: 400;
}
.bound {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  font-weight: 400;
  color: var(--text-3);
}
.bound::before {
  content: '';
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--accent);
}

.check-list {
  display: flex;
  flex-direction: column;
}
.check-row {
  display: grid;
  grid-template-columns: 16px 1fr auto;
  align-items: center;
  gap: 10px;
  padding: 6px 0;
  border-bottom: 1px dashed var(--border);
  font-size: 13px;
}
.check-row:last-child {
  border-bottom: none;
}
.check-label {
  color: var(--text);
}
.check-mark {
  width: 14px;
  height: 14px;
  border-radius: 50%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 9px;
  line-height: 1;
}
.check-mark.ok {
  background: var(--accent-weak);
  color: var(--accent);
}
.check-mark.ok::after {
  content: '✓';
}
.check-mark.no {
  background: var(--danger-weak);
  color: var(--danger);
}
.check-mark.no::after {
  content: '!';
  font-weight: 700;
}

.footnote {
  margin: 16px 2px 0;
  line-height: 1.7;
}
.footnote code {
  color: var(--text-2);
}
.jobs-hint {
  margin: 4px 0 0;
  color: var(--warn);
}
.dialog-scroll {
  max-height: 62vh;
  overflow-y: auto;
  padding-right: 6px;
}

@media (max-width: 960px) {
  .grid {
    grid-template-columns: 1fr !important;
  }
}
</style>