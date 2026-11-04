<script setup>
import { computed } from 'vue'

const props = defineProps({
  tasks: { type: Array, default: () => [] },
  busyTaskId: { type: Number, default: null }
})
const emit = defineEmits(['analyze', 'view', 'refresh'])

const STATUS_MAP = {
  0: { label: '待分析', cls: 'badge-0' },
  1: { label: '排队中', cls: 'badge-1' },
  2: { label: '处理中', cls: 'badge-2' },
  3: { label: '已完成', cls: 'badge-3' },
  4: { label: '失败', cls: 'badge-4' },
  5: { label: '合并中', cls: 'badge-5' }
}

const sortedTasks = computed(() => {
  return [...props.tasks].sort((a, b) => (b.id || 0) - (a.id || 0))
})

function statusOf(task) {
  return STATUS_MAP[task.status] || STATUS_MAP[0]
}

function formatSize(bytes) {
  if (!bytes) return '0 B'
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(2)} MB`
  return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} GB`
}

function canAnalyze(task) {
  return task.status === 0 || task.status === 3 || task.status === 4
}
</script>

<template>
  <div class="card">
    <div class="row" style="justify-content: space-between; margin-bottom: 6px">
      <h2>我的视频任务</h2>
      <button class="btn btn-ghost" @click="emit('refresh')">刷新列表</button>
    </div>

    <p v-if="sortedTasks.length === 0" class="muted">还没有视频，先在上方上传一个吧。</p>

    <div v-for="task in sortedTasks" :key="task.id" class="task">
      <div class="task-head">
        <div>
          <strong>{{ task.fileName }}</strong>
          <span class="badge" :class="statusOf(task).cls" style="margin-left: 10px">
            {{ statusOf(task).label }}
          </span>
          <div class="mono" style="margin-top: 6px">
            taskId={{ task.id }} · {{ formatSize(task.totalSize) }} · {{ task.totalChunks }} 片
          </div>
          <div class="mono">md5={{ task.videoMd5 }}</div>
          <div v-if="task.errorMsg" class="error" style="margin-top: 6px">{{ task.errorMsg }}</div>
        </div>

        <div class="row">
          <button
            v-if="canAnalyze(task)"
            class="btn"
            :disabled="busyTaskId === task.id"
            @click="emit('analyze', task)"
          >
            {{ task.status === 3 ? '重新分析' : '开始 AI 分析' }}
          </button>
          <button
            v-if="task.status === 3"
            class="btn btn-ghost"
            @click="emit('view', task)"
          >
            查看结果
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
