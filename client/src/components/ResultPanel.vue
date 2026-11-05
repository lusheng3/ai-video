<script setup>
defineProps({
  detail: { type: Object, default: null },
  playUrl: { type: String, default: '' }
})
const emit = defineEmits(['close'])
</script>

<template>
  <div v-if="detail" class="card">
    <div class="row" style="justify-content: space-between; margin-bottom: 14px">
      <h2>分析结果 · taskId={{ detail.taskId }}</h2>
      <button class="btn btn-ghost" @click="emit('close')">关闭</button>
    </div>

    <div v-if="detail.status !== 3" class="muted">
      {{ detail.message || '任务尚未完成' }}
      <span v-if="detail.progress != null">（{{ detail.progress }}%）</span>
      <div
        v-if="detail.progress != null"
        class="progress"
        style="margin-top: 8px; max-width: 420px"
      >
        <div :style="{ width: Math.min(100, Math.max(0, detail.progress)) + '%' }"></div>
      </div>
    </div>

    <template v-else>
      <h3 style="margin-bottom: 8px">核心摘要</h3>
      <p style="line-height: 1.8">{{ detail.summary?.summary }}</p>

      <h3 style="margin: 18px 0 8px">核心要点</h3>
      <ul style="line-height: 1.9; margin: 0; padding-left: 22px">
        <li v-for="(point, index) in detail.summary?.keyPoints || []" :key="index">{{ point }}</li>
      </ul>

      <h3 style="margin: 18px 0 8px">章节时间轴</h3>
      <div v-for="(chapter, index) in detail.summary?.chapters || []" :key="index" class="chapter">
        <strong>{{ chapter.title }}</strong>
        <span class="muted" style="margin-left: 8px">
          {{ chapter.startTime || '--' }} ~ {{ chapter.endTime || '--' }}
        </span>
        <div class="muted" style="margin-top: 4px">{{ chapter.content }}</div>
      </div>

      <h3 style="margin: 18px 0 8px">原始逐字稿</h3>
      <pre class="transcript">{{ detail.transcription }}</pre>

      <div v-if="playUrl">
        <h3 style="margin: 18px 0 8px">视频回放（5 分钟预签名 URL）</h3>
        <video :src="playUrl" controls preload="metadata"></video>
      </div>
    </template>
  </div>
</template>
