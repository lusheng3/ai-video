# 设计文档（Design Document）：AI长视频解析平台

## 1. 项目目标与范围（Scope & Non-Goals）

### 1.1 核心目标
构建一个面向长视频内容解析的全栈系统（核心重心在 **Spring Boot 后端高并发与分布式架构**），完整落地简历「项目二：AI长视频解析平台」的全部 5 条核心技术亮点：
1. **RocketMQ 异步解耦**：将耗时 60s+ 的视频转码、语音转写与 AI 总结从 HTTP 请求线程剥离，接口仅保留落库与消息投递，提交响应时间控制在 **50ms 以内**。
2. **分片上传、秒传与断点续传**：前端按 **5MB** 切片并计算 MD5，后端基于 **Redis Set** 维护已传分片索引，支持秒传预检、中断后按差集补传、基于 **MinIO `composeObject`** 的服务端原子合并，以及合并后流式复算完整 MD5 与客户端指纹对拍。
3. **Redisson + MD5 内容级去重与消费幂等**：通过「前置状态拦截 + Redisson WatchDog 分布式锁（消费者标准六步流程）+ MySQL 唯一索引物理兜底」三重防线，杜绝重复点击与 MQ 重投带来的算力和 Token 浪费。
4. **MinIO 服务无状态化与安全存储**：音视频文件全量下沉至 MinIO 私有 Bucket，通过 **5 分钟有效期的 Pre-signed URL（预签名 URL）** 保障防盗链与隐私安全，通过 **MinIO Lifecycle（生命周期规则）** 24 小时自动物理清理 `temp_chunks/` 僵尸分片。
5. **Redis 令牌桶限流与两级指数退避重试**：基于 **Redis Hash + Lua 脚本**实现惰性令牌桶限流（拦截突发与恶意刷接口，返回 HTTP 429）；配合「API 调用级指数退避重试（`1s -> 2s -> 4s`）+ RocketMQ 阶梯延迟重投（最多 3 次）+ 死信队列（DLQ）兜底」，控制第三方抖动与成本风险。

### 1.2 非目标（Non-Goals）
- 不引入复杂的多智能体循环（Planner-Executor-Critic）、向量数据库（Qdrant/Milvus）或 RAG 混合检索（这部分归属简历项目一）。
- 不引入关键帧 Tesseract OCR、浏览器插件或外链爬虫下载。

---

## 2. 核心用户与系统旅程（User & System Journeys）

### 旅程 A：账号注册与登录
1. 用户提交用户名与密码进行注册或登录（`POST /api/user/register`、`POST /api/user/login`）。
2. 服务端使用 PBKDF2 + 随机盐校验密码哈希，生成会话 Token 存入 Redis（`auth:token:{token}`，TTL 24 小时）。注册请求中的 `accountType` 被忽略，新用户固定为普通用户。

### 旅程 B：大文件分片上传、秒传与断点续传
1. **切片与计算指纹**：前端读取本地视频文件，按固定 **5MB**（5,242,880 字节）切片，并在 Web Worker 中计算整个文件的 32 位十六进制 MD5（`fileMd5`）及各分片的 `chunkMd5`。
2. **预检请求（`GET /api/upload/check`）**：
   - 前端携带 `fileMd5`、`fileName`、`totalSize`、`totalChunks` 请求后端；
   - **情况 1（秒传）**：后端查询 MySQL `video_task_record`（或已完成缓存），若存在相同 `video_md5` 且文件已合并就绪，直接为当前用户生成/复用任务记录并返回 `deduplicated = true` 与 `taskId`、`fileObjectKey`，前端直接跳过上传（100% 进度）；
   - **情况 2（断点续传）**：若未完成，后端查询 Redis Set `upload:task:{fileMd5}` 的所有成员（`SMEMBERS`），返回已上传成功的分片序号数组 `uploadedChunks`（如 `[0, 1, 4]`）；前端用全集 `{0..totalChunks-1}` 减去 `uploadedChunks` 得到差集，仅上传缺失分片；
   - **情况 3（全新上传）**：Redis Set 为空，返回空数组 `[]`，前端从第 0 片开始上传。
