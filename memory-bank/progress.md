# 项目进度记录（Progress Log）

- **2026-11-03 [Bootstrap]**：
  - 依据用户简历「项目二：AI长视频解析平台」与《项目拷打》PDF 核心后端主线，完成需求澄清并砍掉无关的复杂 Agent/RAG 包装。
  - 建立 `memory-bank/`，完成 `design-document.md`、`tech-stack.md`、`implementation-plan.md`、`architecture.md` 与 `AGENTS.md`/`CLAUDE.md` 初始化。
- **2026-11-03 [步骤 1 完成]**：
  - 创建 `docker-compose.yml` 与 `rocketmq/broker.conf`。
  - 初始化 `server/pom.xml` 与 `application.yml`，实现 `AppProperties` 全参数配置化。
  - 实现统一响应体 `Result<T>`、错误码 `ErrorCode`、业务异常 `BusinessException`、全局异常处理器 `GlobalExceptionHandler` 与健康检查 `HealthController`。
  - 通过 `GlobalExceptionHandlerTest` 单元测试（3/3 通过）。
- **2026-11-03 [步骤 2 完成]**：
  - 编写 `db/schema.sql`，创建 `user_info`、`video_task_record`、`video_transcription`、`ai_summary_result` 4 张垂直拆分业务表，落实 `TINYINT` 状态机、`(user_id, create_time)` 与 `(status, create_time)` 联合索引、`video_md5` 与 `task_id` 唯一约束及逻辑删除规范。
  - 建立完整的企业级分层包结构：`entity`、`enums`、`mapper`、`dto`、`service`、`config`、`controller`。
  - 实现 `AuthService`、`AuthInterceptor`、`WebMvcConfig` 与 `UserController`（注册、登录、退出、当前身份查询）。
  - 通过 `AuthServiceTest` 单元测试（全量 6/6 通过）。
- **2026-11-03 [步骤 3 完成]**：
  - 实现 `MinioConfig` 与 `MinioService`，封装私有 Bucket 创建、`temp_chunks/` 前缀 1 天自动物理删除的 Lifecycle 生命周期规则、分片上传 `putChunkObject`、服务端原子合并 `composeChunks`（`minioClient.composeObject`）、5 分钟有效期预签名链接 `getPresignedPlayUrl` 与对象存在性探测 `objectExists`。
  - 通过 `MinioServiceTest` 单元测试（全量 9/9 通过）。
- **2026-11-03 [步骤 4 完成]**：
  - 实现 `UploadService`、`UploadController` 与 `UploadReconciliationJob`：
    - `GET /api/upload/check`：秒传预检（`deduplicated = true`）与 Redis Set（`upload:task:{fileMd5}`）已上传分片序号集合返回；
    - `POST /api/upload/chunk`：分片 MD5 比特翻转校验 + 严格“先落盘 MinIO，后 `SADD` 记账 Redis Set（24h TTL）”；
    - `POST /api/upload/merge`：前端总指挥触发合并，Redisson 合并锁（`dovideo:merge:{fileMd5}`）+ 状态幂等校验 + 分片总数核对 + 状态 5（合并中）标记 + MinIO `composeObject` 服务端合并；
    - `UploadReconciliationJob`：定时扫描超时卡在“合并中(5)”的记录向 MinIO `statObject` 核实校准，保障最终一致性；
    - `GET /api/media/play-url/{taskId}` 与 `GET /api/media/list`：5 分钟预签名播放链接与用户历史列表。
  - 通过 `UploadServiceTest` 6 项单元测试（全量 15/15 通过）。
- **2026-11-03 [步骤 5 完成]**：
  - 编写 `resources/lua/token_bucket.lua`：基于 Redis Hash（`tokens`、`last_time`）实现惰性令牌补充、判断与原子扣减，并设置 60s `EXPIRE` 回收冷数据。
  - 实现 `@RateLimit` 自定义注解与 `RateLimitAspect` AOP 切面，结合 `user_info.account_type` 自动区分普通用户（1个/秒，容量5）与付费VIP用户（5个/秒，容量20）配额，超限抛出 `BusinessException(ErrorCode.RATE_LIMITED)` 映射 HTTP 429。
  - 支持 `@RateLimit(rate = 20.0, capacity = 50)` 按接口单独覆盖限流参数（注解参数化，切面无需改动）。
  - 通过 `RateLimitAspectTest` 单元测试（全量 18/18 通过）。
