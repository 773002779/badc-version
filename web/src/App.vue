<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  Bell,
  Checked,
  Document,
  Grid,
  Monitor,
  Moon,
  Odometer,
  Setting,
  Sunny,
  Tickets,
  User
} from '@element-plus/icons-vue'
import { getUnreadNotificationCount, listNotifications, markNotificationRead } from './api'
import { ApiError, currentUser, setCurrentUser } from './api/http'
import type { NotificationView } from './api/types'
import { currentTheme, toggleTheme } from './theme'
import { formatDateTime } from './utils/format'
import {
  bumpNotifications,
  notificationRevision,
  notificationTarget
} from './utils/notification'
import EmptyState from './components/EmptyState.vue'

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

// ------------------------------------------------------------------ bell (P4-B)

/** How many newest rows the dropdown shows; the full list lives on `/notifications`. */
const BELL_SIZE = 5

const unread = ref(0)
const bellOpen = ref(false)
const bellLoading = ref(false)
const bellError = ref<string | null>(null)
const recent = ref<NotificationView[]>([])
const bellWrap = ref<HTMLElement | null>(null)
let unreadTimer: number | undefined

const unreadBadge = computed(() => (unread.value > 99 ? '99+' : String(unread.value)))

async function loadUnread() {
  try {
    unread.value = (await getUnreadNotificationCount()).unread
  } catch {
    // The bell is optional chrome: a failed poll stays silent and retries on the next tick.
  }
}

async function loadRecent() {
  bellLoading.value = true
  bellError.value = null
  try {
    recent.value = (await listNotifications({ page: 1, size: BELL_SIZE })).records
  } catch (e) {
    bellError.value = e instanceof ApiError ? e.message : '加载通知失败'
    recent.value = []
  } finally {
    bellLoading.value = false
  }
}

function toggleBell() {
  bellOpen.value = !bellOpen.value
  if (bellOpen.value) loadRecent()
}

function closeBell() {
  bellOpen.value = false
}

/** A click anywhere outside the bell closes the dropdown; clicks inside keep it open. */
function onDocumentClick(event: MouseEvent) {
  if (!bellOpen.value) return
  const target = event.target
  if (bellWrap.value && target instanceof Node && bellWrap.value.contains(target)) return
  bellOpen.value = false
}

async function openRecent(n: NotificationView) {
  bellOpen.value = false
  if (!n.read) {
    try {
      await markNotificationRead(n.id)
      n.read = true
      unread.value = Math.max(0, unread.value - 1)
      // Tell the notification centre (if it is open in another view) that the read state moved.
      bumpNotifications()
    } catch {
      // Reading is best-effort here; the centre can retry and still shows the row as unread.
    }
  }
  await router.push(notificationTarget(n) ?? '/notifications')
}

async function goAll() {
  bellOpen.value = false
  await router.push('/notifications')
}

watch(
  () => route.fullPath,
  () => {
    closeBell()
    loadUnread()
  }
)

// A mark-read in the notification centre bumps the shared revision; refresh the badge at once.
watch(notificationRevision, () => loadUnread())

onMounted(() => {
  loadUnread()
  unreadTimer = window.setInterval(loadUnread, 30_000)
  document.addEventListener('click', onDocumentClick)
})

onUnmounted(() => {
  if (unreadTimer !== undefined) window.clearInterval(unreadTimer)
  document.removeEventListener('click', onDocumentClick)
})