3. **并发上传分片（`POST /api/upload/chunk`，先落盘后记账）**：
   - 前端以最大 3 并发发送分片请求，携带 `fileMd5`、`chunkIndex`、`totalChunks`、`chunkMd5` 与二进制文件流；
   - 后端先流式读取分片并计算 MD5，与 `chunkMd5` 比对，不一致则拒绝并返回 400（防传输比特翻转）；
   - 校验通过后，**先**将分片流上传至 MinIO 私有 Bucket 的临时路径 `temp_chunks/{fileMd5}/{chunkIndex}`；
   - MinIO 落盘成功后，**后**执行 Redis `SADD upload:task:{fileMd5} {chunkIndex}`，并设置/刷新 Key 过期时间为 **24 小时**。
4. **前端触发合并（`POST /api/upload/merge`，前端当总指挥）**：
   - 前端确认所有分片请求均返回 200 OK 后，主动发起一次 `/api/upload/merge` 请求；
   - **第 1 步（合并锁 + 状态幂等）**：后端通过 Redisson 尝试获取合并锁 `dovideo:merge:{fileMd5}`，未抢到锁返回 `HTTP 409`；抢到锁后先查 MySQL 是否已存在合并完成的记录，若已完成直接返回结果；
   - **第 2 步（校验分片完整性）**：读取 Redis Set `SCARD upload:task:{fileMd5}`，确认已传分片数量严格等于 `totalChunks`；
   - **第 3 步（状态标记“合并中”）**：在 MySQL `video_task_record` 插入或更新状态为 `5（合并中）`，配合后台定时巡检任务兜底“MinIO 合并成功但 DB 更新失败”的一致性问题；
   - **第 4 步（MinIO 服务端合并 + 完整 MD5 对拍）**：调用 MinIO `composeObject` API，将 `temp_chunks/{fileMd5}/0..N-1` 在存储引擎内部原子拼接为正式对象 `videos/{fileMd5}/{fileName}`；随后流式读取完整对象并重新计算 MD5，与请求中的 `fileMd5` 对拍；
   - **第 5 步（落库与清理）**：对拍一致时更新 `video_task_record` 状态为 `0（待分析/已上传）`，清理 MinIO 临时分片与 Redis Set `upload:task:{fileMd5}`，返回 `taskId`；对拍不一致时删除错误成品、清理分片与 Redis 进度，将任务标记为失败并返回 400。

### 旅程 C：令牌桶限流与 RocketMQ 异步分析提交（50ms 极速响应）
1. 用户点击“开始 AI 分析”（`POST /api/analysis/submit`），携带 `taskId`（或 `videoMd5`）。
2. **第 1 步（Redis + Lua 令牌桶限流）**：
   - 拦截器/切面执行 Lua 脚本操作 `rate_limiter:/api/analysis/submit:{userId}`（Hash 结构，含 `tokens` 与 `last_time`）；
   - 惰性计算补充令牌并原子扣减 1 个令牌；若令牌不足，直接返回 `HTTP 429 Too Many Requests`（“当前请求过多，请稍后再试”）。
3. **第 2 步（目标级结果复用 + 原子活动 Key）**：
   - 计算 `goalDigest = SHA-256(trim(userGoal))`，查询 `ai_summary_result(video_md5, goal_digest)`；已有同目标结果时返回 200 + `REUSED`；
   - 使用 `SET NX` 抢占 `analysis:active:task:{taskId}`，TTL 6 小时；失败返回 409 + `DUPLICATE`，不投递 MQ。
4. **第 3 步（落库 + 投递专属 Topic）**：
   - 将当前任务状态、`user_goal`、`goal_digest` 更新为排队中，在 `video:status:{taskId}` 写入 `PROCESSING`；
   - 构造轻量 JSON 消息 `{taskId, userId, videoMd5, minioPath}`，通过 RocketMQ **同步发送（Sync Send）** 投递至 `video-analysis-topic`；
   - 若 MQ 投递抛出异常，捕获异常回滚 Redis 状态并向前端返回“系统繁忙，请重试”，释放 Tomcat 线程；
   - 投递成功（收到 `SEND_OK`）后返回 HTTP 202 + `ACCEPTED`。

