<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { createUnit, getMe, getSubsystem, listUnits, updateSubsystem } from '../api'
import type { SubsystemView, UnitView } from '../api/types'
import { ApiError } from '../api/http'
import PageHeader from '../components/PageHeader.vue'
import DataSection from '../components/DataSection.vue'
import EmptyState from '../components/EmptyState.vue'
import KeyValue from '../components/KeyValue.vue'
import StatusDot from '../components/StatusDot.vue'
import { ARTIFACT_COORDINATE_HINT, artifactCoordinateError } from '../utils/artifact'

const route = useRoute()
const id = Number(route.params.id)

const subsystem = ref<SubsystemView | null>(null)
const units = ref<UnitView[]>([])
const loading = ref(true)
const error = ref<string | null>(null)
const canManage = ref(false)
const dialog = ref(false)
const projectDialog = ref(false)
const projectDraft = ref('')
const savingProject = ref(false)
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
    const [s, u, me] = await Promise.all([
      getSubsystem(id),
      listUnits({ pageNo: 1, pageSize: 100, subsystemId: id }),
      getMe()
    ])
    subsystem.value = s
    units.value = u.records
    canManage.value = me.perms.includes('setting:manage')
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '加载失败'
  } finally {
    loading.value = false
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
    <PageHeader :title="subsystem?.name ?? '系统详情'" description="基本信息与下属部署单元。">
      <el-button @click="load">刷新</el-button>
      <el-button type="primary" @click="dialog = true">新建部署单元</el-button>
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
</style>