// ------------------------------------------------------------------- identity

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
        <el-icon><Grid /></el-icon><span>系统</span>
      </router-link>
      <router-link class="nav-item" to="/units">
        <el-icon><Monitor /></el-icon><span>部署单元</span>
      </router-link>

      <div class="nav-group">平台</div>
      <router-link class="nav-item" to="/control">
        <el-icon><Checked /></el-icon><span>生产管控</span>
      </router-link>
      <router-link class="nav-item" to="/records">
        <el-icon><Document /></el-icon><span>发布记录</span>
      </router-link>
      <router-link class="nav-item" to="/">
        <el-icon><Odometer /></el-icon><span>总览</span>
      </router-link>
      <router-link class="nav-item" to="/settings">
        <el-icon><Setting /></el-icon><span>设置</span>
      </router-link>

      <div class="sidebar-foot">P4 · 回滚与记录</div>
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
        <div ref="bellWrap" class="notify-wrap">
          <button
            class="icon-button notify-bell"
            :aria-label="unread > 0 ? `消息通知，${unread} 条未读` : '消息通知'"
            :aria-expanded="bellOpen"
            @click="toggleBell"
          >
            <el-icon><Bell /></el-icon>
            <span v-if="unread > 0" class="notify-badge">{{ unreadBadge }}</span>
          </button>

          <div v-if="bellOpen" class="notify-panel" role="dialog" aria-label="最新通知">
            <div class="notify-panel-head">
              <span class="strong">消息通知</span>
              <span class="weak small">{{ unread > 0 ? `${unread} 条未读` : '全部已读' }}</span>
            </div>

            <div v-if="bellLoading" class="notify-panel-loading">
              <el-skeleton :rows="3" animated />
            </div>
            <div v-else-if="bellError" class="notify-panel-error">
              <el-alert type="error" :closable="false" show-icon :title="bellError" />
            </div>
            <ul v-else-if="recent.length" class="bell-list">
              <li v-for="n in recent" :key="n.id" class="bell-item" :class="{ unread: !n.read }">
                <button class="bell-hit" @click="openRecent(n)">
                  <span class="bell-dot" :class="{ on: !n.read }" />
                  <span class="bell-copy">
                    <span class="bell-title" :class="{ strong: !n.read }">{{ n.title }}</span>
                    <span class="bell-meta">
                      <span>{{ n.eventLabel }}</span>
                      <span>{{ formatDateTime(n.createdAt) }}</span>
                    </span>
                  </span>
                </button>
              </li>
            </ul>
            <EmptyState v-else title="暂无通知" hint="构建、部署、审批与封板事件会出现在这里。" />

            <div class="notify-panel-foot">
              <button class="notify-more" @click="goAll">查看全部</button>
            </div>
          </div>
        </div>
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

<style scoped>
/* Topbar bell + dropdown (P4-B). Same graphite palette as the rest of the shell. */
.notify-wrap {
  position: relative;
  display: inline-flex;
}

.notify-bell {
  position: relative;
}

.notify-badge {
  position: absolute;
  top: -5px;
  right: -5px;
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  border-radius: 999px;
  background: var(--text);
  color: var(--bg);
  font-size: 10px;
  font-weight: 600;
  line-height: 16px;
  text-align: center;
  font-variant-numeric: tabular-nums;
  pointer-events: none;
}

.notify-panel {
  position: absolute;
  top: 40px;
  right: 0;
  z-index: 30;
  width: 360px;
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface-2);
  box-shadow: var(--shadow-pop);
}

.notify-panel-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 14px;
  border-bottom: 1px solid var(--border);
}

.notify-panel-loading,
.notify-panel-error {
  padding: 14px;
}

.bell-list {
  list-style: none;
  margin: 0;
  padding: 0;
  max-height: 340px;
  overflow-y: auto;
}

.bell-item + .bell-item {
  border-top: 1px solid var(--border);
}

.bell-hit {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  width: 100%;
  padding: 10px 14px;
  border: 0;
  background: transparent;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
}

.bell-hit:hover {
  background: var(--surface-3);
}

.bell-item.unread .bell-hit {
  background: color-mix(in srgb, var(--accent-weak) 55%, var(--surface-2));
}

.bell-item.unread .bell-hit:hover {
  background: var(--accent-weak);
}

.bell-dot {
  flex: none;
  width: 6px;
  height: 6px;
  margin-top: 6px;
  border-radius: 999px;
  background: transparent;
}

.bell-dot.on {
  background: var(--accent);
}

.bell-copy {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.bell-title {
  font-size: 13px;
  line-height: 1.45;
  overflow: hidden;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.bell-meta {
  display: flex;
  gap: 10px;
  color: var(--text-3);
  font-size: 11px;
}

.notify-panel-foot {
  display: flex;
  justify-content: flex-end;
  padding: 8px 14px;
  border-top: 1px solid var(--border);
}

.notify-more {
  border: 0;
  background: transparent;
  color: var(--accent);
  font: inherit;
  font-size: 12px;
  cursor: pointer;
}

.notify-more:hover {
  text-decoration: underline;
}

@media (max-width: 600px) {
  .notify-panel {
    width: calc(100vw - 32px);
  }
}
</style>