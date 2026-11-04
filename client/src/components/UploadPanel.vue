<script setup>
import { computed, onBeforeUnmount, ref } from 'vue'
import SparkMD5 from 'spark-md5'
import { api } from '../api'

const emit = defineEmits(['uploaded'])

const CHUNK_SIZE = 5 * 1024 * 1024
const CONCURRENCY = 3
const MAX_ATTEMPTS = 3
const BACKOFF_BASE_MS = 1000
const CHUNK_TIMEOUT_MS = 60 * 1000
const MERGE_TIMEOUT_MS = 5 * 60 * 1000

const file = ref(null)
const phase = ref('idle')
const fileMd5 = ref('')
const totalChunks = ref(0)
const completedCount = ref(0)
const md5Progress = ref(0)
const statusText = ref('')
const error = ref('')
const deduplicated = ref(false)
const failedChunks = ref([])
const fatal = ref(false)

let worker = null
let completedSet = new Set()

const busy = computed(() =>
  ['hashing', 'checking', 'uploading', 'merging'].includes(phase.value)
)

const progress = computed(() => {
  if (phase.value === 'done') return 100
  if (!totalChunks.value) return 0
  return Math.round((completedCount.value / totalChunks.value) * 100)
})

const primaryLabel = computed(() => {
  switch (phase.value) {
    case 'hashing': return '正在计算 MD5...'
    case 'checking': return '正在核对分片...'
    case 'uploading': return '正在上传...'
    case 'merging': return '正在合并...'
    case 'partial': return '重试失败分片'
    case 'error': return '重试'
    case 'done': return '上传完成'
    default: return '开始上传 / 断点续传'
  }
})

const primaryDisabled = computed(() => !file.value || busy.value || phase.value === 'done')
const failedSummary = computed(() => failedChunks.value.slice(0, 20).map(item => item.index).join(', '))

function formatSize(bytes) {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(2)} MB`
  return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} GB`
}

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms))
}

function isRetryable(err) {
  if (!err) return true
  if (err.name === 'AbortError') return true
  if (typeof err.status === 'number') {
    if (err.status === 408 || err.status === 429 || err.status >= 500) return true
    if (err.status === 400 && /MD5|损坏|corrupt/i.test(err.message || '')) return true
    return false
  }
  return true
}

function isFatal(err) {
  return typeof err?.status === 'number' && [400, 401, 403].includes(err.status)
    && !(err.status === 400 && /MD5|损坏|corrupt/i.test(err.message || ''))
}

function resetState() {
  phase.value = 'idle'
  fileMd5.value = ''
  totalChunks.value = 0
  completedCount.value = 0
  completedSet = new Set()
  md5Progress.value = 0
  statusText.value = ''
  error.value = ''
  deduplicated.value = false
  failedChunks.value = []
  fatal.value = false
}

function onFileChange(event) {
  const picked = event.target.files?.[0]
  if (!picked) return
  if (worker) {
    worker.terminate()
    worker = null
  }
  resetState()
  file.value = picked
}

function computeFileMd5(targetFile) {
  return new Promise((resolve, reject) => {
    worker = new Worker(new URL('../workers/md5.worker.js', import.meta.url), { type: 'module' })
    worker.onmessage = (event) => {
      const data = event.data
      if (data.type === 'progress') {
        md5Progress.value = data.progress
        statusText.value = `正在计算文件 MD5：${data.progress}%`
      } else if (data.type === 'done') {
        worker.terminate()
        worker = null
        resolve(data.md5)
      } else if (data.type === 'error') {
        worker.terminate()
        worker = null
        reject(new Error(data.message))
      }
    }
    worker.onerror = (e) => {
      worker?.terminate()
      worker = null
      reject(new Error(e.message || 'MD5 Worker 执行失败'))
    }
    worker.postMessage({ file: targetFile })
  })
}

async function uploadChunkWithTimeout(params, blob) {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), CHUNK_TIMEOUT_MS)
  try {
    return await api.uploadChunk(params, blob, { signal: controller.signal })
  } finally {
    clearTimeout(timer)
  }
}

/**
 * 单个分片：内部自动重试 3 次（1s -> 2s -> 4s + 随机抖动），永远不抛异常。
 * 返回 { success, index, message?, permanent?, fatal? }
 */
