const TOKEN_KEY = 'dovideo_token'
const USER_KEY = 'dovideo_user'

export function getToken() {
  return localStorage.getItem(TOKEN_KEY) || ''
}

export function getStoredUser() {
  try {
    return JSON.parse(localStorage.getItem(USER_KEY) || 'null')
  } catch {
    return null
  }
}

export function saveSession(authData) {
  localStorage.setItem(TOKEN_KEY, authData.token)
  localStorage.setItem(USER_KEY, JSON.stringify({
    userId: authData.userId,
    username: authData.username,
    nickname: authData.nickname,
    accountType: authData.accountType
  }))
}

export function clearSession() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
}

async function request(path, options = {}) {
  const headers = { ...(options.headers || {}) }
  const token = getToken()
  if (token) headers.Authorization = `Bearer ${token}`
  if (options.body && !(options.body instanceof FormData)) {
    headers['Content-Type'] = 'application/json; charset=utf-8'
  }

  const response = await fetch(path, { ...options, headers })
  const text = await response.text()
  let payload = null
  try {
    payload = text ? JSON.parse(text) : null
  } catch {
    payload = null
  }

  if (!response.ok) {
    const error = new Error(payload?.message || `请求失败（HTTP ${response.status}）`)
    error.status = response.status
    error.payload = payload
    throw error
  }
  return payload
}

export const api = {
  register: (body) => request('/api/user/register', {
    method: 'POST',
    body: JSON.stringify(body)
  }),
  login: (body) => request('/api/user/login', {
    method: 'POST',
    body: JSON.stringify(body)
  }),
  checkUpload: (params, options = {}) => request(`/api/upload/check?${new URLSearchParams(params)}`, options),
  uploadChunk: (params, blob, options = {}) => {
    const form = new FormData()
    form.append('file', blob)
    return request(`/api/upload/chunk?${new URLSearchParams(params)}`, {
      method: 'POST',
      body: form,
      ...options
    })
  },
  merge: (body, options = {}) => request('/api/upload/merge', {
    method: 'POST',
    body: JSON.stringify(body),
    ...options
  }),
  listTasks: () => request('/api/media/list'),
  playUrl: (taskId) => request(`/api/media/play-url/${taskId}`),
  submitAnalysis: (body) => request('/api/analysis/submit', {
    method: 'POST',
    body: JSON.stringify(body)
  }),
  analysisStatus: (taskId) => request(`/api/analysis/status/${taskId}`)
}
