<script setup lang="ts">
import { DocumentRemove } from '@element-plus/icons-vue'

defineProps<{
  title?: string
  hint?: string
}>()
</script>

<template>
  <div class="empty">
    <el-icon class="icon"><DocumentRemove /></el-icon>
    <div class="strong">{{ title ?? '暂无数据' }}</div>
    <div v-if="hint" class="small">{{ hint }}</div>
    <slot />
  </div>
</template>