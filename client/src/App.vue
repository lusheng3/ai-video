<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { api, clearSession, getStoredUser, getToken } from './api'
import { createTaskEventStreams } from './taskEvents'
import LoginPanel from './components/LoginPanel.vue'
import UploadPanel from './components/UploadPanel.vue'
import TaskList from './components/TaskList.vue'
import ResultPanel from './components/ResultPanel.vue'

const session = ref(getStoredUser())
const tasks = ref([])
const detail = ref(null)
const playUrl = ref('')
const error = ref('')
const busyTaskId = ref(null)
const toast = ref('')
const pollingTimers = new Map()
const analysisEvents = createTaskEventStreams()

function showToast(message) {
  toast.value = message
  setTimeout(() => {
    if (toast.value === message) toast.value = ''
  }, 4000)
}

async function loadTasks() {
  if (!getToken()) return
  try {
    const res = await api.listTasks()
    tasks.value = res.data || []
  } catch (e) {
    if (e.status === 401) {
      session.value = null
      clearSession()
    } else {
      error.value = e.message
    }
  }
}

function handleLoggedIn(auth) {
  session.value = auth
  error.value = ''
  loadTasks()
}

function logout() {
  stopAllPolling()
  analysisEvents.stopAll()
  clearSession()
  session.value = null
  tasks.value = []
  detail.value = null
  playUrl.value = ''
}

function stopAllPolling() {
  for (const timer of pollingTimers.values()) clearInterval(timer)
  pollingTimers.clear()
}

function applyStatus(taskId, statusData) {
  const task = tasks.value.find((item) => item.id === taskId)
  if (!task) return
  task.status = statusData.status
  task.errorMsg = statusData.errorMsg
  task.stageMessage = statusData.message
  task.stageLabel = statusData.stageLabel || statusData.stage
  task.progress = statusData.progress ?? task.progress
}

function applyEvent(taskId, event) {
  const task = tasks.value.find((item) => item.id === taskId)
  if (task) {
    if (event.status != null) task.status = event.status
    if (event.message) task.stageMessage = event.message
    if (event.stageLabel) task.stageLabel = event.stageLabel
    if (event.progress != null) task.progress = event.progress
    if (event.errorMsg) task.errorMsg = event.errorMsg
  }
  if (detail.value?.taskId === taskId) {
    if (event.status != null) detail.value.status = event.status
    if (event.message) detail.value.message = event.message
    if (event.progress != null) detail.value.progress = event.progress
    if (event.errorMsg) detail.value.errorMsg = event.errorMsg
  }
}

async function finishTask(taskId) {
  analysisEvents.stop(taskId)
  const timer = pollingTimers.get(taskId)
  if (timer) {
    clearInterval(timer)
    pollingTimers.delete(taskId)
  }
  await loadTasks()
  if (detail.value?.taskId === taskId) {
    const task = tasks.value.find((item) => item.id === taskId)
    if (task) await viewTask(task)
  }
}

/**
 * 优先用 SSE 实时接收阶段事件；连续重连失败或接口不可用时自动降级为 3 秒轮询。
 */
function watchTask(taskId) {
  analysisEvents.start(taskId, {
    onEvent(event) {
      applyEvent(taskId, event)
      if (event.status === 3 || event.status === 4) finishTask(taskId)
    },
    onFallback() {
      startPolling(taskId)
    },
    onError() {
      // 重连过程不打扰用户，降级后由轮询兜底
    }
  })
}

function startPolling(taskId) {
  if (pollingTimers.has(taskId)) return
  const timer = setInterval(async () => {
    try {
      const res = await api.analysisStatus(taskId)
      applyStatus(taskId, res.data)
      if (res.data.status === 3 || res.data.status === 4) finishTask(taskId)
    } catch {
      // 网络抖动时保持轮询，等待下一次
    }
  }, 3000)
  pollingTimers.set(taskId, timer)
}

async function analyze(task) {
  const goal = window.prompt('请输入本次分析目标：', '提炼视频核心内容')
  if (!goal || !goal.trim()) return
  busyTaskId.value = task.id
  error.value = ''
  try {
    const res = await api.submitAnalysis({ taskId: task.id, userGoal: goal.trim() })
    task.status = res.data.status
    task.stageMessage = res.data.message
    task.progress = 0
    showToast(res.data.message)
    watchTask(task.id)
    await loadTasks()
  } catch (e) {
    error.value = e.message
  } finally {
    busyTaskId.value = null
  }
}

async function viewTask(task) {
  error.value = ''
  try {
    const res = await api.analysisStatus(task.id)
    detail.value = res.data
    playUrl.value = ''
    if (res.data.status === 3) {
      try {
        const play = await api.playUrl(task.id)
        playUrl.value = play.data.playUrl
      } catch {
        playUrl.value = ''
      }
    }
  } catch (e) {
    error.value = e.message
  }
}

function handleUploaded(taskId) {
  showToast(`上传完成，taskId=${taskId}`)
  loadTasks()
}

onMounted(async () => {
  if (!getToken()) return
  await loadTasks()
  for (const task of tasks.value) {
    if (task.status === 1 || task.status === 2) watchTask(task.id)
  }
})

onBeforeUnmount(() => {
  stopAllPolling()
  analysisEvents.stopAll()
})
</script>

<template>
  <div class="header">
    <div>
      <h1 style="font-size: 26px">DoVideo AI · 长视频解析工作台</h1>
      <p class="muted" style="margin: 6px 0 0">
        分片上传 · 秒传续传 · RocketMQ 异步分析 · 3 秒轮询 · 预签名回放
      </p>
    </div>
    <div v-if="session" class="row">
      <span class="muted">{{ session.nickname || session.username }}</span>
      <span class="badge" :class="session.accountType === 1 ? 'badge-3' : 'badge-0'">
        {{ session.accountType === 1 ? 'VIP' : '普通用户' }}
      </span>
      <button class="btn btn-ghost" @click="logout">退出</button>
    </div>
  </div>

  <div v-if="toast" class="card" style="padding: 12px 16px; border-color: #2ecc71">
    {{ toast }}
  </div>

  <div v-if="error" class="card" style="padding: 12px 16px; border-color: #ff5c5c">
    <span class="error">{{ error }}</span>
    <button class="btn btn-ghost" style="margin-left: 12px" @click="error = ''">知道了</button>
  </div>

  <LoginPanel v-if="!session" @logged-in="handleLoggedIn" />

  <template v-else>
    <UploadPanel @uploaded="handleUploaded" />
    <TaskList
      :tasks="tasks"
      :busy-task-id="busyTaskId"
      @analyze="analyze"
      @view="viewTask"
      @refresh="loadTasks"
    />
    <ResultPanel :detail="detail" :play-url="playUrl" @close="detail = null" />
  </template>
</template>
