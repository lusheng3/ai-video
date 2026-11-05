# DoVideo AI 后端接口契约（API Contract）

> Base URL：`http://127.0.0.1:8080`
> 所有响应统一为 `Result<T>`：`{ "code": 200, "message": "OK", "data": T }`
> 除白名单接口外，均需请求头 `Authorization: Bearer <token>`

## 0. 统一错误码

| HTTP | code | 场景 |
|---|---|---|
| 400 | 400 | 参数校验失败 / 分片 MD5 损坏 |
| 401 | 401 | 未登录或 Token 过期 |
| 403 | 403 | 越权访问他人资源 |
| 404 | 404 | 任务不存在 |
| 409 | 409 | 分片未齐 / 正在合并 / 重复处理 |
| 429 | 429 | 令牌桶限流触发 |
| 503 | 503 | MQ 投递失败 / MinIO 不可用 |
| 500 | 500 | 服务器内部错误 |

---

## 1. 认证

### 1.1 注册
`POST /api/user/register`

```json
{ "username": "alice", "password": "123456", "nickname": "Alice", "accountType": 0 }
```

响应 `data`：

```json
{
  "userId": 1,
  "username": "alice",
  "nickname": "Alice",
  "accountType": 0,
  "token": "6f1c..."
}
```

### 1.2 登录
`POST /api/user/login`

```json
{ "username": "alice", "password": "123456" }
```

响应同注册。`accountType`：`0` 普通用户（1 令牌/秒，容量 5）；`1` VIP（5 令牌/秒，容量 20）。

### 1.3 退出
`POST /api/user/logout`，Header 带 Token。

### 1.4 当前用户
`GET /api/user/me` → `{ "userId": 1, "accountType": 0 }`

---

## 2. 分片上传与合并

### 2.1 上传预检（秒传 / 断点续传）
`GET /api/upload/check?fileMd5={32位小写md5}&fileName={name}&totalSize={bytes}&totalChunks={n}`

响应 `data`：

```json
{
  "deduplicated": false,
  "taskId": null,
  "fileUrl": null,
  "uploadedChunks": [0, 1, 4]
}
```

- `deduplicated = true`：秒传命中，前端无需上传，直接拿 `taskId`。
- `uploadedChunks`：Redis Set `upload:task:{fileMd5}` 中已成功落盘的分片序号，前端取差集补传。

### 2.2 上传单个分片
`POST /api/upload/chunk`（`multipart/form-data`）

| 参数 | 位置 | 说明 |
|---|---|---|
| `fileMd5` | query | 文件 MD5 |
| `chunkIndex` | query | 分片序号，从 0 开始 |
| `totalChunks` | query | 分片总数 |
| `chunkMd5` | query | 可选，服务端会重算比对，防比特翻转 |
| `file` | form | 分片二进制（≤ 20MB） |

响应：`{ "code": 200, "message": "OK", "data": null }`

### 2.3 合并分片
`POST /api/upload/merge`

```json
{ "fileMd5": "385a...", "fileName": "lecture.mp4", "totalSize": 12582912, "totalChunks": 3 }
```

响应 `data`：

```json
{ "taskId": 1, "videoMd5": "385a...", "fileUrl": "videos/385a.../lecture.mp4", "status": 0 }
```

> 服务端：Redisson 合并锁 + 状态幂等 + `SCARD == totalChunks` 校验 + MinIO `composeObject`。

### 2.4 预签名播放地址
`GET /api/media/play-url/{taskId}`

```json
{ "playUrl": "http://127.0.0.1:9000/dovideo-private/videos/...?X-Amz-Expires=300&..." }
```

有效期 5 分钟，私有 Bucket 裸链不可访问。

### 2.5 我的任务列表
`GET /api/media/list`

```json
[
  {
    "id": 1,
    "userId": 1,
    "fileName": "lecture.mp4",
    "totalSize": 12582912,
    "totalChunks": 3,
    "videoMd5": "385a...",
    "fileUrl": "videos/385a.../lecture.mp4",
    "status": 3,
    "errorMsg": null,
    "createTime": "2026-11-04T21:59:23"
  }
]
```

