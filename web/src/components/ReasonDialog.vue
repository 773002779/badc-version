<script setup lang="ts">
import { computed, ref, watch } from 'vue'

/**
 * The shared confirmation dialog of the four stop-loss actions (P5-B design §6): one message that
 * spells out the cascade, one optional warning block, and one required reason (1–200 characters).
 *
 * <p>The component owns the validation and only emits a trimmed reason; the caller owns the request
 * and closes the dialog on success, so a server rejection keeps the typed reason for a retry. The
 * reason is cleared every time the dialog opens: an operator who opens 停止 after a 退出 must not
 * inherit the previous text.
 */
const props = withDefaults(
  defineProps<{
    modelValue: boolean
    title: string
    /** What the action does, shown above the input. */
    message?: string
    /** The irreversible part, in the warning tone (kept batches, read-only task, …). */
    warning?: string
    confirmText?: string
    placeholder?: string
    /** Uses the danger button tone for 停止 / 归档; close/exit stay primary. */
    danger?: boolean
    /** The parent's request is in flight: the confirm button shows a spinner and blocks a re-click. */
    busy?: boolean
  }>(),
  {
    message: '',
    warning: '',
    confirmText: '确认',
    placeholder: '请填写原因（1–200 字）',
    danger: false,
    busy: false
  }
)

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'confirm', reason: string): void
}>()

const reason = ref('')
const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value)
})

const trimmed = computed(() => reason.value.trim())
const tooLong = computed(() => trimmed.value.length > 200)
const canConfirm = computed(() => trimmed.value.length > 0 && !tooLong.value && !props.busy)

/** A fresh dialog always starts empty; a server rejection leaves the text until it is closed. */
watch(
  () => props.modelValue,
  (open) => {
    if (open) reason.value = ''
  }
)

function submit() {
  if (!canConfirm.value) return
  emit('confirm', trimmed.value)
}
</script>

<template>
  <el-dialog
    v-model="visible"
    class="reason-dialog"
    :title="title"
    width="520px"
    :close-on-click-modal="false"
  >
    <p v-if="message" class="reason-message">{{ message }}</p>

    <el-alert
      v-if="warning"
      class="reason-warning"
      type="warning"
      :closable="false"
      show-icon
      :title="warning"
    />

    <el-input
      v-model="reason"
      class="reason-input"
      type="textarea"
      :rows="3"
      maxlength="200"
      show-word-limit
      :placeholder="placeholder"
    />

    <div class="reason-meta">
      <span class="small" :class="tooLong ? 'bad' : 'weak'">
        {{ tooLong ? '原因不能超过 200 字' : '原因必填，1–200 字，作为操作留痕' }}
      </span>
    </div>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button
        class="reason-confirm"
        :type="danger ? 'danger' : 'primary'"
        :disabled="!canConfirm"
        :loading="busy"
        @click="submit"
      >
        {{ confirmText }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.reason-message {
  margin: 0 0 10px;
  font-size: 13px;
  line-height: 1.65;
  color: var(--text-2);
}
.reason-warning {
  margin-bottom: 12px;
}
.reason-meta {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-top: 6px;
}
.reason-spacer {
  flex: 1;
}
.bad {
  color: var(--danger);
}
</style>