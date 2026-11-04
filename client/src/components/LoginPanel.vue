<script setup>
import { ref } from 'vue'
import { api, saveSession } from '../api'

const emit = defineEmits(['logged-in'])

const mode = ref('login')
const username = ref('')
const password = ref('')
const nickname = ref('')
const loading = ref(false)
const error = ref('')

function switchMode(next) {
  mode.value = next
  error.value = ''
}

async function submit() {
  error.value = ''
  if (!username.value.trim() || !password.value) {
    error.value = '请输入用户名和密码'
    return
  }
  loading.value = true
  try {
    const body = { username: username.value.trim(), password: password.value }
    if (mode.value === 'register') {
      body.nickname = nickname.value.trim() || body.username
    }
    const res = mode.value === 'login'
      ? await api.login(body)
      : await api.register(body)
    saveSession(res.data)
    emit('logged-in', res.data)
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="card">
    <div class="row" style="justify-content: space-between; margin-bottom: 16px">
      <h2>{{ mode === 'login' ? '登录' : '注册' }}</h2>
      <div class="row">
        <button class="btn btn-ghost" @click="switchMode('login')">登录</button>
        <button class="btn btn-ghost" @click="switchMode('register')">注册</button>
      </div>
    </div>

    <div class="row">
      <input v-model="username" placeholder="用户名（3~32 位）" style="flex: 1; min-width: 180px" />
      <input v-model="password" type="password" placeholder="密码（6~64 位）" style="flex: 1; min-width: 180px" />
      <input
        v-if="mode === 'register'"
        v-model="nickname"
        placeholder="昵称（可选）"
        style="flex: 1; min-width: 140px"
      />
      <button class="btn" :disabled="loading" @click="submit">
        {{ loading ? '处理中...' : (mode === 'login' ? '登录' : '注册并登录') }}
      </button>
    </div>
    <p v-if="error" class="error" style="margin-top: 10px">{{ error }}</p>
    <p class="muted" style="margin-top: 10px">
      后端已内置 Mock 模式：ASR / LLM 走模拟返回，压测不产生真实费用。
    </p>
  </div>
</template>