状态机：`0` 待分析、`1` 排队中、`2` 处理中、`3` 已完成、`4` 失败、`5` 合并中。

---

## 3. AI 异步分析

### 3.1 提交分析（令牌桶限流）
`POST /api/analysis/submit`

```json
{ "taskId": 1, "userGoal": "提炼视频核心内容" }
```

响应 `data`：

```json
{
  "taskId": 1,
  "videoMd5": "385a...",
  "status": 1,
  "statusLabel": "QUEUED",
  "reused": false,
  "message": "任务已进入异步分析队列"
}
```

- 命中 Redis `video:status:{md5}=PROCESSING` 或 MySQL 已有同内容结果时，不会重复投递 MQ。
- 超过令牌桶容量返回 **HTTP 429**。
- MQ 投递失败会回滚状态并返回 **HTTP 503**。

### 3.2 查询状态（SSE 不可用时的降级轮询）
`GET /api/analysis/status/{taskId}`

处理中（`stage` / `progress` 与 SSE 事件同源，轮询也能显示细粒度进度）：

```json
{
  "taskId": 1,
  "videoMd5": "385a...",
  "status": 2,
  "statusLabel": "PROCESSING",
  "stage": "ASR",
  "stageLabel": "正在逐段语音转写",
  "message": "正在转写第 3/10 段音频",
  "progress": 32
}
```

完成：

```json
{
  "taskId": 1,
  "videoMd5": "385a...",
  "status": 3,
  "statusLabel": "SUCCESS",
  "stage": "COMPLETED",
  "progress": 100,
  "message": "分析完成",
  "transcription": "00:00 大家好...",
  "summary": {
    "summary": "本视频讲解了...",
    "keyPoints": ["要点1", "要点2"],
    "chapters": [
      { "title": "前序遍历定义", "startTime": "00:00", "endTime": "02:30", "content": "..." }
    ],
    "tokenCost": 0
  }
}
```

失败：`status=4`，返回 `errorMsg`。

### 3.3 实时事件流（SSE）
`GET /api/analysis/events/{taskId}`，Header 需 `Authorization: Bearer <token>`，`Accept: text/event-stream`。

订阅成功后服务端先补发一帧当前快照，随后推送阶段事件；终态事件发送完立即关闭连接：

```text
event: task-status
data: {"taskId":1,"status":2,"statusLabel":"PROCESSING","stage":"ASR","stageLabel":"正在逐段语音转写","message":"正在转写第 3/10 段音频","progress":32,"errorMsg":null,"timestamp":1762300000000}
```

| stage | 含义 | 默认 progress |
|---|---|---|
| `QUEUED` | 已投递 MQ，等待消费 | 0 |
| `CONSUMING` | 消费者已接手 | 5 |
| `AUDIO_EXTRACTING` | 下载视频 + FFmpeg 提取音频 | 10 |
| `AUDIO_SEGMENTED` | 音频分段完成 | 20 |
| `ASR` | 逐段转写（message 含 `第 i/N 段`） | 30 |
| `LLM` | 生成结构化总结 | 78 |
| `PERSISTING` | 落库保存 | 92 |
| `COMPLETED` / `REUSED` | 终态：完成 | 100 |
| `RETRYING` | 本次失败，等待 MQ 重投 | 0 |
| `FAILED` | 终态：失败 | 0 |

- 事件先写 Redis `video:stage:{taskId}`（TTL 1h）作为快照，再通过 Redis Pub/Sub `dovideo:task-events` 广播到所有实例。
- 前端断线重连后由首帧快照补齐状态；连续重连失败时自动降级为 3 秒轮询。

### 3.4 同步对照组（仅 Mock 模式）
`POST /api/analysis/sync-benchmark?taskId=1&userGoal=...`

返回 `{ "taskId": 1, "elapsedMs": 3005, "mode": "SYNC_BLOCKING" }`，用于 JMeter 对比同步阻塞与异步 50ms。

---

## 4. 健康检查
`GET /api/health`（无需 Token）

```json
{
  "code": 200,
  "message": "OK",
  "data": { "status": "UP", "mockEnabled": true, "analysisTopic": "video-analysis-topic" }
}
```