- **2026-11-04 [步骤 6 完成]**：
  - 抽象 `AsrClient` / `LlmClient` 双接口，ASR 与 LLM 供应商完全解耦、独立配置（`app.ai.asr.*` 与 `app.ai.llm.*`）。
  - 实现 `OpenAiCompatibleAsrClient`（/audio/transcriptions，默认硅基流动 SenseVoice）与 `OpenAiCompatibleLlmClient`（/chat/completions，默认 DeepSeek 官方 `deepseek-chat`）。
  - 实现 `MockAsrClient` / `MockLlmClient`，受 `app.mock-enabled` 控制，支持零成本本地压测。
  - 实现 `ExponentialBackoffRetry`：429/5xx/IO 异常按 `1s -> 2s -> 4s` 退避重试最多 3 次；400/401/403 等永久异常立即失败。
  - 实现 `AudioExtractionService`：MinIO 下载视频 → FFmpeg 提取 16kHz 单声道 wav → `finally` 删除本地临时文件，保证服务无状态化；Mock 模式跳过 FFmpeg。
  - 实现 `SummaryPromptBuilder` 四段式 Prompt + Few-Shot，强制输出严格 JSON。
  - 通过 `ExponentialBackoffRetryTest` 与 `OpenAiCompatibleLlmClientTest`（MockWebServer 模拟 429/401/JSON 围栏）。
- **2026-11-04 [步骤 7 完成]**：
  - 实现 `AnalysisService`：锁外前置状态拦截（Redis `video:status:{md5}` → MySQL 同内容历史结果复用）→ 标记 `QUEUED` + 写 Redis `PROCESSING` → `RocketMQTemplate.syncSend` 同步投递轻量消息（仅 taskId/videoMd5/minioPath/userGoal）→ 校验 `SEND_OK` 后立即返回；投递异常回滚状态并返回 HTTP 503。
  - 实现 `VideoAnalysisConsumer`（`maxReconsumeTimes = 3`），严格落实标准六步流程：解析消息 → `tryLock()`（无 leaseTime，启用 WatchDog）→ 抢锁失败直接 ACK → 二次幂等校验（任务已 SUCCESS / 同内容已有结果）→ 音频提取 + ASR + LLM 总结 → 唯一索引兜底落库 + 更新 SUCCESS + `finally` 检查 `isHeldByCurrentThread()` 释放锁。
  - 实现 `AnalysisResultWriter`：逐字稿与结构化总结写入垂直分表，捕获 `DuplicateKeyException` 幂等容忍；同步更新主表状态与 Redis 成功状态。
  - 实现 `DeadLetterConsumer`：监听 `%DLQ%video-analysis-group`，将任务状态置为 `FAILED` 并写入错误原因，前端轮询可立刻感知。
  - 实现 `AnalysisController`：`POST /api/analysis/submit`（`@RateLimit(rate=1.0, capacity=5)`）、`GET /api/analysis/status/{taskId}`、`POST /api/analysis/sync-benchmark`（仅 Mock 模式，供 JMeter 同步/异步对比）。
  - 通过 `AnalysisServiceTest`、`VideoAnalysisConsumerTest`、`DeadLetterConsumerTest`（全量 35/35 通过，`mvn test` BUILD SUCCESS）。

