# 实施计划（Implementation Plan）

本计划严格派生自 `memory-bank/design-document.md` 与 `memory-bank/tech-stack.md`。每一步均保持小粒度、无代码、边界清晰且可独立验证。在当前步骤验证通过前，绝不提前进入下一步。

---

## 步骤 1：基础设施编排与后端工程脚手架初始化
- **执行内容**：
  1. 在项目根目录编写 `docker-compose.yml` 与 `rocketmq/broker.conf`，编排 MySQL 8、Redis 7、MinIO、RocketMQ NameServer 与 Broker。
  2. 初始化 `server/` Spring Boot 3 Maven 工程（`pom.xml`），引入 Web、MyBatis-Plus、MySQL Driver、StringRedisTemplate、Redisson、RocketMQ Spring Boot Starter、MinIO SDK、Jackson、Lombok 及测试依赖。
  3. 编写 `application.yml`（将数据库连接、Redis、MinIO、RocketMQ Topic、令牌桶限流速率、重试次数、Mock 压测开关 `app.mock-enabled` 全部抽为可配置项）及统一响应体 `Result<T>`、错误码 `ErrorCode`、业务异常 `BusinessException` 与全局异常处理器 `GlobalExceptionHandler`（含 HTTP 429 / 409 / 400 / 500 标准映射）。
- **验证方式**：
  - 执行 `mvn test-compile` 编译通过；
  - 编写并运行基础上下文单测，验证全局异常处理器对 `RATE_LIMITED`（429）、`CONFLICT`（409）等异常的 HTTP 状态码映射正确。

---

## 步骤 2：4 张垂直拆分数据库表、实体与用户认证模块
- **执行内容**：
  1. 编写初始化 SQL 脚本 `schema.sql`，创建 `user_info`、`video_task_record`、`video_transcription`、`ai_summary_result` 四张核心表，落实 `TINYINT` 状态机、`(user_id, create_time)` 与 `(status, create_time)` 联合索引、`video_md5` 与 `task_id` 唯一约束及 `create_time / update_time / is_deleted` 字段规范。
  2. 创建对应的 Entity、MyBatis-Plus Mapper 接口。
  3. 实现 `AuthService`、`UserController`（`/api/user/register`、`/api/user/login`、`/api/user/logout`）与 `AuthInterceptor` 登录拦截器（基于 Redis 存储会话 Token 并注入 `userId` 与 `accountType`）。
- **验证方式**：
  - 编写并运行 `AuthServiceTest` 单测，验证注册唯一键冲突拦截、密码哈希校验、登录签发 Redis Token 及拦截器鉴权逻辑通过。

---

## 步骤 3：MinIO 存储引擎封装（生命周期规则、服务端合并、预签名 URL）
- **执行内容**：
  1. 实现 `MinioService`（及配置类 `MinioConfig`）：
     - 启动时检查并创建私有 Bucket；
     - 自动为 Bucket 配置 **Lifecycle 生命周期规则**（前缀 `temp_chunks/` 满 1 天自动过期物理删除）；
     - 实现分片上传 `putChunkObject`、服务端原子合并 `composeChunks`（调用 `minioClient.composeObject`）、对象存在性检查 `statObject`、批量删除临时分片 `removeChunkObjects`、以及生成 5 分钟有效期的 **Pre-signed URL** `getPresignedPlayUrl`。
- **验证方式**：
  - 编写并运行 `MinioServiceTest` 单元测试，验证 Lifecycle 规则构造、`composeObject` 参数拼装及预签名 URL 有效期生成逻辑正确。

---

