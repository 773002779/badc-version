<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { listSubsystems } from '../api'
import { ApiError, currentUser, setCurrentUser } from '../api/http'
import { applyTheme, currentTheme } from '../theme'
import type { Theme } from '../theme'
import PageHeader from '../components/PageHeader.vue'
import DataSection from '../components/DataSection.vue'
import KeyValue from '../components/KeyValue.vue'

const theme = ref<Theme>(currentTheme())
const userId = ref(currentUser())
const checking = ref(false)
const backend = ref<{ ok: boolean; message: string; subsystems?: number } | null>(null)

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

onMounted(checkBackend)
</script>

<template>
  <div>
    <PageHeader title="设置" description="本地偏好与对接状态。P1 只有构建与测试部署。" />

    <div class="grid" style="grid-template-columns: minmax(0, 1fr) minmax(0, 1fr)">
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

        <DataSection title="对接状态">
          <KeyValue label="CODING 连接" value="需在 coding_conn 表配置（暂无管理接口）" />
          <KeyValue label="端点模板" value="server/src/main/resources/coding-endpoints.yml" mono />
          <KeyValue label="Webhook" value="POST /api/v1/hooks/coding（HMAC-SHA256）" mono />
          <KeyValue label="门禁 / 生产管控" value="P2 / P3 交付" />
        </DataSection>
      </div>
    </div>
  </div>
</template>

<style scoped>
@media (max-width: 960px) {
  .grid {
    grid-template-columns: 1fr !important;
  }
}
</style>