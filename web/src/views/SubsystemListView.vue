<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { createSubsystem, listSubsystems } from '../api'
import type { PageResult, SubsystemView } from '../api/types'
import { ApiError } from '../api/http'
import PageHeader from '../components/PageHeader.vue'
import DataSection from '../components/DataSection.vue'
import EmptyState from '../components/EmptyState.vue'

const rows = ref<PageResult<SubsystemView> | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)
const keyword = ref('')
const dialog = ref(false)
const saving = ref(false)

const draft = reactive({ name: '', ownerId: 'dev', agentId: '', description: '', gitUrl: '', codingProject: '' })

async function load() {
  loading.value = true
  error.value = null
  try {
    rows.value = await listSubsystems(1, 100, keyword.value || undefined)
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)

async function submit() {
  if (!draft.name.trim() || !draft.ownerId.trim()) {
    ElMessage.warning('名称与负责人必填')
    return
  }
  saving.value = true
  try {
    await createSubsystem({
      name: draft.name.trim(),
      ownerId: draft.ownerId.trim(),
      agentId: draft.agentId || undefined,
      description: draft.description || undefined,
      gitUrl: draft.gitUrl || undefined,
      codingProject: draft.codingProject || undefined
    })
    dialog.value = false
    draft.name = ''
    ElMessage.success('子系统已创建')
    await load()
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '创建失败')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div>
    <PageHeader title="子系统" description="研发组织单元，下挂部署单元与二方库。">
      <el-button type="primary" @click="dialog = true">新建子系统</el-button>
    </PageHeader>

    <el-alert v-if="error" type="error" :closable="false" show-icon :title="error" style="margin-bottom: 16px" />

    <DataSection title="全部子系统" :subtitle="`${rows?.total ?? 0} 个`" flush>
      <template #actions>
        <el-input v-model="keyword" placeholder="名称" clearable style="width: 180px" @keyup.enter="load" @clear="load" />
        <el-button @click="load">查询</el-button>
      </template>

      <el-table v-if="rows && rows.records.length" :data="rows.records" row-key="id" style="width: 100%">
        <el-table-column prop="name" label="子系统" min-width="200">
          <template #default="{ row }">
            <router-link :to="`/subsystems/${row.id}`" class="strong">{{ row.name }}</router-link>
            <div class="weak small">{{ row.description || '—' }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="ownerId" label="负责人" width="120" />
        <el-table-column prop="agentId" label="授权代理" width="120" />
        <el-table-column prop="codingProject" label="CODING 项目" min-width="140">
          <template #default="{ row }"><span class="mono">{{ row.codingProject || '—' }}</span></template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="100" />
      </el-table>
      <EmptyState v-else-if="!loading" title="还没有子系统" hint="先建子系统，再建部署单元。" />
      <div v-else style="padding: 24px"><el-skeleton :rows="4" animated /></div>
    </DataSection>

    <el-dialog v-model="dialog" title="新建子系统" width="560px">
      <el-form label-position="top">
        <div class="grid grid-2">
          <el-form-item label="名称" required>
            <el-input v-model="draft.name" placeholder="例如 SCC-PORTAL-CORE-OS" maxlength="128" />
          </el-form-item>
          <el-form-item label="负责人工号" required>
            <el-input v-model="draft.ownerId" maxlength="32" />
          </el-form-item>
          <el-form-item label="工具授权代理">
            <el-input v-model="draft.agentId" maxlength="32" />
          </el-form-item>
          <el-form-item label="CODING 项目">
            <el-input v-model="draft.codingProject" maxlength="128" />
          </el-form-item>
        </div>
        <el-form-item label="Git 仓库地址">
          <el-input v-model="draft.gitUrl" placeholder="git url" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="draft.description" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>