### 旅程 D：消费者标准六步流程 + Redisson WatchDog + 退避重试与死信
消费者 `VideoAnalysisConsumer` 监听 `video-analysis-topic`，严格按以下六步执行：
1. **步骤 1（解析消息）**：反序列化消息体，提取 `taskId`、`videoMd5`、`minioPath`。
2. **步骤 2（争抢目标锁和内容锁）**：先获取 `lock:analysis:{videoMd5}:{goalDigest}`，再获取 `lock:analysis-content:{videoMd5}`；均不指定固定 `leaseTime`，由 WatchDog 续期；
3. **步骤 3（抢锁失败处理）**：等待一段时间后重新检查同目标结果；已有结果则复用，否则抛异常交由 RocketMQ 延迟重试，不能留下永久 QUEUED 任务。
4. **步骤 4（二次幂等校验）**：抢锁成功后，查询 MySQL 中该 `videoMd5` 是否已有 `SUCCESS` 结果（防止前序线程刚跑完释放锁后的串行重投）；若已完成，直接复用结果、更新当前任务为 `3（已完成）` 并 `return`。
5. **步骤 5（执行核心业务 + 内层指数退避重试）**：
   - 更新任务状态为 `2（处理中）`；
   - 从 MinIO 下载视频流（或通过 FFmpeg 从预签名流提取 16kHz 单声道音频临时文件，处理完在 `finally` 删除本地临时音频，保持服务无状态）；
   - 调用硅基流动 ASR 接口将音频转为逐字稿文本（若开启 `app.mock-enabled=true` 则走可配置耗时的本地逼真模拟，便于 JMeter 压测）；
   - 调用硅基流动 DeepSeek 接口，使用结构化 Markdown Prompt（`[Role]` / `[Task]` / `[Constraint]` / `[Output Format]` + Few-Shot 示例）强制输出标准 JSON 格式总结（包含 `summary` 核心摘要、`chapters` 时间轴章节、`keyPoints` 核心要点）；
   - **内层重试**：ASR 与 LLM 调用均包裹指数退避重试器，针对网络超时、HTTP 429、HTTP 5xx 按 **`1s -> 2s -> 4s`** 退避重试最多 3 次；参数非法或 401 等永久错误立即终止不重试。
6. **步骤 6（垂直分表落库 + 唯一索引物理兜底 + 释放锁）**：
   - 在本地事务中：向 `video_transcription` 插入逐字稿（`video_md5` 唯一索引，捕获 `DuplicateKeyException` 幂等容忍），向 `ai_summary_result` 插入结构化 JSON 总结（`video_md5` 唯一索引，捕获 `DuplicateKeyException` 幂等容忍），将 `video_task_record` 状态更新为 `3（已完成）`；
   - 更新 `video:status:{taskId}=SUCCESS`，写入 `analysis:completed:{videoMd5}:{goalDigest}` 并释放活动 Key；
   - 在 `finally` 块中，检查 `lock.isHeldByCurrentThread()` 为 `true` 时调用 `lock.unlock()` 安全释放锁。
- **外层 MQ 阶梯重试与死信兜底**：
  - 若步骤 5 或步骤 6 抛出运行时异常，消费者不吞异常而是向上抛出，触发 RocketMQ 原生阶梯延迟重投（配置 `maxReconsumeTimes = 3`）；
  - 当 3 次重试全部耗尽后，消息进入死信队列（`%DLQ%video-analysis-group` 及自建死信备份 Topic `video-analysis-dlq-topic`）；
  - 死信监听器 `DeadLetterConsumer` 捕获消息，将 `video_task_record` 状态更新为 `4（失败）`、清理活动 Key，并更新 Redis 失败状态。

### 旅程 E：前端 3 秒延迟轮询与预签名安全播放
1. 前端拿到 `taskId` 后开启 `setInterval` 每 **3 秒** 请求 `GET /api/analysis/status/{taskId}`。
2. 后端优先读取 Redis 状态缓存，状态为 `PROCESSING` 时快速返回状态码；状态为 `SUCCESS` 时联表查询 `video_transcription` 与 `ai_summary_result` 一次性返回完整转写稿与结构化总结；状态为 `FAILED` 时返回错误信息。
3. 前端收到 `SUCCESS` 或 `FAILED` 后立刻执行 `clearInterval` 停止轮询，渲染报告或错误提示。
4. 用户点击播放视频或音频时，请求 `GET /api/media/play-url/{taskId}`，后端校验用户权限后通过 MinIO SDK 签发 **5 分钟有效期的 Pre-signed URL** 返回前端播放。

---

## 3. 边界情况与容错矩阵（Edge Cases）