- **2026-11-04 [步骤 7 端到端运行验证]**：
  - 启动 Docker 全量中间件（MySQL 13306 / Redis 16379 / MinIO 9000-9001 / RocketMQ NameServer 9876 + Broker 10911），应用 mvn spring-boot:run 启动成功，GET /api/health 返回 200。
  - 运行时修复：RocketMQ Broker 初始 256m 堆内存不足导致 exit 253（提升为 1g）；命名卷挂载到 /home/rocketmq/store 因容器内 rocketmq(uid=3000) 无写权限导致 Broker 启动失败（开发环境改为不挂载持久卷）；ExponentialBackoffRetry 多构造器导致 Spring 无法实例化（标注 @Autowired）；Mock 与真实 AsrClient/LlmClient 同时注册导致 Bean 冲突（真实实现加 @ConditionalOnProperty(app.mock-enabled=false)）。
  - 端到端冒烟测试通过：注册 → /upload/check（deduplicated=false）→ 3 个 5MB 分片上传 → /upload/merge（MinIO composeObject，taskId=1）→ /analysis/submit 返回 QUEUED → 3s 轮询 PROCESSING → SUCCESS；MySQL 中 ideo_task_record.status=3、ideo_transcription 135 字、i_summary_result 含 3 个 keyPoints 与 3 个 chapters。
  - 秒传验证：同一 MD5 再次 /upload/check 返回 deduplicated=True, taskId=1。
  - 异步 RT 验证：冷启动首次提交 268ms（含 MQ 路由首次发现与 JIT），热路径第二次提交 **52ms**。
  - 令牌桶验证：连续 7 次提交，前 5 次 HTTP 200，第 6、7 次准确返回 HTTP 429（capacity=5, rate=1/s）。
  - RocketMQ 消费日志确认：nalysis_mq_sent → ideo_analysis_completed，两个任务均成功消费。

- **2026-11-04 [步骤 8 完成]**：
  - docs/api-contract.md：完整接口契约（认证、分片上传/秒传/断点续传、合并、预签名播放、异步提交、3s 轮询、同步对照组、错误码）。
  - client/：Vue 3 + Vite 工作台，包含登录/注册、分片上传（5MB 切片 + Web Worker 计算 MD5 + 3 并发 + 秒传/续传）、任务列表、3 秒定时轮询（SUCCESS/FAILED 自动清除）、转写稿与结构化总结展示、5 分钟预签名视频回放。
  - jmeter/dovideo-benchmark.jmx + jmeter/README.md：setUp 登录提取 token、异步提交 RT、3s 轮询、令牌桶 200x5+429x5、同步对照组（默认禁用）；XML 结构校验通过。
  - 验证：
pm install（35 包）+ 
pm run build 成功（dist 88.82 kB JS / 2.62 kB CSS）；
pm run dev 启动后 http://127.0.0.1:5173 返回 200，Vite /api 代理到后端 /api/health 返回 200。

- **2026-11-04 [真实 Key 接入验证]**：
  - 硅基流动 Key 验证：GET /v1/models 返回 200（99 个模型）；首次 POST /v1/audio/transcriptions（SenseVoiceSmall）成功返回 27 秒中文逐字稿；POST /v1/chat/completions（DeepSeek-V3）成功返回。
  - 随后真实链路调用 ASR 时返回 HTTP 402 {"code":30001,"message":"Sorry, your account balance is insufficient"}，确认账户余额不足，需要充值后才能继续。
  - FFmpeg 已安装（winget Gyan.FFmpeg 9.0.2），真实链路日志确认 fmpeg_extract_audio_success ... size=850138，音频提取正常。
  - 改进：消费者新增永久性 AI 错误识别（401/402/403/400 等），直接置 FAILED 并 ACK，不再浪费 3 次 RocketMQ 重试；新增单测，全量 36/36 通过。

- **2026-11-04 [真实 Key 全链路成功]**：
  - 充值后重新验证：fmpeg_extract_audio_success（850KB）→ sr_transcribe_success chars=103（约 6.2s）→ llm_summarize_success keyPoints=4 chapters=3 tokens=683（约 8.9s）→ ideo_analysis_completed，taskId=3 状态 SUCCESS，端到端约 16s。
  - 真实逐字稿存在同音字错误（遍利/右子数/递归站），DeepSeek 在总结中自动纠正为「遍历/右子树/递归栈」，体现 ASR + LLM 串联价值。
  - 修复成功后 error_msg 残留问题：MyBatis-Plus updateById 默认忽略 null，改用 LambdaUpdateWrapper.set(error_msg, null) 显式清空；单测 36/36 通过。