async function uploadWithRetry(index) {
  let lastMessage = ''
  for (let attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
    try {
      const start = index * CHUNK_SIZE
      const end = Math.min(start + CHUNK_SIZE, file.value.size)
      const blob = file.value.slice(start, end)
      const buffer = await blob.arrayBuffer()
      const chunkMd5 = SparkMD5.ArrayBuffer.hash(buffer)
      statusText.value = `正在上传分片 ${index + 1}/${totalChunks.value}`
        + (attempt > 1 ? `（第 ${attempt} 次尝试）` : '')
      await uploadChunkWithTimeout({
        fileMd5: fileMd5.value,
        chunkIndex: index,
        totalChunks: totalChunks.value,
        chunkMd5
      }, blob)
      return { success: true, index }
    } catch (err) {
      lastMessage = err?.message || '网络异常'
      if (err?.name === 'AbortError') {
        lastMessage = `分片 ${index} 上传超时（${CHUNK_TIMEOUT_MS / 1000}s）`
      }
      const retryable = isRetryable(err)
      if (!retryable || attempt >= MAX_ATTEMPTS) {
        return {
          success: false,
          index,
          message: lastMessage,
          permanent: !retryable,
          fatal: isFatal(err)
        }
      }
      const delay = BACKOFF_BASE_MS * Math.pow(2, attempt - 1)
        + Math.floor(Math.random() * 300)
      statusText.value = `分片 ${index + 1} 失败，${delay}ms 后重试（${attempt}/${MAX_ATTEMPTS}）`
      await sleep(delay)
    }
  }
  return { success: false, index, message: lastMessage }
}

async function processChunk(index) {
  if (fatal.value) return
  const result = await uploadWithRetry(index)
  if (result.success) {
    if (!completedSet.has(index)) {
      completedSet.add(index)
      completedCount.value = completedSet.size
    }
    failedChunks.value = failedChunks.value.filter(item => item.index !== index)
  } else {
    if (!failedChunks.value.some(item => item.index === index)) {
      failedChunks.value.push({
        index,
        message: result.message,
        permanent: result.permanent
      })
    }
    if (result.fatal) {
      fatal.value = true
      error.value = result.message
    }
  }
}

async function uploadMissing() {
  const missing = []
  for (let index = 0; index < totalChunks.value; index++) {
    if (!completedSet.has(index)) missing.push(index)
  }
  if (missing.length === 0) {
    await reconcile()
    return
  }

  phase.value = 'uploading'
  statusText.value = `待上传 ${missing.length} 个分片（已传 ${completedSet.size}/${totalChunks.value}）`
  const queue = [...missing]
  const workerCount = Math.min(CONCURRENCY, queue.length)
  const workers = Array.from({ length: workerCount }, async () => {
    while (queue.length > 0 && !fatal.value) {
      const index = queue.shift()
      await processChunk(index)
    }
  })
  await Promise.all(workers)

  if (fatal.value) {
    phase.value = 'error'
    return
  }
  await reconcile()
}

/**
 * 以服务端 /check 的结果为准，避免“本地失败但服务端已收到”的误判。
 */
async function reconcile() {
  phase.value = 'checking'
  statusText.value = '正在与服务端核对分片...'
  const res = await api.checkUpload({
    fileMd5: fileMd5.value,
    fileName: file.value.name,
    totalSize: file.value.size,
    totalChunks: totalChunks.value
  })
  const data = res.data || {}

  if (data.deduplicated) {
    deduplicated.value = true
    phase.value = 'done'
    statusText.value = `⚡ 秒传成功，复用已有视频（taskId=${data.taskId}）`
    emit('uploaded', data.taskId)
    return
  }

  completedSet = new Set(data.uploadedChunks || [])
  completedCount.value = completedSet.size

  if (completedSet.size >= totalChunks.value) {
    await mergeAndFinish()
    return
  }

  phase.value = 'partial'
  const missing = []
  for (let index = 0; index < totalChunks.value; index++) {
    if (!completedSet.has(index)) missing.push(index)
  }
  failedChunks.value = missing.map(index => ({
    index,
    message: '服务端确认该分片尚未上传',
    permanent: false
  }))
  statusText.value = `已确认 ${completedSet.size}/${totalChunks.value} 个分片，还有 ${missing.length} 个未完成`
}

async function mergeWithRetry(body) {
  let lastError = null
  for (let attempt = 1; attempt <= 3; attempt++) {
    const controller = new AbortController()
    const timer = setTimeout(() => controller.abort(), MERGE_TIMEOUT_MS)
    try {
      return await api.merge(body, { signal: controller.signal })
    } catch (err) {
      lastError = err
      const retryable = isRetryable(err) || err?.status === 409
      if (!retryable || attempt >= 3) throw err
      statusText.value = `合并请求失败，正在重试（${attempt}/3）...`
      await sleep(1500 * attempt)
    } finally {
      clearTimeout(timer)
    }
  }
  throw lastError
}

