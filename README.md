# DoVideo AI

面向长视频内容理解的全栈项目。项目重点不是单纯调用大模型，而是把大文件上传、长耗时任务、重复请求、MQ 重复投递和第三方 API 抖动处理成一条可恢复、可观测的异步链路。

## 核心能力

- 5 MB 分片上传、断点续传和同用户秒传
- 分片 MD5 校验与合并后完整文件 MD5 对拍
- 完整 MD5 验证通过后才允许内容级对象复用
- Redis `SET NX` 请求幂等，拦截同一上传任务的并发分析提交
- Redisson WatchDog 分布式锁，防止多消费者同时处理相同内容
- 转录按 `videoMd5` 复用，总结按 `videoMd5 + goalDigest` 隔离
- RocketMQ 异步削峰、失败重试和死信兜底
- SSE 阶段推送，断线后支持轮询降级
- Redis Hash + Lua 令牌桶限流
- Actuator + Prometheus 业务指标
- Flyway 数据库迁移

## 系统架构

```text
Vue 3
  |
  | HTTP / SSE
  v
Spring Boot API
  |        \
  |         \ Redis
  |          - 登录会话
  |          - 活动任务 Key
  |          - 状态快照
  |          - 分布式锁
  |          - 令牌桶
  |
  +--> MySQL
  |     - 用户
  |     - 视频任务
  |     - 逐字稿
  |     - 目标级分析结果
  |
  +--> MinIO
  |     - 视频对象
  |     - 临时分片
  |
  +--> RocketMQ
        - video-analysis-topic
        - DLQ
          |
          v
       VideoAnalysisConsumer
       - Redisson 目标锁
       - Redisson 内容锁
       - ASR
       - LLM
       - 结果落库
```

## 幂等设计

请求提交、消费者执行和最终落库使用不同机制，不能互相替代。

```text
用户重复上传同一视频
  -> /api/upload/check
  -> 同用户已完成记录
  -> deduplicated=true

用户狂点“开始分析”
  -> analysis:active:task:{taskId} SET NX
  -> 只有一个请求投递 MQ

MQ 重复投递
  -> lock:analysis:{videoMd5}:{goalDigest}
  -> 同目标只允许一个消费者执行

不同目标并发分析
  -> lock:analysis-content:{videoMd5}
  -> ASR 只执行一次，多个目标复用逐字稿

最终重复写入
  -> video_transcription UNIQUE(video_md5)
  -> ai_summary_result UNIQUE(video_md5, goal_digest)
```

同一条上传任务同一时间只允许一个分析目标运行。分析完成后，可以继续提交其他目标；不同目标共享逐字稿，但各自保存结构化结果。

## 技术栈

| 层次 | 技术 |
|---|---|
| 后端 | Java 21、Spring Boot 3.5、MyBatis-Plus |
| 前端 | Vue 3、Vite |
| 消息 | RocketMQ 5.3 |
| 缓存和锁 | Redis 7、Redisson |
| 对象存储 | MinIO |
| 数据库 | MySQL 8、Flyway |
| AI | OpenAI 兼容 ASR / Chat Completions 接口 |
| 监控 | Spring Boot Actuator、Micrometer、Prometheus |
| 测试 | JUnit 5、Mockito、JMeter |

## 本地启动

### 1. 环境

- JDK 21
- Maven 3.9+
- Node.js 20+
- Docker Desktop
- FFmpeg / FFprobe，真实 ASR 模式需要

### 2. 启动中间件

```powershell
docker compose up -d
```

默认端口：

| 服务 | 地址 |
|---|---|
| MySQL | `127.0.0.1:13306` |
| Redis | `127.0.0.1:16379` |
| MinIO API | `http://127.0.0.1:9000` |
| MinIO Console | `http://127.0.0.1:9001` |
| RocketMQ NameServer | `127.0.0.1:9876` |

默认开发配置使用 Mock ASR / LLM，不产生第三方费用。

### 3. 启动后端

```powershell
cd server
mvn spring-boot:run
```

Flyway 会自动执行 `server/src/main/resources/db/migration/` 下的迁移。

健康检查：

```powershell
Invoke-RestMethod http://127.0.0.1:8080/api/health
```

