<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  Grid,
  Monitor,
  Moon,
  Odometer,
  Setting,
  Sunny,
  Tickets,
  User
} from '@element-plus/icons-vue'
import { currentUser, setCurrentUser } from './api/http'
import { currentTheme, toggleTheme } from './theme'

const route = useRoute()
const router = useRouter()

const theme = ref(currentTheme())
const userId = ref(currentUser())
const userDialog = ref(false)
const draftUser = ref(userId.value)

const crumb = computed(() => (route.meta.crumb as string) ?? (route.meta.title as string) ?? '')
const parentPath = computed(() => route.meta.parent as string | undefined)
const parentLabel = computed(() => {
  if (!parentPath.value) return ''
  const resolved = router.resolve(parentPath.value)
  return (resolved.meta.title as string) ?? ''
})

function onToggleTheme() {
  theme.value = toggleTheme()
}

function saveUser() {
  setCurrentUser(draftUser.value)
  userId.value = currentUser()
  userDialog.value = false
  // A different identity means every cached page is stale; reload the current route.
  router.go(0)
}

const initial = computed(() => (userId.value || '?').slice(0, 1).toUpperCase())
</script>

<template>
  <div class="shell">
    <aside class="sidebar">
      <div class="brand">
        <svg class="brand-mark" viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <path d="M12 2.5 21 7v10l-9 4.5L3 17V7l9-4.5Z" stroke="currentColor" stroke-width="1.4" />
          <path d="M8 10.5 12 8l4 2.5v4L12 17l-4-2.5v-4Z" fill="currentColor" opacity=".28" />
        </svg>
        <div>
          <div class="brand-name">发版流水线</div>
          <div class="brand-sub">Release Pipeline</div>
        </div>
      </div>

      <div class="nav-group">研发流水线</div>
      <router-link class="nav-item" to="/tasks">
        <el-icon><Tickets /></el-icon><span>任务</span>
      </router-link>
      <router-link class="nav-item" to="/subsystems">
        <el-icon><Grid /></el-icon><span>子系统</span>
      </router-link>
      <router-link class="nav-item" to="/units">
        <el-icon><Monitor /></el-icon><span>部署单元</span>
      </router-link>

      <div class="nav-group">平台</div>
      <router-link class="nav-item" to="/">
        <el-icon><Odometer /></el-icon><span>总览</span>
      </router-link>
      <router-link class="nav-item" to="/settings">
        <el-icon><Setting /></el-icon><span>设置</span>
      </router-link>

      <div class="sidebar-foot">P1 · 构建与测试部署</div>
    </aside>

    <div class="main">
      <header class="topbar">
        <nav class="breadcrumb" aria-label="breadcrumb">
          <template v-if="parentPath">
            <router-link :to="parentPath">{{ parentLabel }}</router-link>
            <span class="sep">/</span>
          </template>
          <span class="current">{{ crumb }}</span>
        </nav>
        <div class="topbar-spacer" />
        <button class="icon-button" :aria-label="theme === 'dark' ? '切换到浅色' : '切换到深色'" @click="onToggleTheme">
          <el-icon><component :is="theme === 'dark' ? Sunny : Moon" /></el-icon>
        </button>
        <button class="user-chip" @click="userDialog = true">
          <span class="avatar">{{ initial }}</span>
          <span>{{ userId }}</span>
        </button>
      </header>

      <main class="content">
        <router-view v-slot="{ Component }">
          <transition name="fade" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </main>
    </div>

    <el-dialog v-model="userDialog" title="当前身份" width="420px">
      <p class="muted small" style="margin-top: 0">
        一期使用本地账号，请求会带上 <code class="mono">X-User-Id</code>。该账号需存在于
        <code class="mono">app_user</code> 且具备角色。
      </p>
      <el-input v-model="draftUser" placeholder="工号，例如 dev" @keyup.enter="saveUser">
        <template #prefix><el-icon><User /></el-icon></template>
      </el-input>
      <template #footer>
        <el-button @click="userDialog = false">取消</el-button>
        <el-button type="primary" @click="saveUser">应用并刷新</el-button>
      </template>
    </el-dialog>
  </div>
</template>