async function mergeAndFinish() {
  phase.value = 'merging'
  statusText.value = `全部分片已就绪（${totalChunks.value}/${totalChunks.value}），正在调用 MinIO composeObject 合并...`
  try {
    const res = await mergeWithRetry({
      fileMd5: fileMd5.value,
      fileName: file.value.name,
      totalSize: file.value.size,
      totalChunks: totalChunks.value
    })
    phase.value = 'done'
    completedCount.value = totalChunks.value
    statusText.value = `✅ 合并完成（taskId=${res.data.taskId}，${formatSize(file.value.size)}）`
    emit('uploaded', res.data.taskId)
  } catch (err) {
    phase.value = 'error'
    error.value = `合并失败：${err?.message || '网络异常'}`
    failedChunks.value = []
  }
}

async function checkAndContinue() {
  phase.value = 'checking'
  statusText.value = '正在预检分片...'
  const res = await api.checkUpload({
    fileMd5: fileMd5.value,
    fileName: file.value.name,
    totalSize: file.value.size,
    totalChunks: totalChunks.value
  })
  const data = res.data || {}

  if (data.deduplicated) {
    deduplicated.value = true
    phase.value = 'done'
    completedCount.value = totalChunks.value
    statusText.value = `⚡ 秒传成功，复用已有视频（taskId=${data.taskId}）`
    emit('uploaded', data.taskId)
    return
  }

  completedSet = new Set(data.uploadedChunks || [])
  completedCount.value = completedSet.size
  failedChunks.value = []

  if (completedSet.size >= totalChunks.value) {
    await mergeAndFinish()
    return
  }

  statusText.value = `已传 ${completedSet.size}/${totalChunks.value}，继续上传缺失分片...`
  await uploadMissing()
}

async function startUpload() {
  if (!file.value || busy.value) return
  error.value = ''
  deduplicated.value = false
  failedChunks.value = []
  fatal.value = false
  completedSet = new Set()
  completedCount.value = 0
  totalChunks.value = Math.ceil(file.value.size / CHUNK_SIZE)
  phase.value = 'hashing'
  statusText.value = '正在计算文件 MD5...'

  try {
    fileMd5.value = await computeFileMd5(file.value)
    await checkAndContinue()
  } catch (err) {
    phase.value = 'error'
    error.value = err?.message || '上传失败'
  }
}

async function resumeUpload() {
  if (!file.value || busy.value) return
  if (!fileMd5.value || !totalChunks.value) {
    await startUpload()
    return
  }
  error.value = ''
  fatal.value = false
  try {
    await checkAndContinue()
  } catch (err) {
    phase.value = 'error'
    error.value = err?.message || '续传失败'
  }
}

function onPrimaryClick() {
  if (phase.value === 'partial' || phase.value === 'error') {
    resumeUpload()
  } else {
    startUpload()
  }
}

onBeforeUnmount(() => {
  worker?.terminate()
  worker = null
})
</script>

<template>
  <div class="card">
    <h2 style="margin-bottom: 14px">分片上传（5MB / 片，3 并发，失败自动重试）</h2>
    <div class="row">
      <input type="file" accept="video/*" :disabled="busy" @change="onFileChange" />
      <button class="btn" :disabled="primaryDisabled" @click="onPrimaryClick">
        {{ primaryLabel }}
      </button>
    </div>

    <p v-if="file" class="muted" style="margin-top: 10px">
      已选择：{{ file.name }}（{{ formatSize(file.size) }}，共 {{ totalChunks || '?' }} 片）
    </p>

    <div v-if="statusText || progress > 0" style="margin-top: 14px">
      <div class="progress">
        <div :style="{ width: progress + '%' }"></div>
      </div>
      <p class="muted" style="margin-top: 8px">{{ statusText }}</p>
      <p v-if="totalChunks" class="muted" style="margin-top: 4px">
        已确认分片：{{ completedCount }} / {{ totalChunks }}
      </p>
    </div>

    <p v-if="deduplicated" class="badge badge-3" style="margin-top: 10px">
      秒传命中（未上传任何字节）
    </p>

    <div
      v-if="failedChunks.length > 0"
      style="margin-top: 12px; border: 1px solid #4a3a1a; background: #2a2313; border-radius: 10px; padding: 12px"
    >
      <p style="margin: 0; color: #f5a524">
        ⚠️ {{ failedChunks.length }} 个分片未完成：{{ failedSummary }}
        <span v-if="failedChunks.length > 20"> ...</span>
      </p>
      <p class="muted" style="margin: 6px 0 0">
        点击上方「重试失败分片」即可，只会补传缺失分片，不会重传整个文件。
      </p>
    </div>

    <p v-if="error" class="error" style="margin-top: 10px">{{ error }}</p>
  </div>
</template>