- **2026-11-05 [前端弱网上传改造]**：
  - 真实 1.63GB 文件上传时，分片 167 经 Vite 代理连 8080 出现 connect ETIMEDOUT，Promise.all 整批 reject，页面显示 Failed to fetch；Redis Set 已保存 333/335 片（缺 167、222），证明断点数据安全。
  - 重构 UploadPanel.vue：分片级状态 + 单分片自动重试 3 次（1s→2s→4s + jitter）+ 60s AbortController 超时 + 错误分类（401/403/400 永久失败，408/429/5xx/网络可重试）+ worker 失败不中断整批 + 批次结束 /check 服务端对账 + 仅全部分片就绪才 merge + PARTIAL_FAILED 状态与失败分片列表 + merge 409/网络重试。
  - api.js 支持传入 AbortSignal；
  - npm run build 通过（93.70 kB JS）。

- **2026-11-05 [长视频分段 ASR]**：
  - `AudioExtractionService` 按 `app.ai.asr-segment-seconds`（默认 300s）FFmpeg 切段，逐段调用 ASR 后拼接为带 `[start-end]` 时间范围的完整逐字稿，规避单文件大小上限并支持单段失败重试。
  - 真实 1.63GB / 2964.7s（约 49 分钟）视频验证：切 10 段、ASR 10 段全部成功，tokenCost=7877，keyPoints=5、chapters=7。

- **2026-11-05 [方案 B：SSE 实时进度 + 3s 轮询降级]**：
  - 后端新增 `AnalysisStageEnum`（QUEUED/CONSUMING/AUDIO_EXTRACTING/AUDIO_SEGMENTED/ASR/LLM/PERSISTING/COMPLETED/REUSED/RETRYING/FAILED）、`AnalysisEvent`、`TaskEventService`、`TaskEventRedisConfig`；`AnalysisStatusResponse` 增加 `stage/stageLabel/progress`。
  - 事件链路：消费者每进入一个阶段 `publishStage` → 先写 Redis 快照 `video:stage:{taskId}`（TTL 1h）→ `PUBLISH dovideo:task-events` → 各实例 `RedisMessageListenerContainer` 收到后投递给本机 `SseEmitter`，因此水平扩容后浏览器连任意实例都能收到事件。
  - `GET /api/analysis/events/{taskId}`（`text/event-stream`）：先注册订阅再补发首帧快照，避免「查状态与建连接之间任务刚好完成」丢终态；终态事件发送后立即关闭连接。
  - 提交顺序：先发 `QUEUED` 再投递 MQ，防止消费者抢先 `CONSUMING` 导致进度回退；MQ 投递失败时 `clearStage` 清理快照；消费者失败/重试/死信均发布对应终态或 `RETRYING` 事件。
  - 前端 `taskEvents.js`：因 `EventSource` 不能携带 `Authorization`，改用 `fetch` 读取事件流；断线指数退避 1s→2s→4s→8s→15s，连续 5 次失败或接口 404/405 时降级为 3s 轮询；401/403 直接终态报错。`TaskList.vue`/`ResultPanel.vue` 展示阶段文案与进度条。
  - 文档：`docs/api-contract.md` 新增 3.3 SSE 事件流与阶段表，3.2 轮询补充 `stage/progress`。
  - 验证：`mvn test` 全量 **40/40** 通过；已完成任务订阅 SSE 收到 `COMPLETED` 快照后连接立即关闭；真实任务订阅中实时收到 `ASR(59%) → LLM(78%) → PERSISTING(92%) → COMPLETED(100%)`；Redis 快照 TTL 实测 3591s；前端 `npm run build` 通过（97.57 kB JS）。

- **2026-11-07 [完整文件 MD5 对拍]**：
  - `MinioService.calculateObjectMd5` 新增流式计算完整对象 MD5，不把 GB 级成品重新落盘。
  - `/api/upload/merge` 在 `composeObject` 成功后重新计算完整 MD5，与前端提交的 `fileMd5` 对拍；不一致时删除错误成品、清空 Redis 分片进度与 MinIO 临时分片，任务置为 FAILED 并返回 400。
  - `UploadReconciliationJob` 不再只凭对象存在推进成功；超时“合并中”任务必须重新核对完整 MD5，一致才转为 UPLOADED。
  - `checkUpload` 与合并幂等查询只接受 UPLOADED/SUCCESS 状态，避免失败任务被误判为秒传命中。
  - 新增完整 MD5 流式计算、正常对拍、对拍失败清理、后台校准对拍测试；`mvn test` 全量 **43/43** 通过。