## 步骤 4：分片上传、秒传、Redis Set 断点续传与合并校准模块
- **执行内容**：
  1. 实现 `UploadService` 与 `UploadController`：
     - `GET /api/upload/check`：校验 `fileMd5`，若 MySQL 已存在已完成记录则直接创建/返回秒传结果（`deduplicated = true`）；否则从 Redis Set `upload:task:{fileMd5}` 读取已上传分片号集合返回前端；
     - `POST /api/upload/chunk`：接收分片流并流式计算 MD5 与前端 `chunkMd5` 比对（防比特翻转）；**先**上传至 MinIO `temp_chunks/{fileMd5}/{chunkIndex}`，**后**执行 Redis `SADD upload:task:{fileMd5} {chunkIndex}` 并刷新 24 小时 TTL；
     - `POST /api/upload/merge`：使用 Redisson `dovideo:merge:{fileMd5}` 加合并锁 + 检查数据库完成状态做幂等保护 → 校验 Redis Set `SCARD == totalChunks` → 将数据库任务状态置为 `5（合并中）` → 调用 MinIO `composeObject` 合并 → 更新状态为 `0（待分析）` 并清理 Redis Set 与临时分片；
     - `GET /api/media/play-url/{taskId}` 与 `GET /api/media/list`：校验归属后返回 5 分钟 Pre-signed URL 及用户历史任务列表（走 `(user_id, create_time)` 索引）。
  2. 实现 `UploadReconciliationJob` 定时校准任务：定期扫描卡在状态 `5（合并中）` 超时的记录，向 MinIO 核实成品文件是否存在，自动推进为已完成或回退为待上传。
- **验证方式**：
  - 编写并运行 `UploadServiceTest` 单测，覆盖：秒传命中、断点续传差集返回、分片 MD5 损坏拒绝、先落盘后记账顺序、并发 `/merge` 锁拦截与幂等返回、以及合并异常校准。

---

## 步骤 5：Redis Hash + Lua 惰性令牌桶限流模块
- **执行内容**：
  1. 在 `resources/lua/token_bucket.lua` 中编写标准的令牌桶 Lua 脚本：基于 Hash 结构（`tokens`、`last_time`），使用惰性计算补充令牌、判断余量、原子扣减并设置 `EXPIRE` 过期回收。
  2. 定义 `@RateLimit` 自定义注解与 `RateLimitAspect` AOP 切面（支持按用户 ID / 接口路径限流，并可根据 `user_info.account_type` 区分免费与 VIP 配额；配置开关支持压测时动态调整）。
  3. 触发限流时抛出 `BusinessException(ErrorCode.RATE_LIMITED)`，由全局异常处理器返回 `HTTP 429`。
- **验证方式**：
  - 编写并运行 `RateLimitAspectTest` 单测，验证 Lua 脚本参数传递、令牌充足放行、令牌耗尽返回 429 限流的行为。

---

## 步骤 6：FFmpeg 音频提取 + 硅基流动 ASR/DeepSeek 客户端（含指数退避重试与 Mock 模式）
- **执行内容**：
  1. 实现通用指数退避重试工具 `ExponentialBackoffRetry`：针对网络超时、HTTP 429、HTTP 5xx 按 `1s -> 2s -> 4s` 退避重试最多 3 次，对 400/401 等永久异常直接抛出不重试。
  2. 实现 `FfmpegAudioService`：从视频流/文件提取音频至临时文件，并在完成后立即删除本地临时文件，保障服务无状态化。
  3. 实现 `SiliconFlowAiService`：
     - 当 `app.mock-enabled = false` 时，真实调用硅基流动 ASR 接口转写逐字稿，并用结构化 Markdown Prompt + Few-Shot 示例调用 DeepSeek 接口强制输出 JSON 总结（`summary`, `key_points`, `chapters`）；
     - 当 `app.mock-enabled = true` 时，按可配置延迟返回标准结构化模拟数据，支撑零成本本地开发与 JMeter 高并发压测。
- **验证方式**：
  - 编写并运行 `ExponentialBackoffRetryTest` 与 `SiliconFlowAiServiceTest` 单测，验证 `1s->2s->4s` 退避次数、永久异常快速失败及 JSON 结构化解析逻辑。