Prometheus 指标：

```text
http://127.0.0.1:8080/actuator/prometheus
```

### 4. 启动前端

```powershell
cd client
npm install
npm run dev
```

打开 `http://127.0.0.1:5173`。

## 关键配置

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `APP_MOCK_ENABLED` | `true` | 使用 Mock ASR / LLM |
| `ASR_BASE_URL` | 硅基流动 | ASR 服务地址 |
| `ASR_API_KEY` | `demo-key` | ASR Key |
| `LLM_BASE_URL` | DeepSeek | LLM 服务地址 |
| `LLM_API_KEY` | `demo-key` | LLM Key |
| `ANALYSIS_ACTIVE_TTL_HOURS` | `6` | 活动任务 Key TTL |
| `ANALYSIS_STATUS_TTL_HOURS` | `6` | 状态快照 TTL |
| `ANALYSIS_LOCK_WAIT_SECONDS` | `120` | 消费者等锁时间 |
| `ANALYSIS_STALE_AFTER_MINUTES` | `120` | 卡死任务判定时间 |

## 主要接口

```text
POST /api/user/register
POST /api/user/login

GET  /api/upload/check
POST /api/upload/chunk
POST /api/upload/merge

POST /api/analysis/submit
GET  /api/analysis/status/{taskId}
GET  /api/analysis/events/{taskId}

GET  /api/media/list
GET  /api/media/play-url/{taskId}
```

详细请求和响应见 `docs/api-contract.md`。

## 业务指标

新增指标包括：

```text
dovideo_analysis_submit_total{result="accepted|duplicate|reused|failed"}
dovideo_analysis_mq_send_failure_total
dovideo_analysis_lock_total{result="acquired|wait_failed|content_wait_failed"}
dovideo_analysis_result_total{result="completed|reused|retry|failed"}
dovideo_analysis_processing_seconds
dovideo_analysis_lock_wait_seconds{stage="analysis|content"}
dovideo_analysis_stale_recovered_total
dovideo_rate_limited_total{scope="/api/analysis/submit"}
```

建议重点监控：

- MQ 消费延迟和积压
- AI API 429 / 5xx
- MinIO 写入失败率
- MySQL 写入失败率
- `submit=duplicate` 比例
- 锁等待 P95/P99
- 死信数量和恢复数量

## 测试

后端：

```powershell
cd server
mvn test
```

前端：

```powershell
cd client
npm run build
```

测试覆盖包括：

- 24 个并发分析提交只有一个成功投递 MQ
- 活动 Key 已存在时不发送 MQ
- MQ 投递失败时回滚活动状态
- 同目标结果复用
- 不同目标复用逐字稿
- Redisson 等锁失败交回 MQ 重试
- 永久 AI 错误快速失败
- 卡死任务恢复
- 完整 MD5 对拍和跨用户内容复用
- 非 VIP 用户注册无法自行提升权限

## JMeter 压测

现有两个脚本：

- `jmeter/dovideo-benchmark.jmx`：提交 RT、状态轮询、限流和同步对照
- `jmeter/dovideo-idempotency.jmx`：多线程同时提交同一分析任务

幂等压测要求：

```text
关闭或调高接口限流
已准备一个上传完成且未分析的 taskId
50 个线程同时提交相同 taskId + userGoal
预期：1 个 HTTP 202，其余 HTTP 409
预期：dovideo_analysis_submit_total{result="accepted"} 只增加 1
```

详细运行方式见 `jmeter/README.md`。

本机实测结果见 `jmeter/RESULTS.md`。

## 安全边界

- 注册接口忽略客户端传入的 `accountType`，新用户固定为普通用户。
- 密码使用 PBKDF2 + 随机盐，兼容旧 SHA-256 哈希并在登录后自动升级。
- 跨用户秒传不信任客户端 MD5；用户必须完成真实上传，服务端完整 MD5 校验通过后才复用已有对象。
- MinIO Bucket 默认私有，播放使用短时预签名 URL。

## 当前范围

本项目聚焦 Java 后端的高并发、异步、幂等和容错链路，暂不包含 Agent、OCR 和向量检索。新增 AI 能力前应优先保证现有分析状态机和数据一致性可验证。
