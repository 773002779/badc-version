<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  createUnit,
  getCodingConn,
  getMe,
  getSubsystem,
  listReleases,
  listUnits,
  saveCodingConn,
  updateSubsystem
} from '../api'
import type { CodingConnView, ReleaseRecordRow, SubsystemView, UnitView } from '../api/types'
import { ApiError } from '../api/http'
import PageHeader from '../components/PageHeader.vue'
import DataSection from '../components/DataSection.vue'
import EmptyState from '../components/EmptyState.vue'
import KeyValue from '../components/KeyValue.vue'
import StatusDot from '../components/StatusDot.vue'
import { formatDateTime } from '../utils/format'
import { ARTIFACT_COORDINATE_HINT, artifactCoordinateError } from '../utils/artifact'

const route = useRoute()
const id = Number(route.params.id)

const subsystem = ref<SubsystemView | null>(null)
const units = ref<UnitView[]>([])
// PART-01: the system's release records (the 变更明细 is carried by each record row's
// CRID/version) — best-effort, an unreadable list renders the empty state.
const releases = ref<ReleaseRecordRow[]>([])
const loading = ref(true)
const error = ref<string | null>(null)
const canManage = ref(false)
const dialog = ref(false)
const projectDialog = ref(false)
const projectDraft = ref('')
const savingProject = ref(false)
const releaseCount = ref(0)
const hasReleases = computed(() => releases.value.length > 0)
const form = reactive({
  name: '',
  ownerId: 'dev',
  type: 'UNIT',
  modulePath: '',
  frameworkTemplate: 'spring-boot',
  artifactGroupId: '',
  artifactArtifactId: ''
})

// A UNIT has no artifact (P6-A design §1); switching back clears what was typed so the form never
// carries hidden coordinates into a UNIT create.
watch(
  () => form.type,
  (type) => {
    if (type !== 'LIB') {
      form.artifactGroupId = ''
      form.artifactArtifactId = ''
    }
  }
)

/** The LIB coordinate rule shared with the edit dialog; a UNIT is never checked. */
function libCoordinatesValid(): boolean {
  const error =
    artifactCoordinateError(form.artifactGroupId, '制品 groupId') ??
    artifactCoordinateError(form.artifactArtifactId, '制品 artifactId')
  if (error) {
    ElMessage.warning(error)
    return false
  }
  return true
}

async function load() {
  loading.value = true
  error.value = null
  try {
    const [s, u, me, records] = await Promise.all([
      getSubsystem(id),
      listUnits({ pageNo: 1, pageSize: 100, subsystemId: id }),
      getMe(),
      listReleases({ systemId: id, size: 10 }).catch(() => null)
    ])
    subsystem.value = s
    units.value = u.records
    canManage.value = me.perms.includes('setting:manage')
    // PART-01: the system's newest release records (server-side data scope applies).
    releases.value = records?.records ?? []
    releaseCount.value = records?.total ?? 0
    // PART-05: this system's CODING connection override (best-effort — a caller without
    // setting:manage gets 403 and the block stays read-only).
    if (canManage.value) {
      await loadOverride()
    }
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

// PART-05: the per-subsystem CODING connection override of this system. The engine resolves
// GLOBAL→SUBSYSTEM per unit, so a system with its own tenant only overrides here.
const connOverride = ref<CodingConnView | null>(null)
const overrideError = ref<string | null>(null)
const overrideSaving = ref(false)
const overrideForm = reactive({ baseUrl: '', team: '', project: '', token: '' })

async function loadOverride() {
  overrideError.value = null
  try {
    const view = await getCodingConn(id)
    connOverride.value = view
    overrideForm.baseUrl = view.baseUrl ?? ''
    overrideForm.team = view.team ?? ''
    overrideForm.project = view.project ?? ''
    overrideForm.token = ''
  } catch (e) {
    overrideError.value = e instanceof ApiError ? e.message : '读取连接覆盖失败'
  }
}

async function saveOverride() {
  if (!overrideForm.baseUrl.trim()) {
    ElMessage.warning('覆盖连接的地址必填')
    return
  }
  overrideSaving.value = true
  try {
    const view = await saveCodingConn(
      {
        baseUrl: overrideForm.baseUrl.trim(),
        team: overrideForm.team.trim(),
        project: overrideForm.project.trim(),
        token: overrideForm.token.trim() || undefined
      },
      id
    )
    connOverride.value = view
    ElMessage.success('CODING 连接覆盖已保存（引擎按 GLOBAL→系统 解析）')
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '保存失败')
  } finally {
    overrideSaving.value = false
  }
}

onMounted(load)

function editProject() {
  projectDraft.value = subsystem.value?.codingProject ?? ''
  projectDialog.value = true
}

async function saveProject() {
  const s = subsystem.value
  if (!s) return
  savingProject.value = true
  try {
    await updateSubsystem(id, {
      name: s.name,
      ownerId: s.ownerId,
      agentId: s.agentId ?? undefined,
      status: s.status,
      description: s.description ?? undefined,
      gitUrl: s.gitUrl ?? undefined,
      codingProject: projectDraft.value.trim()
    })
    projectDialog.value = false
    ElMessage.success('CODING 项目已保存')
    await load()
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '保存失败')
  } finally {
    savingProject.value = false
  }
}

