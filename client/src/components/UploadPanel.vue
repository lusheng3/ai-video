<script setup>
import { onBeforeUnmount, ref } from 'vue'
import SparkMD5 from 'spark-md5'
import { api } from '../api'

const emit = defineEmits(['uploaded'])

const CHUNK_SIZE = 5 * 1024 * 1024
const CONCURRENCY = 3

const file = ref(null)
const md5Progress = ref(0)
const uploadProgress = ref(0)
const statusText = ref('')
const busy = ref(false)
const error = ref('')
const deduplicated = ref(false)

let worker = null

function onFileChange(event) {
  const picked = event.target.files?.[0]
  if (!picked) return
  file.value = picked
  md5Progress.value = 0
  uploadProgress.value = 0
  statusText.value = ''
  error.value = ''
  deduplicated.value = false
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

function formatSize(bytes) {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(2)} MB`
  return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} GB`
}

async function startUpload() {
  if (!file.value || busy.value) return
  busy.value = true
  error.value = ''
  deduplicated.value = false
  uploadProgress.value = 0
  const target = file.value
  const totalChunks = Math.ceil(target.size / CHUNK_SIZE)

  try {
    const fileMd5 = await computeFileMd5(target)
    statusText.value = `文件 MD5：${fileMd5}，正在预检...`

    const checkRes = await api.checkUpload({
      fileMd5,
      fileName: target.name,
      totalSize: target.size,
      totalChunks
    })
    const check = checkRes.data

    if (check.deduplicated) {
      deduplicated.value = true
      statusText.value = `⚡ 秒传成功，复用已有视频（taskId=${check.taskId}）`
      uploadProgress.value = 100
      emit('uploaded', check.taskId)
      return
    }

    const uploaded = new Set(check.uploadedChunks || [])
    const missing = []
    for (let i = 0; i < totalChunks; i++) {
      if (!uploaded.has(i)) missing.push(i)
    }
    statusText.value = missing.length === totalChunks
      ? `全新上传：共 ${totalChunks} 个分片`
      : `断点续传：已传 ${uploaded.size}/${totalChunks}，补传 ${missing.length} 个分片`

    let finished = uploaded.size
    const queue = [...missing]
    const workerCount = Math.min(CONCURRENCY, queue.length)
    const workers = Array.from({ length: workerCount }, async () => {
      while (queue.length > 0) {
        const index = queue.shift()
        const start = index * CHUNK_SIZE
        const end = Math.min(start + CHUNK_SIZE, target.size)
        const blob = target.slice(start, end)
        const buffer = await blob.arrayBuffer()
        const chunkMd5 = SparkMD5.ArrayBuffer.hash(buffer)
        await api.uploadChunk({
          fileMd5,
          chunkIndex: index,
          totalChunks,
          chunkMd5
        }, blob)
        finished += 1
        uploadProgress.value = Math.round((finished / totalChunks) * 100)
        statusText.value = `分片上传中：${finished}/${totalChunks}`
      }
    })
    await Promise.all(workers)

    statusText.value = '所有分片已上传，正在调用 MinIO composeObject 合并...'
    const mergeRes = await api.merge({
      fileMd5,
      fileName: target.name,
      totalSize: target.size,
      totalChunks
    })
    uploadProgress.value = 100
    statusText.value = `✅ 合并完成（taskId=${mergeRes.data.taskId}，${formatSize(target.size)}）`
    emit('uploaded', mergeRes.data.taskId)
  } catch (e) {
    error.value = e.message
    statusText.value = ''
  } finally {
    busy.value = false
  }
}

onBeforeUnmount(() => {
  worker?.terminate()
  worker = null
})
</script>

<template>
  <div class="card">
    <h2 style="margin-bottom: 14px">分片上传（5MB / 片，3 并发）</h2>
    <div class="row">
      <input type="file" accept="video/*" @change="onFileChange" />
      <button class="btn" :disabled="!file || busy" @click="startUpload">
        {{ busy ? '处理中...' : '开始上传 / 断点续传' }}
      </button>
    </div>

    <p v-if="file" class="muted" style="margin-top: 10px">
      已选择：{{ file.name }}（{{ formatSize(file.size) }}）
    </p>

    <div v-if="statusText" style="margin-top: 14px">
      <div class="progress">
        <div :style="{ width: (busy ? Math.max(md5Progress, uploadProgress) : uploadProgress) + '%' }"></div>
      </div>
      <p class="muted" style="margin-top: 8px">{{ statusText }}</p>
    </div>

    <p v-if="deduplicated" class="badge badge-3" style="margin-top: 10px">秒传命中（未上传任何字节）</p>
    <p v-if="error" class="error" style="margin-top: 10px">{{ error }}</p>
  </div>
</template>