| 异常/边界场景 | 系统的确定性处理行为 |
|---|---|
| 单个分片上传网络中断或 MD5 校验不匹配 | 后端不写 Redis Set，返回失败；前端对该分片单独重试最多 3 次，仍失败则暂停，下次通过 `/check` 算差集断点续传 |
| 合并后完整文件 MD5 与客户端指纹不一致 | 删除错误成品，清空 Redis 分片进度并删除临时分片，任务标记为 `4（失败）`，返回 HTTP 400，要求重新上传 |
| 用户传了一半彻底放弃（僵尸任务） | Redis Set `upload:task:{fileMd5}` 24 小时 TTL 自动过期；MinIO `temp_chunks/` 前缀对象由 Bucket Lifecycle 规则满 1 天自动物理删除 |
| 最后几个分片并发完成 | 后端不在 `/chunk` 接口自动合并，统一由前端收到全部 200 后调一次 `/merge`，且 `/merge` 加 `dovideo:merge:{fileMd5}` 锁，彻底消除并发合并冲突 |
| MinIO `composeObject` 成功但随后更新 MySQL 失败 | 合并前先将任务状态置为 `5（合并中）`；后台定时校准任务扫描超时仍处于“合并中”的记录，重新计算成品完整 MD5，一致才补齐完成状态，不一致则删除成品并标记失败，不存在则回退为“待上传” |
| 令牌桶放行后，发送 RocketMQ 网络断开 | 删除 `analysis:active:task:{taskId}`、清理状态快照并回滚数据库为 UPLOADED，返回 503 |
| 活动 Key 丢失但任务长期 QUEUED/PROCESSING | `AnalysisRecoveryJob` 扫描超时任务，标记 FAILED 并释放后续重试能力 |
| 视频转码 + AI 分析耗时超过 30 秒 | Redisson WatchDog 后台定时任务每 10 秒检查当前线程仍持锁，自动将锁 TTL 重置为 30 秒，绝不提前过期；若 JVM 宕机，WatchDog 停止续期，30 秒后锁自动释放，绝不死锁 |
| 缓存与锁极端同时失效，多线程并发写结果 | `video_transcription` 与 `ai_summary_result` 对 `video_md5` 建立 `UNIQUE` 唯一索引，数据库 B+ 树拒绝重复写入并抛出 `DuplicateKeyException`，代码捕获后幂等收敛 |
| 第三方 API 持续宕机或写库失败 | 内层 `1s->2s->4s` 退避 3 次 + 外层 RocketMQ 阶梯延迟重试 3 次，耗尽后进死信监听器，状态置为 `FAILED`，前端停止轮询弹窗提示，不无限转圈 |

---

## 4. 数据库与 Redis 状态模型（Data & State Model）

### 4.1 MySQL 4 张核心业务表（全部标配 `create_time`, `update_time`, `is_deleted`）

#### 表 1：`user_info`（用户主表）
- `id` BIGINT PK AUTO_INCREMENT
- `username` VARCHAR(64) NOT NULL UNIQUE（唯一索引 `uk_username`）
- `password_hash` VARCHAR(128) NOT NULL
- `nickname` VARCHAR(64) NOT NULL
- `account_type` TINYINT NOT NULL DEFAULT 0（`0`: 免费用户，`1`: VIP 付费用户，决定限流速率）
- `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
- `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
- `is_deleted` TINYINT NOT NULL DEFAULT 0

#### 表 2：`video_task_record`（视频任务流水核心表）
- `id` BIGINT PK AUTO_INCREMENT（即 `taskId`）
- `user_id` BIGINT NOT NULL
- `file_name` VARCHAR(255) NOT NULL
- `total_size` BIGINT NOT NULL DEFAULT 0
- `total_chunks` INT NOT NULL DEFAULT 1
- `video_md5` VARCHAR(32) NOT NULL（文件 128-bit MD5 的 32 位十六进制字符串）
- `file_url` VARCHAR(512) NULL（MinIO 内的对象 Key，如 `videos/{md5}/{name}`）
- `status` TINYINT NOT NULL DEFAULT 0（状态机：`0` 待上传/已上传待分析，`1` 排队中，`2` 处理中，`3` 已完成，`4` 失败，`5` 合并中）
- `error_msg` VARCHAR(512) NULL
- `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
- `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
- `is_deleted` TINYINT NOT NULL DEFAULT 0
- **索引设计**：
  - `idx_user_create (user_id, create_time)`：支撑用户查询历史任务列表，天然按时间倒序免内存排序；
  - `idx_status_create (status, create_time)`：支撑后台校准/巡检任务快速捞取“合并中”或积压任务；
  - `idx_video_md5 (video_md5)`：支撑秒传与跨用户同内容结果快速检索。