async function submit() {
  if (!form.name.trim()) {
    ElMessage.warning('名称必填')
    return
  }
  if (form.type === 'LIB' && !libCoordinatesValid()) return
  try {
    await createUnit({
      subsystemId: id,
      name: form.name.trim(),
      ownerId: form.ownerId.trim() || 'dev',
      type: form.type,
      modulePath: form.modulePath || undefined,
      frameworkTemplate: form.frameworkTemplate || undefined,
      artifactGroupId: form.type === 'LIB' ? form.artifactGroupId.trim() : undefined,
      artifactArtifactId: form.type === 'LIB' ? form.artifactArtifactId.trim() : undefined
    })
    dialog.value = false
    form.name = ''
    form.artifactGroupId = ''
    form.artifactArtifactId = ''
    ElMessage.success('部署单元已创建')
    await load()
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '创建失败')
  }
}
</script>

<template>
  <div>
    <PageHeader :title="subsystem?.name ?? '系统详情'" description="基本信息、部署单元与发布记录。">
      <el-button @click="load">刷新</el-button>
      <el-button type="primary" @click="dialog = true">新建部署单元</el-button>
      <!-- PART-01: the 管控配置 entry jumps to the control page with this system preselected. -->
      <router-link :to="{ path: '/control', query: { subsystemId: id } }">
        <el-button title="查看该系统的冻结与发布窗口（生产管控页预选本系统）">管控配置</el-button>
      </router-link>
    </PageHeader>

    <el-alert v-if="error" type="error" :closable="false" show-icon :title="error" style="margin-bottom: 16px" />

    <div class="grid" style="grid-template-columns: 360px minmax(0, 1fr)">
      <DataSection title="基本信息">
        <KeyValue label="系统 ID" :value="subsystem?.id" mono />
        <KeyValue label="负责人" :value="subsystem?.ownerId" />
        <KeyValue label="授权代理" :value="subsystem?.agentId" />
        <KeyValue label="状态">
            <StatusDot :status="subsystem?.status" />
          </KeyValue>
        <KeyValue label="CODING 项目">
          <span class="row" style="gap: 8px">
            <span class="mono">{{ subsystem?.codingProject || '—' }}</span>
            <el-button v-if="canManage" link type="primary" size="small" @click="editProject">
              编辑
            </el-button>
          </span>
          <span class="weak small" style="display: block; margin-top: 2px">
            未填时使用平台连接的默认项目
          </span>
        </KeyValue>
        <KeyValue label="Git 仓库" :value="subsystem?.gitUrl" mono />
        <KeyValue label="描述" :value="subsystem?.description" />
      </DataSection>

      <!-- PART-05: this system's CODING connection override (the engine resolves GLOBAL→系统). -->
      <DataSection class="coding-override" title="CODING 连接覆盖" :subtitle="connOverride && connOverride.configured ? '已配置' : '未配置（走全局连接）'" style="margin-bottom: 16px">
        <el-alert v-if="overrideError" type="error" :closable="false" show-icon :title="overrideError" style="margin-bottom: 12px" />
        <p class="weak small" style="margin: 0 0 12px">
          为本系统配置独立的 CODING 租户（地址 / 团队 / 项目 / 凭证）；引擎调用按
          <b>全局 → 系统覆盖</b> 的顺序解析，本系统下所有部署单元的构建、部署与检查都优先走这份连接。
          留空凭证表示沿用已保存的凭证；删除覆盖需管理员在数据库操作。
        </p>
        <div v-if="canManage" class="conn-grid">
          <label class="field span-2">
            <span class="field-label">服务地址</span>
            <input v-model="overrideForm.baseUrl" class="override-input" placeholder="https://coding.example.com" spellcheck="false" />
          </label>
          <label class="field">
            <span class="field-label">团队</span>
            <input v-model="overrideForm.team" class="override-input" placeholder="团队标识" spellcheck="false" />
          </label>
          <label class="field">
            <span class="field-label">项目</span>
            <input v-model="overrideForm.project" class="override-input" placeholder="项目标识" spellcheck="false" />
          </label>
          <label class="field span-2">
            <span class="field-label">
              访问凭证
              <span v-if="connOverride && connOverride.tokenHint" class="weak">（已保存 {{ connOverride.tokenHint }}，留空不修改）</span>
            </span>
            <input v-model="overrideForm.token" type="password" class="override-input" placeholder="留空沿用已保存凭证" autocomplete="new-password" />
          </label>
          <div class="span-2">
            <el-button type="primary" :loading="overrideSaving" @click="saveOverride">保存覆盖</el-button>
          </div>
        </div>
        <div v-else class="weak small">需要 <code class="mono">setting:manage</code> 权限才能查看与修改连接覆盖。</div>
      </DataSection>

      <DataSection title="部署单元" :subtitle="`${units.length} 个`" flush>
        <el-table v-if="units.length" :data="units" row-key="id" style="width: 100%">
          <el-table-column prop="name" label="名称" min-width="160">
            <template #default="{ row }">
              <router-link :to="`/units/${row.id}`" class="strong">{{ row.name }}</router-link>
              <div class="weak small">{{ row.modulePath || '—' }}</div>
            </template>
          </el-table-column>
          <el-table-column prop="type" label="类型" width="100" />
          <el-table-column prop="ownerId" label="owner" width="110" />
          <el-table-column prop="frameworkTemplate" label="框架模板" width="120" />
          <el-table-column prop="branchMode" label="分支模式" width="110" />
          <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <StatusDot :status="row.status" />
          </template>
        </el-table-column>
        </el-table>
        <EmptyState v-else-if="!loading" title="还没有部署单元" hint="部署单元是构建与部署的最小单位。" />
        <div v-else style="padding: 24px"><el-skeleton :rows="4" animated /></div>
      </DataSection>

      <!-- PART-01: the system's release records; each row carries the 变更明细 (CRID/version). -->
      <DataSection
        class="system-releases"
        title="发布记录"
        :subtitle="releaseCount ? `最近 ${releases.length} / ${releaseCount} 条` : '暂无记录'"
        flush
      >
        <template #actions>
          <router-link :to="{ path: '/records', query: { systemId: id } }" class="small">
            查看全部
          </router-link>
        </template>
        <el-table v-if="hasReleases" :data="releases" row-key="publishNo" style="width: 100%">
          <el-table-column label="发布单" min-width="170">
            <template #default="{ row }">
              <router-link :to="`/releases/${row.publishNo}`" class="mono">{{ row.publishNo }}</router-link>
            </template>
          </el-table-column>
          <el-table-column label="变更项" min-width="140">
            <template #default="{ row }">
              <span class="mono">{{ row.crid }}</span>
              <div class="weak small">{{ row.version }}</div>
            </template>
          </el-table-column>
          <el-table-column prop="unitName" label="部署单元" min-width="130">
            <template #default="{ row }">
              <router-link :to="`/units/${row.unitId}`">{{ row.unitName ?? row.unitId }}</router-link>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="130">
            <template #default="{ row }"><StatusDot :status="row.orderStatus" /></template>
          </el-table-column>
          <el-table-column label="创建时间" width="170">
            <template #default="{ row }">
              <span class="mono weak">{{ formatDateTime(row.createdAt) }}</span>
            </template>
          </el-table-column>
        </el-table>
        <EmptyState v-else-if="!loading" title="暂无发布记录" hint="该系统的变更项发布后，发布单会出现在这里。" />
        <div v-else style="padding: 24px"><el-skeleton :rows="4" animated /></div>
      </DataSection>
    </div>

    <el-dialog v-model="dialog" title="新建部署单元" width="520px">
      <el-form label-position="top">
        <div class="grid grid-2">
          <el-form-item label="名称" required>
            <el-input v-model="form.name" class="unit-name-input" placeholder="例如 quota-api" maxlength="128" />
          </el-form-item>
          <el-form-item label="类型">
            <el-select v-model="form.type" class="unit-type-select" style="width: 100%">
              <el-option label="部署单元" value="UNIT" />
              <el-option label="二方库" value="LIB" />
            </el-select>
          </el-form-item>
          <el-form-item label="owner 工号">
            <el-input v-model="form.ownerId" maxlength="32" />
          </el-form-item>
          <el-form-item v-if="form.type === 'LIB'" label="制品 groupId" required class="lib-coord-group">
            <el-input v-model="form.artifactGroupId" class="lib-group-input" maxlength="128" placeholder="例如 com.sf.demo" />
          </el-form-item>
          <el-form-item v-if="form.type === 'LIB'" label="制品 artifactId" required class="lib-coord-artifact">
            <el-input v-model="form.artifactArtifactId" class="lib-artifact-input" maxlength="128" placeholder="例如 demo-sdk" />
          </el-form-item>
          <el-form-item label="模块路径（共库时用于 commit 比对）">
            <el-input v-model="form.modulePath" maxlength="255" />
          </el-form-item>
        </div>
        <p v-if="form.type === 'LIB'" class="weak small coord-hint">
          二方库必须填写制品坐标；groupId 与 artifactId 分别匹配 {{ ARTIFACT_COORDINATE_HINT }}。
        </p>
        <el-form-item label="框架模板">
          <el-input v-model="form.frameworkTemplate" maxlength="64" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" @click="submit">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="projectDialog" title="CODING 项目" width="460px">
      <p class="weak small" style="margin: 0 0 10px">
        决定该系统的任务候选与构建归属。留空则继承「设置 → CODING 对接」里的默认项目。
      </p>
      <el-input v-model="projectDraft" placeholder="例如 SCC-QUOTA" maxlength="128" spellcheck="false" />
      <template #footer>
        <el-button @click="projectDialog = false">取消</el-button>
        <el-button type="primary" :loading="savingProject" @click="saveProject">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.coord-hint {
  margin: -2px 0 12px;
  line-height: 1.6;
}

@media (max-width: 960px) {
  .grid {
    grid-template-columns: 1fr !important;
  }
}
.override-input {
  width: 100%;
  height: 32px;
  padding: 0 11px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--bg);
  color: var(--text);
  font-size: 13px;
}
.override-input:focus {
  outline: none;
  border-color: var(--accent);
}
.conn-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px 18px;
}
.conn-grid .span-2 {
  grid-column: span 2;
}
</style>