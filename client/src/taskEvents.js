import { getToken } from './api'

const MAX_RECONNECT_ATTEMPTS = 5
const MAX_BACKOFF_MS = 15000
const ENDPOINT = '/api/analysis/events'

/**
 * 分析任务 SSE 事件流（方案 B）。
 *
 * 设计要点：
 * 1. 用 fetch 而不是 EventSource：EventSource 不能带 Authorization 头；
 * 2. 断线后指数退避重连（1s → 2s → 4s → 8s → 15s），连续失败达到上限后
 *    主动通知调用方降级为 3 秒轮询；
 * 3. 收到终态事件（status=3/4）立即停止，不空转重连；
 * 4. 后端在订阅成功后会先补发一帧当前快照，因此重连不会丢状态。
 */
export function createTaskEventStreams() {
  const streams = new Map()

  const stop = (taskId) => {
    const key = String(taskId)
    const entry = streams.get(key)
    if (!entry) return
    entry.controller.abort()
    streams.delete(key)
  }

  const stopAll = () => {
    for (const entry of streams.values()) entry.controller.abort()
    streams.clear()
  }

  const start = (taskId, handlers = {}) => {
    const key = String(taskId)
    stop(key)

    const controller = new AbortController()
    const entry = { controller }
    streams.set(key, entry)

    let attempt = 0
    let fallbackStarted = false

    const release = () => {
      if (streams.get(key) === entry) streams.delete(key)
    }

    const fallback = (reason) => {
      if (fallbackStarted) return
      fallbackStarted = true
      release()
      handlers.onFallback?.(reason)
    }

    const run = async () => {
      while (!controller.signal.aborted) {
        try {
          const response = await fetch(`${ENDPOINT}/${key}`, {
            headers: {
              Accept: 'text/event-stream',
              Authorization: `Bearer ${getToken()}`
            },
            signal: controller.signal
          })

          if (response.status === 401 || response.status === 403) {
            const error = new Error(`事件流鉴权失败（HTTP ${response.status}）`)
            error.status = response.status
            handlers.onError?.(error, true)
            release()
            return
          }
          if (response.status === 404 || response.status === 405) {
            // 服务端未部署 SSE 接口（灰度/旧版本），直接降级轮询
            fallback(new Error(`事件流接口不可用（HTTP ${response.status}）`))
            return
          }
          if (!response.ok) {
            throw new Error(`事件流连接失败（HTTP ${response.status}）`)
          }
          if (!response.body) {
            throw new Error('服务端未返回事件流')
          }

          attempt = 0
          const terminal = await consume(response.body, handlers.onEvent, controller.signal)
          if (terminal) {
            release()
            return
          }
          // 服务端正常关闭但未给终态（例如 30 分钟超时）：稍等后重连
          await wait(1000, controller.signal)
        } catch (error) {
          if (controller.signal.aborted) {
            release()
            return
          }
          attempt += 1
          handlers.onError?.(error, false)
          if (attempt >= MAX_RECONNECT_ATTEMPTS) {
            fallback(error)
            return
          }
          const backoff = Math.min(MAX_BACKOFF_MS, 1000 * 2 ** (attempt - 1))
          await wait(backoff + Math.floor(Math.random() * 300), controller.signal)
        }
      }
      release()
    }

    run().catch((error) => {
      if (controller.signal.aborted) {
        release()
        return
      }
      release()
      fallback(error)
    })
  }

  return {
    start,
    stop,
    stopAll,
    has: (taskId) => streams.has(String(taskId))
  }
}

async function consume(body, onEvent, signal) {
  const reader = body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  const cancel = () => reader.cancel().catch(() => {})
  signal.addEventListener('abort', cancel, { once: true })

  try {
    while (!signal.aborted) {
      const { value, done } = await reader.read()
      if (signal.aborted) return false
      buffer += decoder.decode(value || new Uint8Array(), { stream: !done })

      const frames = buffer.split(/\r?\n\r?\n/)
      buffer = frames.pop() || ''
      for (const frame of frames) {
        const data = frame.split(/\r?\n/)
          .filter((line) => line.startsWith('data:'))
          .map((line) => line.slice(5).trimStart())
          .join('\n')
        if (!data) continue
        let event
        try {
          event = JSON.parse(data)
        } catch {
          continue
        }
        onEvent?.(event)
        if (event.status === 3 || event.status === 4) return true
      }
      if (done) return false
    }
    return false
  } finally {
    signal.removeEventListener('abort', cancel)
    await reader.cancel().catch(() => {})
    reader.releaseLock()
  }
}

function wait(ms, signal) {
  if (signal.aborted) return Promise.resolve()
  return new Promise((resolve) => {
    const timer = setTimeout(finish, ms)
    signal.addEventListener('abort', finish, { once: true })
    function finish() {
      clearTimeout(timer)
      signal.removeEventListener('abort', finish)
      resolve()
    }
  })
}