---

## 步骤 7：RocketMQ 异步提交、消费者标准六步流程、WatchDog 锁与死信兜底
- **执行内容**：
  1. 实现 `AnalysisService` 与 `AnalysisController`：
     - `POST /api/analysis/submit`（挂载 `@RateLimit`）：执行锁外第一层前置状态拦截（查 Redis `video:status:{videoMd5}` → 查 MySQL 历史已完成结果复用）→ 新任务更新状态为排队中并写 Redis `PROCESSING` → 同步发送 `{taskId, videoMd5, minioPath}` 到 `video-analysis-topic`（捕获发送异常回滚状态并返回 503）→ 50ms 内返回 `taskId`；
     - `POST /api/analysis/sync-benchmark`：同步阻塞对照组接口，用于 JMeter 对比实测“同步 60s+ vs 异步 <50ms”；
     - `GET /api/analysis/status/{taskId}`：供前端 3 秒轮询查询状态，优先读 Redis，状态为 `SUCCESS` 时组装返回 `video_transcription` 与 `ai_summary_result` 完整数据，状态为 `FAILED` 时返回错误原因。
  2. 实现 `VideoAnalysisConsumer`（监听 `video-analysis-topic`，配置 `maxReconsumeTimes = 3`），严格落实**标准六步流程**：
     - ① 解析 `videoMd5` 与 `taskId` → ② `redissonClient.getLock("lock:video:" + videoMd5).tryLock()`（无 `leaseTime`，启用 WatchDog 10s 自动续期） → ③ 抢锁失败直接 return → ④ 抢锁成功查 MySQL 二次幂等校验 → ⑤ 执行音频提取、ASR 与 DeepSeek 总结 → ⑥ 写入 `video_transcription` 与 `ai_summary_result`（捕获 `DuplicateKeyException` 唯一索引物理兜底），更新主表与 Redis 为 `SUCCESS`，`finally` 检查 `isHeldByCurrentThread()` 释放锁。
  3. 实现 `DeadLetterConsumer`（监听死信 Topic）：当 3 次 MQ 重试全部耗尽进入死信队列时，将 MySQL 与 Redis 任务状态更新为 `FAILED` 并记录错误原因与容灾日志，防止前端无限转圈。
- **验证方式**：
  - 编写并运行 `AnalysisServiceTest` 与 `VideoAnalysisConsumerTest` 单测，验证：前置拦截秒回、MQ 发送失败兜底、消费者六步流程、WatchDog `tryLock()` 调用、二次幂等跳过、`DuplicateKeyException` 唯一索引兜底、以及失败重投与死信状态流转。

---

## 步骤 8：Vue 3 可视化工作台与 JMeter 压测脚本交付
- **执行内容**：
  1. 整理后端完整接口文档到 `docs/api-contract.md` 作为前端上下文约束。
  2. 在 `client/` 下搭建 Vue 3 + Vite 前端工作台，按 4 个模块实现：
     - 分片上传组件（5MB 切片、Web Worker 算 MD5、`/check` 秒传与断点续传进度条、3 并发上传分片、前端总指挥调 `/merge`）；
     - 任务列表与状态展示组件（发起 AI 分析、3 秒定时器轮询状态、达到 `SUCCESS`/`FAILED` 自动清除定时器、预签名 URL 视频播放）；
     - 转写逐字稿展示板块；
     - AI 结构化总结板块（核心摘要、关键要点、章节时间轴）。
  3. 在 `jmeter/` 目录下提供开箱即用的 JMeter 压测计划 `dovideo-benchmark.jmx`，包含：异步提交 RT 压测线程组、并发重复提交幂等验证线程组、令牌桶限流验证线程组与 3s 状态轮询线程组。
- **验证方式**：
  - 前端 `npm run build` 构建通过，后端 `mvn test` 全量单测通过，前后端接口契约完全对齐。