#### 表 3：`video_transcription`（原始转录明细表 — 垂直分表）
- `id` BIGINT PK AUTO_INCREMENT
- `task_id` BIGINT NOT NULL
- `video_md5` VARCHAR(32) NOT NULL
- `transcription_content` LONGTEXT NOT NULL（万字级语音转写逐字稿，独立存储避免拖垮主表查询）
- `word_count` INT NOT NULL DEFAULT 0
- `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
- `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
- `is_deleted` TINYINT NOT NULL DEFAULT 0
- **唯一约束**：`UNIQUE KEY uk_transcription_md5 (video_md5)`、`UNIQUE KEY uk_transcription_task (task_id)`

#### 表 4：`ai_summary_result`（AI 分析结果表 — 垂直分表）
- `id` BIGINT PK AUTO_INCREMENT
- `task_id` BIGINT NOT NULL
- `video_md5` VARCHAR(32) NOT NULL
- `structured_data` JSON NOT NULL（结构化总结：`{"summary": "...", "key_points": [...], "chapters": [...]}`）
- `token_cost` INT NOT NULL DEFAULT 0
- `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
- `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
- `is_deleted` TINYINT NOT NULL DEFAULT 0
- **唯一约束**：`UNIQUE KEY uk_summary_video_goal (video_md5, goal_digest)`；`task_id` 只建普通索引，允许同一上传任务串行生成多个目标结果。

### 4.2 Redis Key 规范汇总

| Key 格式 | Redis 类型 | TTL | 用途说明 |
|---|---|---|---|
| `auth:token:{token}` | String (JSON) | 24h | 登录会话用户信息 |
| `upload:task:{fileMd5}` | **Set** | 24h | 存储已成功上传的分片序号 `chunkNumber`（天然去重 + O(1) 查询） |
| `upload:meta:{fileMd5}` | Hash | 24h | 存储上传会话元数据（`fileName`, `totalSize`, `totalChunks`, `userId`） |
| `dovideo:merge:{fileMd5}` | Redisson Lock | WatchDog | `/merge` 接口合并互斥锁 |
| `rate_limiter:{api}:{userId}` | **Hash** | 60s | 令牌桶状态：`tokens`（余量）、`last_time`（上次毫秒时间戳），由 Lua 脚本原子操作 |
| `analysis:active:task:{taskId}` | String | 6h | 请求层 SET NX 活动任务 Key，防前端狂点和重复 MQ |
| `video:status:{taskId}` | String | 6h | 任务状态快照 |
| `analysis:completed:{videoMd5}:{goalDigest}` | String | 7d | 同视频同目标完成标记 |
| `lock:analysis:{videoMd5}:{goalDigest}` | Redisson Lock | WatchDog | 同目标消费者互斥 |
| `lock:analysis-content:{videoMd5}` | Redisson Lock | WatchDog | ASR/逐字稿内容级互斥与复用 |

---

## 5. 验收标准（Acceptance Criteria）
1. **分片、断点续传与秒传验收**：上传一个多切片文件，中途中断后调 `/api/upload/check` 能准确返回已传分片号；补传剩余分片后调 `/api/upload/merge` 能通过 MinIO `composeObject` 合成完整文件，并由服务端流式重算完整 MD5 与客户端 `fileMd5` 对拍；再次上传同一文件时 `/api/upload/check` 直接触发秒传（`deduplicated=true`）。
2. **异步 50ms 响应与轮询闭环验收**：调用 `/api/analysis/submit` 响应时间在本地基准测试下稳定 < 50ms；前端每 3s 轮询 `/api/analysis/status/{taskId}` 能依次观察到 `PROCESSING -> SUCCESS` 并拿到完整转写稿与 JSON 总结。
3. **幂等与防重验收**：50 个并发请求对同一 taskId + goal 提交时，1 个返回 202、49 个返回 409；Prometheus accepted=1、duplicate=49，MQ 与 ASR 只执行一次。
4. **限流与退避容错验收**：高频请求超出令牌桶阈值时准确返回 `HTTP 429`；模拟第三方 API 超时能观察到 `1s -> 2s -> 4s` 指数退避日志；连续失败 3 次后任务状态准确变更为 `FAILED` 并进入死信處理。
5. **MinIO 安全与生命周期验收**：直接裸访问 MinIO 对象 URL 被拒绝（403），通过后端签发的 Pre-signed URL 可在 5 分钟内正常播放；MinIO Bucket 成功挂载 `temp_chunks/` 1 天自动删除规则。
6. **目标隔离验收**：同一视频的逐字稿只保留一份，不同 `goalDigest` 各自保存一份结构化结果。
7. **可观测性验收**：`/actuator/prometheus` 可读取提交、重复、限流、锁等待、完成、失败和卡死恢复指标。
