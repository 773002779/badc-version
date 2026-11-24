import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/', name: 'dashboard', component: () => import('./views/DashboardView.vue'), meta: { title: '总览' } },
  { path: '/tasks', name: 'tasks', component: () => import('./views/TaskListView.vue'), meta: { title: '任务', crumb: '流水线任务' } },
  { path: '/tasks/:id', name: 'task-detail', component: () => import('./views/TaskDetailView.vue'), meta: { title: '任务详情', crumb: '任务详情', parent: '/tasks' } },
  { path: '/releases/:publishNo', name: 'release', component: () => import('./views/ReleaseView.vue'), meta: { title: '发布内容', crumb: '发布内容' } },
  { path: '/subsystems', name: 'subsystems', component: () => import('./views/SubsystemListView.vue'), meta: { title: '系统', crumb: '系统' } },
  { path: '/subsystems/:id', name: 'subsystem-detail', component: () => import('./views/SubsystemDetailView.vue'), meta: { title: '系统详情', crumb: '系统详情', parent: '/subsystems' } },
  { path: '/units', name: 'units', component: () => import('./views/UnitListView.vue'), meta: { title: '部署单元', crumb: '部署单元' } },
  { path: '/units/:id', name: 'unit-detail', component: () => import('./views/UnitDetailView.vue'), meta: { title: '部署单元详情', crumb: '部署单元详情', parent: '/units' } },
  { path: '/control', name: 'control', component: () => import('./views/ControlView.vue'), meta: { title: '生产管控', crumb: '生产管控' } },
  { path: '/records', name: 'records', component: () => import('./views/RecordsView.vue'), meta: { title: '发布记录', crumb: '发布记录' } },
  { path: '/settings', name: 'settings', component: () => import('./views/SettingsView.vue'), meta: { title: '设置', crumb: '设置' } },
  { path: '/:pathMatch(.*)*', redirect: '/' }
]

export default createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 })
})